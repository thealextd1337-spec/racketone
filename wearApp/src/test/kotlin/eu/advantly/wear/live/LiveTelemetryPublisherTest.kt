package eu.advantly.wear.live

import eu.advantly.core.model.MatchId
import eu.advantly.sync.protocol.LiveTelemetry
import eu.advantly.wear.workout.ExerciseSelection
import eu.advantly.wear.workout.WatchExerciseType
import eu.advantly.wear.workout.WorkoutMetric
import eu.advantly.wear.workout.WorkoutPhase
import eu.advantly.wear.workout.WorkoutSport
import eu.advantly.wear.workout.WorkoutUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTelemetryPublisherTest {
    private val match = MatchId("match")
    private val workout =
        WorkoutUiState(
            phase = WorkoutPhase.ACTIVE,
            selection = ExerciseSelection(WorkoutSport.TENNIS, WatchExerciseType.TENNIS, false, WorkoutMetric.entries.toSet()),
            heartRateBpm = 144,
            steps = 321,
        )

    @Test
    fun mapsLatestValuesAtMostEveryFiveSecondsIncludingUnchangedHeartbeat() {
        val publisher = LiveTelemetryPublisher()
        assertEquals(LiveTelemetry(1, match, 144, 321), publisher.next(match, true, true, true, workout, 0))
        assertNull(publisher.next(match, true, true, true, workout.copy(heartRateBpm = 145, steps = 322), 4_999))
        assertEquals(
            LiveTelemetry(1, match, 145, 322),
            publisher.next(match, true, true, true, workout.copy(heartRateBpm = 145, steps = 322), 5_000),
        )
        assertEquals(LiveTelemetry(1, match, 144, 321), publisher.next(match, true, true, true, workout, 10_000))
    }

    @Test
    fun requiresEnabledMatchActiveExerciseAndReachablePhone() {
        val publisher = LiveTelemetryPublisher()
        assertNull(publisher.next(null, true, true, true, workout, 0))
        assertNull(publisher.next(match, false, true, true, workout, 0))
        assertNull(publisher.next(match, true, false, true, workout, 0))
        assertNull(publisher.next(match, true, true, false, workout, 0))
        WorkoutPhase.entries.filter { it != WorkoutPhase.ACTIVE }.forEach {
            assertNull(publisher.next(match, true, true, true, workout.copy(phase = it), 0))
        }
        assertNull(publisher.next(match, true, true, true, workout.copy(selection = null), 0))
        assertEquals(LiveTelemetry(1, match, 144, 321), publisher.next(match, true, true, true, workout, 0))
    }

    @Test
    fun reconnectSendsCurrentSampleAndNewMatchIdWithoutQueuedHistory() {
        val publisher = LiveTelemetryPublisher()
        publisher.next(match, true, true, true, workout, 0)
        assertNull(publisher.next(match, true, true, false, workout, 10_000))
        val nextMatch = MatchId("next")
        assertEquals(
            LiveTelemetry(1, nextMatch, null, 400),
            publisher.next(nextMatch, true, true, true, workout.copy(heartRateBpm = null, steps = 400), 20_000),
        )
    }

    @Test
    fun missingMetricsStayMissingAndZeroStepsAreAvailable() {
        assertEquals(
            LiveTelemetry(1, match),
            LiveTelemetryPublisher().next(match, true, true, true, workout.copy(heartRateBpm = null, steps = null), 0),
        )
        assertEquals(
            LiveTelemetry(1, match, steps = 0),
            LiveTelemetryPublisher().next(match, true, true, true, workout.copy(heartRateBpm = null, steps = 0), 0),
        )
    }

    @Test
    fun ambientThrottlesHeartRateAndStepsTogether() {
        val throttle = ValueThrottle<Pair<Int?, Long?>>()
        assertEquals(140 to 10L, throttle.display(140 to 10L, 0, true))
        assertEquals(140 to 10L, throttle.display(150 to 50L, 59_999, true))
        assertEquals(150 to 50L, throttle.display(150 to 50L, 60_000, true))
        assertEquals(151 to 51L, throttle.display(151 to 51L, 60_001, false))
    }
}
