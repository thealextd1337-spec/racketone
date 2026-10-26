package eu.advantly.wear.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import org.robolectric.annotation.Config

/** Recovery-Paket: späterer Übernahmeweg auf der Startliste, nur solange der Host ihn anbietet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
class RestorePreviousUiTest {
    @get:Rule val rule = createComposeRule()

    private val home = WatchScreen.Home(null, null, emptyList(), workoutEnabled = false, telemetryVisibility = TelemetryVisibility.PRIVATE)

    @Test
    fun startScreenOffersTheLaterRestoreOnlyWhenProvided() {
        var restores = 0
        val provided = mutableStateOf<(() -> Unit)?>(null)
        rule.setContent {
            WearTestFrame {
                HomeScreen(home, WorkoutPermissions.ALL, powerSave = false, recording<HomeActions>(mutableListOf()), provided.value)
            }
        }
        rule.onNodeWithTag(RESTORE_PREVIOUS_TAG).assertDoesNotExist()
        provided.value = { restores++ }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(RESTORE_PREVIOUS_TAG))
        rule.onNodeWithTag(RESTORE_PREVIOUS_TAG).performClick()
        assertEquals(1, restores)
    }
}
