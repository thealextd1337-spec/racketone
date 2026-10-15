package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Undo über Spiel-/Satz-/Matchgrenzen, effektive Events, Ordnung, Doppel-Taps (02 §7.3, 01 §4.4). */
class UndoAndLogTest {
    private fun ev(
        id: String,
        lamport: Long,
        payload: EventPayload,
        device: String = "d",
        parent: String? = null,
        wallClockMs: Long = lamport * 1_000L,
    ) = ScoreEvent(EventId(id), MatchId("m"), DeviceId(device), lamport, lamport, wallClockMs, parent?.let { EventId(it) }, payload)

    @Test
    fun undoAcrossSetBoundaryRestoresEverything() {
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val m = MatchDriver(format)
        repeat(5) { m.points("AAAA") }
        m.points("BBBB")
        m.points("AAA")
        val beforeSetBall = m.state
        m.point(Side.A)
        assertEquals(1, m.state.setsWon.a)
        m.undo()
        assertEquals(beforeSetBall.copy(effects = listOf(Effect.UNDONE)), m.state)
    }

    @Test
    fun undoAfterMatchEndReopensMatch() {
        val m = MatchDriver(Presets.TABLE_TENNIS_SINGLES_BO3.format())
        m.points("AAAAAAAAAAA")
        m.points("AAAAAAAAAA")
        val before = m.state
        m.point(Side.A)
        assertEquals(MatchStatus.FINISHED, m.state.status)
        m.undo()
        assertEquals(before.copy(effects = listOf(Effect.UNDONE)), m.state)
    }

    @Test
    fun redoIsUndoOfUndo() {
        val m = MatchDriver(Presets.PADEL_GOLDEN_BO3.format())
        m.points("AB")
        val afterPoint = m.state
        val undo = m.undo()
        m.send(Undo(undo))
        assertEquals(afterPoint.copy(effects = listOf(Effect.UNDONE)), m.state)
    }

    @Test
    fun undoOfMiddleEventMakesLaterEventsInvalid() {
        val m = MatchDriver(Presets.TENNIS_OPEN.format())
        repeat(5) { m.points("AAAA") }
        m.points("AAA")
        val setBall = m.point(Side.A)
        val decision = m.send(ContinueDecision(true))
        m.point(Side.B)
        m.send(Undo(setBall))
        // ohne Satzball gibt es keine offene Frage → ContinueDecision wird übersprungen; der B-Punkt zählt im Satz 1
        assertEquals(listOf(decision), m.state.ignoredEvents)
        assertEquals(SidePair(3, 1), checkNotNull(m.state.current).points)
        assertEquals(MatchStatus.IN_PROGRESS, m.state.status)
    }

    @Test
    fun undoOfUnknownTargetIsNoOp() {
        val m = MatchDriver(Presets.PICKLEBALL_SINGLES_11_SIDEOUT.format()).points("AA")
        val before = m.state
        m.send(Undo(EventId("does-not-exist")))
        assertEquals(before.copy(effects = listOf(Effect.UNDONE)), m.state)
    }

    @Test
    fun pausedResumedAndPointDetailSet() {
        val m = MatchDriver(Presets.BADMINTON_SINGLES_BO3_15.format())
        m.send(Resumed)
        assertEquals(listOf(Effect.IGNORED), m.state.effects)
        m.send(Paused)
        m.send(Paused)
        assertEquals(listOf(Effect.IGNORED), m.state.effects)
        m.send(Resumed)
        assertEquals(listOf(Effect.RESUMED), m.state.effects)
        val point = m.point(Side.A)
        m.send(PointDetailSet(point, PointDetail(PointDetailKind.ACE)))
        assertEquals(emptyList(), m.state.effects)
        m.send(PointDetailSet(EventId("nope"), null))
        assertEquals(listOf(Effect.IGNORED), m.state.effects)
        assertEquals(SidePair(1, 0), checkNotNull(m.state.current).points)
    }

    @Test
    fun orderingIsByLamportThenDeviceThenId() {
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val lineup = lineupFor(format.discipline)
        val events =
            listOf(
                ev("z", 1, PointWon(Side.A), device = "b"),
                ev("y", 1, PointWon(Side.B), device = "a"),
                ev("x", 2, Undo(EventId("z"))),
            )
        val forward = ScoringEngine.fold(format, lineup, events)
        val backward = ScoringEngine.fold(format, lineup, events.reversed())
        assertEquals(forward, backward)
        assertEquals(SidePair(0, 1), checkNotNull(forward.current).points)
        assertEquals(listOf("y", "z", "x"), EventLog.ordered(events).map { it.id.value })
    }

    @Test
    fun duplicatesAreIdempotent() {
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val lineup = lineupFor(format.discipline)
        val events = listOf(ev("e1", 1, PointWon(Side.A)), ev("e2", 2, PointWon(Side.A)))
        assertEquals(ScoringEngine.fold(format, lineup, events), ScoringEngine.fold(format, lineup, events + events))
    }

    @Test
    fun doubleTapIsAbsorbedAndContradictionReported() {
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val lineup = lineupFor(format.discipline)
        val tapWatch = ev("w1", 2, PointWon(Side.A), device = "watch", parent = "g", wallClockMs = 100_000)
        val tapPhone = ev("p1", 2, PointWon(Side.A), device = "phone", parent = "g", wallClockMs = 102_000)
        val replay = ScoringEngine.replay(format, lineup, listOf(tapWatch, tapPhone))
        assertEquals(listOf(EventId("w1")), replay.absorbedEvents)
        assertEquals(SidePair(1, 0), checkNotNull(replay.state.current).points)
        assertEquals(emptyList(), replay.state.effects, "absorbiertes Event ohne Effekt")

        val late = tapPhone.copy(wallClockMs = 106_000)
        assertEquals(SidePair(2, 0), checkNotNull(ScoringEngine.fold(format, lineup, listOf(tapWatch, late)).current).points)

        val other = tapPhone.copy(payload = PointWon(Side.B))
        val conflict = ScoringEngine.replay(format, lineup, listOf(tapWatch, other))
        assertEquals(listOf(TapConflict(EventId("p1"), EventId("w1"))), conflict.conflicts)
        assertEquals(SidePair(1, 1), checkNotNull(conflict.state.current).points)
    }

    @Test
    fun foldOverFullLogWithGenesis() {
        val format = Presets.PICKLEBALL_DOUBLES_11_SIDEOUT.format()
        val lineup = lineupFor(format.discipline)
        val log =
            listOf(
                ev("g", 0, MatchCreated(format, lineup, MatchMeta())),
                ev("e1", 1, PointWon(Side.A)),
                ev("e2", 2, MatchCreated(format, lineup)),
            )
        val state = ScoringEngine.fold(log)
        assertEquals("1-0-2", state.scoreCall)
        assertEquals(listOf(EventId("e2")), state.ignoredEvents)
    }

    @Test
    fun undoTargetSkipsNonUndoableEvents() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format())
        assertNull(m.engineState.undoTarget)
        val point = m.point(Side.A)
        m.send(Paused)
        assertEquals(point, m.engineState.undoTarget)
        m.undo()
        assertNull(m.engineState.undoTarget)
        assertEquals(MatchStatus.PAUSED, m.state.status)
    }

    @Test
    fun pointHistoryDeliversStateBeforeAndAfter() {
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val m = MatchDriver(format).points("AAA")
        val history = ScoringEngine.pointHistory(format, m.lineup, m.events)
        assertEquals(3, history.size)
        assertEquals("30-0", history[2].before.scoreCall)
        assertTrue(history[2].after.statusFlags.isNotEmpty())
    }
}
