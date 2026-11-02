package eu.advantly.ui.phone.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.push.NotificationSettingsPort
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.auth.FakePhoneAuth
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.testModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Profil → „Benachrichtigungen“ (Plan Live §3.3): standardmäßig aus, nur mit Konto und Push-Plattform sichtbar. */
@OptIn(ExperimentalTestApi::class)
class NotificationsUiTest {
    private class Settings : NotificationSettingsPort {
        override val enabled = MutableStateFlow(false)
        override val hint = MutableStateFlow(true)
        val requests = mutableListOf<Boolean>()

        override fun setEnabled(enabled: Boolean) {
            requests += enabled
            this.enabled.value = enabled
        }

        override fun dismissHint() {
            hint.value = false
        }
    }

    @Test fun germanSectionStartsOffAndToggles() = profile(Locale.GERMANY, "Benachrichtigungen".uppercase(), "Push-Benachrichtigungen")

    @Test fun englishSectionStartsOffAndToggles() = profile(Locale.US, "Notifications".uppercase(), "Push notifications")

    private fun profile(
        locale: Locale,
        heading: String,
        toggle: String,
    ) {
        val previous = Locale.getDefault()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            Locale.setDefault(locale)
            runDesktopComposeUiTest(width = 360, height = 1600) {
                val auth = FakePhoneAuth().also { it.session.value = CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT) }
                val settings = Settings()
                val app = testModel().apply { navigator.tab = AppTab.Profile }
                setContent { AdvantlyApp(model = app, authModel = AuthViewModel(auth, scope), notifications = settings) }
                onNodeWithText(heading).performScrollTo()
                onNodeWithText(toggle).performScrollTo()
                onNodeWithTag("notifications_toggle").performClick()
                assertEquals(true, settings.enabled.value)
                onNodeWithTag("notifications_toggle").performClick()
                assertEquals(listOf(true, false), settings.requests)
            }
        } finally {
            scope.cancel()
            Locale.setDefault(previous)
        }
    }

    @Test fun hiddenWithoutPushPlatformOrAccount() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            runDesktopComposeUiTest(width = 360, height = 1600) {
                setContent { AdvantlyTheme { ProfileScreen("0.1.0", notifications = Settings()) } }
                onNodeWithTag("profile").assertExists()
                onNodeWithTag("notifications_settings").assertDoesNotExist()
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
