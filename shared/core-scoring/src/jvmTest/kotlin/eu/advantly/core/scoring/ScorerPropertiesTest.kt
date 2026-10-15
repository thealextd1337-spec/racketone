package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
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

/**
 * Property-Tests der Single-Scorer-Regel (02 §7.5): Zustellreihenfolge und Batch-Aufteilung sind egal, und Eingaben von
 * Nicht-Zählern haben keinerlei Wirkung auf den Stand.
 */
@OptIn(ExperimentalKotest::class)
class ScorerPropertiesTest {
    private val config = PropTestConfig(seed = 2026L, iterations = 200)
    private val devices = listOf("phone", "watch", "watch2").map { DeviceId(it) }

    /** Zufälliges Mehrgeräte-Log mit Übergaben, Undos und Eingaben von Nicht-Zählern (Lamport = Position). */
    private fun randomLog(
        seed: Long,
        length: Int,
    ): Pair<MatchCreated, List<ScoreEvent>> {
        val random = Random(seed)
        val format = Presets.all[random.nextInt(Presets.all.size)].let { it.format(it.disciplines.first()) }
        val created = MatchCreated(format, lineupFor(format.discipline), scorerDeviceId = devices[0])
        val events = mutableListOf<ScoreEvent>()
        repeat(length) { i ->
            val device = devices[random.nextInt(devices.size)]
            val payload: EventPayload =
                when (random.nextInt(12)) {
                    0 -> ScorerChanged(devices[random.nextInt(devices.size)])
                    1 -> if (events.isEmpty()) PointWon(Side.A) else Undo(events[random.nextInt(events.size)].id)
                    2 -> ContinueDecision(anotherSet = random.nextBoolean())
                    else -> PointWon(if (random.nextBoolean()) Side.A else Side.B)
                }
            val lamport = 1L + i - random.nextInt(2) // gelegentlich gleiche Lamports (gleichzeitige Eingaben)
            events +=
                ScoreEvent(EventId("e$i"), MatchId("m"), device, i + 1L, lamport, i * 3_000L, events.lastOrNull()?.id, payload)
        }
        return created to events
    }

    private fun genesis(created: MatchCreated) = ScoreEvent(EventId("g"), MatchId("m"), devices[0], 0, 0, 0, null, created)

    @Test
    fun deliveryOrderAndBatchesDoNotMatter() {
        runBlocking {
            checkAll(config, Arb.long(), Arb.int(0..120)) { seed, length ->
                val (created, log) = randomLog(seed, length)
                val reference = ScoringEngine.replay(listOf(genesis(created)) + log).state
                val random = Random(seed xor 7)
                var engine = ScoringEngine.replay(listOf(genesis(created)))
                val shuffled = log.shuffled(random)
                var i = 0
                while (i < shuffled.size) {
                    val n = 1 + random.nextInt(5)
                    engine = ScoringEngine.applyAll(engine, shuffled.subList(i, minOf(shuffled.size, i + n)))
                    i += n
                }
                assertEquals(reference, engine.state)
            }
        }
    }

    @Test
    fun inputsOfNonScorersHaveNoEffect() {
        runBlocking {
            checkAll(config, Arb.long(), Arb.int(0..120)) { seed, length ->
                val (created, log) = randomLog(seed, length)
                val full = ScoringEngine.replay(listOf(genesis(created)) + log).state
                val rejected = full.rejectedByRole.toSet()
                val without = ScoringEngine.replay(listOf(genesis(created)) + log.filter { it.id !in rejected }).state

                fun MatchState.score() = listOf(status, result, setsWon, sets, current, serve, scorerDeviceId)
                assertEquals(without.score(), full.score())
                assertEquals(emptyList(), without.rejectedByRole)
            }
        }
    }
}
