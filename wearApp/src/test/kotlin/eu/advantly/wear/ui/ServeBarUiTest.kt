package eu.advantly.wear.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
import eu.advantly.wear.live.LiveUi
import eu.advantly.wear.live.ServeBarUi
import eu.advantly.wear.scoring.ServePanel
import eu.advantly.wear.scoring.ServePanelGeometry
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
 * Aufschlag-Auswahl nach dem Punkt (Plan 0.5.1 §4a): Spielstand links, rechts große Tasten übereinander („2. Auf-/schlag“,
 * „Doppel-/fehler“). Geprüft über die echten Layout-Grenzen: Tasten liegen genau auf ihrer Trefferfläche
 * ([ServePanel]) und im runden Display, Labels sind groß, vollständig und an der festen Stelle getrennt, der Spielstand
 * (Punkte, Satzstand, Aufschläger) steht frei links, ein Tap links zählt weiterhin den Punkt der Hälfte. Pixel Watch
 * 41/45 mm (192/228 dp), Galaxy Watch8 40 mm (206 dp, 340 dpi), kleine runde Uhr (180 dp), größte Systemschrift.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ServeBarUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun pixelWatch41mm() = checkPanel(serveBar(), "serve-panel-de-192dp")

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun pixelWatch41mmLargestSystemFont() {
        RuntimeEnvironment.setFontScale(1.24f)
        checkPanel(serveBar(selected = ServeOutcome.SECOND_SERVE_IN), "serve-panel-de-192dp-font124")
    }

    @Test
    @Config(qualifiers = "de-w180dp-h180dp-round-watch-hdpi")
    fun smallRoundWatch() = checkPanel(serveBar(), "serve-panel-de-180dp")

    @Test
    @Config(qualifiers = "de-w180dp-h180dp-round-watch-hdpi")
    fun smallRoundWatchLargeFontThreeSets() {
        RuntimeEnvironment.setFontScale(1.3f)
        val live = liveUi().let { it.copy(sets = it.sets + it.sets.first()) }
        checkPanel(serveBar(), "serve-panel-de-180dp-font130", live)
    }

    @Test
    @Config(qualifiers = "de-w228dp-h228dp-round-watch-xhdpi")
    fun pixelWatch45mm() = checkPanel(serveBar(selected = ServeOutcome.DOUBLE_FAULT), "serve-panel-de-228dp")

    @Test
    @Config(qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
    fun galaxyWatch8() = checkPanel(serveBar(), "serve-panel-de-galaxy8")

    @Test
    @Config(qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
    fun galaxyWatch8LargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        checkPanel(serveBar(selected = ServeOutcome.DOUBLE_FAULT), "serve-panel-de-galaxy8-font130")
    }

    @Test
    @Config(qualifiers = "en-w206dp-h206dp-round-watch-340dpi")
    fun englishGalaxyWatch8LargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        checkPanel(serveBar(), "serve-panel-en-galaxy8-font130")
    }

    @Test
    @Config(qualifiers = "en-w192dp-h192dp-round-watch-xhdpi")
    fun english() = checkPanel(serveBar(), "serve-panel-en-192dp")

    @Test
    @Config(qualifiers = "de-w240dp-h240dp-round-watch-xhdpi")
    fun galaxyWatch480px() = checkPanel(serveBar(), "serve-panel-de-galaxy-480px")

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun doubleFaultsOnly() = checkPanel(serveBar().copy(options = listOf(ServeOutcome.DOUBLE_FAULT)), "serve-panel-de-192dp-df-only")

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun secondServeOnlyWhenTheServerWonThePoint() = checkPanel(serveBar(withDoubleFault = false))

    /** Die Gegner schlagen auf: ihre Aufschläger-Kennung steht auch links (Namenszeile unter dem Satzstand). */
    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun opponentsServing() = checkPanel(serveBar(), "serve-panel-de-192dp-them-serving", liveUi(serving = Side.B))

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun doubles() = checkPanel(serveBar(), "serve-panel-de-192dp-doubles", liveUi(doubles = true))

    /** Ambient: keine Tasten, Spielstand in der Mitte. */
    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun ambientShowsNoButtons() {
        render(liveUi(serveBar = serveBar()), isAmbient = true)
        assertTrue(rule.onAllNodesWithText(panelLabel(ServeOutcome.SECOND_SERVE_IN)).fetchSemanticsNodes().isEmpty())
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        for (side in Side.entries) assertEquals("Punkte $side mittig", root.center.x, bounds(LiveTags.points(side)).center.x, 2f)
        rule.screenshot("serve-panel-de-192dp-ambient")
    }

    /** Nach Auswahl bzw. Ablauf gleitet der Spielstand zurück in die Mitte. */
    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun scoreGlidesBackToTheCenter() {
        var bar by mutableStateOf<ServeBarUi?>(serveBar())
        val recorder = RecordingActions()
        rule.mainClock.autoAdvance = false
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
        rule.mainClock.advanceTimeByFrame()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val left = bounds(LiveTags.points(Side.A)).center.x
        assertTrue("links", left < root.center.x - 20f)
        bar = null
        rule.waitForIdle() // Zustandswechsel übernehmen
        rule.mainClock.advanceTimeBy(100)
        val gliding = bounds(LiveTags.points(Side.A)).center.x
        assertTrue("gleitet ($left → $gliding)", gliding > left + 1f && gliding < root.center.x - 1f)
        rule.screenshot("serve-panel-de-192dp-returning")
        rule.mainClock.advanceTimeBy(500)
        assertEquals("wieder mittig", root.center.x, bounds(LiveTags.points(Side.A)).center.x, 2f)
        assertTrue(rule.onAllNodesWithText(panelLabel(ServeOutcome.SECOND_SERVE_IN)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun serveChipsOnTheSetEndPage() = checkChips("serve-chips-de-192dp")

    @Test
    @Config(qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
    fun serveChipsOnGalaxyWatch8LargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        checkChips("serve-chips-de-galaxy8-font130")
    }

    /** Satzende-Seite: je Wert eine Taste über die volle Breite, ≥ 56 dp hoch, Label einzeilig und groß. */
    private fun checkChips(screenshot: String) {
        val recorder = RecordingActions()
        val bar = serveBar()
        val decision = DecisionUi(setNumber = 1, score = SidePair(6, 4), winner = Side.A, options = listOf(NextSetKind.REGULAR))
        rule.setContent {
            WearTestFrame {
                LiveScreen(
                    liveUi(serveBar = bar, decision = decision),
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
            val chip = rule.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue("$label: ≥ 100 dp breit (${chip.width})", chip.width >= 100.dp)
            assertTrue("$label: ≥ 56 dp hoch (${chip.height})", chip.height >= 56.dp)
            val font = assertLabelFits(label, lines = 1)
            assertTrue("$label: große Schrift ($font dp)", font >= 13f)
        }
        rule.screenshot(screenshot)
        rule.onNodeWithText(label(ServeOutcome.DOUBLE_FAULT)).performTouchInput { click() }
        assertEquals(listOf("setServe(DOUBLE_FAULT)"), recorder.calls)
    }

    private fun checkPanel(
        bar: ServeBarUi,
        screenshot: String? = null,
        live: LiveUi = liveUi(),
    ) {
        val recorder = render(live.copy(serveBar = bar))
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val density = density()
        val geometry = ServePanel.geometry(root.width, root.height, density, bar.options.size)
        val buttons = bar.options.map { rule.onNodeWithText(panelLabel(it)).fetchSemanticsNode() }
        bar.options.forEachIndexed { index, outcome ->
            val label = panelLabel(outcome)
            val node = buttons[index]
            val box = node.boundsInRoot
            val expected = geometry.buttons[index]
            // Darstellung = Trefferfläche
            assertEquals("$label links", expected.left, box.left, 1f)
            assertEquals("$label rechts", expected.right, box.right, 1f)
            assertEquals("$label oben", expected.top, box.top, 1f)
            assertEquals("$label unten", expected.bottom, box.bottom, 1f)
            assertTrue("$label: ≥ 56 dp hoch", box.height / density >= 56f - 0.5f)
            assertEquals("TalkBack liest das ganze Wort", listOf(label(outcome)), node.config[SemanticsProperties.ContentDescription])
            val inset = ServePanel.CORNER_DP * density * 0.3f
            assertInsideCircle("$label Taste", Rect(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset), root)
            assertInsideCircle("$label Text", rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot, root)
            val font = assertLabelFits(label, lines = 2)
            assertTrue("$label: große Schrift ($font dp)", font >= 14f)
        }
        // Spielstand links: Punkte, Satzstand und Namenszeile mit Aufschläger-Kennung frei, im Kreis, links der Tasten
        val score =
            listOf(LiveTags.points(Side.A), LiveTags.points(Side.B), LiveTags.SETS, LiveTags.label(Side.A), LiveTags.label(Side.B))
                .associateWith(::bounds)
        for ((tag, r) in score) {
            assertTrue("$tag links der Tasten (${r.right} > ${geometry.scoreRight})", r.right <= geometry.scoreRight + 1f)
            assertInsideCircle(tag, r, root)
            buttons.forEach { assertFalse("$tag unter einer Taste", r.overlaps(it.boundsInRoot)) }
        }
        val entries = score.entries.toList()
        for (i in entries.indices) {
            for (j in i + 1 until entries.size) {
                assertFalse("${entries[i].key} überlappt ${entries[j].key}", entries[i].value.overlaps(entries[j].value))
            }
        }
        val marker = bounds(LiveTags.SERVE_MARKER)
        assertTrue("Aufschläger-Kennung sichtbar", marker.width > 0f && marker.right <= geometry.scoreRight + 1f)
        val sets = rule.onNodeWithTag(LiveTags.SETS, useUnmergedTree = true).textLayout()
        assertFalse("Satzstand abgeschnitten", sets.hasVisualOverflow)
        screenshot?.let { rule.screenshot(it) }
        tapEverything(bar, geometry, recorder)
    }

    /** Jede sichtbare Taste wählt genau ihren Wert und zählt nie; links zählt oben „Wir“, unten „Gegner“. */
    private fun tapEverything(
        bar: ServeBarUi,
        geometry: ServePanelGeometry,
        recorder: RecordingActions,
    ) {
        bar.options.forEach { outcome ->
            val box = rule.onNodeWithText(panelLabel(outcome)).fetchSemanticsNode().boundsInRoot
            rule.onRoot().performTouchInput { click(box.center) }
        }
        assertEquals(bar.options.map { "setServe($it)" }, recorder.calls.filter { it.startsWith("setServe") })
        assertFalse("kein Punkt durch einen Tap auf die Tasten", recorder.calls.any { it.startsWith("point") })
        // knapp links der Tasten (Schutzstreifen): nichts
        val first = geometry.buttons.first()
        rule.onRoot().performTouchInput { click(Offset((geometry.scoreRight + first.left) / 2, first.center.y)) }
        assertEquals(bar.options.size, recorder.calls.size)
        for (side in Side.entries) {
            val digits = bounds(LiveTags.points(side))
            rule.onRoot().performTouchInput { click(digits.center) }
        }
        assertEquals(listOf("point(A)", "point(B)"), recorder.calls.filter { it.startsWith("point") })
    }

    private fun render(
        live: LiveUi,
        isAmbient: Boolean = false,
    ): RecordingActions {
        val recorder = RecordingActions()
        rule.setContent {
            WearTestFrame {
                LiveScreen(live, activeWorkout(), heartRate = 150, isAmbient = isAmbient, toast = null, actions = recorder.actions)
            }
        }
        rule.waitForIdle()
        return recorder
    }

    /** Vollständig, genau [lines] Zeilen, nicht abgeschnitten; liefert die tatsächliche Schriftgröße in dp. */
    private fun assertLabelFits(
        label: String,
        lines: Int,
    ): Float {
        val layout = rule.onNodeWithText(label, useUnmergedTree = true).textLayout()
        // tatsächliche Schriftgröße aus der Zeilenhöhe (lineHeight = 1.1 em); autoSize meldet im Style die Ausgangsgröße
        val fontDp = (layout.getLineBottom(0) - layout.getLineTop(0)) / 1.1f / density()
        val shown = (0 until layout.lineCount).map { label.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim() }
        println("Label '$label': $shown, ${"%.1f".format(fontDp)} dp, ${layout.size} px")
        assertFalse("$label abgeschnitten", layout.hasVisualOverflow)
        assertEquals("$label: Zeilen $shown", lines, layout.lineCount)
        assertEquals("$label vollständig", label.length, layout.getLineEnd(layout.lineCount - 1))
        assertEquals(
            "$label: Umbruch nur an der festen Stelle",
            label.split('\n', ' ').filter { it.isNotEmpty() },
            shown.flatMap { it.split(' ') },
        )
        assertTrue("$label: lesbar ($fontDp dp)", fontDp >= ServeLabel.MIN_SP - 0.25f)
        return fontDp
    }

    private fun assertInsideCircle(
        what: String,
        r: Rect,
        root: Rect,
    ) {
        val radius = root.width / 2
        listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).forEach { corner ->
            val distance = hypot(corner.x - root.center.x, corner.y - root.center.y)
            assertTrue("$what: Ecke $corner außerhalb des Kreises ($distance > $radius)", distance <= radius + 1f)
        }
    }

    private fun bounds(tag: String): Rect {
        val density = density()
        val r = rule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        return Rect(r.left.value * density, r.top.value * density, r.right.value * density, r.bottom.value * density)
    }

    private fun density(): Float =
        RuntimeEnvironment
            .getApplication()
            .resources.displayMetrics.density

    private fun label(outcome: ServeOutcome): String =
        RuntimeEnvironment.getApplication().getString(
            when (outcome) {
                ServeOutcome.FIRST_SERVE_IN -> R.string.serve_first_in
                ServeOutcome.SECOND_SERVE_IN -> R.string.serve_second_in
                ServeOutcome.DOUBLE_FAULT -> R.string.serve_double_fault
            },
        )

    private fun panelLabel(outcome: ServeOutcome): String =
        when (outcome) {
            ServeOutcome.FIRST_SERVE_IN -> label(outcome)
            ServeOutcome.SECOND_SERVE_IN -> RuntimeEnvironment.getApplication().getString(R.string.serve_panel_second_in)
            ServeOutcome.DOUBLE_FAULT -> RuntimeEnvironment.getApplication().getString(R.string.serve_panel_double_fault)
        }
}
