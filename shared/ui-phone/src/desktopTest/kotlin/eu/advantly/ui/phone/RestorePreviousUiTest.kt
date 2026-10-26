package eu.advantly.ui.phone

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.ui.phone.app.AppTab
import kotlin.test.Test
import kotlin.test.assertEquals

/** Recovery-Paket: späterer Übernahmeweg im Profil, nur solange der Host ihn anbietet. */
@OptIn(ExperimentalTestApi::class)
class RestorePreviousUiTest {
    @Test
    fun profileOffersTheLaterRestoreOnlyWhileTheHostProvidesIt() {
        runDesktopComposeUiTest(width = 360, height = 640) {
            val app = testModel()
            var restores = 0
            val callback = mutableStateOf<(() -> Unit)?>(null)
            setContent { AdvantlyApp(model = app, onRestorePrevious = callback.value) }
            runOnIdle { app.navigator.tab = AppTab.Profile }
            onNodeWithTag("profile_restore_previous").assertDoesNotExist()
            runOnIdle { callback.value = { restores++ } }
            onNodeWithTag("profile_restore_previous").performClick()
            assertEquals(1, restores)
        }
    }
}
