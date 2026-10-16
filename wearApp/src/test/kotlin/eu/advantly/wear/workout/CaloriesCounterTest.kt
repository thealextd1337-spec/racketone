package eu.advantly.wear.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Review #47: Kalorien aus der „Gar nicht“-Phase erscheinen nach dem Wiedereinschalten nicht wieder. */
class CaloriesCounterTest {
    @Test
    fun exerciseTotalIsShownUntilTelemetryIsSwitchedOff() {
        var counter = CaloriesCounter()
        assertNull(counter.kcal)
        counter = counter.withTotal(80.0).withTotal(120.0)
        assertEquals(120.0, counter.kcal!!, 0.0)
    }

    @Test
    fun afterOffOnlyNewCaloriesCount() {
        var counter = CaloriesCounter().withTotal(120.0).restarted()
        assertNull(counter.kcal)
        // Rohwert enthält die OFF-Phase (120 → 200): wird zur Basis
        counter = counter.withTotal(200.0)
        assertEquals(0.0, counter.kcal!!, 0.0)
        assertEquals(15.0, counter.withTotal(215.0).kcal!!, 0.0)
    }

    @Test
    fun exerciseRestartCannotProduceNegativeValues() {
        val counter = CaloriesCounter().restarted().withTotal(200.0).withTotal(5.0)
        assertEquals(5.0, counter.kcal!!, 0.0)
    }

    /** Review-Runde 2: Nach Prozessverlust (REATTACH) zählt nicht der volle Rohwert inkl. einer früheren OFF-Phase. */
    @Test
    fun recoveryStartsAtZeroWithTheFirstSampleAsBaseline() {
        var counter = CaloriesCounter.recovered()
        assertNull(counter.kcal)
        counter = counter.withTotal(340.0)
        assertEquals(0.0, counter.kcal!!, 0.0)
        assertEquals(12.0, counter.withTotal(352.0).kcal!!, 0.0)
        // auch beim Schrittzähler: Recovery beginnt bei null
        val steps =
            MatchStepCounter
                .recovered()
                .forMatch(
                    eu.advantly.core.model
                        .MatchId("m"),
                ).withTotal(5_000)
        assertEquals(0L, steps.steps)
    }
}
