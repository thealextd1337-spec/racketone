package eu.advantly.wear.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseTypeSelectorTest {
    /** Fake für `ExerciseCapabilities`: Typ → unterstützte Messgrößen. */
    private class FakeCapabilities(
        private val byType: Map<WatchExerciseType, Set<WorkoutMetric>>,
    ) : ExerciseCapabilitiesView {
        override val supportedTypes: Set<WatchExerciseType> = byType.keys

        override fun supportedMetrics(type: WatchExerciseType): Set<WorkoutMetric> = byType[type].orEmpty()
    }

    private val all = WorkoutMetric.entries.toSet()

    @Test
    fun nativeBadmintonWhenSupported() {
        val caps =
            FakeCapabilities(
                mapOf(
                    WatchExerciseType.BADMINTON to setOf(WorkoutMetric.HEART_RATE_BPM, WorkoutMetric.CALORIES_TOTAL),
                    WatchExerciseType.TENNIS to all,
                    WatchExerciseType.WORKOUT to all,
                ),
            )
        val selection = ExerciseTypeSelector.select(SpikeSport.BADMINTON, caps)!!
        assertEquals(WatchExerciseType.BADMINTON, selection.type)
        assertFalse(selection.isFallback)
        // DataTypes aus den BADMINTON-Capabilities, nicht aus TENNIS (05 §0.1)
        assertEquals(setOf(WorkoutMetric.HEART_RATE_BPM, WorkoutMetric.CALORIES_TOTAL), selection.metrics)
    }

    @Test
    fun tableTennisFallsBackToTennis() {
        val caps =
            FakeCapabilities(
                mapOf(
                    WatchExerciseType.TENNIS to setOf(WorkoutMetric.HEART_RATE_BPM, WorkoutMetric.STEPS),
                    WatchExerciseType.WORKOUT to all,
                ),
            )
        val selection = ExerciseTypeSelector.select(SpikeSport.TABLE_TENNIS, caps)!!
        assertEquals(WatchExerciseType.TENNIS, selection.type)
        assertTrue(selection.isFallback)
        assertEquals(SpikeSport.TABLE_TENNIS, selection.sport)
        assertEquals(setOf(WorkoutMetric.HEART_RATE_BPM, WorkoutMetric.STEPS), selection.metrics)
    }

    @Test
    fun fallsBackToWorkoutWhenNeitherNativeNorTennis() {
        val caps = FakeCapabilities(mapOf(WatchExerciseType.WORKOUT to setOf(WorkoutMetric.HEART_RATE_BPM)))
        SpikeSport.entries.forEach { sport ->
            val selection = ExerciseTypeSelector.select(sport, caps)!!
            assertEquals(WatchExerciseType.WORKOUT, selection.type)
            assertTrue(selection.isFallback)
            assertEquals(setOf(WorkoutMetric.HEART_RATE_BPM), selection.metrics)
        }
    }

    @Test
    fun tennisIsNativeForTennis() {
        val caps = FakeCapabilities(mapOf(WatchExerciseType.TENNIS to all, WatchExerciseType.BADMINTON to all))
        val selection = ExerciseTypeSelector.select(SpikeSport.TENNIS, caps)!!
        assertEquals(WatchExerciseType.TENNIS, selection.type)
        assertFalse(selection.isFallback)
        assertEquals(all, selection.metrics)
    }

    @Test
    fun tennisNeverFallsBackToBadminton() {
        val caps = FakeCapabilities(mapOf(WatchExerciseType.BADMINTON to all))
        assertNull(ExerciseTypeSelector.select(SpikeSport.TENNIS, caps))
    }

    @Test
    fun noSupportedTypeYieldsNull() {
        assertNull(ExerciseTypeSelector.select(SpikeSport.BADMINTON, FakeCapabilities(emptyMap())))
    }

    @Test
    fun nativeTypeWithoutHeartRateStaysNative() {
        // Die Typwahl richtet sich nach supportedExerciseTypes, nicht danach, ob Puls dabei ist.
        val caps =
            FakeCapabilities(
                mapOf(
                    WatchExerciseType.TABLE_TENNIS to setOf(WorkoutMetric.CALORIES_TOTAL),
                    WatchExerciseType.TENNIS to all,
                ),
            )
        val selection = ExerciseTypeSelector.select(SpikeSport.TABLE_TENNIS, caps)!!
        assertEquals(WatchExerciseType.TABLE_TENNIS, selection.type)
        assertEquals(setOf(WorkoutMetric.CALORIES_TOTAL), selection.metrics)
    }

    @Test
    fun candidateOrderMatchesSpec() {
        assertEquals(
            listOf(WatchExerciseType.BADMINTON, WatchExerciseType.TENNIS, WatchExerciseType.WORKOUT),
            ExerciseTypeSelector.candidates(SpikeSport.BADMINTON),
        )
        assertEquals(
            listOf(WatchExerciseType.TABLE_TENNIS, WatchExerciseType.TENNIS, WatchExerciseType.WORKOUT),
            ExerciseTypeSelector.candidates(SpikeSport.TABLE_TENNIS),
        )
        assertEquals(listOf(WatchExerciseType.TENNIS, WatchExerciseType.WORKOUT), ExerciseTypeSelector.candidates(SpikeSport.TENNIS))
    }
}
