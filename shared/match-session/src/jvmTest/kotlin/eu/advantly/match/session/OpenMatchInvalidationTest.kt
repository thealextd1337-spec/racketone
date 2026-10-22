package eu.advantly.match.session

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.data.CloudPullMatch
import eu.advantly.data.CloudPullMetadataPage
import eu.advantly.data.LocalData
import eu.advantly.data.MatchRecord
import eu.advantly.data.ReplicaAccessLevel
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.data.scoreEvents
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.PeerKind
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Vertrag W3 §3.3: Data-Änderungen erreichen offene Sessions, ohne Live-Zählen eigener Matches zu blockieren. */
class OpenMatchInvalidationTest {
    private val scheduler = ManualScheduler()
    private val driver = createInMemoryJvmDriver()
    private val data = LocalData(driver)
    private val session =
        MatchSession(
            data,
            null,
            scheduler,
            SessionConfig(MatchSessions.deviceId(data), PeerKind.PHONE),
            WallClock { 1_760_000_000_000 + scheduler.nowMs() },
            RandomIdGenerator(Random(3)),
        )

    @AfterTest
    fun tearDown() {
        session.close()
        driver.close()
    }

    private fun signIn(): eu.advantly.data.CloudUploadSession {
        data.players.ensureSelf("Alex", 0) { PlayerId("phone-self") }
        session.start()
        val token = data.cloudUploads.activate("alice")
        session.activateAccount("alice", token)
        data.replicas.activate(data.replicas.captureContext()) {}
        scheduler.runUntilIdle()
        return token
    }

    /** Katalogseite bei G 1 mit dem lokalen Kopf als Serverkopf und Redaktionsepoche [epoch] (Pull im Hintergrund). */
    private fun catalog(
        version: Long,
        record: MatchRecord,
        epoch: Long,
    ): Long {
        val run = data.cloudPulls.beginRun("alice") {}.runSerial
        val match = CloudPullMatch(record.id.value, version, """{"version":$version}""", "member", 1, epoch, false, record)
        val page = CloudPullMetadataPage(listOf(match), emptyList(), emptyList(), version, false, 1)
        assertTrue(data.cloudPulls.commitMetadata("alice", run, data.cloudPulls.progress("alice").cursor, page) {})
        return run
    }

    private fun storedPoints(match: MatchId): Int =
        driver
            .executeQuery(
                null,
                "SELECT count(*) FROM match_event WHERE match_id = '${match.value}' AND type = 'PointWon'",
                { c ->
                    c.next()
                    app.cash.sqldelight.db.QueryResult
                        .Value(c.getLong(0)!!.toInt())
                },
                0,
            ).value

    @Test
    fun revokedMatchClosesAndCommandsFailWithoutThrowing() {
        signIn()
        val match = session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        assertTrue(session.recordPoint(Side.A))
        val revision = session.state.value.lifecycleRevision

        // Pull im Hintergrund entzieht das Match (FEED bei G 1); die Session hat das Signal noch nicht verarbeitet.
        data.replicas.applyAccess(data.replicas.captureContext(), match.value, 1, ReplicaAccessLevel.FEED) {}
        assertFalse(session.recordPoint(Side.A), "Schreibspur zu: kein Punkt, keine Exception bis zur UI")
        assertEquals(
            1,
            session.state.value.match!!
                .pointsPlayed,
        )

        scheduler.runUntilIdle()
        assertNull(session.state.value.match, "weder lesbar noch beschreibbar: offenes Match schließt")
        assertNotEquals(revision, session.state.value.lifecycleRevision)
    }

    @Test
    fun ownPointsNeverReplayAndAClosedReadLaneKeepsCounting() {
        val token = signIn()
        val match = session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        assertTrue(session.recordPoint(Side.A))
        val uploaded = data.cloudUploads.capture(token, 100)
        data.cloudUploads.acknowledge(uploaded, uploaded.events.mapTo(mutableSetOf()) { it.id }, metadata = uploaded.metadataRevisions)
        val record = data.matches.get(match)!!
        catalog(1, record, epoch = 0)
        scheduler.runUntilIdle()
        val replays = session.lifecycleReplays
        val revision = session.state.value.lifecycleRevision

        repeat(5) { assertTrue(session.recordPoint(Side.B)) }
        scheduler.runUntilIdle()
        assertEquals(replays, session.lifecycleReplays, "eigene Punkte bauen die Engine nie neu auf")
        assertEquals(revision, session.state.value.lifecycleRevision, "eigener Kopfstand ist keine Namensänderung")

        // Redaktion angekündigt: Lesespur zu, Schreibspur offen – Engine bleibt, Zählen geht weiter
        val run = catalog(2, record, epoch = 1)
        scheduler.runUntilIdle()
        assertFalse(data.replicas.canReadMember(data.replicas.captureContext(), match.value))
        assertEquals(
            6,
            session.state.value.match!!
                .pointsPlayed,
        )
        assertTrue(session.recordPoint(Side.A))
        assertEquals(
            7,
            session.state.value.match!!
                .pointsPlayed,
        )

        // Transfer abgeschlossen: wieder lesbar, einmaliger Neuaufbau aus dem Store ergibt denselben Stand
        val work = checkNotNull(data.cloudPulls.nextEventWork("alice", run))
        val redaction = checkNotNull(data.cloudPulls.redactionWork(work, run))
        assertTrue(data.cloudPulls.stageRedaction(work, run, redaction, 2, emptyList(), emptyList(), true, true) {})
        scheduler.runUntilIdle()
        assertTrue(session.lifecycleReplays > replays)
        assertEquals(
            7,
            session.state.value.match!!
                .pointsPlayed,
        )
        assertEquals(7, storedPoints(match))
        assertEquals(
            data.events.scoreEvents(match).size,
            session.state.value.match!!
                .eventCount,
        )
    }

    /** Eigenes Match im Fenster zwischen Katalog und Readiness (Lesespur zu, Schreibspur offen), Zählstand 1:1. */
    private fun ownMatchInTheWindow(): MatchId {
        val token = signIn()
        val match = session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        assertTrue(session.recordPoint(Side.A))
        val uploaded = data.cloudUploads.capture(token, 100)
        data.cloudUploads.acknowledge(uploaded, uploaded.events.mapTo(mutableSetOf()) { it.id }, metadata = uploaded.metadataRevisions)
        val record = data.matches.get(match)!!
        catalog(1, record, epoch = 0)
        assertTrue(session.recordPoint(Side.B))
        catalog(2, record, epoch = 1)
        scheduler.runUntilIdle()
        assertFalse(data.replicas.canReadMember(data.replicas.captureContext(), match.value))
        return match
    }

    @Test
    fun aPullCommitInTheWindowNeverClosesTheOwnLiveMatch() {
        val match = ownMatchInTheWindow()
        session.eventsCommitted(match.value)
        scheduler.runUntilIdle()
        assertEquals(
            match,
            session.state.value.match
                ?.id,
        )
        assertTrue(session.recordPoint(Side.A))
        assertEquals(
            3,
            session.state.value.match!!
                .pointsPlayed,
        )
        assertEquals(3, storedPoints(match))
    }

    @Test
    fun aRefusedDeletionLeavesNoStampAndKeepsTheMatchOpen() {
        val match = ownMatchInTheWindow()
        assertFailsWith<IllegalStateException> { session.deleteMatch(match) }
        assertNull(data.settings.get("session.deletedStamp.${match.value}"), "kein Beleg für eine Löschung, die nicht stattfand")
        assertFalse(data.matches.isDeleted(match))
        assertEquals(
            match,
            session.state.value.match
                ?.id,
        )
        assertTrue(session.recordPoint(Side.A))
    }

    @Test
    fun aDeletedMatchLeavesNoSessionSettings() {
        data.players.ensureSelf("Alex", 0) { PlayerId("phone-self") }
        session.start()
        val match = session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        val keys = {
            data.settings
                .all()
                .keys
                .filter { it.endsWith(match.value) }
        }
        assertEquals(listOf("session.neverSentToWatch.${match.value}"), keys())

        session.deleteMatch(match)
        assertTrue(data.matches.isDeleted(match))
        assertEquals(emptyList(), keys())
    }

    @Test
    fun headerAdoptionReachesTheOpenMatch() {
        data.players.ensureSelf("Alex", 0) { PlayerId("phone-self") }
        session.start()
        val previous = session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        val match = session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        assertNull(
            session.state.value.match!!
                .previousMatchId,
        )
        val revision = session.state.value.lifecycleRevision

        // Kopfübernahme eines anderen Schreibers (Pull): Vorgänger kommt nachträglich
        val record = data.matches.get(match)!!
        data.matches.insertIfAbsent(match, record.format, record.lineup, record.meta, record.createdAtMs, previous)
        scheduler.runUntilIdle()
        assertEquals(
            previous,
            session.state.value
                .match(match)!!
                .previousMatchId,
        )
        assertNotEquals(revision, session.state.value.lifecycleRevision)
        assertEquals(0, storedPoints(match))
        assertTrue(data.events.scoreEvents(match).none { it.payload is PointWon })
    }
}
