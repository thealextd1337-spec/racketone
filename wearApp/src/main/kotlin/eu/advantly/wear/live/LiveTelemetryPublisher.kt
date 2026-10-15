package eu.advantly.wear.live

import eu.advantly.core.model.MatchId
import eu.advantly.sync.protocol.LiveTelemetry
import eu.advantly.wear.diagnostics.PeriodicTrigger
import eu.advantly.wear.workout.WorkoutPhase
import eu.advantly.wear.workout.WorkoutUiState

/** Reine Versandentscheidung und Mapping; vorhandene monotone Drosselung, kein Puffer und keine Wiederholung. */
class LiveTelemetryPublisher {
    private val throttle = PeriodicTrigger(INTERVAL_MS)

    fun next(
        matchId: MatchId?,
        workoutEnabled: Boolean,
        exerciseActive: Boolean,
        phoneReachable: Boolean,
        workout: WorkoutUiState,
        nowMs: Long,
    ): LiveTelemetry? {
        if (matchId == null || !workoutEnabled || !exerciseActive || !phoneReachable || !workout.telemetryActive) return null
        if (!throttle.shouldFire(nowMs)) return null
        return LiveTelemetry(LiveTelemetry.VERSION, matchId, workout.heartRateBpm, workout.steps)
    }

    companion object {
        const val INTERVAL_MS: Long = 5_000
    }
}

/** ACTIVE ohne Selection bedeutet: kein unterstützter Exercise-Typ, also kein laufendes Workout. */
val WorkoutUiState.telemetryActive: Boolean get() = phase == WorkoutPhase.ACTIVE && selection != null
