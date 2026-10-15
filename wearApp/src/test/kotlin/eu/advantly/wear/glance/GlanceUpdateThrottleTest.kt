package eu.advantly.wear.glance

import eu.advantly.core.scoring.MatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlanceUpdateThrottleTest {
    private val throttle = GlanceUpdateThrottle()

    private fun state(points: String = "0:0") =
        GlanceState(
            live =
                GlanceMatch(
                    "0:0",
                    "0:0",
                    points,
                    null,
                    true,
                    MatchStatus.IN_PROGRESS,
                ),
        )

    @Test
    fun firstUpdateIsImmediateAndEqualValuesDoNotUpdate() {
        val state = state()
        assertEquals(0L, throttle.delayFor(state, 100))
        throttle.sent(state, 100)
        assertNull(throttle.delayFor(state.copy(), 5_100))
    }

    @Test
    fun burstKeepsOriginalDeadlineForLatestValue() {
        throttle.sent(state(), 1_000)
        assertEquals(4_900L, throttle.delayFor(state("15:0"), 1_100))
        assertEquals(4_000L, throttle.delayFor(state("30:0"), 2_000))
        assertEquals(0L, throttle.delayFor(state("30:0"), 6_000))
        throttle.sent(state("30:0"), 6_000)
        assertNull(throttle.delayFor(state("30:0"), 6_100))
    }

    @Test
    fun undoBackToPublishedValueCancelsTrailingRequest() {
        throttle.sent(state(), 0)
        assertEquals(4_000L, throttle.delayFor(state("15:0"), 1_000))
        assertNull(throttle.delayFor(state(), 2_000))
    }

    @Test
    fun closeAndRoleChangesAreUpdatesAndTimeCannotMakeDelayNegative() {
        throttle.sent(state(), 100)
        assertEquals(0L, throttle.delayFor(GlanceState(), 5_100))
        assertEquals(5_000L, throttle.delayFor(state().copy(live = state().live!!.copy(scorer = false)), 50))
    }

    @Test
    fun staleLaunchNeverReplacesExistingMatch() {
        listOf(MatchStatus.IN_PROGRESS, MatchStatus.PAUSED, MatchStatus.AWAITING_DECISION).forEach { status ->
            GlanceAction.entries.forEach { assertEquals(GlanceAction.OPEN, GlanceLaunch.resolve(it.name, status)) }
        }
        assertEquals(GlanceAction.REPEAT, GlanceLaunch.resolve("REPEAT", null))
        assertEquals(GlanceAction.NEW, GlanceLaunch.resolve("NEW", null))
        assertEquals(GlanceAction.OPEN, GlanceLaunch.resolve("invalid", null))
        assertEquals(GlanceAction.OPEN, GlanceLaunch.resolve(null, null))
        assertEquals(GlanceAction.REPEAT, GlanceLaunch.resolve("REPEAT", MatchStatus.FINISHED))
        assertEquals(GlanceAction.NEW, GlanceLaunch.resolve("NEW", MatchStatus.FINISHED))
    }
}
