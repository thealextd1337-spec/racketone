package eu.advantly.presentation.stats

import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.match.play
import eu.advantly.presentation.match.setupFor
import eu.advantly.presentation.match.testStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatchStatisticsTest {
    @Test
    fun tennisPointsServeReturnAndBreakPoints() {
        val store = testStore()
        val id = store.start(store.setupFor(Presets.TENNIS_ONE_SET.format(), firstServerSide = Side.B))
        // Spiel 1 (B schlägt auf): A macht 3 Breakbälle, B wehrt einen ab, A verwertet den zweiten
        store.play("AAABA")
        // Spiel 2 (A schlägt auf): zu null gehalten
        store.play("AAAA")
        val stats = MatchStatistics.compute(store.record(id)!!)
        assertEquals(SidePair(8, 1), stats.pointsWon)
        assertEquals(9, stats.totalPoints)
        assertEquals(Ratio(4, 4), stats.servicePoints.a)
        assertEquals(Ratio(1, 5), stats.servicePoints.b)
        assertEquals(Ratio(4, 5), stats.returnPoints.a)
        assertEquals(Ratio(1, 2), stats.breakPoints.a)
        assertEquals(50, stats.breakPoints.a.percent)
        assertEquals(Ratio(0, 0), stats.breakPoints.b)
        assertNull(stats.breakPoints.b.percent)
        assertEquals(SidePair(5, 1), stats.longestStreak)
        assertEquals(SidePair(2, 0), stats.games)
        assertEquals(true, stats.netDurationMs > 0)
    }

    @Test
    fun rallySportsHaveNoGamesButCountSetPoints() {
        val store = testStore()
        val id = store.start(store.setupFor(Presets.BADMINTON_1GAME_15.format()))
        store.play("A".repeat(14) + "B" + "A")
        val stats = MatchStatistics.compute(store.record(id)!!)
        assertNull(stats.games)
        assertEquals(Ratio(1, 2), stats.setPoints.a)
        assertEquals(SidePair(15, 1), stats.pointsWon)
    }

    @Test
    fun undonePointsAndPausesDoNotCount() {
        val store = testStore()
        val id = store.start(store.setupFor(Presets.TENNIS_ONE_SET.format()))
        store.play("AB")
        store.undo()
        store.pause()
        store.resume()
        val stats = MatchStatistics.compute(store.record(id)!!)
        assertEquals(SidePair(1, 0), stats.pointsWon)
        // StepClock: 1 s je Event; Genesis → letztes Event = 5 s, davon 1 s Pause
        assertEquals(4_000, stats.netDurationMs)
    }
}
