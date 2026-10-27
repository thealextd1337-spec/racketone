package eu.advantly.ui.phone.friends

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.advantly.cloud.sync.BlockedProfile
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.FoundProfile
import eu.advantly.cloud.sync.FriendRequestResult
import eu.advantly.cloud.sync.FriendsPort
import eu.advantly.cloud.sync.PulledFriendship
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.app.AppTab
import eu.advantly.ui.phone.auth.FakePhoneAuth
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.testModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class UiFriends : FriendsPort {
    val calls = mutableListOf<String>()
    var failure: String? = null
    val blocked = mutableListOf<BlockedProfile>()

    private fun call(name: String) {
        calls += name
        failure?.let { throw CloudException(it, true) }
    }

    override suspend fun friendCode() = "K7QF92MX".also { call("code") }

    override suspend fun findProfile(query: String): FoundProfile? {
        call("find:$query")
        return FoundProfile("bob", "Bob Builder", "bob_99")
    }

    override suspend fun sendFriendRequest(
        profileId: String,
        viaCode: Boolean,
    ): FriendRequestResult {
        call("send:$profileId")
        return FriendRequestResult.REQUESTED
    }

    override suspend fun respondFriendRequest(
        requesterId: String,
        accept: Boolean,
    ) = call("respond:$requesterId:$accept")

    override suspend fun removeFriend(profileId: String) = call("remove:$profileId")

    override suspend fun blockUser(profileId: String) {
        call("block:$profileId")
        blocked += BlockedProfile(profileId, "2026-10-27T10:00:00Z")
    }

    override suspend fun unblockUser(profileId: String) {
        call("unblock:$profileId")
        blocked.removeAll { it.profileId == profileId }
    }

    override suspend fun blockedProfiles() = blocked.toList().also { call("blocked") }
}

@OptIn(ExperimentalTestApi::class)
class FriendsScreenTest {
    private val local =
        listOf(
            PulledFriendship("anna", "Anna mit einem sehr langen Alias", "accepted", outgoing = true, serverVersion = 1),
            PulledFriendship("carl", "Carl", "pending", outgoing = false, serverVersion = 2),
            PulledFriendship("dora", "Dora", "pending", outgoing = true, serverVersion = 3),
        )

    private fun withLocale(
        locale: Locale,
        block: () -> Unit,
    ) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun ComposeUiTest.click(tag: String) = onNodeWithTag(tag).performScrollTo().performClick()

    private fun ComposeUiTest.screenshot(name: String) {
        val directory = File(System.getenv("ADVANTLY_SCREENSHOT_DIR") ?: "build/reports/screenshots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test fun segmentsAddFlowRequestsAndConfirmationsWorkAtDoubleFontSize() =
        withLocale(Locale.ENGLISH) {
            runDesktopComposeUiTest(width = 360, height = 1600) {
                val scope = CoroutineScope(Dispatchers.Unconfined + Job())
                val auth = FakePhoneAuth().apply { session.value = CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT) }
                val port = UiFriends()
                val model = FriendsViewModel(auth, port, { if (it == "alice") local else emptyList() }, null, scope)
                val shared = mutableListOf<String>()
                port.failure = "connection_failed"
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        AdvantlyTheme(darkTheme = false) { FriendsScreen(model, onOpenAccount = {}, onShareText = { shared += it }) }
                    }
                }
                try {
                    // Gescheitertes Laden bleibt nicht bei „wird geladen“ stehen.
                    onNodeWithTag("friends_error").assertTextContains("No connection", substring = true)
                    port.failure = null
                    click("friends_code_retry")
                    onNodeWithTag("friends_code").assertTextContains("K7QF-92MX")
                    screenshot("friends-en-200")
                    click("friends_share")
                    assertEquals(listOf("Add me on Advantly – my friend code: K7QF-92MX"), shared)

                    // Hinzufügen: exakte Suche, Treffer, Anfrage senden → sofort unter „Gesendet“.
                    onNodeWithTag("friends_query").performScrollTo().performTextInput("bob_99")
                    click("friends_search")
                    onNodeWithTag("friends_found").assertTextContains("Bob Builder · @bob_99")
                    click("friends_send")
                    onNodeWithTag("friends_request_result").assertTextContains("Friend request sent.")

                    // Entfernen braucht eine Rückfrage; Abbrechen ändert nichts.
                    click("friends_remove_anna")
                    onNodeWithText("Remove Anna mit einem sehr langen Alias from your friends? Shared visibility between you ends.")
                        .assertIsDisplayed()
                    onNodeWithTag("friends_confirm_cancel").performClick()
                    assertTrue(port.calls.none { it.startsWith("remove") })
                    click("friends_remove_anna")
                    onNodeWithTag("friends_confirm").performClick()
                    onNodeWithTag("friends_person_anna").assertDoesNotExist()

                    // Blockieren und Aufheben jeweils mit Rückfrage.
                    click("friends_blocked_show")
                    click("friends_segment_requests")
                    onNodeWithTag("friends_segment_requests").assertTextContains("Requests (1)")
                    onNodeWithTag("friends_person_bob").assertExists()
                    click("friends_block_carl")
                    onNodeWithTag("friends_confirm").performClick()
                    onNodeWithTag("friends_person_carl").assertDoesNotExist()
                    click("friends_segment_friends")
                    click("friends_unblock_carl")
                    onNodeWithTag("friends_confirm").performClick()
                    onNodeWithTag("friends_blocked_carl").assertDoesNotExist()

                    click("friends_segment_requests")
                    click("friends_withdraw_dora")
                    onNodeWithTag("friends_confirm").performClick()
                    onNodeWithTag("friends_person_dora").assertDoesNotExist()

                    click("friends_segment_feed")
                    onNodeWithTag("friends_feed").assertIsDisplayed()

                    // Rate-Limit verständlich statt Rohfehler.
                    click("friends_segment_friends")
                    port.failure = "rate_limit_exceeded"
                    click("friends_search")
                    onNodeWithTag("friends_error").assertTextContains("Too many searches", substring = true)
                    assertEquals(
                        listOf(
                            "code",
                            "code",
                            "find:bob_99",
                            "send:bob",
                            "remove:anna",
                            "blocked",
                            "block:carl",
                            "blocked",
                            "unblock:carl",
                            "blocked",
                            "remove:dora",
                            "find:bob_99",
                        ),
                        port.calls,
                    )
                } finally {
                    model.close()
                    scope.cancel()
                }
            }
        }

    @Test fun withoutAccountTheTabExplainsAndLeadsToTheAccountInGermanAtDoubleFontSize() =
        withLocale(Locale.GERMAN) {
            runDesktopComposeUiTest(width = 360, height = 892) {
                val scope = CoroutineScope(Dispatchers.Unconfined + Job())
                val auth = FakePhoneAuth()
                val port = UiFriends()
                val friends = FriendsViewModel(auth, port, { local }, null, scope)
                val account = AuthViewModel(auth, scope, friends = friends)
                val app = testModel().apply { navigator.tab = AppTab.Friends }
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        Box(Modifier.size(360.dp, 892.dp)) { AdvantlyApp(model = app, darkTheme = true, authModel = account) }
                    }
                }
                try {
                    onNodeWithTag("friends_hint")
                        .assertTextContains("Mit einem Konto kannst du Freunde hinzufügen. Matches zählen geht auch ohne Konto.")
                    onNodeWithTag("friends_person_anna").assertDoesNotExist()
                    screenshot("friends-signed-out-de-200")
                    click("friends_open_account")
                    assertEquals(AppTab.Profile, app.navigator.tab)
                    onNodeWithTag("auth_country").assertExists()
                    assertTrue(port.calls.isEmpty())

                    // Ohne Zustimmung: eigener Hinweis, weiterhin keine Daten und keine Aufrufe.
                    auth.session.value = CloudSession("alice", true, consentStatus = ConsentStatus.DECLINED)
                    app.navigator.tab = AppTab.Friends
                    onNodeWithTag("friends_hint").assertTextContains("Zustimmung", substring = true)
                    assertTrue(port.calls.isEmpty())
                } finally {
                    friends.close()
                    scope.cancel()
                }
            }
        }
}
