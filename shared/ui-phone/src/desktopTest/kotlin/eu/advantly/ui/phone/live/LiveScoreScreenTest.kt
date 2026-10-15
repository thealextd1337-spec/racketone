package eu.advantly.ui.phone.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import eu.advantly.presentation.live.DemoScenario
import eu.advantly.presentation.live.HapticCue
import eu.advantly.presentation.live.Team
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.platform.Haptics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compose-UI-Tests (Desktop/JVM) für den Live-Score-Screen: Tap → Score, Undo, Geste, Entprellung, Hinweis. */
@OptIn(ExperimentalTestApi::class)
class LiveScoreScreenTest {
    private val haptics = mutableListOf<HapticCue>()

    private fun ComposeUiTest.showScreen(controller: LiveScoreController = LiveScoreController()): LiveScoreController {
        setContent {
            AdvantlyTheme(darkTheme = true) {
                Box(Modifier.size(412.dp, 892.dp)) {
                    LiveScoreScreen(controller = controller)
                }
            }
        }
        // Nach dem ersten Frame überschreibt der Screen die Haptik mit der Plattform-Variante (Desktop: still) –
        // für die Tests eine aufzeichnende einsetzen.
        waitForIdle()
        runOnIdle { controller.haptics = Haptics { haptics += it } }
        return controller
    }

    private fun ComposeUiTest.assertScore(
        us: String,
        them: String,
    ) {
        onNodeWithTag("score_us", useUnmergedTree = true).assertTextEquals(us)
        onNodeWithTag("score_them", useUnmergedTree = true).assertTextEquals(them)
    }

    @Test
    fun tapOnPanelsScoresPoints() =
        runComposeUiTest {
            showScreen()
            assertScore("0", "0")
            onNodeWithTag("panel_us").performClick()
            assertScore("15", "0")
            onNodeWithTag("panel_them").performClick()
            onNodeWithTag("panel_them").performClick()
            assertScore("15", "30")
        }

    @Test
    fun undoButtonRevertsLastPointAndSnackbarOffersRedo() =
        runComposeUiTest {
            showScreen()
            onNodeWithTag("panel_us").performClick()
            onNodeWithTag("panel_them").performClick()
            assertScore("15", "15")

            onNodeWithTag("undo_button").performClick()
            assertScore("15", "0")
            onNodeWithTag("undo_snackbar").assertExists()

            onNodeWithTag("redo_button").performClick()
            assertScore("15", "15")
            onNodeWithTag("undo_snackbar").assertDoesNotExist()
        }

    @Test
    fun swipeDownOnPanelUndoes() =
        runComposeUiTest {
            showScreen()
            onNodeWithTag("panel_us").performClick()
            onNodeWithTag("panel_us").performClick()
            assertScore("30", "0")
            onNodeWithTag("panel_them").performTouchInput { swipeDown(startY = top + 10f, endY = bottom - 10f) }
            assertScore("15", "0")
            onNodeWithTag("undo_snackbar").assertExists()
            assertTrue(HapticCue.Undo in haptics)
        }

    @Test
    fun rapidDoubleTapCountsBothTaps() =
        runComposeUiTest {
            val controller = showScreen()
            onNodeWithTag("panel_us").performTouchInput {
                click()
                advanceEventTime(60)
                click()
            }
            assertScore("30", "0")
            assertEquals(2, controller.rawTaps)
            assertEquals(2, controller.countedPoints)
            assertEquals(listOf(HapticCue.PointUs, HapticCue.PointUs), haptics)
        }

    @Test
    fun debounceSwallowsSecondTapWithin600Millis() =
        runComposeUiTest {
            val controller = showScreen()
            runOnIdle { controller.debounceEnabled = true }
            onNodeWithTag("panel_them").performTouchInput {
                click()
                advanceEventTime(60)
                click()
            }
            assertScore("0", "15")
            assertEquals(1, controller.debouncedTaps)
            assertEquals(listOf(HapticCue.PointThem, HapticCue.Rejected), haptics)
        }

    @Test
    fun demoScenarioShowsHintPill() =
        runComposeUiTest {
            val controller = showScreen()
            onNodeWithTag("hint_pill").assertDoesNotExist()
            runOnIdle { controller.demo(DemoScenario.BreakPoint) }
            onNodeWithTag("hint_pill").assertExists()
            assertScore("40", "15")
            onNodeWithTag("panel_them_serve", useUnmergedTree = true).assertExists()
        }

    @Test
    fun outdoorToggleSwitchesContrastMode() =
        runComposeUiTest {
            val controller = showScreen()
            onNodeWithTag("outdoor_toggle").assertIsOff()
            onNodeWithTag("outdoor_toggle").performClick()
            onNodeWithTag("outdoor_toggle").assertIsOn()
            assertTrue(controller.outdoorContrast)
        }

    @Test
    fun latencyTrackerRecordsTapToFrame() =
        runComposeUiTest {
            val controller = showScreen()
            repeat(3) { onNodeWithTag("panel_us").performClick() }
            waitForIdle()
            assertEquals(3, controller.tracker.tapToFrame.count)
            val summary = controller.tracker.summary()
            assertTrue(summary.tapToFrameP50!! >= summary.tapToDrawP50!!)
        }

    @Test
    fun matchEndRejectsFurtherTaps() =
        runComposeUiTest {
            val controller = showScreen()
            runOnIdle { controller.demo(DemoScenario.MatchPoint) }
            onNodeWithTag("panel_us").performClick()
            runOnIdle { assertEquals(Team.Us, controller.state.winner) }
            onNodeWithTag("panel_them").performClick()
            runOnIdle { assertEquals(Team.Us, controller.state.winner) }
            assertEquals(HapticCue.Rejected, haptics.last())
        }
}
