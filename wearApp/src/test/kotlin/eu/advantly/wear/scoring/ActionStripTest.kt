package eu.advantly.wear.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionStripTest {
    private val w = 400f
    private val h = 400f

    @Test
    fun stripCoversTheMiddleBandAndStaysBelowTheTopDeadZone() {
        // das ganze nicht zählende Mittelband liegt im Streifen, nur schmale Ränder der Hälften kommen dazu
        assertTrue(ActionStrip.TOP <= 0.5f - TapZones.MIDDLE_BAND / 2)
        assertTrue(ActionStrip.BOTTOM >= 0.5f + TapZones.MIDDLE_BAND / 2)
        assertTrue(ActionStrip.TOP > TapZones.TOP_DEAD_ZONE)
        assertTrue("Ränder der Hälften bleiben schmal", ActionStrip.HEIGHT <= 0.2f)
        assertEquals("mittig", 0.5f, (ActionStrip.TOP + ActionStrip.BOTTOM) / 2, 0.0001f)
    }

    @Test
    fun containsOnlyTheStripArea() {
        assertTrue(ActionStrip.contains(200f, 200f, w, h))
        assertTrue(ActionStrip.contains(30f, 170f, w, h))
        assertFalse("über dem Streifen (Wir)", ActionStrip.contains(200f, 160f, w, h))
        assertFalse("unter dem Streifen (Gegner)", ActionStrip.contains(200f, 240f, w, h))
        assertFalse("Rand links", ActionStrip.contains(10f, 200f, w, h))
        assertFalse("Rand rechts", ActionStrip.contains(390f, 200f, w, h))
        assertFalse(ActionStrip.contains(200f, 200f, 0f, 0f))
    }

    @Test
    fun segmentsSplitTheStripEvenly() {
        val left = w * ActionStrip.SIDE_MARGIN
        val segment = w * ActionStrip.WIDTH / 3
        assertEquals(0, ActionStrip.segment(left + 1f, w, 3))
        assertEquals(0, ActionStrip.segment(left + segment - 1f, w, 3))
        assertEquals(1, ActionStrip.segment(200f, w, 3))
        assertEquals(2, ActionStrip.segment(left + 2 * segment + 1f, w, 3))
        assertEquals("rechter Rand gehört zum letzten Segment", 2, ActionStrip.segment(w - left, w, 3))
        assertEquals(0, ActionStrip.segment(199f, w, 2))
        assertEquals(1, ActionStrip.segment(201f, w, 2))
        assertNull(ActionStrip.segment(5f, w, 3))
        assertNull(ActionStrip.segment(200f, w, 0))
    }
}
