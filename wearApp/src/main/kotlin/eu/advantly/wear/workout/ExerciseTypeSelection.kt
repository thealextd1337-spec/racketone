package eu.advantly.wear.workout

/** Sportarten, die der Spike zur Auswahl anbietet (Padel/Pickleball laufen wie Tennis, 05 E3). */
enum class SpikeSport { TENNIS, BADMINTON, TABLE_TENNIS }

/**
 * Die für uns relevanten Health-Services-`ExerciseType`s, plattformunabhängig gespiegelt,
 * damit die Auswahl-Logik ohne Android als JVM-Test läuft. Abbildung auf die echten Typen: [HealthServicesMapping].
 */
enum class WatchExerciseType { TENNIS, BADMINTON, TABLE_TENNIS, WORKOUT }

/** Gewünschte Messgrößen (05 §2.1). Dauer kommt aus dem `ActiveDurationCheckpoint`, nicht aus einem DataType. */
enum class WorkoutMetric {
    HEART_RATE_BPM,
    HEART_RATE_BPM_STATS,
    CALORIES_TOTAL,
    STEPS,
}

/** Sicht auf `ExerciseCapabilities` – in Tests ein Fake, auf der Uhr [HealthServicesMapping.toView]. */
interface ExerciseCapabilitiesView {
    val supportedTypes: Set<WatchExerciseType>

    fun supportedMetrics(type: WatchExerciseType): Set<WorkoutMetric>
}

/** Ergebnis der Typwahl: tatsächlich verwendeter Typ, ob es ein Fallback ist, und die anzufordernden Messgrößen. */
data class ExerciseSelection(
    val sport: SpikeSport,
    val type: WatchExerciseType,
    val isFallback: Boolean,
    val metrics: Set<WorkoutMetric>,
)

/**
 * Typwahl nach 05 §0.1 (normativ):
 * 1. nativer Typ, wenn in `supportedExerciseTypes` enthalten,
 * 2. sonst `TENNIS` (nächstliegender Rückschlagsport),
 * 3. sonst `WORKOUT` (generisch).
 *
 * Die Messgrößen kommen **immer** aus den Capabilities des tatsächlich verwendeten Typs –
 * nie aus einer festen Liste und nie aus den TENNIS-Capabilities, wenn ein anderer Typ genutzt wird.
 */
object ExerciseTypeSelector {
    val WANTED_METRICS: Set<WorkoutMetric> = WorkoutMetric.entries.toSet()

    /** Reihenfolge der Kandidaten je Sportart. */
    fun candidates(sport: SpikeSport): List<WatchExerciseType> =
        when (sport) {
            SpikeSport.TENNIS -> listOf(WatchExerciseType.TENNIS, WatchExerciseType.WORKOUT)
            SpikeSport.BADMINTON -> listOf(WatchExerciseType.BADMINTON, WatchExerciseType.TENNIS, WatchExerciseType.WORKOUT)
            SpikeSport.TABLE_TENNIS -> listOf(WatchExerciseType.TABLE_TENNIS, WatchExerciseType.TENNIS, WatchExerciseType.WORKOUT)
        }

    /** Liefert `null`, wenn das Gerät keinen der Kandidaten unterstützt (dann zählen ohne Workout/Puls). */
    fun select(
        sport: SpikeSport,
        capabilities: ExerciseCapabilitiesView,
    ): ExerciseSelection? {
        val options = candidates(sport)
        val type = options.firstOrNull { it in capabilities.supportedTypes } ?: return null
        return ExerciseSelection(
            sport = sport,
            type = type,
            isFallback = type != options.first(),
            metrics = capabilities.supportedMetrics(type).intersect(WANTED_METRICS),
        )
    }
}
