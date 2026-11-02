package eu.advantly.wear.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.ResultReason
import eu.advantly.health.HealthSummary
import eu.advantly.wear.live.FinishedUi
import eu.advantly.wear.live.LiveUi
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Matchende-Seite (0.5.1): kleine Bilanz der Uhr – Schritte und höchster Puls, Zahlen nach Sprache, fehlende Werte entfallen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MatchSummaryUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun germanStepsAndMaxHeartRate() {
        render(HealthSummary(steps = 4_312, maxBpm = 176))
        rule.onNodeWithText("Schritte 4.312 · Max. Puls 176").assertIsDisplayed()
        rule.screenshot("match-summary-de-192dp")
    }

    @Test
    @Config(qualifiers = "en-w206dp-h206dp-round-watch-340dpi")
    fun englishOnGalaxyWatch8LargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        render(HealthSummary(steps = 12_345, maxBpm = 181))
        rule.onNodeWithText("Steps 12,345 · Max HR 181").assertIsDisplayed()
        rule.screenshot("match-summary-en-galaxy8-font130")
    }

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun onlyTheAvailableValue() {
        render(HealthSummary(steps = 980, maxBpm = null))
        rule.onNodeWithText("Schritte 980").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Max. Puls", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun nothingWithoutSummary() {
        render(null)
        assertTrue(rule.onAllNodesWithText("Schritte", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Max. Puls", substring = true).fetchSemanticsNodes().isEmpty())
    }

    private fun render(health: HealthSummary?) {
        val live: LiveUi =
            liveUi().let {
                it.copy(
                    status = eu.advantly.core.scoring.MatchStatus.FINISHED,
                    finished = FinishedUi(ResultReason.COMPLETED, Side.A, it.sets, canFollowUp = false, health = health),
                )
            }
        rule.setContent {
            WearTestFrame {
                LiveScreen(live, activeWorkout(), heartRate = null, isAmbient = false, toast = null, actions = RecordingActions().actions)
            }
        }
        rule.waitForIdle()
    }
}
