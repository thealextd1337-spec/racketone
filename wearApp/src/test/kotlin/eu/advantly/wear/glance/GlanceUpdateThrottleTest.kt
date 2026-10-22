package eu.advantly.wear.glance

import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun firstStateIsOnlyBaselineAndEqualValuesDoNotUpdate() {
        val state = state()
        assertNull(throttle.delayFor(state, 100))
        assertNull(throttle.delayFor(state.copy(), 5_100))
        assertEquals(0L, throttle.delayFor(state("15:0"), 5_100))
    }

    @Test
    fun repeatedBackgroundProcessStartsDoNotRequestUpdates() {
        val stored = GlanceStateStorage.encode(state())
        repeat(5) {
            val restarted = GlanceUpdateThrottle(initialState = GlanceStateStorage.decode(stored))
            assertNull(restarted.delayFor(state(), 100))
        }
    }

    @Test
    fun restartRecoversLostTrailingUpdateButDoesNotRepeatIt() {
        val stored = GlanceStateStorage.encode(state())
        val latest = state("30:0")
        val restarted = GlanceUpdateThrottle(initialState = GlanceStateStorage.decode(stored))
        assertEquals(0L, restarted.delayFor(latest, 100))
        restarted.sent(latest, 100)
        val nextProcess = GlanceUpdateThrottle(initialState = GlanceStateStorage.decode(GlanceStateStorage.encode(latest)))
        assertNull(nextProcess.delayFor(latest, 0))
    }

    private fun named(name: String?) =
        state().copy(
            live = state().live!!.copy(server = GlancePlayer(name, false, Side.B, 0)),
            last = GlanceSetup(Presets.TENNIS_SINGLES_BO3.format(), listOf(GlancePlayer(name, false, Side.B, 0))),
        )

    @Test
    fun namesAreNeverStoredAndRestartResolvesThemWithoutWakeup() {
        val stored = GlanceStateStorage.encode(named("Bea"))
        assertFalse(stored.contains("Bea"))
        val restarted = GlanceUpdateThrottle(initialState = GlanceStateStorage.decode(stored))
        // Kaltstart löst den Namen neu auf; derselbe Zustand mit Namen ist keine Änderung
        assertNull(restarted.delayFor(named("Bea"), 100))
        // Redaktion nach dem Senden: der leere Name ist eine Änderung
        restarted.sent(named("Bea"), 100)
        assertEquals(0L, restarted.delayFor(named(null), 5_100))
    }

    @Test
    fun legacyStoredStateWithNamesIsDiscarded() {
        val legacy =
            """{"last":{"format":${Json.encodeToString(MatchFormat.serializer(), Presets.TENNIS_SINGLES_BO3.format())},""" +
                """"players":[{"name":"Bea","isSelf":false,"side":"B","index":0}]}}"""
        assertNull(GlanceStateStorage.decode(legacy))
        assertEquals(named(null).last, GlanceStateStorage.decode(legacy.replace("\"Bea\"", "null"))?.last)
    }

    @Test
    fun corruptOrMissingStorageCreatesBaseline() {
        listOf(null, "broken", "{\"live\":42}").forEach { value ->
            assertNull(GlanceStateStorage.decode(value))
            assertNull(GlanceUpdateThrottle(initialState = GlanceStateStorage.decode(value)).delayFor(state(), 0))
        }
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
