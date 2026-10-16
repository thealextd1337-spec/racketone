package eu.advantly.wear.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.wear.live.WatchScreen
import eu.advantly.wear.workout.WorkoutPermissions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** P2-J: Startliste der Uhr zeigt die Telemetrie-Sichtbarkeit und ändert sie reihum (Pflichtgröße 206 dp). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
class TelemetryVisibilityUiTest {
    @get:Rule val rule = createComposeRule()

    private fun render(
        visibility: TelemetryVisibility,
        calls: MutableList<String>,
    ) {
        val home = WatchScreen.Home(null, null, emptyList(), workoutEnabled = true, telemetryVisibility = visibility)
        rule.setContent { WearTestFrame { HomeScreen(home, WorkoutPermissions.ALL, powerSave = false, recording<HomeActions>(calls)) } }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(TELEMETRY_VISIBILITY_TAG))
        rule.onNodeWithTag(TELEMETRY_VISIBILITY_TAG).assertIsDisplayed()
    }

    @Test
    fun defaultShowsOnlyMeAndOneTapSwitchesOff() {
        RuntimeEnvironment.setFontScale(1.3f)
        val calls = mutableListOf<String>()
        render(TelemetryVisibility.PRIVATE, calls)
        rule.onNodeWithText("Puls & Schritte").assertIsDisplayed()
        rule.onNodeWithText("Nur für mich", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TELEMETRY_VISIBILITY_TAG).performClick()
        assertEquals(listOf("setTelemetryVisibility(OFF)"), calls)
    }

    @Test
    fun offExplainsThatNothingIsSentAndShareMentionsLaterSharing() {
        val calls = mutableListOf<String>()
        render(TelemetryVisibility.OFF, calls)
        rule.onNodeWithText("Gar nicht", substring = true).assertIsDisplayed()
        rule.onNodeWithText("es wird nichts gesendet", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TELEMETRY_VISIBILITY_TAG).performClick()
        assertEquals(listOf("setTelemetryVisibility(SHARE)"), calls)
        assertEquals(TelemetryVisibility.PRIVATE, nextTelemetryVisibility(TelemetryVisibility.SHARE))
    }
}
