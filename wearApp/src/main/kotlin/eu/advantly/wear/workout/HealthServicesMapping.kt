package eu.advantly.wear.workout

import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseCapabilities
import androidx.health.services.client.data.ExerciseType

/** Abbildung zwischen unseren Spiegel-Enums und den Health-Services-Typen (androidx.health:health-services-client). */
object HealthServicesMapping {
    fun exerciseType(type: WatchExerciseType): ExerciseType =
        when (type) {
            WatchExerciseType.TENNIS -> ExerciseType.TENNIS
            WatchExerciseType.BADMINTON -> ExerciseType.BADMINTON
            WatchExerciseType.TABLE_TENNIS -> ExerciseType.TABLE_TENNIS
            WatchExerciseType.WORKOUT -> ExerciseType.WORKOUT
        }

    fun dataType(metric: WorkoutMetric): DataType<*, *> =
        when (metric) {
            WorkoutMetric.HEART_RATE_BPM -> DataType.HEART_RATE_BPM
            WorkoutMetric.HEART_RATE_BPM_STATS -> DataType.HEART_RATE_BPM_STATS
            WorkoutMetric.CALORIES_TOTAL -> DataType.CALORIES_TOTAL
            WorkoutMetric.STEPS -> DataType.STEPS
            WorkoutMetric.STEPS_TOTAL -> DataType.STEPS_TOTAL
        }

    /** Echte Capabilities des Geräts als [ExerciseCapabilitiesView]. */
    fun ExerciseCapabilities.toView(): ExerciseCapabilitiesView {
        val caps = this
        return object : ExerciseCapabilitiesView {
            override val supportedTypes: Set<WatchExerciseType> =
                WatchExerciseType.entries.filter { exerciseType(it) in caps.supportedExerciseTypes }.toSet()

            override fun supportedMetrics(type: WatchExerciseType): Set<WorkoutMetric> {
                val hsType = exerciseType(type)
                if (hsType !in caps.supportedExerciseTypes) return emptySet()
                val supported = caps.getExerciseTypeCapabilities(hsType).supportedDataTypes
                return WorkoutMetric.entries.filter { dataType(it) in supported }.toSet()
            }
        }
    }
}
