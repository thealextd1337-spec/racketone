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

    @Test
    fun metricsFollowPermissionMatrix() {
        // Matrix nur Puls / nur Aktivität / beide / keins. Kalorien und Schritte brauchen ACTIVITY_RECOGNITION.
        val caps = FakeCapabilities(mapOf(WatchExerciseType.TENNIS to all))
        val heartRate = setOf(WorkoutMetric.HEART_RATE_BPM, WorkoutMetric.HEART_RATE_BPM_STATS)
        val activity = setOf(WorkoutMetric.STEPS, WorkoutMetric.CALORIES_TOTAL)
        val cases =
            listOf(
                Triple(true, false, heartRate),
                Triple(false, true, activity),
                Triple(true, true, heartRate + activity),
                Triple(false, false, emptySet()),
            )
        cases.forEach { (hr, ar, expected) ->
            val permissions = WorkoutPermissions(heartRate = hr, activityRecognition = ar, notifications = true)
            assertEquals("heartRate=$hr activity=$ar", expected, permissions.allowedMetrics)
            assertEquals(
                "heartRate=$hr activity=$ar",
                expected,
                ExerciseTypeSelector.select(SpikeSport.TENNIS, caps, permissions)!!.metrics,
            )
        }
    }

    @Test
    fun caloriesNeverRequestedWithHeartRateOnly() {
        val onlyHeartRate = WorkoutPermissions(heartRate = true, activityRecognition = false, notifications = true)
        val caps = FakeCapabilities(mapOf(WatchExerciseType.BADMINTON to setOf(WorkoutMetric.CALORIES_TOTAL, WorkoutMetric.HEART_RATE_BPM)))
        val metrics = ExerciseTypeSelector.select(SpikeSport.BADMINTON, caps, onlyHeartRate)!!.metrics
        assertFalse(WorkoutMetric.CALORIES_TOTAL in metrics)
        assertEquals(setOf(WorkoutMetric.HEART_RATE_BPM), metrics)
    }

    @Test
    fun withoutHeartRateAndActivityNoHealthServiceAndNoMetrics() {
        val none = WorkoutPermissions(heartRate = false, activityRecognition = false, notifications = true)
        assertFalse(none.canRunHealthService)
        assertEquals(emptySet<WorkoutMetric>(), none.allowedMetrics)
        assertTrue(WorkoutPermissions(heartRate = true, activityRecognition = false, notifications = false).canRunHealthService)
        assertTrue(WorkoutPermissions(heartRate = false, activityRecognition = true, notifications = false).canRunHealthService)
    }

    @Test
    fun allPermissionsKeepAllSupportedMetrics() {
        assertEquals(WorkoutMetric.entries.toSet(), WorkoutPermissions.ALL.allowedMetrics)
    }
}
