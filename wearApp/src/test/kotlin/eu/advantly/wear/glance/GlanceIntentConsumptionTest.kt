package eu.advantly.wear.glance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlanceIntentConsumptionTest {
    @Test
    fun originalIntentCannotStartAgainAfterProcessDeathOrConfigurationChange() {
        listOf("REPEAT", "NEW", "OPEN").forEach { action ->
            val initial = GlanceIntentConsumption()
            assertEquals(action, initial.consume(action))
            assertTrue(initial.consumed)
            assertNull(initial.consume(action))
            val restored = GlanceIntentConsumption(consumed = initial.consumed)
            assertNull(restored.consume(action))
        }
    }

    @Test
    fun explicitNewIntentCanStartOnceAfterRestoration() {
        val restored = GlanceIntentConsumption(consumed = true)
        assertNull(restored.consume("REPEAT"))
        restored.onNewIntent()
        assertEquals("NEW", restored.consume("NEW"))
        assertNull(restored.consume("NEW"))
    }

    @Test
    fun ordinaryResumeDoesNotConsumeFutureNewIntent() {
        val consumption = GlanceIntentConsumption()
        assertNull(consumption.consume(null))
        assertEquals("OPEN", consumption.consume("OPEN"))
    }
}
