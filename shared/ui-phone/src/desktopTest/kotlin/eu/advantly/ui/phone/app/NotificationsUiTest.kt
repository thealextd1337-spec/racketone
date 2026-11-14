package eu.advantly.ui.phone.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.cloud.sync.BlockedProfile
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.FoundProfile
import eu.advantly.cloud.sync.FriendRequestResult
import eu.advantly.cloud.sync.FriendsPort
import eu.advantly.cloud.sync.PulledFriendship
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.presentation.push.NotificationPrompt
import eu.advantly.presentation.push.NotificationPromptPort
import eu.advantly.presentation.push.NotificationPromptStore
import eu.advantly.presentation.push.NotificationSettingsPort
import eu.advantly.presentation.push.PromptStage
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.auth.FakePhoneAuth
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.startWith
import eu.advantly.ui.phone.testModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

private object NoCalls : FriendsPort {
    override suspend fun friendCode() = "K7QF92MX"

    override suspend fun findProfile(query: String): FoundProfile? = null

    override suspend fun sendFriendRequest(
        profileId: String,
        viaCode: Boolean,
    ): FriendRequestResult = error("unused")

    override suspend fun respondFriendRequest(
        requesterId: String,
        accept: Boolean,
    ) = error("unused")

    override suspend fun removeFriend(profileId: String) = error("unused")

    override suspend fun blockUser(profileId: String) = error("unused")

    override suspend fun unblockUser(profileId: String) = error("unused")

    override suspend fun blockedProfiles() = emptyList<BlockedProfile>()
}

/** Erklärseite mit der echten Zustandsmaschine; „Einschalten“ zählt nur. */
private class Prompt : NotificationPromptPort {
    val store =
        object : NotificationPromptStore {
            override var stage = PromptStage.NEW
            override var laterAtMs = 0L
            override var hintHiddenAtMs = 0L
        }
    private val machine = NotificationPrompt(store) { 0L }
    override val prompt = machine.prompt
    var accepted = 0

    override fun onMoment() = machine.onMoment(displayOn = false)

    override fun accept() {
        accepted++
        machine.decided()
    }

    override fun later() = machine.later()
}

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

    @Test fun germanPromptOpensOnTheFirstFriendAndLaterCloses() =
        prompt(
            Locale.GERMANY,
            "Kein Match verpassen",
            "Erfahre sofort, wenn dich ein Freund in ein Match einträgt oder deine Einladung annimmt.",
            "Später",
        )

    @Test fun englishPromptOpensOnTheFirstFriendAndLaterCloses() =
        prompt(Locale.US, "Never miss a match", "Know right away when a friend adds you to a match or accepts your invitation.", "Later")

    private fun prompt(
        locale: Locale,
        title: String,
        body: String,
        later: String,
    ) {
        val previous = Locale.getDefault()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            Locale.setDefault(locale)
            runDesktopComposeUiTest(width = 360, height = 1600) {
                val prompt = Prompt()
                setContent { AdvantlyApp(model = testModel(), authModel = account(scope, friends = true), notificationPrompt = prompt) }
                onNodeWithTag("notifications_prompt").assertExists()
                onNodeWithText(title).assertExists()
                onNodeWithText(body).assertExists()
                onNodeWithText(later).performClick()
                onNodeWithTag("notifications_prompt").assertDoesNotExist()
                assertEquals(PromptStage.LATER, prompt.store.stage)
                onNodeWithTag("tab_friends").assertExists()
            }
        } finally {
            scope.cancel()
            Locale.setDefault(previous)
        }
    }

    @Test fun noPromptWithoutFriendsOrOverAMatchBeingScored() {
        val previous = Locale.getDefault()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            Locale.setDefault(Locale.US)
            runDesktopComposeUiTest(width = 360, height = 1600) {
                val alone = Prompt()
                val scoring = Prompt()
                val app = testModel().apply { startWith(Presets.TENNIS_OPEN.format(), "") }
                app.navigator.push(Overlay.Live)
                var friends by mutableStateOf(false)
                setContent {
                    if (friends) {
                        AdvantlyApp(model = app, authModel = account(scope, friends = true), notificationPrompt = scoring)
                    } else {
                        AdvantlyApp(model = testModel(), authModel = account(scope, friends = false), notificationPrompt = alone)
                    }
                }
                onNodeWithTag("notifications_prompt").assertDoesNotExist()
                assertEquals(false, alone.prompt.value)
                friends = true
                waitForIdle()
                assertEquals(true, scoring.prompt.value)
                onNodeWithTag("notifications_prompt").assertDoesNotExist()
                app.navigator.pop()
                waitForIdle()
                onNodeWithTag("notifications_prompt_enable").performClick()
                assertEquals(1, scoring.accepted)
                onNodeWithTag("notifications_prompt").assertDoesNotExist()
            }
        } finally {
            scope.cancel()
            Locale.setDefault(previous)
        }
    }

    private fun account(
        scope: CoroutineScope,
        friends: Boolean,
    ): AuthViewModel {
        val auth = FakePhoneAuth().also { it.session.value = CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT) }
        val local = if (friends) listOf(PulledFriendship("anja", "Anja", "accepted", outgoing = false, serverVersion = 1)) else emptyList()
        return AuthViewModel(auth, scope, friends = FriendsViewModel(auth, NoCalls, { local }, null, scope))
    }
}
