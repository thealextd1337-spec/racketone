package eu.advantly.ui.phone.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.cloud.sync.BlockedProfile
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.FoundProfile
import eu.advantly.cloud.sync.FriendRequestResult
import eu.advantly.cloud.sync.FriendsPort
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.PhoneSyncPort
import eu.advantly.presentation.auth.PhoneSyncState
import eu.advantly.presentation.auth.PhoneSyncStatus
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.auth.FakePhoneAuth
import eu.advantly.ui.phone.testModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Profil 0.3.0 (schlank): Kopf, eine Sicherungszeile, Freunde/Einstellungen, Konto & Datenschutz aufklappbar. */
@OptIn(ExperimentalTestApi::class)
class ProfileAccountUiTest {
    private object Me : FriendsPort {
        override suspend fun friendCode() = "K7QF92MX"

        override suspend fun ownProfile() = FoundProfile("alice", "Alice", "alice_1")

        override suspend fun findProfile(query: String): FoundProfile? = null

        override suspend fun sendFriendRequest(
            profileId: String,
            viaCode: Boolean,
        ) = FriendRequestResult.REQUESTED

        override suspend fun respondFriendRequest(
            requesterId: String,
            accept: Boolean,
        ) = Unit

        override suspend fun removeFriend(profileId: String) = Unit

        override suspend fun blockUser(profileId: String) = Unit

        override suspend fun unblockUser(profileId: String) = Unit

        override suspend fun blockedProfiles() = emptyList<BlockedProfile>()
    }

    private class Sync : PhoneSyncPort {
        override val state = MutableStateFlow(PhoneSyncState(available = true, status = PhoneSyncStatus.BACKED_UP))

        override fun syncNow() = Unit

        override fun onForeground() = Unit
    }

    @Test fun slimProfileShowsHeaderStatusAndHidesCloudInternals() {
        val previous = Locale.getDefault()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            Locale.setDefault(Locale.GERMAN)
            runDesktopComposeUiTest(width = 360, height = 1600) {
                val auth =
                    FakePhoneAuth().also { it.session.value = CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT) }
                val friends = FriendsViewModel(auth, Me, { emptyList() }, null, scope)
                val model = AuthViewModel(auth, scope, sync = Sync(), friends = friends)
                val app = testModel().apply { navigator.tab = AppTab.Profile }
                val opened = mutableListOf<String>()
                setContent {
                    CompositionLocalProvider(
                        LocalUriHandler provides
                            object : UriHandler {
                                override fun openUri(uri: String) {
                                    opened += uri
                                }
                            },
                    ) {
                        AdvantlyApp(model = app, authModel = model)
                    }
                }
                onNodeWithTag("profile_alias").assertTextEquals("Alice")
                onNodeWithTag("profile_username").assertTextEquals("@alice_1")
                onNodeWithTag("profile_friend_code").assertTextEquals("K7QF-92MX")
                onNodeWithTag("backup_status_text").assertTextEquals("Alles gesichert ✓")
                onNodeWithTag("backup_action").assertDoesNotExist()
                onNodeWithTag("telemetry_settings").performScrollTo().assertIsDisplayed()
                for (gone in listOf(
                    "cloud_upload_section",
                    "cloud_pull_section",
                    "cloud_sync_now",
                    "account_devices_show",
                    "auth_local",
                    "profile_account",
                )) {
                    onNodeWithTag(gone).assertDoesNotExist()
                }
                // Export und Löschung erst unter „Konto & Datenschutz“.
                onNodeWithTag("account_delete").assertDoesNotExist()
                onNodeWithTag("profile_account_privacy").performScrollTo().performClick()
                onNodeWithTag("account_export").performScrollTo().assertIsDisplayed()
                onNodeWithTag("account_delete").performScrollTo().assertIsDisplayed()
                onNodeWithTag("profile_privacy").performScrollTo().performClick()
                onNodeWithTag("profile_terms").performScrollTo().performClick()
                assertEquals(listOf("https://advantly.eu/app-datenschutz.html", "https://advantly.eu/nutzungsbedingungen.html"), opened)
                onNodeWithTag("profile_friends").performScrollTo().performClick()
                assertEquals(AppTab.Friends, app.navigator.tab)
                friends.close()
            }
        } finally {
            scope.cancel()
            Locale.setDefault(previous)
        }
    }
}
