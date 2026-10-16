package eu.advantly.wear.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performRotaryScrollInput
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.match.LocalDay
import eu.advantly.wear.live.DecisionUi
import eu.advantly.wear.live.LiveOverlay
import eu.advantly.wear.live.ServerChoiceUi
import eu.advantly.wear.live.SetupStep
import eu.advantly.wear.live.WatchScreen
import eu.advantly.wear.match.SetupCatalog
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drehbare Lünette/Krone (Galaxy Watch, Pixel Watch): Listen und Dialoge (Satzende, Aufschläger-Auswahl, Setup)
 * scrollen per Rotary – die `ScalingLazyColumn`s nutzen das Standard-Rotary-Verhalten mit Fokus. Auf der Score-Seite
 * löst Rotary nie einen Punkt (oder sonst eine Aktion) aus.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-w198dp-h198dp-round-watch-xhdpi")
class RotaryUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun rotaryOnTheScorePageNeverCountsAPoint() {
        val recorder = RecordingActions()
        rule.setContent {
            WearTestFrame {
                LiveScreen(
                    liveUi(),
                    activeWorkout(),
                    heartRate = null,
                    isAmbient = false,
                    toast = null,
                    actions = recorder.actions,
                )
            }
        }
        rule.onRoot().performRotaryScrollInput { rotateToScrollVertically(400f) }
        rule.onRoot().performRotaryScrollInput { rotateToScrollVertically(-400f) }
        rule.waitForIdle()
        assertTrue(recorder.calls.toString(), recorder.calls.isEmpty())
    }

    @Test
    fun setEndDialogScrollsWithTheBezel() {
        val decision =
            DecisionUi(
                setNumber = 1,
                score = SidePair(6, 4),
                winner = Side.A,
                options = listOf(NextSetKind.REGULAR, NextSetKind.TIEBREAK_7, NextSetKind.MATCH_TIEBREAK_10),
            )
        val recorder = RecordingActions()
        rule.setContent {
            WearTestFrame {
                LiveScreen(
                    liveUi(serveBar = serveBar(), decision = decision),
                    activeWorkout(),
                    heartRate = null,
                    isAmbient = false,
                    toast = null,
                    actions = recorder.actions,
                )
            }
        }
        assertScrollsWithRotary()
        assertTrue("Rotary wählt nichts aus", recorder.calls.isEmpty())
    }

    @Test
    fun serverChoiceScrollsWithTheBezel() {
        val live = liveUi(doubles = true)
        val choice = ServerChoiceUi(game = 2, side = Side.B, players = live.teams.b, chosen = null, prompt = true)
        rule.setContent {
            WearTestFrame {
                LiveScreen(
                    live.copy(overlay = LiveOverlay.SERVER_CHOICE, serverChoice = choice),
                    activeWorkout(),
                    heartRate = null,
                    isAmbient = false,
                    toast = null,
                    actions = RecordingActions().actions,
                )
            }
        }
        assertScrollsWithRotary()
    }

    @Test
    fun setupScrollsWithTheBezel() {
        val setup =
            WatchScreen.Setup(
                step = SetupStep.FORMAT,
                sport = Sport.TENNIS,
                disciplines = SetupCatalog.disciplines(Sport.TENNIS),
                discipline = SetupCatalog.disciplines(Sport.TENNIS).first(),
                formats = SetupCatalog.formats(Sport.TENNIS, LocalDay(2026, 10, 16)),
                defaultFormat = null,
                format = null,
                workoutEnabled = true,
            )
        rule.setContent { WearTestFrame { SetupScreen(setup, recording<SetupActions>()) } }
        assertScrollsWithRotary()
    }

    /** Die Liste hat Rotary-Fokus: Drehen verschiebt den Inhalt. */
    private fun assertScrollsWithRotary() {
        rule.waitForIdle()
        val list = rule.onNode(hasScrollAction())
        val before = list.fetchSemanticsNode().children.map { it.boundsInRoot.top }
        list.performRotaryScrollInput { rotateToScrollVertically(150f) }
        rule.waitForIdle()
        val after = list.fetchSemanticsNode().children.map { it.boundsInRoot.top }
        assertNotEquals("Liste scrollt per Rotary", before, after)
    }
}
