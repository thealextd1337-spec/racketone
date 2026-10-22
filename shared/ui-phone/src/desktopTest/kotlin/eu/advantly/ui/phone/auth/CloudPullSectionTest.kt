package eu.advantly.ui.phone.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.presentation.auth.PhonePullBlock
import eu.advantly.presentation.auth.PhonePullPort
import eu.advantly.presentation.auth.PhonePullResult
import eu.advantly.presentation.auth.PhonePullState
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CloudPullSectionTest {
    private class Pull : PhonePullPort {
        override val state = MutableStateFlow(PhonePullState(available = true))
        var pulls = 0

        override fun pullNow() {
            pulls++
        }

        override fun close(onDrained: () -> Unit) = onDrained()
    }

    @Test fun blockedPullNamesItsReasonInBothLanguagesAndBusyDisablesTheAction() {
        val original = Locale.getDefault()
        try {
            for (language in listOf("de", "en")) {
                Locale.setDefault(Locale.forLanguageTag(language))
                runDesktopComposeUiTest {
                    val pull = Pull()
                    setContent { AdvantlyTheme { CloudPullSection(pull) } }
                    onNodeWithTag("cloud_pull_result").assertDoesNotExist()
                    onNodeWithTag("cloud_pull_now").assertIsEnabled().performClick()
                    assertEquals(1, pull.pulls)
                    pull.state.value = PhonePullState(available = true, busy = true)
                    waitForIdle()
                    onNodeWithTag("cloud_pull_now").assertIsNotEnabled()
                    onNodeWithTag("cloud_pull_result").assertTextEquals(if (language == "de") "Wird geladen…" else "Downloading…")
                    pull.state.value =
                        PhonePullState(available = true, result = PhonePullResult.BLOCKED, blocked = PhonePullBlock.READINESS)
                    waitForIdle()
                    onNodeWithTag("cloud_pull_blocked").assertTextEquals(
                        if (language == "de") {
                            "Einige geteilte Matches sind noch nicht vollständig geprüft."
                        } else {
                            "Some shared matches are not fully verified yet."
                        },
                    )
                    onNodeWithTag("cloud_pull_now").assertIsEnabled()
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
