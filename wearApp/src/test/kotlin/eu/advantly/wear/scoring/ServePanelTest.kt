package eu.advantly.wear.scoring

import androidx.compose.ui.geometry.Offset
import eu.advantly.core.model.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Aufschlag-Auswahl (Plan 0.5.1 §4a): Tasten rechts übereinander im runden Display, links zählen „Wir“ (oben) und
 * „Gegner“ (unten) weiter, dazwischen ein Schutzstreifen. Kleine runde Uhr (180 dp), Pixel Watch 41/45 mm (192/228 dp),
 * Galaxy Watch8 40 mm (206 dp bei 340 dpi), Galaxy Watch 44 mm (240 dp).
 */
class ServePanelTest {
    private val displays = listOf(180f to 1.5f, 192f to 2f, 206f to 340f / 160f, 228f to 2f, 240f to 2f)

    @Test
    fun buttonsAreLargeAndInsideTheRoundDisplay() {
        for ((dp, d) in displays) {
            val px = dp * d
            for (count in 1..2) {
                val g = ServePanel.geometry(px, px, d, count)
                assertEquals(count, g.buttons.size)
                val corner = ServePanel.CORNER_DP * d * (1 - 1 / sqrt(2f))
                g.buttons.forEach { b ->
                    assertTrue("$dp dp: ≥ 56 dp hoch (${b.height / d})", b.height >= 56f * d - 0.01f)
                    assertTrue("$dp dp: etwa ein Viertel der Höhe", b.height >= px * 0.25f - 0.01f && b.height <= px * 0.32f)
                    assertEquals("$dp dp: 45 % breit", px * 0.45f, b.width, 0.01f)
                    // äußerster Punkt jeder abgerundeten Ecke liegt im Kreis
                    for (p in listOf(
                        Offset(b.left + corner, b.top + corner),
                        Offset(b.right - corner, b.top + corner),
                        Offset(b.left + corner, b.bottom - corner),
                        Offset(b.right - corner, b.bottom - corner),
                    )) {
                        assertTrue("$dp dp: Ecke $p außerhalb", hypot(p.x - px / 2, p.y - px / 2) <= px / 2 + 0.01f)
                    }
                    assertTrue("$dp dp: Tasten rechts", b.center.x > px / 2)
                }
                assertEquals("$dp dp: mittig", px / 2, (g.buttons.first().top + g.buttons.last().bottom) / 2, 0.01f)
            }
            // waagerechte Lage unabhängig von der Zahl der Tasten (Spielstand gleitet immer an dieselbe Stelle)
            assertEquals(ServePanel.geometry(px, px, d, 1).scoreRight, ServePanel.geometry(px, px, d, 2).scoreRight, 0.01f)
        }
    }

    @Test
    fun scoringZonesOnTheLeftKeep48dp() {
        for ((dp, d) in displays) {
            val px = dp * d
            val g = ServePanel.geometry(px, px, d, 2)
            val min = 48f * d - 0.01f
            val usTop = px * TapZones.TOP_DEAD_ZONE
            val usBottom = px * (0.5f - TapZones.MIDDLE_BAND / 2)
            val themTop = px * (0.5f + TapZones.MIDDLE_BAND / 2)
            assertTrue("$dp dp: Wir hoch genug", usBottom - usTop >= min)
            assertTrue("$dp dp: Gegner hoch genug", px - themTop >= min)
            // je Zählfläche ein Quadrat von 48 dp am Mittelband: Breite vom runden Rand bis zum Schutzstreifen
            for (y in listOf(usBottom - 48f * d, themTop + 48f * d)) {
                val edge = px / 2 - sqrt(px * px / 4 - (y - px / 2) * (y - px / 2))
                assertTrue("$dp dp: Zählfläche bei $y breit genug (${(g.scoreRight - edge) / d} dp)", g.scoreRight - edge >= min)
            }
            val x = g.scoreRight / 2
            assertEquals(StripHit.OUTSIDE, ServePanel.hit(x, (usTop + usBottom) / 2, g))
            assertEquals(Side.A, TapZones.classify((usTop + usBottom) / 2, px))
            assertEquals(StripHit.OUTSIDE, ServePanel.hit(x, (themTop + px) / 2, g))
            assertEquals(Side.B, TapZones.classify((themTop + px) / 2, px))
        }
    }

    @Test
    fun hitTestMatchesTheVisibleButtons() {
        val d = 2f
        val px = 192f * d
        val g = ServePanel.geometry(px, px, d, 2)
        val (upper, lower) = g.buttons
        assertEquals(0, ServePanel.button(upper.center.x, upper.center.y, g))
        assertEquals(1, ServePanel.button(lower.center.x, lower.center.y, g))
        assertEquals(0, ServePanel.button(upper.left, upper.top, g))
        assertEquals(1, ServePanel.button(lower.right, lower.bottom, g))
        // Lücke in der Mitte geteilt
        val gap = (upper.bottom + lower.top) / 2
        assertEquals(0, ServePanel.button(upper.center.x, gap - 1f, g))
        assertEquals(1, ServePanel.button(upper.center.x, gap + 1f, g))
        // knapp neben den Tasten (Schutz) bzw. im Streifen zwischen Zählflächen und Tasten: nichts
        assertEquals(StripHit.STRIP, ServePanel.hit(upper.center.x, upper.top - 1f, g))
        assertEquals(StripHit.GUARD, ServePanel.hit(upper.center.x, g.hitTop - 1f, g))
        assertEquals(StripHit.GUARD, ServePanel.hit(upper.center.x, g.hitBottom + 1f, g))
        assertEquals(StripHit.GUARD, ServePanel.hit((g.scoreRight + g.buttonsLeft) / 2, upper.center.y, g))
        assertNull(ServePanel.button(g.scoreRight - 1f, upper.center.y, g))
        assertEquals(StripHit.OUTSIDE, ServePanel.hit(g.scoreRight - 1f, upper.center.y, g))
        // eine Taste („Nur Doppelfehler“): mittig, ganze Trefferzone
        val single = ServePanel.geometry(px, px, d, 1)
        assertEquals(0, ServePanel.button(single.buttons[0].center.x, single.buttons[0].center.y, single))
        assertEquals(px / 2, single.buttons[0].center.y, 0.01f)
    }
}
