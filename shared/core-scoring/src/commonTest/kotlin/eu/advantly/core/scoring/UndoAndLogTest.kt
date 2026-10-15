package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun ignoredEventIsNeverUndoTarget() {
        // Review-Gegenprobe: OPEN-Satzball, danach unzulässiges PointWon während AWAITING_DECISION
        val m = MatchDriver(Presets.TENNIS_OPEN.format())
        repeat(5) { m.points("AAAA") }
        m.points("AAA")
        val setBall = m.point(Side.A)
        assertEquals(MatchStatus.AWAITING_DECISION, m.state.status)
        val ignoredPoint = m.point(Side.B)
        assertEquals(listOf(ignoredPoint), m.state.ignoredEvents)
        assertEquals(setBall, m.engineState.undoTarget, "apply")
        assertEquals(setBall, ScoringEngine.replay(m.format, m.lineup, m.events).undoTarget, "fold")
        m.send(Correction.server(p("B1")))
        assertEquals(setBall, m.engineState.undoTarget, "auch übersprungene Korrekturen sind kein Ziel")
    }

    @Test
    fun undoPointingForwardIsIgnoredInFoldAndApply() {
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val lineup = lineupFor(format.discipline)
        val undo = ev("u", 1, Undo(EventId("p")))
        val point = ev("p", 2, PointWon(Side.A))
        val folded = ScoringEngine.fold(format, lineup, listOf(undo, point))
        assertEquals(SidePair(1, 0), checkNotNull(folded.current).points)
        assertEquals(listOf(EventId("u")), folded.ignoredEvents)
        for (delivery in listOf(listOf(undo, point), listOf(point, undo))) {
            var engine = ScoringEngine.replay(format, lineup, emptyList())
            delivery.forEach { engine = ScoringEngine.apply(engine, it) }
            assertEquals(folded, engine.state, "Zustellung ${delivery.map { it.id }}")
        }
        // Undo mit Ziel davor wirkt normal, auch wenn es vor dem Ziel zugestellt wird
        val laterUndo = ev("u2", 3, Undo(EventId("p")))
        var engine = ScoringEngine.replay(format, lineup, emptyList())
        listOf(laterUndo, point).forEach { engine = ScoringEngine.apply(engine, it) }
        assertEquals(ScoringEngine.fold(format, lineup, listOf(point, laterUndo)), engine.state)
        assertEquals(SidePair(0, 0), checkNotNull(engine.state.current).points)
    }

    @Test
    fun divergentCopiesOfAnIdAreRejectedDeterministically() {
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val lineup = lineupFor(format.discipline)
        val first = ev("e1", 1, PointWon(Side.A))
        val copyA = ev("x", 2, PointWon(Side.A))
        val copyB = ev("x", 2, PointWon(Side.B))
        val ab = ScoringEngine.replay(format, lineup, listOf(first, copyA, copyB))
        val ba = ScoringEngine.replay(format, lineup, listOf(first, copyB, copyA))
        assertEquals(ab.state, ba.state)
        assertEquals(SidePair(1, 0), checkNotNull(ab.state.current).points)
        assertEquals(listOf(EventId("x")), ab.state.ignoredEvents)
        assertEquals(listOf(EventId("x")), ab.rejectedEvents)
        // gleiche id, anderer Lamport: apply muss wie fold zurückweisen statt No-op
        val shifted = copyA.copy(lamport = 7)
        var engine = ScoringEngine.replay(format, lineup, emptyList())
        listOf(first, copyA, shifted).forEach { engine = ScoringEngine.apply(engine, it) }
        assertEquals(ScoringEngine.fold(format, lineup, listOf(first, copyA, shifted)), engine.state)
        assertEquals(listOf(EventId("x")), engine.state.ignoredEvents)
        // bitgleiche Wiederholung bleibt ein No-op
        assertEquals(SidePair(2, 0), checkNotNull(ScoringEngine.fold(format, lineup, listOf(first, copyA, copyA)).current).points)
    }

    @Test
    fun undoResolvesConflict() {
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val lineup = lineupFor(format.discipline)
        val a = ev("a", 2, PointWon(Side.A), device = "watch", parent = "g")
        val b = ev("b", 2, PointWon(Side.B), device = "phone", parent = "g")
        val undo = ev("u", 3, Undo(EventId("b")), device = "phone", parent = "a")
        val resolved = ScoringEngine.replay(format, lineup, listOf(a, b, undo))
        assertEquals(emptyList(), resolved.conflicts)
        assertEquals(listOf(TapConflict(EventId("b"), EventId("a"))), resolved.resolvedConflicts)
        var engine = ScoringEngine.replay(format, lineup, emptyList())
        listOf(a, b, undo).forEach { engine = ScoringEngine.apply(engine, it) }
        assertEquals(emptyList(), engine.conflicts)
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
    fun genesisIdCollisionMakesLogInvalidInFoldAndApply() {
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val lineup = lineupFor(format.discipline)
        val genesis = ev("g", 0, MatchCreated(format, lineup))
        val clash = ev("g", 0, PointWon(Side.B))
        // Review-Gegenprobe: [g=MatchCreated, g=PointWon(B)] darf nicht 0:1 ergeben
        assertFailsWith<IllegalArgumentException> { ScoringEngine.fold(listOf(genesis, clash)) }
        assertFailsWith<IllegalArgumentException> { ScoringEngine.fold(listOf(clash, genesis)) }
        val start = ScoringEngine.replay(listOf(genesis, ev("e1", 1, PointWon(Side.A))))
        assertFailsWith<IllegalArgumentException> { ScoringEngine.apply(start, clash) }
        assertFailsWith<IllegalArgumentException> { ScoringEngine.applyAll(start, listOf(ev("e2", 2, PointWon(Side.A)), clash)) }
    }

    @Test
    fun repeatedGenesisIsNoOpInFoldAndApply() {
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val lineup = lineupFor(format.discipline)
        val genesis = ev("g", 0, MatchCreated(format, lineup))
        val point = ev("e1", 1, PointWon(Side.A))
        val once = ScoringEngine.replay(listOf(genesis, point))
        assertEquals(once.state, ScoringEngine.fold(listOf(genesis, point, genesis)))
        assertEquals(genesis, once.genesis)
        val again = ScoringEngine.apply(once, genesis)
        assertTrue(again === once, "bitgleiche Genesis ist ein No-op")
        assertEquals(emptyList(), again.state.ignoredEvents)
        // eine zweite Genesis mit eigener ID bleibt ein übersprungenes Event
        val second = ev("g2", 2, MatchCreated(format, lineup))
        assertEquals(listOf(EventId("g2")), ScoringEngine.apply(once, second).state.ignoredEvents)
        assertEquals(ScoringEngine.fold(listOf(genesis, point, second)), ScoringEngine.apply(once, second).state)
    }

    @Test
    fun applyAllEqualsFoldForLateBatches() {
        val format = Presets.TENNIS_OPEN.format()
        val lineup = lineupFor(format.discipline)
        val events = (1..60).map { ev("e$it", it.toLong(), PointWon(if (it % 3 == 0) Side.B else Side.A), parent = "e${it - 1}") }
        val early = events.filterIndexed { i, _ -> i % 2 == 0 }
        val late = events - early.toSet()
        val start = ScoringEngine.replay(format, lineup, early)
        assertEquals(ScoringEngine.fold(format, lineup, events), ScoringEngine.applyAll(start, late.reversed()).state)
        val inOrder = ScoringEngine.applyAll(ScoringEngine.replay(format, lineup, emptyList()), events)
        assertEquals(ScoringEngine.fold(format, lineup, events), inOrder.state)
        assertTrue(ScoringEngine.applyAll(inOrder, events) === inOrder, "bekannte Events sind ein No-op")
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
        val history = ScoringEngine.pointHistory(format, m.lineup, m.events, scorer = null)
        assertEquals(3, history.size)
        assertEquals("30-0", history[2].before.scoreCall)
        assertTrue(history[2].after.statusFlags.isNotEmpty())
    }
}
