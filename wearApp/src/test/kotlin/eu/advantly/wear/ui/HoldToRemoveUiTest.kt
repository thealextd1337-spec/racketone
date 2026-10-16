package eu.advantly.wear.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.SidePair
import eu.advantly.wear.live.LiveUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Halten zum Abziehen (Feldtest 2026-10-16): Während des Haltens läuft eine Füllung von rechts nach links über die
 * Hälfte; sie ist genau bei der Haltezeit (1 s) voll und löst dann – Finger liegt noch – aus. Früher loslassen setzt
 * sie zurück, ohne auszulösen. Galaxy Watch8 40 mm (206 dp) als Pflichtgröße.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
class HoldToRemoveUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(live: LiveUi = liveUi().copy(canRemovePoint = SidePair(true, true))): RecordingActions {
        val recorder = RecordingActions()
        rule.mainClock.autoAdvance = false
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
        rule.mainClock.advanceTimeByFrame()
        return recorder
    }

    private fun digitsCenter(side: Side): Offset {
        val r = rule.onNodeWithTag(LiveTags.points(side), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return r.center
    }

    /** Fortschritt der Hälfte (Semantik der Zählfläche). */
    private fun progress(side: Side): Float {
        val nodes =
            rule.onRoot().fetchSemanticsNode().let { root ->
                generateSequence(listOf(root)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten()
            }
        val center = digitsCenter(side)
        return nodes
            .filter { it.config.contains(HoldProgressKey) && it.boundsInRoot.contains(center) }
            .map { it.config[HoldProgressKey] }
            .single()
    }

    @Test
    fun holdingFillsAndTriggersExactlyAtTheThreshold() {
        val recorder = render()
        val at = digitsCenter(Side.B)
        rule.onRoot().performTouchInput { down(at) }
        rule.mainClock.advanceTimeBy(500)
        assertEquals("halb voll nach 0,5 s", 0.5f, progress(Side.B), 0.06f)
        assertEquals("andere Hälfte unberührt", 0f, progress(Side.A), 0.001f)
        assertTrue("noch nicht ausgelöst", recorder.calls.none { it.startsWith("removePoint") })
        rule.mainClock.advanceTimeBy(560)
        assertEquals(
            "ausgelöst, solange der Finger liegt",
            listOf("removePoint(B)"),
            recorder.calls.filter { it.startsWith("removePoint") },
        )
        assertEquals("voll beim Auslösen", 1f, progress(Side.B), 0.001f)
        rule.screenshot("hold-de-galaxy8-full")
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle() // Zustandswechsel übernehmen (kein Frame steht an, solange die Füllung voll steht)
        rule.mainClock.advanceTimeBy(400)
        assertEquals("nach dem Loslassen zurückgesetzt", 0f, progress(Side.B), 0.001f)
        assertEquals("einmal ausgelöst, kein Punkt", listOf("removePoint(B)"), recorder.calls)
    }

    @Test
    fun releasingEarlyResetsWithoutTriggering() {
        val recorder = render()
        val at = digitsCenter(Side.A)
        rule.onRoot().performTouchInput { down(at) }
        rule.mainClock.advanceTimeBy(600)
        assertEquals(0.6f, progress(Side.A), 0.06f)
        rule.screenshot("hold-de-galaxy8-partial")
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(400)
        assertEquals("zurückgesetzt", 0f, progress(Side.A), 0.001f)
        assertTrue("kein Abzug: ${recorder.calls}", recorder.calls.none { it.startsWith("removePoint") })
        assertTrue("auch kein Punkt (länger als ein Tap)", recorder.calls.none { it.startsWith("point") })
    }

    @Test
    fun movingCancelsTheHold() {
        val recorder = render()
        val at = digitsCenter(Side.B)
        rule.onRoot().performTouchInput { down(at) }
        rule.mainClock.advanceTimeBy(300)
        rule.onRoot().performTouchInput { moveBy(Offset(0f, 60f)) }
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals("abgebrochen", 0f, progress(Side.B), 0.001f)
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        assertTrue(recorder.calls.toString(), recorder.calls.none { it.startsWith("removePoint") })
    }

    @Test
    fun noProgressWithoutAPointToRemove() {
        val recorder = render(liveUi().copy(canRemovePoint = SidePair(false, false)))
        val at = digitsCenter(Side.B)
        rule.onRoot().performTouchInput { down(at) }
        rule.mainClock.advanceTimeBy(1_200)
        assertEquals(0f, progress(Side.B), 0.001f)
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        // wie bisher: Halten ohne Abzug meldet der Controller („nichts abzuziehen“)
        assertEquals(listOf("removePoint(B)"), recorder.calls.filter { it.startsWith("removePoint") })
    }
}
