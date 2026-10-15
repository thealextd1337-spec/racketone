package eu.advantly.wear.glance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileLayoutMetricsTest {
    @Test
    fun smallRoundWatchKeepsBothButtonsInsideCircleAtAllSupportedFontScales() {
        listOf(1f, 1.15f, 1.3f, 1.5f, 2f).forEach { scale ->
            val metrics = TileLayoutMetrics(192, 192, true, scale)
            val radius = 96f
            assertTrue(
                metrics.contentWidth * metrics.contentWidth / 4 + metrics.contentHeight * metrics.contentHeight / 4 < radius * radius,
            )
            assertTrue("idle overflow at $scale", metrics.idleHeight() <= metrics.contentHeight)
            val liveHeight =
                12f + (TileLayoutMetrics.SCORE_SIZE + TileLayoutMetrics.DETAIL_SIZE * (1 + metrics.liveLines)) *
                    TileLayoutMetrics.LINE_HEIGHT * scale
            assertTrue("live overflow at $scale", liveHeight <= metrics.contentHeight)
        }
    }

    @Test
    fun setupUsesEveryLineThatFitsUpToThree() {
        listOf(192, 240, 300).forEach { size ->
            listOf(false, true).forEach { round ->
                listOf(1f, 1.3f).forEach { scale ->
                    val metrics = TileLayoutMetrics(size, size, round, scale)
                    assertTrue(metrics.idleHeight() <= metrics.contentHeight)
                    if (metrics.setupLines < 3) {
                        assertTrue(metrics.idleHeight(setupLines = metrics.setupLines + 1) > metrics.contentHeight)
                    }
                }
            }
        }
        assertEquals(2, TileLayoutMetrics(192, 192, true, 1f).setupLines)
        assertEquals(0, TileLayoutMetrics(192, 192, true, 1.3f).setupLines)
        assertEquals(3, TileLayoutMetrics(240, 240, true, 1f).setupLines)
        assertEquals(3, TileLayoutMetrics(192, 192, false, 1f).setupLines)
    }

    @Test
    fun largerAndSquareDisplaysUseTheirAvailableSpace() {
        listOf(false, true).forEach { round ->
            val small = TileLayoutMetrics(192, 192, round, 1.3f)
            val large = TileLayoutMetrics(240, 240, round, 1.3f)
            assertTrue(large.contentWidth > small.contentWidth)
            assertTrue(large.liveLines >= small.liveLines)
            assertTrue(large.idleHeight() <= large.contentHeight)
        }
    }
}
