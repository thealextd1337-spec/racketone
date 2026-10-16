package eu.advantly.match.session

import kotlin.test.Test
import kotlin.test.assertEquals

class OperationTimingsTest {
    @Test
    fun serveBarCountdownRunsFromOneToZeroOverTheVisibleTime() {
        assertEquals(1f, OperationTimings.remainingFraction(OperationTimings.SERVE_BAR_VISIBLE_MILLIS))
        assertEquals(0.5f, OperationTimings.remainingFraction(2_500))
        assertEquals(0f, OperationTimings.remainingFraction(0))
        assertEquals(0f, OperationTimings.remainingFraction(-300), "abgelaufen")
        assertEquals(0.3f, OperationTimings.remainingFraction(OperationTimings.SERVE_BAR_AFTER_CHOICE_MILLIS), "nach Auswahl verkürzt")
        assertEquals(1f, OperationTimings.remainingFraction(9_000), "nie über voll")
        assertEquals(0f, OperationTimings.remainingFraction(100, totalMillis = 0))
    }

    @Test
    fun holdProgressIsFullExactlyAtTheThreshold() {
        assertEquals(0f, OperationTimings.holdFraction(0))
        assertEquals(0.5f, OperationTimings.holdFraction(OperationTimings.HOLD_TO_REMOVE_MILLIS / 2))
        assertEquals(1f, OperationTimings.holdFraction(OperationTimings.HOLD_TO_REMOVE_MILLIS))
        assertEquals(1f, OperationTimings.holdFraction(OperationTimings.HOLD_TO_REMOVE_MILLIS + 400))
        assertEquals(0f, OperationTimings.holdFraction(-5))
    }
}
