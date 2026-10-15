package eu.advantly.wear.workout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Phase der Workout-Session aus Sicht der UI. */
enum class WorkoutPhase {
    IDLE,
    STARTING,
    ACTIVE,

    /** Eine andere App zeichnet ein Training auf; ersetzen nur nach ausdrücklicher Entscheidung. */
    CONFLICT,
    ENDING,
    FAILED,
}

/** Fehlerart für einen bedienbaren Fehlerzustand (Text + „Erneut versuchen“). */
enum class WorkoutError {
    PERMISSIONS,
    CAPABILITIES,
    STATUS_QUERY,
    START,
    END,
    CALLBACK,
    EXTERNAL_END,
    FOREGROUND,
    INTERNAL,
}

/** Live-Zustand, den der [WorkoutService] schreibt und die UI liest. */
data class WorkoutUiState(
    val phase: WorkoutPhase = WorkoutPhase.IDLE,
    val selection: ExerciseSelection? = null,
    /** Match läuft ohne Workout, weil Puls- und Aktivitätsrecht fehlen – keine Laufzeitgarantie (Hinweis in der UI). */
    val countOnly: Boolean = false,
    val heartRateBpm: Int? = null,
    val caloriesKcal: Double? = null,
    val steps: Long? = null,
    /** Aktive Dauer zum Zeitpunkt [durationAnchorElapsedMillis] (SystemClock.elapsedRealtime) für eine laufende Uhr in der UI. */
    val activeDurationMillis: Long = 0,
    val durationAnchorElapsedMillis: Long? = null,
    val heartRateAvailability: String? = null,
    val error: WorkoutError? = null,
    val errorDetail: String? = null,
)

/** Prozessweiter Halter des Live-Zustands (Service und Activity im selben Prozess, kein Binding nötig). */
object WorkoutStateHolder {
    private val mutable = MutableStateFlow(WorkoutUiState())
    val state: StateFlow<WorkoutUiState> = mutable.asStateFlow()

    fun update(transform: (WorkoutUiState) -> WorkoutUiState) = mutable.update(transform)

    fun fail(
        error: WorkoutError,
        detail: String? = null,
    ) = update { it.copy(phase = WorkoutPhase.FAILED, error = error, errorDetail = detail) }

    fun reset() {
        mutable.value = WorkoutUiState()
    }
}
