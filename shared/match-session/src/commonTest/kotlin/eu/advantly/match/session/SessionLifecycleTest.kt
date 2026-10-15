package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.PendingQuestion
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ResultReason
import eu.advantly.core.scoring.SidePair
import eu.advantly.data.SetScore
import eu.advantly.device.link.LinkState
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** API der Session: Undo/Redo, Satzende-Dialog, Matchende, Folge-Match, Resume, Listener, Standalone. */
class SessionLifecycleTest {
    private fun standalone(): Pair<MatchSession, eu.advantly.data.LocalData> {
        val data = testLocalData()
        val scheduler = ManualScheduler()
        val session = MatchSessions.create(data, null, scheduler, PeerKind.WATCH, clock = { 1_000 + scheduler.nowMs() })
        session.start()
        return session to data
    }

    @Test
    fun standaloneSessionCountsLocally() {
        val (session, data) = standalone()
        assertNull(session.state.value.match)
        assertEquals(LinkState.UNAVAILABLE, session.state.value.sync.link)
        assertEquals(SyncIndicator.LOCAL_ONLY, session.state.value.sync.indicator)
        assertFalse(session.recordPoint(Side.A), "ohne Match")
        val id = session.create(Presets.PADEL_PREMIER_2026.format(), Lineup4)
        assertTrue(session.recordPoint(Side.A))
        assertEquals(
            SidePair(1, 0),
            session.state.value.match
                ?.state
                ?.current
                ?.points,
        )
        // stabile Geräte-ID in den Settings
        assertEquals(session.deviceId, MatchSessions.deviceId(data))
        assertEquals(
            id,
            data.matches
                .active()
                .single()
                .id,
        )
        assertEquals(2, data.events.count(id.value))
    }

    @Test
    fun invalidFormatOrLineupIsRejected() {
        val (session, _) = standalone()
        assertFailsWith<IllegalArgumentException> { session.create(Presets.PADEL_PREMIER_2026.format(), SINGLES) }
        assertNull(session.state.value.match)
    }

    @Test
    fun undoAndRedoWorkAcrossDevices() {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        val phone = cluster["phone"]
        val watch = cluster["watch"]
        phone.session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        cluster.settle()
        assertFalse(watch.match.canUndo)
        repeat(2) { phone.session.recordPoint(Side.A) }
        cluster.settle()
        assertTrue(watch.session.undo())
        cluster.settle()
        assertEquals(SidePair(1, 0), phone.score.current?.points)
        assertTrue(watch.match.canRedo)
        assertFalse(phone.match.canRedo, "Redo-Stapel ist gerätelokal")
        assertTrue(watch.session.redo())
        cluster.settle()
        assertEquals(SidePair(2, 0), phone.score.current?.points)
        assertFalse(watch.match.canRedo)

        assertTrue(watch.session.undo())
        cluster.settle()
        phone.session.recordPoint(Side.B)
        cluster.settle()
        assertFalse(watch.match.canRedo, "neuer Punkt verwirft das Redo")
        assertFalse(watch.session.redo())
        cluster.assertConverged()
    }

    @Test
    fun openModeAsksForAnotherSetOnAllDevices() {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        val phone = cluster["phone"]
        val watch = cluster["watch"]
        phone.session.create(Presets.TENNIS_OPEN.format(), SINGLES)
        cluster.settle()
        repeat(24) { phone.session.recordPoint(Side.A) }
        cluster.settle()
        assertEquals(PendingQuestion.ASK_ANOTHER_SET, watch.score.pending)
        assertFalse(watch.session.recordPoint(Side.A), "Satzende-Dialog offen")
        assertFalse(watch.session.continueDecision(anotherSet = false, matchTiebreak = true))
        assertTrue(watch.session.continueDecision(anotherSet = true))
        cluster.settle()
        assertEquals(MatchStatus.IN_PROGRESS, phone.score.status)
        assertEquals(1, phone.score.setsWon.a)

        repeat(24) { watch.session.recordPoint(Side.B) }
        cluster.settle()
        assertTrue(phone.session.endMatch(), "im Dialog = ContinueDecision(false)")
        cluster.settle()
        cluster.assertConverged()
        assertEquals(ResultReason.DRAW, watch.score.result?.reason)
        val record = assertNotNull(watch.data.matches.get(watch.match.id))
        assertTrue(record.isFinished)
        assertEquals(listOf(SetScore(6, 0), SetScore(0, 6)), record.score.sets)
        assertFalse(watch.session.endMatch())
    }

    @Test
    fun retirementNeedsASide() {
        val (session, _) = standalone()
        session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        assertFalse(session.endMatch(EndReason.RETIRED))
        assertTrue(session.endMatch(EndReason.RETIRED, Side.B))
        assertEquals(
            Side.A,
            session.state.value.match
                ?.state
                ?.result
                ?.winner,
        )
    }

    @Test
    fun followUpMatchIsLinkedAndFollowedByTheWatch() {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        val phone = cluster["phone"]
        val watch = cluster["watch"]
        assertNull(phone.session.createFollowUp())
        val first = phone.session.create(Presets.TENNIS_ONE_SET.format(), SINGLES)
        cluster.settle()
        repeat(24) { watch.session.recordPoint(Side.A) }
        cluster.settle()
        assertEquals(MatchStatus.FINISHED, phone.score.status)
        val continuation = assertNotNull(phone.score.continuation)

        val second = assertNotNull(phone.session.createFollowUp())
        cluster.settle()
        assertEquals(second, watch.match.id, "Uhr folgt dem Folge-Match")
        assertEquals(first, watch.match.previousMatchId)
        assertEquals(MatchMode.OPEN, watch.match.format.match.mode)
        assertNull(watch.match.format.decidingSet)
        assertEquals(continuation.player, watch.match.lineup.firstServer)
        assertEquals(
            listOf(second),
            phone.data.matches
                .followUps(first)
                .map { it.id },
        )
        assertEquals(
            listOf(second),
            watch.data.matches
                .followUps(first)
                .map { it.id },
        )
        assertEquals(
            listOf(second, first),
            watch.data.matches
                .history()
                .map { it.id },
        )
        cluster.assertConverged()
    }

    @Test
    fun resumeRestoresTheMatchAfterARestart() {
        val (session, data) = standalone()
        val id = session.create(Presets.PICKLEBALL_DOUBLES_11_SIDEOUT.format(), Lineup4)
        repeat(5) { session.recordPoint(if (it % 2 == 0) Side.A else Side.B) }
        session.correct(Correction.server(PlayerId("bea")))
        val before = session.state.value.match
        session.close()

        val restarted = MatchSession(data, null, ManualScheduler(), session.config, clock = { 50_000 })
        assertNull(restarted.state.value.match)
        assertFalse(
            restarted.resume(
                eu.advantly.core.model
                    .MatchId("unknown"),
            ),
        )
        assertTrue(restarted.resume(id))
        assertEquals(
            before?.state,
            restarted.state.value.match
                ?.state,
        )
        assertTrue(restarted.recordPoint(Side.A), "deviceSeq geht lückenlos weiter")
        assertEquals(8, data.events.count(id.value))
        restarted.closeMatch()
        assertNull(restarted.state.value.match)
    }

    @Test
    fun listenerGetsCurrentStateAndChanges() {
        val (session, _) = standalone()
        val seen = mutableListOf<SessionState>()
        val listener = SessionListener { seen += it }
        session.addListener(listener)
        assertEquals(1, seen.size)
        session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        session.recordPoint(Side.A)
        assertTrue(seen.size >= 3)
        assertEquals(session.state.value, seen.last())
        session.removeListener(listener)
        val count = seen.size
        session.recordPoint(Side.A)
        assertEquals(count, seen.size)
    }

    @Test
    fun pauseAndResumePlay() {
        val (session, _) = standalone()
        session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        assertFalse(session.resumePlay())
        assertTrue(session.pause())
        assertEquals(
            MatchStatus.PAUSED,
            session.state.value.match
                ?.state
                ?.status,
        )
        assertTrue(session.resumePlay())
        assertEquals(
            MatchStatus.IN_PROGRESS,
            session.state.value.match
                ?.state
                ?.status,
        )
    }

    @Test
    fun deviceIdIsStable() {
        val data = testLocalData()
        val first = MatchSessions.deviceId(data)
        assertEquals(first, MatchSessions.deviceId(data))
        assertTrue(
            eu.advantly.core.model.Uuid7
                .parseOrNull(first.value) != null,
        )
        assertEquals(first, MatchSessions.create(data, null, ManualScheduler(), PeerKind.PHONE).deviceId)
        assertTrue(first != DeviceId(""))
    }

    private companion object {
        val Lineup4 =
            eu.advantly.core.model.Lineup(
                listOf(PlayerId("me"), PlayerId("max")),
                listOf(PlayerId("bea"), PlayerId("cem")),
                PlayerId("me"),
            )
    }
}
