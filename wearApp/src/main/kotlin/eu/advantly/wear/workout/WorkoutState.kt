package eu.advantly.wear.workout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Phase der Workout-Session aus Sicht der UI. */
enum class WorkoutPhase { IDLE, STARTING, ACTIVE, ENDING, FAILED }

/** Live-Zustand, den der [WorkoutService] schreibt und die UI liest. */
data class WorkoutUiState(
    val phase: WorkoutPhase = WorkoutPhase.IDLE,
    val selection: ExerciseSelection? = null,
    val heartRateBpm: Int? = null,
    val caloriesKcal: Double? = null,
    val steps: Long? = null,
    /** Aktive Dauer zum Zeitpunkt [durationAnchorElapsedMillis] (SystemClock.elapsedRealtime) für eine laufende Uhr in der UI. */
    val activeDurationMillis: Long = 0,
    val durationAnchorElapsedMillis: Long? = null,
    val heartRateAvailability: String? = null,
    val errorMessage: String? = null,
)

/** Prozessweiter Halter des Live-Zustands (für den Spike genügt ein Singleton statt Binding). */
object WorkoutStateHolder {
    private val mutable = MutableStateFlow(WorkoutUiState())
    val state: StateFlow<WorkoutUiState> = mutable.asStateFlow()

    fun update(transform: (WorkoutUiState) -> WorkoutUiState) = mutable.update(transform)

    fun reset() {
        mutable.value = WorkoutUiState()
    }
}
