package eu.advantly.wear.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import eu.advantly.core.model.Side
import eu.advantly.wear.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Steuerseite verlassen (0.3.0): Wischen nach links öffnet sie, der Gegen-Wisch nach rechts und System-Zurück führen
 * zurück zur Zähl-Ansicht – das Match bleibt offen (kein Schließen/Beenden), und kein Wisch zählt einen Punkt.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
class ControlsSwipeUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val recorder = RecordingActions()
    private val controlsTitle get() = RuntimeEnvironment.getApplication().getString(R.string.controls_title)

    private fun show() =
        rule.setContent {
            WearTestFrame {
                LiveScreen(liveUi(), activeWorkout(), heartRate = null, isAmbient = false, toast = null, actions = recorder.actions)
            }
        }

    private fun openControls() {
        rule.onRoot().performTouchInput { swipeLeft() }
        rule.onNodeWithText(controlsTitle).assertExists()
        rule.onNodeWithTag(LiveTags.points(Side.A)).assertDoesNotExist()
    }

    private fun assertBackOnScore() {
        rule.onNodeWithTag(LiveTags.points(Side.A)).assertExists()
        rule.onNodeWithTag(LiveTags.points(Side.B)).assertExists()
        rule.onNodeWithText(controlsTitle).assertDoesNotExist()
        // weder Punkt noch Match verlassen: der Wisch löst keine Aktion aus
        assertEquals(emptyList<String>(), recorder.calls)
    }

    @Test
    fun swipingRightLeavesTheControlsBackToScoring() {
        show()
        openControls()
        rule.onRoot().performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertBackOnScore()
        // erneut öffnen und schließen geht wiederholt
        openControls()
        rule.onRoot().performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertBackOnScore()
    }

    @Test
    fun draggingRevealsTheScoreBehindTheControls() {
        show()
        openControls()
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.1f, centerY))
            moveBy(Offset(width * 0.3f, 0f))
        }
        // während des Ziehens schaut die Zähl-Ansicht hervor statt einer schwarzen Fläche
        rule.onNodeWithTag(LiveTags.points(Side.A)).assertExists()
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertEquals("die Vorschau zählt nicht", emptyList<String>(), recorder.calls)
    }

    @Test
    fun systemBackLeavesTheControlsBackToScoring() {
        show()
        openControls()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertBackOnScore()
    }
}
