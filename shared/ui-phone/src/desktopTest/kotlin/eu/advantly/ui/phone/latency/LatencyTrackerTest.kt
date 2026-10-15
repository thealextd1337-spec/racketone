package eu.advantly.ui.phone.latency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LatencyTrackerTest {
    private val ms = 1_000_000L

    @Test
    fun nearestRankPercentiles() {
        val values = (1..100).map { it.toDouble() }
        assertEquals(50.0, percentileOf(values, 50.0))
        assertEquals(95.0, percentileOf(values, 95.0))
        assertEquals(100.0, percentileOf(values, 100.0))
        assertEquals(7.0, percentileOf(listOf(7.0), 95.0))
        assertNull(percentileOf(emptyList(), 50.0))
    }

    @Test
    fun formatsMillisWithOneDecimal() {
        assertEquals("16.7", formatMillis(16.66))
        assertEquals("0.0", formatMillis(0.01))
        assertEquals("–", formatMillis(null))
    }

    @Test
    fun measuresInputToDrawAndNextFrame() {
        val tracker = LatencyTracker()
        tracker.onInput(generation = 1, t0Nanos = 100 * ms, source = InputClockSource.InputEvent)
        assertFalse(tracker.onDrawn(drawnGeneration = 0, nowNanos = 104 * ms), "alter Zustand gezeichnet")
        assertTrue(tracker.onDrawn(drawnGeneration = 1, nowNanos = 108 * ms))
        tracker.onNextFrame(nowNanos = 117 * ms)

        val summary = tracker.summary(InputClockSource.InputEvent)
        assertEquals(1, summary.samples)
        assertEquals(8.0, summary.inputToDrawP50)
        assertEquals(17.0, summary.inputToNextFrameP50)
        assertEquals(listOf(summary), tracker.summaries())
    }

    @Test
    fun overlappingTapsAreEachMeasuredWhenTheirStateIsDrawn() {
        val tracker = LatencyTracker()
        tracker.onInput(1, 0, InputClockSource.InputEvent)
        tracker.onInput(2, 5 * ms, InputClockSource.InputEvent)
        // Beide Zustände landen im selben Frame
        assertTrue(tracker.onDrawn(2, 10 * ms))
        tracker.onNextFrame(20 * ms)
        val window = tracker.inputToNextFrame(InputClockSource.InputEvent)
        assertEquals(2, window.count)
        assertEquals(20.0, window.max())
        assertEquals(15.0, window.percentile(50.0))
    }

    @Test
    fun fallbackSamplesAreKeptApartFromInputEventSamples() {
        val tracker = LatencyTracker()
        tracker.onInput(1, 0, InputClockSource.InputEvent)
        tracker.onDrawn(1, 10 * ms)
        tracker.onNextFrame(30 * ms)
        // Fallback (z. B. Ereignis älter als 2 s → Handler-Zeit): eigener Puffer, verfälscht G1 nicht
        tracker.onInput(2, 100 * ms, InputClockSource.Handler)
        tracker.onDrawn(2, 101 * ms)
        tracker.onNextFrame(102 * ms)

        assertEquals(30.0, tracker.summary(InputClockSource.InputEvent).inputToNextFrameP95)
        assertEquals(1, tracker.summary(InputClockSource.InputEvent).samples)
        assertEquals(2.0, tracker.summary(InputClockSource.Handler).inputToNextFrameP95)
        assertEquals(listOf(InputClockSource.InputEvent, InputClockSource.Handler), tracker.summaries().map { it.source })
    }

    @Test
    fun nextFrameWithoutDrawIsIgnored() {
        val tracker = LatencyTracker()
        tracker.onNextFrame(5 * ms)
        assertTrue(tracker.summaries().isEmpty())
        tracker.onInput(1, 0, InputClockSource.Synthetic)
        tracker.reset()
        assertFalse(tracker.onDrawn(1, 1 * ms))
    }

    @Test
    fun logLineIsMachineReadable() {
        val tracker = LatencyTracker()
        tracker.onInput(1, 0, InputClockSource.Synthetic)
        tracker.onDrawn(1, 4 * ms)
        tracker.onNextFrame(16 * ms)
        assertEquals(
            "ADVANTLY_BENCH source=Synthetic inputToNextFrame p50=16.0 p95=16.0 max=16.0 inputToDraw p50=4.0 p95=4.0 n=1",
            tracker.summary(InputClockSource.Synthetic).toLogLine(),
        )
    }
}
