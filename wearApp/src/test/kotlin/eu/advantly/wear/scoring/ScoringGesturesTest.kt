package eu.advantly.wear.scoring

import eu.advantly.core.model.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoringGesturesTest {
    private val height = 1000f

    @Test
    fun topHalfIsUsBottomHalfIsThem() {
        assertEquals(Side.A, TapZones.classify(200f, height))
        assertEquals(Side.A, TapZones.classify(449f, height))
        assertEquals(Side.B, TapZones.classify(551f, height))
        assertEquals(Side.B, TapZones.classify(990f, height))
    }

    @Test
    fun deadZonesDoNotScore() {
        assertNull("oberste 12 %", TapZones.classify(50f, height))
        assertNull("oberste 12 %", TapZones.classify(119f, height))
        assertNull("Mittelband", TapZones.classify(500f, height))
        assertNull("Mittelband", TapZones.classify(460f, height))
        assertNull("außerhalb", TapZones.classify(1200f, height))
        assertNull("ohne Höhe", TapZones.classify(10f, 0f))
    }

    @Test
    fun smallShortMovementIsTap() {
        assertEquals(ScoringGesture.TAP, classify(dx = 3f, dy = -4f, duration = 120))
    }

    @Test
    fun holdBelowOneSecondIsNothingAndAboveIsLongPress() {
        assertEquals(ScoringGesture.NONE, classify(dx = 0f, dy = 0f, duration = 900))
        assertEquals(ScoringGesture.LONG_PRESS, classify(dx = 2f, dy = 0f, duration = 1_000))
    }

    @Test
    fun swipeDownIsUndo() {
        assertEquals(ScoringGesture.SWIPE_DOWN, classify(dx = 10f, dy = 120f, duration = 200))
    }

    @Test
    fun swipeUpIsIgnored() {
        assertEquals(ScoringGesture.NONE, classify(dx = 0f, dy = -120f, duration = 200))
    }

    @Test
    fun swipeLeftOpensControlsAndRightIsIgnored() {
        assertEquals(ScoringGesture.SWIPE_LEFT, classify(dx = -150f, dy = 20f, duration = 200))
        assertEquals(ScoringGesture.NONE, classify(dx = 150f, dy = 0f, duration = 200))
    }

    @Test
    fun diagonalSwipeIsAmbiguous() {
        assertEquals(ScoringGesture.NONE, classify(dx = -100f, dy = 100f, duration = 200))
    }

    @Test
    fun movementBetweenSlopAndThresholdIsIgnored() {
        assertEquals(ScoringGesture.NONE, classify(dx = 0f, dy = 50f, duration = 200))
    }

    private fun classify(
        dx: Float,
        dy: Float,
        duration: Long,
    ) = GestureClassifier.classify(dx, dy, duration, touchSlopPx = 16f, swipeThresholdPx = 80f)
}
