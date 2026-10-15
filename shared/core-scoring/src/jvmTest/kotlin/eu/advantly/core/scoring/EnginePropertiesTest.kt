package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Engine-Invarianten mit Kotest Property (01 §8), fester Seed. Jede Iteration erzeugt ein zufälliges Match über alle
 * 45 Presets ([RandomMatches]) mit Undo-Ketten, Korrekturen, Pausen, Satzende-Antworten und ungültigen Events.
 */
@OptIn(ExperimentalKotest::class)
class EnginePropertiesTest {
    private val config = PropTestConfig(seed = 2026L, iterations = 300)
    private val presetArb = Arb.int(0 until Presets.all.size)
    private val lengthArb = Arb.int(0..220)

    private fun property(block: suspend (RandomMatch) -> Unit) {
        runBlocking {
            checkAll(config, presetArb, Arb.long(), lengthArb) { preset, seed, length ->
                block(RandomMatches.generate(preset, seed, length))
            }
        }
    }

    /**
     * Ohne Effekte; bei offenen Gabelungen ohne die Event-/Geräteliste der Zweige (ein Undo-Event gehört zum Zweig,
     * das weggelassene Event nicht – Punkte und Vorschauen bleiben gleich).
     */
    private fun MatchState.withoutEffects() =
        copy(
            effects = emptyList(),
            openForks =
                openForks.map { f ->
                    f.copy(branches = f.branches.map { it.copy(events = emptyList(), devices = emptyList()) })
                },
        )

    @Test
    fun incrementalApplyEqualsFold() =
        property { m ->
            assertEquals(ScoringEngine.fold(m.format, m.lineup, m.events), m.incremental.state)
        }

    @Test
    fun undoOfLastEventEqualsStateWithoutEvent() =
        property { m ->
            val last = EventLog.ordered(m.events).lastOrNull() ?: return@property
            if (last.payload is Undo) return@property
            val undo = last.copy(id = EventId("undo"), lamport = last.lamport + 1, parentId = last.id, payload = Undo(last.id))
            val withUndo = ScoringEngine.fold(m.format, m.lineup, m.events + undo)
            val without = ScoringEngine.fold(m.format, m.lineup, m.events - last)
            assertEquals(without.withoutEffects(), withUndo.withoutEffects())
            assertEquals(listOf(Effect.UNDONE), withUndo.effects)
        }

    @Test
    fun deliveryOrderAndDuplicatesDoNotMatter() =
        property { m ->
            val expected = m.incremental.state
            val shuffled = m.events.shuffled(Random(m.events.size.toLong()))
            assertEquals(expected, ScoringEngine.fold(m.format, m.lineup, shuffled))
            val withDuplicates = shuffled + m.events.filterIndexed { i, _ -> i % 3 == 0 }
            assertEquals(expected, ScoringEngine.fold(m.format, m.lineup, withDuplicates))
            // inkrementell in beliebiger Zustellreihenfolge
            var engine = ScoringEngine.replay(m.format, m.lineup, emptyList())
            shuffled.forEach { engine = ScoringEngine.apply(engine, it) }
            assertEquals(expected, engine.state)
        }

    @Test
    fun mergeIsCommutativeAssociativeAndIdempotent() =
        property { m ->
            val expected = m.incremental.state
            val random = Random(m.events.size * 31L + m.devices)
            // drei überlappende Teilmengen, wie sie verschiedene Geräte nach Funklöchern halten
            val parts = List(3) { m.events.filter { random.nextInt(3) != 0 } }
            val rest = m.events - parts.flatten().toSet()
            val sets = listOf(parts[0] + rest, parts[1], parts[2])
            for (order in listOf(listOf(0, 1, 2), listOf(2, 1, 0), listOf(1, 0, 2))) {
                var engine = ScoringEngine.replay(m.format, m.lineup, emptyList())
                order.forEach { idx -> sets[idx].shuffled(random).forEach { engine = ScoringEngine.apply(engine, it) } }
                assertEquals(expected, engine.state, "Merge-Reihenfolge $order")
            }
            val left = ScoringEngine.replay(m.format, m.lineup, sets[0] + sets[1])
            val assocLeft = sets[2].fold(left) { acc, e -> ScoringEngine.apply(acc, e) }
            val right = ScoringEngine.replay(m.format, m.lineup, sets[1] + sets[2])
            val assocRight = sets[0].fold(right) { acc, e -> ScoringEngine.apply(acc, e) }
            assertEquals(assocLeft.state, assocRight.state)
            assertEquals(expected, ScoringEngine.fold(m.format, m.lineup, sets.flatten()))
        }

    @Test
    fun scoresNeverNegativeAndGamesEndExactlyByTheRules() =
        property { m ->
            for (n in listOf(m.events.size / 3, 2 * m.events.size / 3, m.events.size)) {
                val state = ScoringEngine.fold(m.format, m.lineup, m.events.subList(0, n))
                checkInvariants(m, state)
            }
        }

    @Test
    fun statusFlagsPredictTheNextPoint() =
        property { m ->
            val state = m.incremental.state
            if (state.status != MatchStatus.IN_PROGRESS && state.status != MatchStatus.PAUSED) return@property
            for (side in Side.entries) {
                val probe =
                    ScoreEvent(
                        id = EventId("probe"),
                        matchId = MatchId("m"),
                        deviceId = DeviceId("probe"),
                        deviceSeq = 1,
                        lamport = 10_000,
                        wallClockMs = 0,
                        parentId = null,
                        payload = PointWon(side),
                    )
                val next = ScoringEngine.apply(m.incremental, probe).state
                val flag = state.statusFlags.firstOrNull { it.side == side }
                val matchWon = next.status == MatchStatus.FINISHED && next.result?.winner == side
                val setWon = next.setsWon[side] > state.setsWon[side]
                when (flag?.level) {
                    FlagLevel.MATCH_POINT -> assertTrue(matchWon, "Matchball muss das Match beenden")
                    FlagLevel.SET_POINT -> assertTrue(setWon && !matchWon, "Satzball")
                    FlagLevel.GAME_POINT -> assertTrue(!setWon, "Spielball")
                    null -> assertTrue(!setWon, "ohne Flag darf der Satz nicht enden ($side, $state)")
                }
            }
        }

    private fun checkInvariants(
        m: RandomMatch,
        state: MatchState,
    ) {
        val f = m.format
        val current = state.current
        if (current != null) {
            assertTrue(current.points.a >= 0 && current.points.b >= 0)
            current.games?.let { assertTrue(it.a >= 0 && it.b >= 0) }
        }
        assertEquals(state.sets.count { it.winner == Side.A }, state.setsWon.a)
        assertEquals(state.sets.count { it.winner == Side.B }, state.setsWon.b)
        for (set in state.sets) {
            val w = set.winner
            when (set.kind) {
                SetKind.RALLY_GAME -> {
                    val r = checkNotNull(f.rally)
                    val p = checkNotNull(set.points)
                    val cap = r.cap
                    assertTrue(p[w] > p[w.other])
                    assertTrue((p[w] >= r.pointsToWin && p[w] - p[w.other] >= r.winBy) || (cap != null && p[w] == cap), "Rally-Satz $set")
                    assertTrue(p[w] == r.pointsToWin || p[w] - p[w.other] == r.winBy || p[w] == cap, "Satz endet genau beim Sieg: $set")
                }

                SetKind.PB_GAME -> {
                    val pb = checkNotNull(f.pickleball)
                    val p = checkNotNull(set.points)
                    val cap = pb.pointCap
                    assertTrue((p[w] >= pb.pointsToWin && p[w] - p[w.other] >= pb.winBy) || (cap != null && p[w] == cap), "PB-Spiel $set")
                }

                SetKind.REGULAR -> {
                    val g = checkNotNull(set.games)
                    assertTrue(g[w] > g[w.other], "Satz $set")
                    if (set.tiebreak == null) assertTrue(g[w] - g[w.other] >= 2, "Satz ohne TB mit 2 Vorsprung: $set")
                }

                SetKind.MATCH_TIEBREAK -> {
                    val tb = checkNotNull(set.tiebreak)
                    assertTrue(tb[w] - tb[w.other] >= 1)
                    assertEquals(1, checkNotNull(set.games)[w])
                }
            }
        }
        if (state.status == MatchStatus.IN_PROGRESS && current != null) {
            // Ein laufendes Spiel/ein laufender Satz ist nie schon „gewonnen“
            val p = current.points
            for (x in Side.entries) {
                when {
                    f.sport.isRallySport -> {
                        val r = checkNotNull(f.rally)
                        val cap = r.cap
                        assertTrue(!((p[x] >= r.pointsToWin && p[x] - p[x.other] >= r.winBy) || (cap != null && p[x] >= cap)))
                    }

                    f.sport == Sport.PICKLEBALL -> {
                        assertTrue(!(p[x] >= checkNotNull(f.pickleball).pointsToWin && p[x] - p[x.other] >= 2))
                    }

                    current.gameKind == GameKind.STANDARD -> {
                        assertTrue(!(p[x] >= 4 && p[x] - p[x.other] >= 2))
                    }
                }
            }
        }
        if (state.result?.reason == ResultReason.COMPLETED && f.match.mode == MatchMode.BEST_OF) {
            assertEquals(f.setsToWin, state.setsWon[checkNotNull(state.result?.winner)])
        }
        if (state.status == MatchStatus.FINISHED ||
            state.status == MatchStatus.AWAITING_DECISION
        ) {
            assertEquals(emptyList(), state.statusFlags)
        }
    }
}
