package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Performance-Regression (JVM): Nachlieferung nach einem Funkloch als **ein** Batch darf nicht quadratisch werden.
 * Großzügige Grenze, damit der Test nicht flackert.
 */
class PerformanceTest {
    private fun events(n: Int): List<ScoreEvent> {
        val random = Random(2026)
        return (1..n).map { i ->
            val device = if (i % 2 == 0) "phone" else "watch"
            ScoreEvent(
                id = EventId("e$i"),
                matchId = MatchId("m"),
                deviceId = DeviceId(device),
                deviceSeq = i.toLong(),
                lamport = i.toLong(),
                wallClockMs = i * 8_000L,
                parentId = if (i == 1) null else EventId("e${i - 1}"),
                payload = PointWon(if (random.nextInt(100) < 55) Side.A else Side.B),
            )
        }
    }

    @Test
    fun lateHalfAsBatchIsFast() {
        val format = Presets.TENNIS_OPEN.format()
        val lineup = lineupFor(format.discipline)
        val all = events(2_000)
        val early = all.filterIndexed { i, _ -> i % 2 == 0 }
        val late = all.filterIndexed { i, _ -> i % 2 == 1 }.shuffled(Random(7))
        val start = ScoringEngine.replay(format, lineup, early)
        ScoringEngine.applyAll(start, late) // Aufwärmen (JIT)
        val begin = System.nanoTime()
        val merged = ScoringEngine.applyAll(start, late)
        val millis = (System.nanoTime() - begin) / 1_000_000
        println("applyAll: 1.000 späte Events auf 1.000 vorhandene in $millis ms")
        assertTrue(millis < 500, "Batch-Nachlieferung dauerte $millis ms (Grenze 500 ms)")
        assertEquals(ScoringEngine.fold(format, lineup, all), merged.state)
    }

    @Test
    fun linearReplayOfLongLogIsFast() {
        val format = Presets.TENNIS_OPEN.format()
        val lineup = lineupFor(format.discipline)
        val all = events(5_000)
        ScoringEngine.replay(format, lineup, all)
        val begin = System.nanoTime()
        ScoringEngine.replay(format, lineup, all)
        val millis = (System.nanoTime() - begin) / 1_000_000
        println("replay: 5.000 Events in $millis ms")
        assertTrue(millis < 1_000, "Replay dauerte $millis ms (Grenze 1.000 ms)")
    }
}
