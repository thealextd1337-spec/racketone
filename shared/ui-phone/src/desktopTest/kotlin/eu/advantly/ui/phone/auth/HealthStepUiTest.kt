package eu.advantly.ui.phone.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.presentation.auth.AuthStep
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.match.InMemoryTelemetrySettings
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.app.AdvantlyAppModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plan 0.5.1 §5.1 (Schritt „Puls & Schritte“ im Einstieg) und §5.4 (Rückfrage nach Übernahme von der Uhr). */
@OptIn(ExperimentalTestApi::class)
class HealthStepUiTest {
    private fun app(port: InMemoryTelemetrySettings): AdvantlyAppModel {
        val store = InMemoryMatchStore()
        return AdvantlyAppModel(store, store, store, telemetry = port)
    }

    private fun signedIn(): AuthViewModel =
        AuthViewModel(FakePhoneAuth(), CoroutineScope(Dispatchers.Unconfined)).also {
            it.signInExisting()
            it.sendCode("a@b.at")
        }

    @Test
    fun theConsentIsNotPreselectedAndOnlyContinueApplies() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val port = InMemoryTelemetrySettings()
            val auth = signedIn()
            setContent { AdvantlyApp(model = app(port), authModel = auth, darkTheme = false) }
            runOnIdle { auth.verifyCode("123456") }
            runOnIdle { assertEquals(AuthStep.HEALTH, auth.state.value.step) }
            onNodeWithTag("health_choice_record")
                .performScrollTo()
                .assertIsOff()
                .performClick()
                .assertIsOn()
            onNodeWithTag("telemetry_private").assertExists()
            onNodeWithTag("telemetry_share").assertDoesNotExist()
            runOnIdle { assertFalse(port.healthConsent.value, "erst „Weiter“ wirkt") }
            onNodeWithTag("health_step_continue").performScrollTo().performClick()
            runOnIdle {
                assertTrue(port.healthConsent.value)
                assertEquals(TelemetryVisibility.PRIVATE, port.telemetryVisibility.value)
                assertEquals(AuthStep.ALIAS, auth.state.value.step)
            }
        }

    @Test
    fun laterDecidesNothingAndOffLocksTheRecording() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val port = InMemoryTelemetrySettings()
            val auth = signedIn()
            setContent { AdvantlyApp(model = app(port), authModel = auth, darkTheme = false) }
            runOnIdle { auth.verifyCode("123456") }
            onNodeWithTag("health_choice_record").performScrollTo().performClick()
            onNodeWithTag("telemetry_off").performScrollTo().performClick()
            onNodeWithTag("health_choice_record").performScrollTo().assertIsOff().assertIsNotEnabled()
            onNodeWithTag("health_step_later").performScrollTo().performClick()
            runOnIdle {
                assertTrue(port.healthConsentOpen.value)
                assertEquals(TelemetryVisibility.PRIVATE, port.telemetryVisibility.value, "„Später“ übernimmt nichts")
                assertEquals(AuthStep.ALIAS, auth.state.value.step)
            }
        }

    @Test
    fun aDecidedDeviceSkipsTheStep() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val port = InMemoryTelemetrySettings(consent = true)
            val auth = signedIn()
            setContent { AdvantlyApp(model = app(port), authModel = auth, darkTheme = false) }
            runOnIdle { auth.verifyCode("123456") }
            runOnIdle { assertEquals(AuthStep.ALIAS, auth.state.value.step) }
            onNodeWithTag("health_choice").assertDoesNotExist()
        }

    @Test
    fun adoptedConsentIsKeptOrRevokedOnce() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val kept = InMemoryTelemetrySettings(consent = true, toConfirm = true)
            setContent { AdvantlyApp(model = app(kept), darkTheme = false) }
            onNodeWithTag("health_adopted_keep").performClick()
            onNodeWithTag("health_adopted").assertDoesNotExist()
            runOnIdle { assertTrue(kept.healthConsent.value) }
        }

    @Test
    fun adoptedConsentCanBeSwitchedOff() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val revoked = InMemoryTelemetrySettings(consent = true, toConfirm = true)
            setContent { AdvantlyApp(model = app(revoked), darkTheme = false) }
            onNodeWithTag("health_adopted_off").performClick()
            onNodeWithTag("health_adopted").assertDoesNotExist()
            runOnIdle { assertFalse(revoked.healthConsent.value) }
        }
}
