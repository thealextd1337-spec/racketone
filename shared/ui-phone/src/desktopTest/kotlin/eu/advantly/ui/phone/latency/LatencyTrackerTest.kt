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

        val summary = tracker.summary()
        assertEquals(1, summary.samples)
        assertEquals(8.0, summary.tapToDrawP50)
        assertEquals(17.0, summary.tapToFrameP50)
        assertEquals(InputClockSource.InputEvent, summary.source)
    }

    @Test
    fun overlappingTapsAreEachMeasuredWhenTheirStateIsDrawn() {
        val tracker = LatencyTracker()
        tracker.onInput(1, 0, InputClockSource.Handler)
        tracker.onInput(2, 5 * ms, InputClockSource.Handler)
        // Beide Zustände landen im selben Frame
        assertTrue(tracker.onDrawn(2, 10 * ms))
        tracker.onNextFrame(20 * ms)
        assertEquals(2, tracker.tapToFrame.count)
        assertEquals(20.0, tracker.tapToFrame.max())
        assertEquals(15.0, tracker.tapToFrame.percentile(50.0))
    }

    @Test
    fun nextFrameWithoutDrawIsIgnored() {
        val tracker = LatencyTracker()
        tracker.onNextFrame(5 * ms)
        assertEquals(0, tracker.summary().samples)
        tracker.onInput(1, 0, InputClockSource.Synthetic)
        tracker.reset()
        assertFalse(tracker.onDrawn(1, 1 * ms))
    }
}
