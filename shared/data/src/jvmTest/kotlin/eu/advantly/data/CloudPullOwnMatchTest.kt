package eu.advantly.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Eigenes LOCAL-Match bei aktiviertem Store: bestätigter Kopf gilt als Snapshot, Abdeckung nur über exakt heruntergeladene Events. */
class CloudPullOwnMatchTest {
    private val format = Presets.TENNIS_SINGLES_BO3.format()
    private val lineup = Lineup(listOf(PlayerId("me")), listOf(PlayerId("guest")), PlayerId("me"))
    private val genesis =
        ScoreEvent(
            EventId("genesis"),
            MatchId(MATCH),
            DeviceId(PHONE),
            1,
            1,
            1,
            null,
            MatchCreated(format, lineup),
        ).toStoredEvent()

    private fun ownMatch(driver: SqlDriver): Pair<LocalData, CloudUploadSession> {
        val data = LocalData(driver)
        data.players.ensureSelf("Alex", 0) { PlayerId("me") }
        data.accountIdentities.initialize(PHONE, "me", "PHONE")
        data.accountIdentities.select("legacy:$PHONE", "alice", 0, "PHONE") { error("keine neue ID") }
        data.replicas.activate(data.replicas.captureContext()) {}
        val session = data.cloudUploads.activate("alice")
        data.cloudUploads.bindOwnedDevices(session, setOf(PHONE))
        data.players.addGuest(PlayerId("guest"), "Bea", 0)
        data.events.append(genesis)
        data.matches.insertIfAbsent(MatchId(MATCH), format, lineup, MatchMeta(venue = "Lokal"), 1)
        return data to session
    }

    /** Server-Ack für alles Ausgewählte, wie U4 nach erfolgreichem Push. */
    private fun upload(
        data: LocalData,
        session: CloudUploadSession,
    ) {
        val snapshot = data.cloudUploads.capture(session, 100)
        data.cloudUploads.acknowledge(snapshot, snapshot.events.mapTo(mutableSetOf()) { it.id }, metadata = snapshot.metadataRevisions)
    }

    private fun cloudHead(venue: String = "Lokal") =
        MatchRecord(MatchId(MATCH), format, lineup, MatchMeta(venue = venue), 1, 1, MatchStatus.IN_PROGRESS, ScoreSummary.EMPTY, null)

    private fun LocalData.catalog(
        version: Long,
        venue: String = "Lokal",
    ): Long {
        val run = cloudPulls.beginRun(accountIdentities.profile!!) {}.runSerial
        val profile = accountIdentities.profile!!
        val page =
            CloudPullMetadataPage(
                listOf(CloudPullMatch(MATCH, version, """{"version":$version}""", "member", 1, 0, false, cloudHead(venue))),
                emptyList(),
                emptyList(),
                version,
                false,
                1,
            )
        check(cloudPulls.commitMetadata(profile, run, cloudPulls.progress(profile).cursor, page) {})
        return run
    }

    private fun LocalData.header() = cloud.metadataState(CloudMetadataKind.HEADER, MATCH)!!

    private fun LocalData.meta() = matches.get(MatchId(MATCH))?.meta

    @Test
    fun confirmedOwnHeaderIsTakenOverAndKeepsItsUploadRight() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            data.catalog(1, venue = "Server")
            assertEquals(1, data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, MATCH)!!.materializedVersion)
            val header = data.header()
            assertEquals(CloudMetadataOrigin.LOCAL, header.origin)
            assertEquals(PHONE, header.sourceDeviceId)
            listOf(CloudMetadataKind.HEADER, CloudMetadataKind.PARTICIPANTS, CloudMetadataKind.PROJECTION).forEach {
                val state = data.cloud.metadataState(it, MATCH)!!
                assertEquals(state.selected.revision, state.uploadedRevision, it.name)
            }
            assertEquals(
                0,
                data.cloudUploads
                    .capture(session, 100)
                    .metadataRevisions.size,
            )
            // Herkunft bleibt eigen: eine spätere lokale Änderung wird wieder für dieses Konto hochgeladen.
            data.replicas.markReady(data.replicas.captureContext(), MATCH, 1, 0) {}
            assertEquals("Server", data.meta()!!.venue)
            data.matches.updateMeta(MatchId(MATCH), MatchMeta(venue = "Neu"))
            assertTrue(
                data.cloudUploads
                    .capture(session, 100)
                    .metadataRevisions
                    .any { it.kind == CloudMetadataKind.HEADER },
            )
        }
    }

    @Test
    fun unconfirmedOwnHeaderIsNeverOverwritten() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            data.matches.updateMeta(MatchId(MATCH), MatchMeta(venue = "Ungesendet"))
            val before = data.header()
            data.catalog(1, venue = "Server")
            assertNull(data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, MATCH)!!.materializedVersion)
            assertEquals(before, data.header())
            assertTrue(before.selected.revision > before.uploadedRevision)
            assertTrue("Ungesendet" in queryHeaderMeta(driver))
        }
    }

    @Test
    fun ownHeaderIsNotAdoptedByAnotherAccount() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            data.cloudUploads.deactivate()
            data.accountIdentities.select("legacy:$PHONE", "bob", 0, "PHONE") { error("keine neue ID") }
            data.catalog(1, venue = "Server")
            assertNull(data.cloudPulls.snapshot("bob", CloudPullEntityKind.MATCH, MATCH)!!.materializedVersion)
            assertEquals(CloudMetadataOrigin.LOCAL, data.header().origin)
            assertTrue("Lokal" in queryHeaderMeta(driver))
        }
    }

    /** Ursache (ii): Ein Upload-Ack allein ist keine Abdeckung; erst die exakt gleiche heruntergeladene Kopie erhält einen Empfangsnachweis. */
    @Test
    fun ownUploadedEventsCoverOnlyThroughAnExactRedownload() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            val point = StoredEvent("point", MATCH, PHONE, 2, 2, """{"type":"PointWon","side":"A"}""", type = "PointWon")
            data.events.append(point)
            upload(data, session)
            assertEquals(0, data.cloud.pendingCount())
            val run = data.catalog(1)
            val work = data.cloudPulls.nextEventWork("alice", run)!!
            assertTrue(data.cloudPulls.receivedWatermarks(work).isEmpty())
            // Gefälschtes eigenes Event: gleiche Gerätesequenz, andere ID beim Server – kein Empfangsnachweis.
            val forged = point.copy(id = "server-point")
            assertFailsWith<IllegalStateException> {
                data.cloudPulls.commitEvents(work, run, listOf(CloudPullReceipt(genesis, 1), CloudPullReceipt(forged, 2)), {})
            }
            assertTrue(data.cloudPulls.receivedWatermarks(work).isEmpty())
            data.cloudPulls.commitEvents(
                work,
                run,
                listOf(CloudPullReceipt(genesis, 1), CloudPullReceipt(point, 2)),
                {},
                endOfStream = true,
            )
            val current = data.cloudPulls.eventWork("alice", MATCH)!!
            assertEquals(listOf(CloudPullWatermark(PHONE, 2, 2)), data.cloudPulls.receivedWatermarks(current))
            val target = data.cloudPulls.readinessWork(current, run)!!
            assertEquals(
                CloudPullReadinessResult.READY,
                data.cloudPulls.applyReadiness(current, run, target, 2, 1, listOf(CloudPullWatermark(PHONE, 2, 2))) {},
            )
            assertTrue(data.replicas.canReadMember(data.replicas.captureContext(), MATCH))
            // Ein neues lokales Event am bereiten Match ist weder Lücke noch Abdeckung.
            data.events.append(point.copy(id = "local-3", deviceSeq = 3, lamport = 3))
            assertEquals(1, data.cloud.pendingCount())
            val again = data.cloudPulls.eventWork("alice", MATCH)!!
            assertEquals(
                CloudPullReadinessResult.READY,
                data.cloudPulls.applyReadiness(
                    again,
                    run,
                    data.cloudPulls.readinessWork(again, run)!!,
                    2,
                    1,
                    listOf(CloudPullWatermark(PHONE, 2, 2)),
                ) {},
            )
            assertTrue(data.replicas.access(data.replicas.captureContext(), MATCH)!!.ready)
        }
    }

    @Test
    fun foreignServerEventAtAnOwnSequenceNeverCompletesReadiness() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            val run = data.catalog(1)
            val work = data.cloudPulls.nextEventWork("alice", run)!!
            data.cloudPulls.commitEvents(work, run, listOf(CloudPullReceipt(genesis, 1)), {}, endOfStream = true)
            val current = data.cloudPulls.eventWork("alice", MATCH)!!
            val target = data.cloudPulls.readinessWork(current, run)!!
            // Server meldet eine zweite eigene Sequenz, die lokal nur unbestätigt oder gar nicht vorliegt.
            assertFalse(
                data.cloudPulls.applyReadiness(current, run, target, 2, 1, listOf(CloudPullWatermark(PHONE, 2, 2))) {} ==
                    CloudPullReadinessResult.READY,
            )
            assertFalse(data.replicas.access(data.replicas.captureContext(), MATCH)!!.ready)
        }
    }

    private fun queryHeaderMeta(driver: SqlDriver): String =
        driver
            .executeQuery(null, "SELECT meta_json FROM match_header WHERE id = '$MATCH'", { c ->
                c.next()
                QueryResult.Value(c.getString(0)!!)
            }, 0)
            .value

    private companion object {
        const val MATCH = "own-match"
        const val PHONE = "phone"
    }
}
