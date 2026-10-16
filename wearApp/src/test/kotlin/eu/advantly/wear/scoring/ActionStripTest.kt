package eu.advantly.wear.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class ActionStripTest {
    /** Pixel Watch 3 (41 mm): 384 px bei 2 px/dp = 192 dp. */
    private val density = 2f
    private val size = 384f
    private val g = ActionStrip.geometry(size, size, density)

    @Test
    fun touchZoneIsAtLeast48dpOnSmallRoundDisplays() {
        assertEquals(48f * density, g.height, 0.01f)
        assertTrue("sichtbar etwas flacher als die Trefferzone", g.visualHeight < g.height && g.visualHeight >= 32f * density)
        // großes Display: Anteil der Höhe statt der Mindesthöhe, mittig
        val big = ActionStrip.geometry(1_000f, 1_000f, 2f)
        assertEquals(160f, big.height, 0.01f)
        assertEquals("mittig", 500f, (big.top + big.bottom) / 2, 0.01f)
    }

    /** Leiste und beide Zählflächen je ≥ 48 dp: Pixel Watch 3 (192/208 dp), Galaxy Watch (≈ 225 dp), große Displays. */
    @Test
    fun stripAndBothScoringAreasKeep48dp() {
        for (dp in listOf(192f, 198f, 208f, 225f, 260f, 450f)) {
            for (d in listOf(1.5f, 2f, 2.625f)) {
                val px = dp * d
                val geo = ActionStrip.geometry(px, px, d)
                val min = 48f * d - 0.01f
                assertTrue("$dp dp: Leiste ${geo.height / d}", geo.height >= min)
                assertTrue("$dp dp: Wir ${geo.usArea / d}", geo.usArea >= min)
                assertTrue("$dp dp: Gegner ${geo.themArea / d}", geo.themArea >= min)
                assertTrue("Schutz bleibt", geo.guard >= ActionStrip.GUARD_DP * d - 0.01f)
                // Zählflächen passen zu TapZones: oberhalb des Schutzes Wir, unterhalb Gegner
                assertEquals(eu.advantly.core.model.Side.A, TapZones.classify(geo.top - geo.guard - 0.5f, px))
                assertEquals(eu.advantly.core.model.Side.B, TapZones.classify(geo.bottom + geo.guard + 0.5f, px))
                assertEquals(px * TapZones.TOP_DEAD_ZONE + geo.usArea, geo.top - geo.guard, 0.01f)
                assertEquals(px, geo.bottom + geo.guard + geo.themArea, 0.01f)
            }
        }
        // 192 dp: Leiste rückt nach unten, damit „Wir“ trotz oberer Totzone 48 dp behält
        assertEquals(48f * density, g.usArea, 0.01f)
        assertTrue(g.top > (size - g.height) / 2)
    }

    @Test
    fun tinyDisplaysKeepBothScoringAreasAndShrinkTheStrip() {
        val geo = ActionStrip.geometry(160f, 160f, 1f)
        assertTrue(geo.usArea >= 47.99f && geo.themArea >= 47.99f)
        assertTrue(geo.height < 48f && geo.height > 40f)
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
        assertEquals(0, ActionStrip.segment(g.left + third - 3f, g, 3))
        assertEquals(1, ActionStrip.segment(size / 2, g, 3))
        assertEquals(2, ActionStrip.segment(g.left + 2 * third + 1f, g, 3))
        assertEquals("rechter Rand gehört zum letzten Segment", 2, ActionStrip.segment(g.right, g, 3))
        assertEquals(0, ActionStrip.segment(size / 2 - 1f, g, 2))
        assertEquals(1, ActionStrip.segment(size / 2 + 1f, g, 2))
        assertNull(ActionStrip.segment(5f, g, 3))
        assertNull(ActionStrip.segment(size / 2, g, 0))
    }

    /** Gewichtete Segmente (Aufschlag-Leiste: „Doppelfehler“ breiter): Darstellung und Treffer aus denselben Bereichen. */
    @Test
    fun weightedSegmentsShareRangesWithTheHitTest() {
        val weights = listOf(1f, 1f, 1.3f)
        val ranges = ActionStrip.segmentRanges(g, weights)
        assertEquals(3, ranges.size)
        assertEquals(g.left, ranges.first().start, 0.01f)
        assertEquals(g.right, ranges.last().endInclusive, 0.01f)
        assertEquals("Abstand", ActionStrip.SEGMENT_GAP_DP * density, ranges[1].start - ranges[0].endInclusive, 0.01f)
        val width = ranges.map { it.endInclusive - it.start }
        assertEquals(width[0], width[1], 0.01f)
        assertEquals(1.3f, width[2] / width[0], 0.001f)
        ranges.forEachIndexed { i, r ->
            assertEquals(i, ActionStrip.segment((r.start + r.endInclusive) / 2, g, weights))
            assertEquals(i, ActionStrip.segment(r.start, g, weights))
            assertEquals(i, ActionStrip.segment(r.endInclusive, g, weights))
        }
        // Lücke: linke Hälfte zum linken, rechte Hälfte zum rechten Segment
        val gapMid = (ranges[0].endInclusive + ranges[1].start) / 2
        assertEquals(0, ActionStrip.segment(gapMid - 1f, g, weights))
        assertEquals(1, ActionStrip.segment(gapMid + 1f, g, weights))
        assertNull(ActionStrip.segment(g.right + 1f, g, weights))
        assertNull(ActionStrip.segment(size / 2, g, emptyList()))
    }

    /** Die breite Leiste (kleiner Seitenrand) bleibt mit ihren Ecken im runden Display – auf allen Größen. */
    @Test
    fun stripCornersStayInsideRoundDisplays() {
        for (dp in listOf(160f, 180f, 192f, 208f, 228f, 260f, 450f)) {
            val geo = ActionStrip.geometry(dp, dp, 1f)
            val r = dp / 2
            for (y in listOf(geo.visualTop, geo.visualTop + geo.visualHeight)) {
                for (x in listOf(geo.left, geo.right)) {
                    // Segmentecken sind gerundet: geprüft wird der Punkt 3 dp diagonal nach innen
                    val dx = abs(x - r) - 3f
                    val dy = abs(y - r) - 3f
                    assertTrue("$dp dp: Ecke ($x, $y) außerhalb", sqrt(dx * dx + dy * dy) <= r)
                }
            }
        }
    }
}
