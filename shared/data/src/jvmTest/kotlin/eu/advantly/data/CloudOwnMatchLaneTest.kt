package eu.advantly.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.End
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.ReceiveRight
import eu.advantly.core.model.StartEnds
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vertragsergänzung eigene Matches (Integrator): Schreibspur unabhängig von ready, Lesespur nur bei angewandter Redaktion,
 * der Pull schreibt den eigenen Kopf nie und belegt ihn nur bei feldgenau getragenem, bestätigtem Stand.
 */
class CloudOwnMatchLaneTest {
    private val format = Presets.TENNIS_SINGLES_BO3.format()

    // Rein lokale Kopffelder: weder firstServer noch receiveRight, startEnds oder updated_at_ms trägt der Server.
    private val lineup =
        Lineup(
            listOf(PlayerId("me")),
            listOf(PlayerId("guest")),
            PlayerId("guest"),
            ReceiveRight(b = PlayerId("guest")),
            StartEnds(End.END_2),
        )

    private fun genesis(device: String = PHONE) =
        ScoreEvent(EventId("genesis"), MatchId(MATCH), DeviceId(device), 1, 1, 1, null, MatchCreated(format, lineup)).toStoredEvent()

    private fun point(
        id: String,
        device: String = PHONE,
        seq: Long,
    ) = StoredEvent(id, MATCH, device, seq, seq + 10, """{"type":"PointWon","side":"A"}""", type = "PointWon")

    /** Eigenes Match von [creator]; [owned] Geräte werden an alice gebunden, [activateFirst] = Store vor der Anlage aktiv. */
    private fun ownMatch(
        driver: SqlDriver,
        creator: String = PHONE,
        owned: Set<String> = setOf(PHONE),
        activateFirst: Boolean = true,
        previous: MatchId? = null,
        localPrevious: Boolean = false,
    ): Pair<LocalData, CloudUploadSession> {
        val data = LocalData(driver)
        data.players.ensureSelf("Alex", 0) { PlayerId("me") }
        data.accountIdentities.initialize(PHONE, "me", "PHONE")
        data.accountIdentities.select("legacy:$PHONE", "alice", 0, "PHONE") { error("keine neue ID") }
        if (creator != PHONE) data.accountIdentities.recordSource(creator, "me", "WATCH", "legacy:$PHONE", local = false)
        if (activateFirst) data.replicas.activate(data.replicas.captureContext()) {}
        val session = data.cloudUploads.activate("alice")
        if (owned.isNotEmpty()) data.cloudUploads.bindOwnedDevices(session, owned)
        data.players.addGuest(PlayerId("guest"), "Bea", 0)
        if (previous != null && localPrevious) {
            data.events.append(
                ScoreEvent(EventId("pred-genesis"), previous, DeviceId(PHONE), 1, 1, 1, null, MatchCreated(format, lineup)).toStoredEvent(),
            )
            data.matches.insertIfAbsent(previous, format, lineup, MatchMeta(venue = "Vorher"), 1)
        }
        data.events.append(genesis(creator))
        data.matches.insertIfAbsent(MatchId(MATCH), format, lineup, MatchMeta(venue = "Lokal"), 1, previous)
        data.matches.updateProgress(MatchId(MATCH), MatchStatus.IN_PROGRESS, ScoreSummary(eventCount = 1), 7777)
        if (!activateFirst) data.replicas.activate(data.replicas.captureContext()) {}
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

    /** Kopf wie `PullMapping.memberRecord`: firstServer = a.first(), updatedAt aus Start/Ende, nur getragene Felder. */
    private fun head(
        venue: String = "Lokal",
        status: MatchStatus = MatchStatus.IN_PROGRESS,
        score: ScoreSummary = ScoreSummary(eventCount = 1),
    ) = MatchRecord(
        MatchId(MATCH),
        format,
        Lineup(lineup.a, lineup.b, lineup.a.first()),
        MatchMeta(venue = venue),
        1,
        1,
        status,
        score,
        null,
    )

    /** Eine Katalogseite; Rückgabe ist die Laufnummer (null = Epochenwechsel hinter dem Cursor, Seite verworfen). */
    private fun LocalData.catalog(
        version: Long,
        record: MatchRecord = head(),
        access: String = "member",
        epoch: Long = 0,
        generation: Long = 1,
    ): Long? {
        val profile = accountIdentities.profile!!
        val run = cloudPulls.beginRun(profile) {}.runSerial
        val match =
            CloudPullMatch(MATCH, version, """{"version":$version}""", access, 1, epoch, false, record.takeIf { access == "member" })
        val page = CloudPullMetadataPage(listOf(match), emptyList(), emptyList(), version, false, generation)
        return run.takeIf { cloudPulls.commitMetadata(profile, run, cloudPulls.progress(profile).cursor, page) {} }
    }

    /** Katalogseite in einem neuen Lauf, die das eigene Match nicht enthält (nur ein fremdes Feed-Match). */
    private fun LocalData.otherPage(
        version: Long,
        generation: Long,
        hasMore: Boolean,
    ) {
        val profile = accountIdentities.profile!!
        val run = cloudPulls.beginRun(profile) {}.runSerial
        val other = CloudPullMatch("other-match", version, """{"version":$version}""", "feed", null, 0, false, null)
        val page = CloudPullMetadataPage(listOf(other), emptyList(), emptyList(), version, hasMore, generation)
        assertTrue(cloudPulls.commitMetadata(profile, run, cloudPulls.progress(profile).cursor, page) {})
    }

    private fun LocalData.proof() = cloudPulls.snapshot(accountIdentities.profile!!, CloudPullEntityKind.MATCH, MATCH)?.materializedVersion

    private fun LocalData.readable() = replicas.canReadMember(replicas.captureContext(), MATCH)

    private fun LocalData.ready() = replicas.access(replicas.captureContext(), MATCH)?.ready == true

    private fun LocalData.count(
        seq: Long,
        device: String = PHONE,
    ) {
        events.append(point("p$device$seq", device, seq))
        matches.updateProgress(MatchId(MATCH), MatchStatus.IN_PROGRESS, ScoreSummary(eventCount = seq.toInt()), 8000 + seq)
    }

    private fun LocalData.assertLocked(seq: Long = 2) {
        assertFailsWith<IllegalStateException> { events.append(point("locked$seq", seq = seq)) }
        assertFailsWith<IllegalStateException> {
            matches.updateProgress(MatchId(MATCH), MatchStatus.IN_PROGRESS, ScoreSummary(eventCount = 9), 9000)
        }
        assertFailsWith<IllegalStateException> { matches.updateMeta(MatchId(MATCH), MatchMeta(venue = "Fremd")) }
        assertFalse(readable())
        assertNull(matches.get(MatchId(MATCH)))
    }

    private fun raw(
        driver: SqlDriver,
        column: String,
    ): String =
        driver
            .executeQuery(null, "SELECT $column FROM match_header WHERE id = '$MATCH'", { c ->
                c.next()
                QueryResult.Value(c.getString(0)!!)
            }, 0)
            .value

    private fun row(driver: SqlDriver) =
        listOf(
            "lineup_json",
            "meta_json",
            "status",
            "score_json",
            "updated_at_ms",
            "format_json",
        ).associateWith { raw(driver, it) }

    @Test
    fun countingBetweenCatalogAndReadinessKeepsTheOwnMatchWritableAndReadable() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            assertNotNull(data.catalog(1))
            assertEquals(1, data.proof())
            assertFalse(data.ready())
            assertTrue(data.readable())
            data.count(2)
            data.matches.updateMeta(MatchId(MATCH), MatchMeta(venue = "Halle"))
            assertEquals(2, data.events.count(MATCH))
            assertEquals(
                "Halle",
                data.matches
                    .get(MatchId(MATCH))!!
                    .meta.venue,
            )
        }
    }

    @Test
    fun unconfirmedProjectionDuringThePullNeitherProvesNorBlocksCounting() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            data.count(2)
            val before = row(driver)
            assertNotNull(data.catalog(1))
            assertNull(data.proof())
            assertEquals(before, row(driver))
            assertTrue(data.readable())
            data.count(3)
            assertEquals(3, data.events.count(MATCH))
            assertEquals(
                3,
                data.matches
                    .get(MatchId(MATCH))!!
                    .score.eventCount,
            )
        }
    }

    /** P2-1: Seite mit älterem Serverstand nach dem Ack eines neueren lokalen Stands. */
    @Test
    fun staleCatalogAfterANewerAckNeitherRegressesNorProves() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            data.matches.updateMeta(MatchId(MATCH), MatchMeta(venue = "Neu"))
            data.matches.updateProgress(MatchId(MATCH), MatchStatus.FINISHED, ScoreSummary(eventCount = 9), 9000)
            upload(data, session)
            val before = row(driver)
            assertNotNull(data.catalog(1, head(venue = "Lokal")))
            assertEquals(before, row(driver))
            assertTrue("Neu" in raw(driver, "meta_json"))
            assertEquals("FINISHED", raw(driver, "status"))
            assertNull(data.proof())
            assertTrue(
                data.cloudUploads
                    .capture(session, 100)
                    .metadataRevisions
                    .isEmpty(),
            )
            assertTrue(data.readable())
            // Erst die Seite mit genau dem bestätigten Stand belegt den Snapshot.
            assertNotNull(data.catalog(2, head(venue = "Neu", status = MatchStatus.FINISHED, score = ScoreSummary(eventCount = 9))))
            assertEquals(2, data.proof())
            assertEquals(before, row(driver))
        }
    }

    /** Ein älterer Beleg wird von einer erneut gelieferten veralteten Seite entzogen, nicht bestätigt. */
    @Test
    fun staleRescanAfterANewerAckWithdrawsTheOldProof() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            assertNotNull(data.catalog(1))
            assertEquals(1, data.proof())
            data.matches.updateMeta(MatchId(MATCH), MatchMeta(venue = "Neu"))
            upload(data, session)
            // G-Wechsel erzwingt einen Neuscan ab null; derselbe Serverstand 1 kommt mit altem Inhalt zurück.
            assertNull(data.catalog(1, generation = 2))
            assertNotNull(data.catalog(1, head(venue = "Lokal"), generation = 2))
            assertNull(data.proof())
            assertTrue("Neu" in raw(driver, "meta_json"))
            assertTrue(data.readable())
        }
    }

    /** P2-2: Der Pull lässt lokale Kopffelder unberührt und belegt trotzdem. */
    @Test
    fun matchingCatalogKeepsLocalOnlyHeaderFields() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            val before = row(driver)
            val participants = data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, MATCH)
            assertNotNull(data.catalog(1))
            assertEquals(1, data.proof())
            assertEquals(before, row(driver))
            assertEquals("7777", raw(driver, "updated_at_ms"))
            assertEquals(lineup, data.matches.get(MatchId(MATCH))!!.lineup)
            assertEquals(participants, data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, MATCH))
            assertTrue(
                data.cloudUploads
                    .capture(session, 100)
                    .metadataRevisions
                    .isEmpty(),
            )
        }
    }

    @Test
    fun anotherAccountGetsNeitherWriteNorReadLane() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            assertNotNull(data.catalog(1))
            data.cloudUploads.deactivate()
            data.accountIdentities.select("legacy:$PHONE", "bob", 0, "PHONE") { error("keine neue ID") }
            data.assertLocked()
            assertNotNull(data.catalog(1))
            assertNull(data.proof())
            data.assertLocked()
            // A → B → A: zurück bei alice gilt die eigene Spur wieder.
            data.accountIdentities.select("legacy:$PHONE", "alice", 0, "PHONE") { error("keine neue ID") }
            assertTrue(data.readable())
            data.count(2)
        }
    }

    @Test
    fun foreignOrUnboundHeaderSourcesGetNoLane() {
        // Kopfquelle gehört bob, aktiv ist alice: Mitgliedschaft allein öffnet keine Spur.
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver)
            data.players.ensureSelf("Alex", 0) { PlayerId("me") }
            data.accountIdentities.initialize(PHONE, "me", "PHONE")
            data.accountIdentities.recordSource(BOB_DEVICE, "bob-self", "WATCH", "legacy:$PHONE", local = false)
            data.cloudUploads.bindOwnedDevices(data.cloudUploads.activate("bob"), setOf(BOB_DEVICE))
            data.cloudUploads.deactivate()
            data.events.append(
                ScoreEvent(
                    EventId("genesis"),
                    MatchId(MATCH),
                    DeviceId(BOB_DEVICE),
                    1,
                    1,
                    1,
                    null,
                    MatchCreated(format, Lineup(listOf(PlayerId("bob-self")), listOf(PlayerId("me")), PlayerId("bob-self"))),
                ).toStoredEvent(),
            )
            data.matches.insertIfAbsent(MatchId(MATCH), format, lineup, MatchMeta(), 1)
            data.accountIdentities.select("legacy:$PHONE", "alice", 0, "PHONE") { error("keine neue ID") }
            data.cloudUploads.bindOwnedDevices(data.cloudUploads.activate("alice"), setOf(PHONE))
            data.replicas.activate(data.replicas.captureContext()) {}
            assertEquals(BOB_DEVICE, data.cloud.metadataState(CloudMetadataKind.HEADER, MATCH)!!.sourceDeviceId)
            assertNotNull(data.catalog(1))
            data.assertLocked()
        }
        // Eigene, aber nie gebundene Altquelle nach dem ersten Cloudkontakt.
        createInMemoryJvmDriver().use { driver ->
            val (data, _) = ownMatch(driver, owned = emptySet())
            assertNotNull(data.catalog(1))
            data.assertLocked()
        }
    }

    @Test
    fun feedAndAbsentCloseTheLane() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            assertNotNull(data.catalog(1, access = "feed"))
            data.assertLocked()
        }
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            data.replicas.applyAccess(data.replicas.captureContext(), MATCH, 1, ReplicaAccessLevel.ABSENT) {}
            data.assertLocked()
        }
    }

    /**
     * Teil 2, P2-2: Neues G gesehen, Katalog unvollständig. Die Schreibspur gilt beim zuletzt vollständigen G weiter, auch nach
     * Abbruch und Neustart mitten im Neuscan; die Lesespur bleibt streng. FEED im neuen Katalog sperrt sofort.
     */
    @Test
    fun runningRescanKeepsTheWriteLaneAtTheLastCompleteGenerationUntilFeed() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            assertNotNull(data.catalog(1))
            data.count(2)
            assertNull(data.catalog(2, generation = 2))
            assertFalse(data.readable())
            data.count(3)
            data.matches.updateMeta(MatchId(MATCH), MatchMeta(venue = "Halle"))
            // Abbruch nach der ersten Seite des neuen G (ohne das Match), Neustart auf derselben Datenbank.
            data.otherPage(2, generation = 2, hasMore = true)
            val restarted = LocalData(driver)
            assertFalse(restarted.readable())
            restarted.count(4)
            assertEquals(4, restarted.events.maxDeviceSeq(MATCH, PHONE))
            // Ein fremdes Konto bekommt die Spur auch während des Neuscans nicht.
            restarted.accountIdentities.select("legacy:$PHONE", "bob", 0, "PHONE") { error("keine neue ID") }
            restarted.assertLocked(seq = 5)
            restarted.accountIdentities.select("legacy:$PHONE", "alice", 0, "PHONE") { error("keine neue ID") }
            restarted.count(5)
            // Seite des neuen G mit FEED: sofort gesperrt.
            assertNotNull(restarted.catalog(6, access = "feed", generation = 2))
            restarted.assertLocked(seq = 6)
        }
    }

    /** Teil 2, P2-2: Ist der Neuscan ohne das Match vollständig (ABSENT beim neuen G), sperrt die Schreibspur sofort. */
    @Test
    fun completedRescanWithoutTheMatchClosesTheWriteLane() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            assertNotNull(data.catalog(1))
            assertNull(data.catalog(2, generation = 2))
            data.count(2)
            data.otherPage(2, generation = 2, hasMore = false)
            data.assertLocked(seq = 3)
        }
    }

    /** Teil 2, P1-1: Bei offener Schreib- und gesperrter Lesespur folgen Seq, Lamport und Bestätigungen der Schreibspur. */
    @Test
    fun writeOnlyWindowKeepsSequencesLamportAndConfirmations() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            assertNotNull(data.catalog(1))
            data.count(2)
            val run = assertNotNull(data.catalog(2, epoch = 1))
            assertFalse(data.readable())
            assertEquals(2, data.events.maxDeviceSeq(MATCH, PHONE))
            assertEquals(12, data.events.maxLamport(MATCH))
            val emitted = StoredEvent("emit", MATCH, PHONE, 3, 13, """{"type":"PointWon","side":"A"}""", type = "PointWon")
            assertEquals(AppendResult.INSERTED, data.events.append(emitted))
            data.events.markConfirmed(MATCH, listOf(StoredSequence(PHONE, 3)))
            data.events.markConfirmedUpTo(MATCH, PHONE, 3)
            // Inhalte bleiben gesperrt; nur Zähler und Bestätigungen folgen der Schreibspur.
            assertTrue(data.events.sequences(MATCH).isEmpty())
            assertTrue(data.events.eventsFor(MATCH).isEmpty())
            // Nach dem Transfer ist der Punkt lesbar und geht hoch (U4 wartet bis zur angewandten Redaktion).
            val work = assertNotNull(data.cloudPulls.nextEventWork("alice", run))
            val page = assertNotNull(data.cloudPulls.redactionWork(work, run))
            assertTrue(data.cloudPulls.stageRedaction(work, run, page, 2, emptyList(), emptyList(), true, true) {})
            assertEquals(3, data.events.count(MATCH))
            assertTrue(
                data.cloudUploads
                    .capture(session, 100)
                    .events
                    .any { it.id == "emit" },
            )
        }
    }

    /** Teil 2, P2-1: Ein gemischter Batch zweier eigener Quellen geht bei gesperrtem Lesen durch, eine fremde Quelle nicht. */
    @Test
    fun mixedOwnSourceBatchSucceedsInTheWriteOnlyWindow() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver, creator = WATCH, owned = setOf(PHONE, WATCH), activateFirst = false)
            data.accountIdentities.recordSource(STRANGER, "stranger-self", "WATCH", "legacy:$PHONE", local = false)
            upload(data, session)
            assertNotNull(data.catalog(1))
            assertNotNull(data.catalog(2, epoch = 1))
            assertFalse(data.readable())
            assertEquals(
                listOf(AppendResult.INSERTED, AppendResult.INSERTED),
                data.events.appendAll(listOf(point("w2", WATCH, 2), point("p1", PHONE, 1))),
            )
            assertFailsWith<IllegalStateException> {
                data.events.appendAll(listOf(point("w3", WATCH, 3), point("s1", STRANGER, 1)))
            }
            assertEquals(2, data.events.maxDeviceSeq(MATCH, WATCH))
        }
    }

    /** Teil 2, P2-3: Ein anderes eigenes Gerät zählte weiter. Status und Summary folgen, lokale Felder bleiben, Beleg entsteht. */
    @Test
    fun newerServerHeadFromAnotherOwnDeviceIsAdoptedWithoutTouchingLocalFields() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            assertNotNull(data.catalog(1))
            assertEquals(1, data.proof())
            val before = row(driver)
            val ahead = ScoreSummary(eventCount = 40)
            assertNotNull(data.catalog(2, head(status = MatchStatus.FINISHED, score = ahead)))
            assertEquals("FINISHED", raw(driver, "status"))
            assertEquals(ahead, data.matches.get(MatchId(MATCH))!!.score)
            assertEquals(2, data.proof())
            val after = row(driver)
            listOf("lineup_json", "meta_json", "updated_at_ms", "format_json").forEach { assertEquals(before[it], after[it], it) }
            assertEquals(lineup, data.matches.get(MatchId(MATCH))!!.lineup)
            assertTrue(
                data.cloudUploads
                    .capture(session, 100)
                    .metadataRevisions
                    .isEmpty(),
            )
            // Eine erneut gelieferte ältere Fassung (Neuscan, gleiche serverVersion) dreht nicht zurück.
            assertNull(data.catalog(2, generation = 2))
            assertNotNull(data.catalog(2, generation = 2))
            assertEquals("FINISHED", raw(driver, "status"))
            assertEquals(after, row(driver))
        }
    }

    /** Teil 2, P2-3: Eine unbestätigte lokale Änderung bleibt, bis sie bestätigt ist; danach folgt der neuere Serverstand. */
    @Test
    fun unconfirmedLocalProgressIsKeptAgainstANewerServerHead() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            assertNotNull(data.catalog(1))
            data.count(2)
            val before = row(driver)
            assertNotNull(data.catalog(2, head(status = MatchStatus.FINISHED, score = ScoreSummary(eventCount = 40))))
            assertEquals(before, row(driver))
            assertNull(data.proof())
            upload(data, session)
            // Erst eine Seite nach dem Ack, die den Stand eines weiter gezählten eigenen Geräts zeigt, wird übernommen.
            assertNotNull(data.catalog(3, head(status = MatchStatus.FINISHED, score = ScoreSummary(eventCount = 41))))
            assertEquals("FINISHED", raw(driver, "status"))
            assertEquals("8002", raw(driver, "updated_at_ms"))
            assertEquals(3, data.proof())
        }
    }

    /**
     * Runde 4, P2-A: Der Server verwirft einen lokal vorhandenen Vorgänger nur, weil er noch nicht hochgeladen war
     * (rpc_sync: Anlage ohne Sicht). Der Link bleibt lokal, der Kopf gilt trotzdem als getragen; ein späteres Update heilt.
     */
    @Test
    fun predecessorDroppedWhileNotYetUploadedStaysLocalAndIsProven() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver, previous = MatchId(PRED), localPrevious = true)
            upload(data, session)
            val before = row(driver)
            assertNotNull(data.catalog(1))
            assertEquals(MatchId(PRED), data.matches.get(MatchId(MATCH))!!.previousMatchId)
            assertEquals(PRED, raw(driver, "previous_match_id"))
            assertEquals(1, data.proof())
            assertEquals(before, row(driver))
            assertTrue(
                data.cloudUploads
                    .capture(session, 100)
                    .metadataRevisions
                    .isEmpty(),
            )
        }
    }

    /** Runde 4, P3-A: Auch ohne Ack seit dem Beleg dreht eine Seite mit weniger Events Status und Summary nie zurück. */
    @Test
    fun newerServerHeadWithFewerEventsIsNeverAdopted() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            data.matches.updateProgress(MatchId(MATCH), MatchStatus.FINISHED, ScoreSummary(eventCount = 9), 7777)
            upload(data, session)
            assertNotNull(data.catalog(1, head(status = MatchStatus.FINISHED, score = ScoreSummary(eventCount = 9))))
            assertEquals(1, data.proof())
            val before = row(driver)
            assertNotNull(data.catalog(2, head(status = MatchStatus.IN_PROGRESS, score = ScoreSummary(eventCount = 5))))
            assertEquals("FINISHED", raw(driver, "status"))
            assertEquals(before, row(driver))
            assertNull(data.proof())
        }
    }

    /** Teil 2, P2-3: Der Server verwirft einen lokal nicht (mehr) vorhandenen Vorgänger: übernommen, danach belegt. */
    @Test
    fun predecessorDiscardedByTheServerIsAdopted() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver, previous = MatchId("before"))
            upload(data, session)
            val before = row(driver)
            assertNotNull(data.catalog(1))
            assertNull(data.matches.get(MatchId(MATCH))!!.previousMatchId)
            assertEquals(1, data.proof())
            assertEquals(before, row(driver))
            assertTrue(
                data.cloudUploads
                    .capture(session, 100)
                    .metadataRevisions
                    .isEmpty(),
            )
        }
    }

    /** E steigt am eigenen Match: Lesen erst nach vollständigem Transfer, Zählen der eigenen Quellen läuft weiter. */
    @Test
    fun redactionEpochRiseBlocksReadingUntilTheTransferButNotWriting() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            data.accountIdentities.recordSource(STRANGER, "stranger-self", "WATCH", "legacy:$PHONE", local = false)
            upload(data, session)
            assertNotNull(data.catalog(1))
            assertTrue(data.readable())
            val run = assertNotNull(data.catalog(2, epoch = 1))
            assertFalse(data.readable())
            assertNull(data.matches.get(MatchId(MATCH)))
            assertEquals(0, data.events.count(MATCH))
            data.count(2)
            data.matches.updateMeta(MatchId(MATCH), MatchMeta(venue = "Halle"))
            // Eine ungebundene Quelle bekommt keine Schreibspur.
            assertFailsWith<IllegalStateException> { data.events.append(point("stranger", STRANGER, 1)) }
            val work = assertNotNull(data.cloudPulls.nextEventWork("alice", run))
            val first = assertNotNull(data.cloudPulls.redactionWork(work, run))
            assertFalse(data.cloudPulls.stageRedaction(work, run, first, 2, listOf("redacted-id"), emptyList(), true, false) {})
            // Offener Transfer: weiterhin gesperrt, Zählen weiter möglich.
            assertFalse(data.readable())
            data.count(3)
            val rest = assertNotNull(data.cloudPulls.redactionWork(work, run))
            assertTrue(data.cloudPulls.stageRedaction(work, run, rest, 2, emptyList(), emptyList(), true, true) {})
            assertTrue(data.readable())
            assertFalse(data.ready())
            assertEquals(3, data.events.count(MATCH))
            assertEquals(
                "Halle",
                data.matches
                    .get(MatchId(MATCH))!!
                    .meta.venue,
            )
        }
    }

    /** Uhr als PEER-Kopfquelle des eigenen Kontos und Handy als zweite eigene Quelle im selben Match. */
    @Test
    fun watchHeaderAndSeveralOwnSourcesCountDuringThePull() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver, creator = WATCH, owned = setOf(PHONE, WATCH), activateFirst = false)
            val header = data.cloud.metadataState(CloudMetadataKind.HEADER, MATCH)!!
            assertEquals(CloudMetadataOrigin.PEER, header.origin)
            assertEquals(WATCH, header.sourceDeviceId)
            upload(data, session)
            assertNotNull(data.catalog(1))
            assertEquals(1, data.proof())
            assertTrue(data.readable())
            data.count(2, WATCH)
            data.count(1, PHONE)
            data.events.appendAll(listOf(point("w3", WATCH, 3), point("p2", PHONE, 2)))
            assertEquals(5, data.events.count(MATCH))
            // Auch bei gesperrtem Lesen (E steigt) schreiben beide eigenen Quellen weiter.
            assertNotNull(data.catalog(2, epoch = 1))
            assertFalse(data.readable())
            data.count(4, WATCH)
            data.count(3, PHONE)
        }
    }

    /** Neustart zwischen Katalog-Commit und Readiness: neue LocalData auf derselben Datenbank. */
    @Test
    fun restartInTheMiddleOfThePullKeepsBothLanesAndReadinessCompletes() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            assertNotNull(data.catalog(1))
            val restarted = LocalData(driver)
            assertTrue(restarted.readable())
            assertFalse(restarted.ready())
            restarted.count(2)
            // Der neue Prozess bindet einen neuen Lauf; Katalog und Eventaufträge der Runde bleiben.
            val run = restarted.cloudPulls.beginRun("alice") {}.runSerial
            val work = assertNotNull(restarted.cloudPulls.nextEventWork("alice", run))
            restarted.cloudPulls.commitEvents(work, run, listOf(CloudPullReceipt(genesis(), 1)), {}, endOfStream = true)
            val current = restarted.cloudPulls.eventWork("alice", MATCH)!!
            assertEquals(
                CloudPullReadinessResult.READY,
                restarted.cloudPulls.applyReadiness(
                    current,
                    run,
                    restarted.cloudPulls.readinessWork(current, run)!!,
                    1,
                    1,
                    listOf(CloudPullWatermark(PHONE, 1, 1)),
                ) {},
            )
            assertTrue(restarted.ready())
            restarted.count(3)
            assertEquals(3, restarted.events.count(MATCH))
        }
    }

    private fun LocalData.assertNoBacklogContent() {
        assertTrue(events.unconfirmedMatches().isEmpty())
        assertTrue(events.unconfirmedEvents(MATCH).isEmpty())
        assertTrue(events.sequences(MATCH).isEmpty())
        assertTrue(events.eventsInRange(MATCH, PHONE, 1, 9).isEmpty())
    }

    private fun LocalData.assertNoBacklog() {
        assertEquals(0, events.unconfirmedCount())
        assertTrue(events.unconfirmedCountByMatch().isEmpty())
        assertNoBacklogContent()
    }

    /**
     * D-1: Im Fenster „schreiben ja, lesen nein“ (laufender Neuscan) zählen und liefern die Rückstandsabfragen die Events
     * eigener Quellen; eine fremde Quelle im selben Match bleibt an der Lesespur.
     */
    @Test
    fun ownBacklogFollowsTheWriteLaneDuringARescan() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            data.accountIdentities.recordSource(STRANGER, "stranger-self", "WATCH", "legacy:$PHONE", local = false)
            upload(data, session)
            assertNotNull(data.catalog(1))
            data.events.append(point("s1", STRANGER, 1))
            data.count(2)
            assertNull(data.catalog(2, generation = 2))
            assertFalse(data.readable())
            data.count(3)
            assertEquals(mapOf(MATCH to 3L), data.events.unconfirmedCountByMatch())
            assertEquals(3, data.events.unconfirmedCount())
            assertEquals(listOf(MATCH), data.events.unconfirmedMatches())
            assertEquals(listOf("genesis", "pphone2", "pphone3"), data.events.unconfirmedEvents(MATCH).map { it.id })
            assertEquals((1L..3L).map { StoredSequence(PHONE, it) }, data.events.sequences(MATCH))
            assertEquals(listOf("pphone2", "pphone3"), data.events.eventsInRange(MATCH, PHONE, 2, 3).map { it.id })
            assertTrue(data.events.eventsInRange(MATCH, STRANGER, 1, 1).isEmpty())
            // Die Bestätigung der Gegenstelle senkt den Rückstand.
            data.events.markConfirmedUpTo(MATCH, PHONE, 2)
            assertEquals(1, data.events.unconfirmedCount())
            assertEquals(listOf("pphone3"), data.events.unconfirmedEvents(MATCH).map { it.id })
            data.events.markConfirmedUpTo(MATCH, PHONE, 3)
            assertEquals(0, data.events.unconfirmedCount())
            assertTrue(data.events.unconfirmedMatches().isEmpty())
        }
    }

    /** D-1: Bei ausstehender Redaktion zählt der eigene Rückstand, Inhalte und Sequenzen kommen erst nach dem Transfer heraus. */
    @Test
    fun pendingRedactionCountsTheOwnBacklogButReleasesNoContent() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            assertNotNull(data.catalog(1))
            val run = assertNotNull(data.catalog(2, epoch = 1))
            assertFalse(data.readable())
            data.count(2)
            assertEquals(mapOf(MATCH to 2L), data.events.unconfirmedCountByMatch())
            assertEquals(2, data.events.unconfirmedCount())
            data.assertNoBacklogContent()
            val work = assertNotNull(data.cloudPulls.nextEventWork("alice", run))
            val first = assertNotNull(data.cloudPulls.redactionWork(work, run))
            assertFalse(data.cloudPulls.stageRedaction(work, run, first, 2, listOf("redacted-id"), emptyList(), true, false) {})
            // Offener Transfer: weiterhin keine Inhalte.
            assertEquals(2, data.events.unconfirmedCount())
            data.assertNoBacklogContent()
            val rest = assertNotNull(data.cloudPulls.redactionWork(work, run))
            assertTrue(data.cloudPulls.stageRedaction(work, run, rest, 2, emptyList(), emptyList(), true, true) {})
            // Neuscan schließt die Lesespur: die Inhalte kommen jetzt über den Own-Content-Pfad (Redaktion bereinigt).
            assertNull(data.catalog(3, epoch = 1, generation = 2))
            assertFalse(data.readable())
            assertEquals(listOf("genesis", "pphone2"), data.events.unconfirmedEvents(MATCH).map { it.id })
            assertEquals(listOf(MATCH), data.events.unconfirmedMatches())
            assertEquals((1L..2L).map { StoredSequence(PHONE, it) }, data.events.sequences(MATCH))
            assertEquals(listOf("pphone2"), data.events.eventsInRange(MATCH, PHONE, 2, 2).map { it.id })
        }
    }

    /** D-1: Mehrere eigene Quellen (Uhr + Handy) im Neuscan-Fenster: Zähler und Inhalte je Gerät deckungsgleich, fremde Quelle fehlt. */
    @Test
    fun multipleOwnDevicesShareTheBacklogDuringARescan() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver, creator = WATCH, owned = setOf(PHONE, WATCH), activateFirst = false)
            data.accountIdentities.recordSource(STRANGER, "stranger-self", "WATCH", "legacy:$PHONE", local = false)
            upload(data, session)
            assertNotNull(data.catalog(1))
            data.events.append(point("s1", STRANGER, 1))
            data.count(2, WATCH)
            data.count(1, PHONE)
            assertNull(data.catalog(2, generation = 2))
            assertFalse(data.readable())
            data.count(3, WATCH)
            data.count(2, PHONE)
            assertEquals(mapOf(MATCH to 5L), data.events.unconfirmedCountByMatch())
            assertEquals(
                setOf(PHONE, WATCH),
                data.events
                    .unconfirmedEvents(MATCH)
                    .map { it.deviceId }
                    .toSet(),
            )
            assertEquals(5, data.events.unconfirmedEvents(MATCH).size)
            val sequences = data.events.sequences(MATCH)
            assertEquals(setOf(PHONE, WATCH), sequences.map { it.deviceId }.toSet())
            sequences.groupBy { it.deviceId }.forEach { (device, own) ->
                assertEquals(own.size, data.events.eventsInRange(MATCH, device, 1, 99).size)
            }
            assertTrue(data.events.eventsInRange(MATCH, STRANGER, 1, 99).isEmpty())
            // Bestätigt nur die Uhr, bleibt der Rückstand des Handys.
            data.events.markConfirmedUpTo(MATCH, WATCH, 3)
            assertEquals(mapOf(MATCH to 2L), data.events.unconfirmedCountByMatch())
            assertEquals(listOf("pphone1", "pphone2"), data.events.unconfirmedEvents(MATCH).map { it.id })
        }
    }

    /** D-1: Eine Quelle mit Owner in einem fremden Konto bleibt im eigenen Match verborgen, auch wenn sie eigen aussieht. */
    @Test
    fun ownLookingDeviceOfAForeignAccountStaysHidden() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            data.accountIdentities.recordSource(BOB_DEVICE, "bob-self", "WATCH", "legacy:$PHONE", local = false)
            driver.execute(null, "INSERT INTO cloud_device_owner VALUES ('$BOB_DEVICE','bob')", 0)
            upload(data, session)
            assertNotNull(data.catalog(1))
            data.events.append(point("b1", BOB_DEVICE, 1))
            assertNull(data.catalog(2, generation = 2))
            assertFalse(data.readable())
            data.count(2)
            assertEquals(mapOf(MATCH to 2L), data.events.unconfirmedCountByMatch())
            assertTrue(data.events.unconfirmedEvents(MATCH).none { it.deviceId == BOB_DEVICE })
            assertTrue(data.events.sequences(MATCH).none { it.deviceId == BOB_DEVICE })
            assertTrue(data.events.eventsInRange(MATCH, BOB_DEVICE, 1, 9).isEmpty())
        }
    }

    /** D-1: Fremdes Konto, FEED und ABSENT schließen die Schreibspur; dann kommt auch kein eigener Rückstand heraus. */
    @Test
    fun closedWriteLaneExposesNoOwnBacklog() {
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            assertNotNull(data.catalog(1))
            data.count(2)
            assertNull(data.catalog(2, generation = 2))
            data.cloudUploads.deactivate()
            data.accountIdentities.select("legacy:$PHONE", "bob", 0, "PHONE") { error("keine neue ID") }
            data.assertNoBacklog()
        }
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            data.count(2)
            assertNotNull(data.catalog(1, access = "feed"))
            data.assertNoBacklog()
        }
        createInMemoryJvmDriver().use { driver ->
            val (data, session) = ownMatch(driver)
            upload(data, session)
            data.count(2)
            data.replicas.applyAccess(data.replicas.captureContext(), MATCH, 1, ReplicaAccessLevel.ABSENT) {}
            data.assertNoBacklog()
        }
    }

    private companion object {
        const val MATCH = "own-match"
        const val PHONE = "phone"
        const val WATCH = "watch"
        const val BOB_DEVICE = "bob-device"
        const val STRANGER = "stranger"
        const val PRED = "pred-match"
    }
}
