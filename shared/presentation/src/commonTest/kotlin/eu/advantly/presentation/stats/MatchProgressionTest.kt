package eu.advantly.presentation.stats

import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.GameKind
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.match.play
import eu.advantly.presentation.match.setupFor
import eu.advantly.presentation.match.testStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MatchProgressionTest {
    @Test
    fun breakSavePartialGameAndUndoRedoUseEffectivePoints() {
        val store = testStore()
        val id = store.start(store.setupFor(Presets.TENNIS_ONE_SET.format(), Side.B))
        store.play("AAABA" + "AA")
        val stats = MatchStatistics.compute(store.record(id)!!)
        assertEquals(Ratio(1, 2), stats.breakPointsSaved.b)
        assertEquals(Ratio(0, 0), stats.breakPointsSaved.a)
        assertEquals(2, stats.progression.size)
        val first = stats.progression.first()
        assertTrue(first.isBreak)
        assertEquals(SidePair(4, 1), first.pointsWon)
        assertEquals(Side.B, first.points[3].winner)
        assertEquals(SidePair("0", "0"), first.points.first().before)
        assertNull(stats.progression.last().winner)
        assertFalse(stats.progression.last().isBreak)
        store.undo()
        assertEquals(
            1,
            MatchStatistics
                .compute(store.record(id)!!)
                .progression
                .last()
                .points.size,
        )
        store.redo()
        assertEquals(stats.progression, MatchStatistics.compute(store.record(id)!!).progression)
    }

    @Test
    fun regularTiebreakCompletesGameWithoutBreakAndKeepsSetBoundary() {
        val store = testStore()
        val id = store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        store.play("AAAABBBB".repeat(6) + "AB".repeat(5) + "AA" + "BBBB")
        val games = MatchStatistics.compute(store.record(id)!!).progression
        assertEquals(14, games.size)
        val tb = games[12]
        assertEquals(GameKind.TIEBREAK, tb.kind)
        assertFalse(tb.isBreak)
        assertEquals(Side.A, tb.winner)
        assertEquals(SidePair(7, 5), tb.pointsWon)
        assertEquals(2, games.last().set)
        assertEquals(1, games.last().game)
    }

    @Test
    fun matchTiebreakAndRallySetsAreNeverBreaks() {
        val store = testStore()
        for (preset in listOf(Presets.TENNIS_TIEBREAK_ONLY_7, Presets.BADMINTON_1GAME_15, Presets.TABLE_TENNIS_SINGLES_BO5)) {
            val id = store.start(store.setupFor(preset.format()))
            store.play("A".repeat(7))
            assertTrue(MatchStatistics.compute(store.record(id)!!).progression.none { it.isBreak })
        }
    }

    @Test
    fun sideOutRalliesAreVisibleWithoutInventingScoringPoints() {
        val store = testStore()
        val id = store.start(store.setupFor(Presets.PICKLEBALL_DOUBLES_11_SIDEOUT.format()))
        store.play("BA")
        val game = MatchStatistics.compute(store.record(id)!!).progression.single()
        assertEquals(SidePair(1, 1), game.pointsWon)
        assertTrue(game.points.none { it.scored })
        assertFalse(game.isBreak)
    }

    @Test
    fun cacheReusesRevisionAndInvalidatesAfterUndoAndNewEnd() {
        val store = testStore()
        val id = store.start(store.setupFor(Presets.TENNIS_ONE_SET.format()))
        var calls = 0
        val cache =
            MatchStatisticsCache {
                calls++
                MatchStatistics.compute(it)
            }
        store.play("AAAAB")
        store.endMatch(EndReason.STOPPED)
        val record = store.record(id)!!
        val stats = cache.get(record)
        assertSame(stats, cache.get(store.record(id)!!))
        assertEquals(1, calls)
        store.undo()
        store.play("A")
        store.endMatch(EndReason.STOPPED)
        assertEquals(6, cache.get(store.record(id)!!).totalPoints)
        assertEquals(2, calls)
    }
}
