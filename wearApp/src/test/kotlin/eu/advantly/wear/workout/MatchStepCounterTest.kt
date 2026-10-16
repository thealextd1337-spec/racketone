package eu.advantly.wear.workout

import eu.advantly.core.model.MatchId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatchStepCounterTest {
    private val first = MatchId("first")
    private val second = MatchId("second")

    @Test
    fun eachMatchStartsAtZeroEvenWhenExerciseContinues() {
        var counter = MatchStepCounter().forMatch(first)
        assertNull(counter.steps)
        counter = counter.withTotal(120)
        assertEquals(120L, counter.steps)
        counter = counter.forMatch(second)
        assertEquals(0L, counter.steps)
        counter = counter.withTotal(137)
        assertEquals(17L, counter.steps)
        assertEquals(17L, counter.forMatch(second).steps)
        assertEquals(17L, counter.withTotal(137).steps)
        assertNull(counter.forMatch(null).steps)
    }

    /** „Gar nicht“ (P2-J) verwirft den Stand; nach dem Wiedereinschalten zählen nur neue Schritte. */
    @Test
    fun restartDropsStepsAndTheNextTotalBecomesTheBaseline() {
        var counter = MatchStepCounter().forMatch(first).withTotal(100).withTotal(180)
        assertEquals(180L, counter.steps)
        counter = counter.restarted()
        assertNull(counter.steps)
        assertNull(counter.forMatch(first).steps)
        counter = counter.withTotal(400)
        assertEquals(0L, counter.steps)
        assertEquals(25L, counter.withTotal(425).steps)
    }

    @Test
    fun exerciseResetCannotProduceNegativeSteps() {
        val counter =
            MatchStepCounter()
                .forMatch(first)
                .withTotal(120)
                .forMatch(second)
                .withTotal(7)
        assertEquals(7L, counter.steps)
    }

    @Test
    fun lostRamBaselineRestartsConservativelyWithoutImportingPreviousMatch() {
        val counter = MatchStepCounter.recovered().forMatch(second).withTotal(500)
        assertEquals(0L, counter.steps)
        assertEquals(23L, counter.withTotal(523).steps)
    }

    @Test
    fun totalStepsArePreferredAndDeltaRemainsFallback() {
        val caps =
            object : ExerciseCapabilitiesView {
                override val supportedTypes = setOf(WatchExerciseType.TENNIS)

                override fun supportedMetrics(type: WatchExerciseType) = setOf(WorkoutMetric.STEPS, WorkoutMetric.STEPS_TOTAL)
            }
        assertEquals(setOf(WorkoutMetric.STEPS_TOTAL), ExerciseTypeSelector.select(WorkoutSport.TENNIS, caps)!!.metrics)
    }
}
