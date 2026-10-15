package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Single-Scorer-Regel (02 §7.5): nur der Zähler an der jeweiligen Position wirkt; Übergabe per `ScorerChanged`. */
class ScorerRulesTest {
    private val format = Presets.TENNIS_SINGLES_BO3.format()
    private val lineup = lineupFor(format.discipline)
    private val watch = DeviceId("watch")
    private val phone = DeviceId("phone")

    private fun ev(
        id: String,
        lamport: Long,
        device: DeviceId,
        payload: EventPayload,
        parent: String? = null,
    ) = ScoreEvent(EventId(id), MatchId("m"), device, lamport, lamport, lamport * 10_000, parent?.let { EventId(it) }, payload)

    private fun genesis(scorer: DeviceId?) = ev("g", 0, phone, MatchCreated(format, lineup, scorerDeviceId = scorer))

    @Test
    fun genesisDefinesTheScorerAndDefaultsToItsOwnDevice() {
        val log = listOf(ev("a", 1, watch, PointWon(Side.A)), ev("b", 2, phone, PointWon(Side.A)))
        val watchScores = ScoringEngine.replay(listOf(genesis(watch)) + log).state
        assertEquals(watch, watchScores.scorerDeviceId)
        assertEquals(listOf(EventId("b")), watchScores.rejectedByRole)
        assertEquals(listOf(EventId("b")), watchScores.ignoredEvents)
        assertEquals(listOf(Effect.IGNORED), watchScores.effects)
        // ohne scorerDeviceId zählt das Gerät der Genesis (hier: phone)
        val legacy = ScoringEngine.replay(listOf(genesis(null)) + log).state
        assertEquals(phone, legacy.scorerDeviceId)
        assertEquals(listOf(EventId("a")), legacy.rejectedByRole)
        // ohne Genesis und ohne Parameter: keine Rollenprüfung (Alt-Testvektoren)
        val unrestricted = ScoringEngine.fold(format, lineup, log)
        assertNull(unrestricted.scorerDeviceId)
        assertEquals(emptyList(), unrestricted.rejectedByRole)
        assertEquals(SidePair(2, 0), unrestricted.current?.points)
    }

    /** Neue Eingaben (Entscheidung 2026-10-15) unterliegen derselben Rollenprüfung: nur der Zähler. */
    @Test
    fun serverChoiceAndServeInfoOnlyFromScorer() {
        val doubles = Presets.TENNIS_BO3_MTB10.format(eu.advantly.core.model.Discipline.DOUBLES)
        val log =
            listOf(
                ev("c", 1, phone, ServerChosen(0, Side.A, p("A2"))),
                ev("a", 2, watch, PointWon(Side.B)),
                ev("d", 3, phone, PointDetailSet(EventId("a"), PointDetail(serve = ServeOutcome.DOUBLE_FAULT))),
            )
        val state = ScoringEngine.fold(doubles, lineupFor(doubles.discipline), log, watch)
        assertEquals(listOf(EventId("c"), EventId("d")), state.rejectedByRole)
        assertEquals(p("A1"), state.serve?.player, "Wahl des Nicht-Zählers wirkt nicht")
    }

    @Test
    fun scorerChangedActsFromItsPositionAndBlocksTheOldScorer() {
        val log =
            listOf(
                ev("a", 1, watch, PointWon(Side.A)),
                ev("c", 2, phone, ScorerChanged(phone)),
                ev("b", 3, watch, PointWon(Side.A)),
                ev("u", 4, watch, Undo(EventId("a"))),
                ev("d", 5, phone, PointWon(Side.B)),
            )
        val state = ScoringEngine.fold(format, lineup, log, watch)
        assertEquals(phone, state.scorerDeviceId)
        assertEquals(listOf(EventId("b"), EventId("u")), state.rejectedByRole)
        assertEquals(SidePair(1, 1), state.current?.points, "Undo des Nicht-Zählers wirkt nicht")
        assertEquals(listOf(Effect.POINT), state.effects)
        val afterChange = ScoringEngine.fold(format, lineup, log.take(2), watch)
        assertEquals(listOf(Effect.SCORER_CHANGED), afterChange.effects)
    }

    @Test
    fun concurrentTakeoversResolveByOrder() {
        // beide übernehmen gleichzeitig (gleicher Lamport): phone < watch → watch gilt danach
        val log =
            listOf(
                ev("t1", 1, phone, ScorerChanged(phone)),
                ev("t2", 1, watch, ScorerChanged(watch)),
                ev("p", 2, phone, PointWon(Side.B)),
                ev("w", 2, watch, PointWon(Side.A)),
            )
        val state = ScoringEngine.fold(format, lineup, log, DeviceId("other"))
        assertEquals(watch, state.scorerDeviceId)
        assertEquals(listOf(EventId("p")), state.rejectedByRole)
        assertEquals(SidePair(1, 0), state.current?.points)
    }

    @Test
    fun undoOnScorerChangedIsInvalidAndScorerMayUndoOldScorerPoints() {
        val log =
            listOf(
                ev("a", 1, watch, PointWon(Side.A)),
                ev("c", 2, phone, ScorerChanged(phone)),
                ev("x", 3, phone, Undo(EventId("c"))),
                ev("y", 4, phone, Undo(EventId("a"))),
            )
        val state = ScoringEngine.fold(format, lineup, log, watch)
        assertEquals(phone, state.scorerDeviceId)
        assertEquals(listOf(EventId("x")), state.ignoredEvents)
        assertEquals(emptyList(), state.rejectedByRole)
        assertEquals(SidePair(0, 0), state.current?.points)
    }

    @Test
    fun pointHistoryAppliesTheRoleRule() {
        // Review-Runde 2, Befund 3: unzulässiger Handy-Punkt darf nicht in der Statistik-Basis auftauchen
        val log = listOf(genesis(watch), ev("w", 1, watch, PointWon(Side.A)), ev("p", 2, phone, PointWon(Side.B)))
        val history = ScoringEngine.pointHistory(log)
        assertEquals(listOf(EventId("w")), history.map { it.event.id })
        assertEquals(ScoringEngine.replay(log).pointsPlayed, history.size)
        assertEquals(watch, history.single().after.scorerDeviceId)
        assertEquals(watch, history.single().before.scorerDeviceId)
        val withParameter = ScoringEngine.pointHistory(format, lineup, log.drop(1), scorer = watch)
        assertEquals(history, withParameter)
        // Wechsel mitten im Log: Zähler und rejectedByRole stehen im Verlauf
        val changed =
            log +
                listOf(
                    ev("c", 3, phone, ScorerChanged(phone)),
                    ev("x", 4, watch, PointWon(Side.A)),
                    ev("q", 5, phone, PointWon(Side.B)),
                )
        val last = ScoringEngine.pointHistory(changed).last()
        assertEquals(EventId("q"), last.event.id)
        assertEquals(phone, last.after.scorerDeviceId)
        assertEquals(listOf(EventId("p"), EventId("x")), last.after.rejectedByRole)
    }

    @Test
    fun incrementalApplyEqualsFoldWithRoles() {
        val log =
            listOf(
                ev("a", 1, watch, PointWon(Side.A)),
                ev("b", 2, watch, PointWon(Side.A), "a"),
                ev("c", 3, phone, PointWon(Side.B), "b"),
                ev("d", 4, phone, ScorerChanged(phone), "c"),
                ev("e", 5, phone, PointWon(Side.B), "d"),
                ev("f", 6, watch, PointWon(Side.A), "e"),
            )
        var engine = ScoringEngine.replay(format, lineup, emptyList(), watch)
        for (event in log) {
            engine = ScoringEngine.apply(engine, event)
            assertEquals(ScoringEngine.fold(format, lineup, engine.events, watch), engine.state)
        }
        val reversed = ScoringEngine.applyAll(ScoringEngine.replay(format, lineup, emptyList(), watch), log.reversed())
        assertEquals(engine.state, reversed.state)
        assertEquals(3, engine.pointsPlayed)
    }
}
