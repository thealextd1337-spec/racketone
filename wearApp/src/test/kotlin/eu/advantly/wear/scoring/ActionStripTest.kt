package eu.advantly.wear.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionStripTest {
    /** Pixel Watch 3 (41 mm): 384 px bei 2 px/dp = 192 dp. */
    private val density = 2f
    private val size = 384f
    private val g = ActionStrip.geometry(size, size, density)

    @Test
    fun touchZoneIsAtLeast48dpOnSmallRoundDisplays() {
        assertEquals(48f * density, g.height, 0.01f)
        assertTrue("sichtbar etwas flacher als die Trefferzone", g.visualHeight < g.height && g.visualHeight >= 32f * density)
        assertEquals("mittig", size / 2, (g.top + g.bottom) / 2, 0.01f)
        // großes Display: Anteil der Höhe statt der Mindesthöhe
        val big = ActionStrip.geometry(1_000f, 1_000f, 2f)
        assertEquals(160f, big.height, 0.01f)
    }

    @Test
    fun stripAndGuardCoverTheMiddleBandAndStayBelowTheTopDeadZone() {
        val bandTop = size * (0.5f - TapZones.MIDDLE_BAND / 2)
        val bandBottom = size * (0.5f + TapZones.MIDDLE_BAND / 2)
        assertTrue(g.top <= bandTop && g.bottom >= bandBottom)
        assertTrue(g.top - g.guard > size * TapZones.TOP_DEAD_ZONE)
    }

    @Test
    fun hitSeparatesStripGuardAndScoringAreas() {
        val x = size / 2
        assertEquals(StripHit.STRIP, ActionStrip.hit(x, size / 2, g))
        assertEquals(StripHit.STRIP, ActionStrip.hit(x, g.top, g))
        assertEquals(StripHit.STRIP, ActionStrip.hit(x, g.bottom, g))
        assertEquals("knapp über der Leiste zählt nicht", StripHit.GUARD, ActionStrip.hit(x, g.top - 1f, g))
        assertEquals("knapp unter der Leiste zählt nicht", StripHit.GUARD, ActionStrip.hit(x, g.bottom + g.guard, g))
        assertEquals(StripHit.OUTSIDE, ActionStrip.hit(x, g.top - g.guard - 1f, g))
        assertEquals(StripHit.OUTSIDE, ActionStrip.hit(x, g.bottom + g.guard + 1f, g))
        assertEquals("seitlich neben der Leiste: Schutz", StripHit.GUARD, ActionStrip.hit(5f, size / 2, g))
        // was die Leiste beim Aufsetzen nicht trifft, liegt auf den Zählflächen der Hälften
        val aboveGuard = g.top - g.guard - 1f
        assertEquals(eu.advantly.core.model.Side.A, TapZones.classify(aboveGuard, size))
    }

    @Test
    fun segmentsSplitTheStripEvenly() {
        val third = g.width / 3
        assertEquals(0, ActionStrip.segment(g.left + 1f, g, 3))
        assertEquals(0, ActionStrip.segment(g.left + third - 1f, g, 3))
        assertEquals(1, ActionStrip.segment(size / 2, g, 3))
        assertEquals(2, ActionStrip.segment(g.left + 2 * third + 1f, g, 3))
        assertEquals("rechter Rand gehört zum letzten Segment", 2, ActionStrip.segment(g.right, g, 3))
        assertEquals(0, ActionStrip.segment(size / 2 - 1f, g, 2))
        assertEquals(1, ActionStrip.segment(size / 2 + 1f, g, 2))
        assertNull(ActionStrip.segment(5f, g, 3))
        assertNull(ActionStrip.segment(size / 2, g, 0))
    }
}
