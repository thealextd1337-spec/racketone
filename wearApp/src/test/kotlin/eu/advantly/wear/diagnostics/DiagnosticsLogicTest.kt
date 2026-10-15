package eu.advantly.wear.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogicTest {
    @Test
    fun gapIsReportedOnlyAboveThreshold() {
        val detector = HeartRateGapDetector(thresholdMillis = 10_000)
        assertNull(detector.onSample(0))
        assertNull(detector.onSample(1_000))
        assertNull("genau 10 s ist keine Lücke", detector.onSample(11_000))
        val gap = detector.onSample(23_000)!!
        assertEquals(11_000, gap.fromEpochMillis)
        assertEquals(23_000, gap.toEpochMillis)
        assertEquals(12_000, gap.durationMillis)
    }

    @Test
    fun outOfOrderAndDuplicateSamplesAreIgnored() {
        val detector = HeartRateGapDetector(thresholdMillis = 10_000)
        detector.onSample(50_000)
        assertNull(detector.onSample(40_000))
        assertNull(detector.onSample(50_000))
        assertNull(detector.onSample(55_000))
    }

    @Test
    fun resetStartsNewSeriesWithoutGap() {
        val detector = HeartRateGapDetector()
        detector.onSample(0)
        detector.reset()
        assertNull(detector.onSample(600_000))
    }

    @Test
    fun periodicTriggerFiresFirstTimeAndThenEveryInterval() {
        val trigger = PeriodicTrigger(intervalMillis = 300_000)
        assertTrue(trigger.shouldFire(1_000))
        assertFalse(trigger.shouldFire(200_000))
        assertTrue(trigger.shouldFire(301_000))
        assertFalse(trigger.shouldFire(400_000))
    }

    @Test
    fun lineFormatIsTabSeparatedAndSanitized() {
        val line = DiagLine.format(0, 1234, "HR", linkedMapOf("bpm" to 142, "note" to "a b\tc"))
        assertEquals("1970-01-01T00:00:00Z\t1234\tHR\tbpm=142 note=a_b_c", line)
    }

    @Test
    fun lineWithoutFieldsHasEmptyDetails() {
        assertEquals("1970-01-01T00:00:01Z\t5\tSERVICE_CREATE\t", DiagLine.format(1_000, 5, "SERVICE_CREATE"))
    }
}
