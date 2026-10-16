package eu.advantly.wear.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.SidePair
import eu.advantly.wear.R
import eu.advantly.wear.live.DecisionUi
import eu.advantly.wear.live.OperationRules
import eu.advantly.wear.live.ServeBarUi
import eu.advantly.wear.scoring.ActionStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

/**
 * Aufschlag-Leiste nach dem Punkt (Feldtest 2026-10-16): ausgeschriebene Labels „1. Aufschlag · 2. Aufschlag ·
 * Doppelfehler“ statt „1. · 2. · DF“ – lesbar, nichts abgeschnitten (auch nicht am runden Rand), Segmente ≥ 40 dp breit
 * und der Tap trifft das sichtbare Segment. Pixel Watch 3 41 mm (192 dp), 45 mm (228 dp), kleine runde Uhr (180 dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ServeBarUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun germanLabelsFitOnPixelWatch41mm() {
        checkStrip(serveBar())
        rule.screenshot("serve-bar-de-192dp")
    }

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun germanLabelsFitWithLargestSystemFont() {
        RuntimeEnvironment.setFontScale(1.24f)
        checkStrip(serveBar(selected = ServeOutcome.SECOND_SERVE_IN))
        rule.screenshot("serve-bar-de-192dp-font124")
    }

    @Test
    @Config(qualifiers = "de-w180dp-h180dp-round-watch-hdpi")
    fun germanLabelsFitOnSmallRoundWatch() {
        checkStrip(serveBar())
        rule.screenshot("serve-bar-de-180dp")
    }

    @Test
    @Config(qualifiers = "de-w228dp-h228dp-round-watch-xhdpi")
    fun germanLabelsFitOnPixelWatch45mm() {
        checkStrip(serveBar(selected = ServeOutcome.FIRST_SERVE_IN))
        rule.screenshot("serve-bar-de-228dp")
    }

    // Galaxy Watch8 40 mm (SM-L320, Zielgerät): 438 px bei 340 dpi ≈ 206 dp – Pflichtgröße, Schrift 1,0 und 1,3

    @Test
    @Config(qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
    fun germanLabelsFitOnGalaxyWatch8() {
        checkStrip(serveBar())
        rule.screenshot("serve-bar-de-galaxy8")
    }

    @Test
    @Config(qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
    fun germanLabelsFitOnGalaxyWatch8LargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        checkStrip(serveBar(selected = ServeOutcome.DOUBLE_FAULT))
    }

    @Test
    @Config(qualifiers = "en-w206dp-h206dp-round-watch-340dpi")
    fun englishLabelsFitOnGalaxyWatch8LargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        checkStrip(serveBar())
    }

    @Test
    @Config(qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
    fun serveChipsOnGalaxyWatch8LargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        serveChipsOnTheSetEndPageUseTheLongLabels()
    }

    // Samsung Galaxy Watch (rund, xhdpi): 396 px (40 mm), 432 px (Watch 6/7 40 mm), 480 px (44 mm/Ultra)

    @Test
    @Config(qualifiers = "de-w198dp-h198dp-round-watch-xhdpi")
    fun germanLabelsFitOnGalaxyWatch396px() = checkStrip(serveBar())

    @Test
    @Config(qualifiers = "de-w216dp-h216dp-round-watch-xhdpi")
    fun germanLabelsFitOnGalaxyWatch432px() = checkStrip(serveBar(selected = ServeOutcome.DOUBLE_FAULT))

    @Test
    @Config(qualifiers = "de-w240dp-h240dp-round-watch-xhdpi")
    fun germanLabelsFitOnGalaxyWatch480px() {
        checkStrip(serveBar())
        rule.screenshot("serve-bar-de-galaxy-480px")
    }

    @Test
    @Config(qualifiers = "en-w192dp-h192dp-round-watch-xhdpi")
    fun englishLabelsFit() {
        checkStrip(serveBar())
        rule.screenshot("serve-bar-en-192dp")
    }

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun withoutDoubleFaultBothServesShareTheStrip() = checkStrip(serveBar(withDoubleFault = false))

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun serveChipsOnTheSetEndPageUseTheLongLabels() {
        val recorder = RecordingActions()
        val bar = serveBar()
        // wie im Feld: Satzende-Seite (Liste) nach dem Satzball, Aufschlag-Leiste als Listeneintrag
        val decision = DecisionUi(setNumber = 1, score = SidePair(6, 4), winner = Side.A, options = listOf(NextSetKind.REGULAR))
        val live = liveUi(serveBar = bar, decision = decision)
        rule.setContent {
            WearTestFrame {
                LiveScreen(
                    live,
                    activeWorkout(),
                    heartRate = null,
                    isAmbient = false,
                    toast = null,
                    actions = recorder.actions,
                )
            }
        }
        bar.options.forEach { outcome ->
            val label = label(outcome)
            // Listeneintrag ggf. unterhalb des sichtbaren Bereichs: Größe ungeclippt prüfen
            val segment = rule.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue("$label: ≥ 40 dp breit (${segment.width})", segment.width >= 40.dp)
            assertTrue("$label: ≥ 40 dp hoch (${segment.height})", segment.height >= 40.dp)
            assertLabelFits(label)
        }
        rule.screenshot("serve-chips-de-192dp")
        rule.onNodeWithText(label(ServeOutcome.DOUBLE_FAULT)).performTouchInput { click() }
        assertEquals(listOf("setServe(DOUBLE_FAULT)"), recorder.calls)
    }

    /** Leiste im Live-Screen: Labels passen, Segmente groß genug, im Kreis, Tap = sichtbares Segment. */
    private fun checkStrip(bar: ServeBarUi) {
        val recorder = RecordingActions()
        rule.setContent {
            WearTestFrame {
                LiveScreen(
                    liveUi(serveBar = bar),
                    activeWorkout(),
                    heartRate = 150,
                    isAmbient = false,
                    toast = null,
                    actions = recorder.actions,
                )
            }
        }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val density =
            RuntimeEnvironment
                .getApplication()
                .resources.displayMetrics.density
        val geometry = ActionStrip.geometry(root.width, root.height, density)
        val ranges = ActionStrip.segmentRanges(geometry, OperationRules.serveWeights(bar))
        bar.options.forEachIndexed { index, outcome ->
            val label = label(outcome)
            val segment = rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            // Darstellung = Trefferbereich
            assertEquals("$label links", ranges[index].start, segment.left, 1f)
            assertEquals("$label rechts", ranges[index].endInclusive, segment.right, 1f)
            assertTrue("$label: ≥ 40 dp breit (${segment.width / density})", segment.width / density >= 40f)
            assertTrue("$label: Trefferzone ≥ 40 dp hoch", geometry.height / density >= 40f)
            assertEquals(index, ActionStrip.segment(segment.center.x, geometry, OperationRules.serveWeights(bar)))
            // abgerundete Segmentecken und der Text liegen im runden Display
            val inset = SEGMENT_CORNER_DP * density * 0.3f
            assertInsideCircle(
                "$label Segment",
                Rect(
                    segment.left + inset,
                    segment.top + inset,
                    segment.right - inset,
                    segment.bottom - inset,
                ),
                root,
            )
            assertInsideCircle("$label Text", rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot, root)
            assertLabelFits(label)
        }
        // Tap auf jedes sichtbare Segment wählt genau dessen Wert
        bar.options.forEach { outcome ->
            val segment = rule.onNodeWithText(label(outcome)).fetchSemanticsNode().boundsInRoot
            rule.onRoot().performTouchInput { click(Offset(segment.center.x, segment.center.y)) }
        }
        assertEquals(bar.options.map { "setServe($it)" }, recorder.calls.filter { it.startsWith("setServe") })
        assertFalse("kein Punkt durch einen Tap auf die Leiste", recorder.calls.any { it.startsWith("point") })
    }

    /** Ausgeschrieben, höchstens zwei Zeilen, nichts abgeschnitten, lesbare Größe. */
    private fun assertLabelFits(label: String) {
        val layout = rule.onNodeWithText(label, useUnmergedTree = true).textLayout()
        val metrics = RuntimeEnvironment.getApplication().resources.displayMetrics
        // tatsächliche Schriftgröße aus der Zeilenhöhe (lineHeight = 1.1 em); autoSize meldet im Style die Ausgangsgröße
        val fontDp = (layout.getLineBottom(0) - layout.getLineTop(0)) / 1.1f / metrics.density
        val fontSp = fontDp / fontScale()
        val lines = (0 until layout.lineCount).map { label.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim() }
        println("Label '$label': $lines, ${"%.1f".format(fontSp)} sp (${"%.1f".format(fontDp)} dp), ${layout.size} px")
        assertFalse("$label abgeschnitten", layout.hasVisualOverflow)
        assertTrue("$label: höchstens 2 Zeilen", layout.lineCount <= 2)
        assertEquals("$label vollständig", label.length, layout.getLineEnd(layout.lineCount - 1))
        assertEquals("$label: Umbruch nur an Leerzeichen", label.split(' '), lines.flatMap { it.split(' ') })
        assertTrue("$label: lesbar ($fontDp dp)", fontDp >= ServeLabel.MIN_SP - 0.25f)
    }

    private fun assertInsideCircle(
        what: String,
        r: Rect,
        root: Rect,
    ) {
        val radius = root.width / 2
        listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).forEach { corner ->
            val distance = hypot(corner.x - root.center.x, corner.y - root.center.y)
            assertTrue("$what: Ecke $corner außerhalb des Kreises ($distance > $radius)", distance <= radius + 0.5f)
        }
    }

    private fun fontScale(): Float =
        RuntimeEnvironment
            .getApplication()
            .resources.configuration.fontScale

    private fun label(outcome: ServeOutcome): String =
        RuntimeEnvironment.getApplication().getString(
            when (outcome) {
                ServeOutcome.FIRST_SERVE_IN -> R.string.serve_first_in
                ServeOutcome.SECOND_SERVE_IN -> R.string.serve_second_in
                ServeOutcome.DOUBLE_FAULT -> R.string.serve_double_fault
            },
        )

    private companion object {
        const val SEGMENT_CORNER_DP = 12f
    }
}
