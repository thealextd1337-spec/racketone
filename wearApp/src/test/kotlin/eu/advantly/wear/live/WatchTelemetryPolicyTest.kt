package eu.advantly.wear.live

import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.wear.ui.activeWorkout
import eu.advantly.wear.workout.WorkoutUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-J: Wirkung der Telemetrie-Sichtbarkeit auf den Workout-Zustand der Uhr. */
class WatchTelemetryPolicyTest {
    private val measured = activeWorkout().copy(heartRateBpm = 142, steps = 812, caloriesKcal = 95.0)

    @Test
    fun offDropsAllValuesAndHidesTheTelemetryButKeepsTheWorkoutRunning() {
        val off = WatchTelemetryPolicy.apply(measured, TelemetryVisibility.OFF)
        assertFalse(off.telemetryAllowed)
        assertFalse(off.telemetryActive)
        assertNull(off.heartRateBpm)
        assertNull(off.steps)
        assertNull(off.caloriesKcal)
        assertEquals(measured.phase, off.phase)
        assertEquals(measured.selection, off.selection)
        assertSame(off, WatchTelemetryPolicy.apply(off, TelemetryVisibility.OFF))
        // Werte, die trotzdem hineingeschrieben würden, fallen sofort wieder heraus
        assertNull(WatchTelemetryPolicy.apply(off.copy(heartRateBpm = 150), TelemetryVisibility.OFF).heartRateBpm)
    }

    @Test
    fun privateAndShareKeepTheValuesAndReenableTheColumns() {
        for (visibility in listOf(TelemetryVisibility.PRIVATE, TelemetryVisibility.SHARE)) {
            assertSame(measured, WatchTelemetryPolicy.apply(measured, visibility))
            val back = WatchTelemetryPolicy.apply(WatchTelemetryPolicy.apply(measured, TelemetryVisibility.OFF), visibility)
            assertTrue(back.telemetryAllowed && back.telemetryActive)
            assertNull("verworfene Werte kommen nicht zurück", back.heartRateBpm)
        }
        assertFalse(WorkoutUiState().telemetryActive)
    }
}
