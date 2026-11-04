package eu.advantly.ui.phone.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.cloud.sync.BlockedProfile
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.FoundProfile
import eu.advantly.cloud.sync.FriendRequestResult
import eu.advantly.cloud.sync.FriendsPort
import eu.advantly.cloud.sync.GuestLinksPort
import eu.advantly.cloud.sync.PulledFriendship
import eu.advantly.cloud.sync.PulledMatch
import eu.advantly.cloud.sync.PulledParticipant
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.presentation.friends.GuestLink
import eu.advantly.presentation.friends.SharedMatchesLocal
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.ui.phone.auth.FakePhoneAuth
import eu.advantly.ui.phone.common.LocalGuestAliases
import eu.advantly.ui.phone.common.playerName
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.friends.MatchSharingSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

private object Friends : FriendsPort {
    override suspend fun friendCode() = "K7QF92MX"

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

private class Guests : GuestLinksPort {
    val calls = mutableListOf<String>()
    var failure: String? = null

    override suspend fun assignGuest(
        profileId: String,
        playerId: String,
        friendId: String,
    ): Int {
        calls += "assign:$playerId:$friendId"
        failure?.let { throw CloudException(it, true) }
        return 3
    }

    override suspend fun unassignGuest(
        profileId: String,
        playerId: String,
    ) {
        calls += "unassign:$playerId"
    }

    override suspend fun rejectGuestAssignment(
        profileId: String,
        playerId: String,
    ) {
        calls += "reject:$playerId"
    }
}

private class Local(
    val links: Map<String, GuestLink>,
) : SharedMatchesLocal {
    override fun feed(profileId: String) = emptyList<PulledMatch>()

    override fun match(
        profileId: String,
        matchId: String,
    ) = PulledMatch(
        id = matchId,
        access = "member",
        sport = "tennis",
        discipline = "singles",
        format = JsonObject(emptyMap()),
        meta = JsonObject(emptyMap()),
        status = "finished",
        serverVersion = 1,
        participants =
            listOf(
                PulledParticipant("s1", "A", 1, "alice-self", "alice", "self", "Alice"),
                PulledParticipant("s2", "B", 1, "test1", profileId, "assigned", "Anja"),
            ),
    )

    override fun linkNotes(
        profileId: String,
        matchId: String,
    ) = emptyMap<String, String>()

    override fun guestLinks(profileId: String) = links
}

/** plan-0.6.3 §3.5/§3.6: Gästeliste mit „Mit Freund verknüpfen“, Bestätigung, Anzahl, Lösen, Fehler und Redaktion. */
@OptIn(ExperimentalTestApi::class)
class GuestLinkUiTest {
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

    private fun ComposeUiTest.screenshot(name: String) {
        val directory = File(System.getenv("ADVANTLY_SCREENSHOT_DIR") ?: "build/reports/screenshots").apply { mkdirs() }
        val roots = onAllNodes(isRoot())
        ImageIO.write(roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun model(
        scope: CoroutineScope,
        guests: Guests,
        links: Map<String, GuestLink>,
        profile: String = "alice",
    ): FriendsViewModel {
        val auth = FakePhoneAuth().apply { session.value = CloudSession(profile, true, consentStatus = ConsentStatus.CURRENT) }
        val friends = listOf(PulledFriendship("anja-id", "Anja", "accepted", outgoing = false, serverVersion = 1))
        return FriendsViewModel(auth, Friends, { friends }, null, scope, local = Local(links), guests = guests)
    }

    @Test fun guestIsLinkedToAFriendAfterConfirmationInGerman() =
        withLocale(Locale.GERMAN) {
            runDesktopComposeUiTest(width = 400, height = 1400) {
                val store = InMemoryMatchStore(selfName = "Alex")
                val guest = store.addGuest("Test1")
                val id = guest.id.value
                val scope = CoroutineScope(Dispatchers.Unconfined + Job())
                val guests = Guests()
                val model = model(scope, guests, mapOf(id to GuestLink()))
                setContent { AdvantlyTheme(darkTheme = true) { Column { GuestList(store, friends = model) } } }
                try {
                    onNodeWithTag("guest_actions_$id").performClick()
                    onNodeWithTag("link_$id").performClick()
                    onNodeWithText("Welcher Freund ist Test1?").assertExists()
                    onNodeWithTag("guest_link_friend_anja-id").performClick()
                    onNodeWithText(
                        "Alle Matches mit Test1 werden Anja zugeordnet. Anja wird benachrichtigt und kann widersprechen.",
                    ).assertExists()
                    screenshot("guest-link-confirm-de")
                    onNodeWithTag("guest_link_confirm").performClick()
                    waitForIdle()
                    assertEquals(listOf("assign:$id:anja-id"), guests.calls)
                    onNodeWithTag("guest_link_done").assertTextEquals("Test1 ist jetzt mit Anja verknüpft. Zugeordnete Matches: 3")
                    onNodeWithTag("guest_linked_$id", useUnmergedTree = true).assertTextEquals("verknüpft mit Anja")
                    onNodeWithTag("guest_link_done_ok").performClick()
                    onNodeWithTag("guest_link_done").assertDoesNotExist()
                    // Verknüpft: nur noch „Verknüpfung lösen“.
                    onNodeWithTag("guest_actions_$id").performClick()
                    onNodeWithTag("link_$id").assertDoesNotExist()
                    onNodeWithTag("unlink_$id").performClick()
                    waitForIdle()
                    assertEquals("unassign:$id", guests.calls.last())
                    onNodeWithTag("guest_linked_$id", useUnmergedTree = true).assertDoesNotExist()
                    // Abgelehnt: eigener Fehlertext.
                    guests.failure = "assignment_rejected_before"
                    onNodeWithTag("guest_actions_$id").performClick()
                    onNodeWithTag("link_$id").performClick()
                    onNodeWithTag("guest_link_friend_anja-id").performClick()
                    onNodeWithTag("guest_link_confirm").performClick()
                    waitForIdle()
                    onNodeWithTag("guest_link_error").assertTextEquals("Diese Person hat die Zuordnung dieses Gasts früher abgelehnt.")
                } finally {
                    model.close()
                    scope.cancel()
                }
            }
        }

    @Test fun errorsRedactionAndFriendNameInOwnMatchesInEnglish() =
        withLocale(Locale.ENGLISH) {
            runDesktopComposeUiTest(width = 400, height = 1400) {
                val store = InMemoryMatchStore(selfName = "Alex")
                val guest = store.addGuest("Test1")
                val gone = store.addGuest("Gelöschter Spieler")
                val offline = store.addGuest("Kim")
                val id = guest.id.value
                val scope = CoroutineScope(Dispatchers.Unconfined + Job())
                val guests = Guests().apply { failure = "assignee_must_be_friend" }
                // Kim hat noch keinen Server-Stand (nicht hochgeladen): keine Verknüpfung angeboten.
                val model = model(scope, guests, mapOf(id to GuestLink(), gone.id.value to GuestLink("anja-id", redacted = true)))
                setContent {
                    AdvantlyTheme(darkTheme = true) {
                        Column {
                            GuestList(store, friends = model)
                            CompositionLocalProvider(LocalGuestAliases provides mapOf(guest.id to "Anja")) {
                                Text(playerName(guest), modifier = Modifier.testTag("own_match_name"))
                            }
                        }
                    }
                }
                try {
                    onNodeWithTag("guest_actions_$id").performClick()
                    onNodeWithTag("link_$id").performClick()
                    onNodeWithTag("guest_link_friend_anja-id").performClick()
                    onNodeWithText("All matches with Test1 will be assigned to Anja. Anja will be notified and can object.").assertExists()
                    onNodeWithTag("guest_link_confirm").performClick()
                    waitForIdle()
                    onNodeWithTag("guest_link_error").assertTextContains("not (or no longer) friends", substring = true)
                    guests.failure = "cloud_connection_failed"
                    onNodeWithTag("guest_actions_$id").performClick()
                    onNodeWithTag("link_$id").performClick()
                    onNodeWithTag("guest_link_friend_anja-id").performClick()
                    onNodeWithTag("guest_link_confirm").performClick()
                    waitForIdle()
                    onNodeWithTag("guest_link_error").assertTextContains("offline or your account is not ready", substring = true)
                    // Redigiert (Freund gelöscht): „Deleted player“, nicht verknüpft, keine Verknüpfen-Aktion.
                    onNodeWithTag("guest_row_${gone.id.value}").assertTextContains("Deleted player")
                    onNodeWithTag("guest_linked_${gone.id.value}", useUnmergedTree = true).assertDoesNotExist()
                    onNodeWithTag("guest_actions_${gone.id.value}").performClick()
                    onNodeWithTag("link_${gone.id.value}").assertDoesNotExist()
                    onNodeWithTag("unlink_${gone.id.value}").assertDoesNotExist()
                    onNodeWithTag("rename_${gone.id.value}").performClick()
                    onNodeWithTag("rename_cancel").performClick()
                    onNodeWithTag("guest_actions_${offline.id.value}").performClick()
                    onNodeWithTag("link_${offline.id.value}").assertDoesNotExist()
                    // Eigene Matches zeigen den Freund statt des Gasts.
                    onNodeWithTag("own_match_name").assertTextEquals("Anja")
                } finally {
                    model.close()
                    scope.cancel()
                }
            }
        }

    @Test fun theFriendRejectsTheAssignmentFromTheMatchDetail() =
        withLocale(Locale.ENGLISH) {
            runDesktopComposeUiTest(width = 400, height = 1000) {
                val scope = CoroutineScope(Dispatchers.Unconfined + Job())
                val guests = Guests()
                val model = model(scope, guests, emptyMap(), profile = "anja-id")
                var gone = 0
                setContent { AdvantlyTheme(darkTheme = true) { Column { MatchSharingSection(model, "m1", { null }) { gone++ } } } }
                try {
                    onNodeWithTag("sharing_assigned").assertTextEquals("A friend assigned one of their guests to you.")
                    onNodeWithTag("sharing_reject").assertDoesNotExist()
                    onNodeWithTag("sharing_reject_assignment").performClick()
                    onNodeWithText("The assignment is removed for all matches with this guest", substring = true).assertExists()
                    onNodeWithTag("sharing_confirm").performClick()
                    waitForIdle()
                    assertEquals(listOf("reject:test1"), guests.calls)
                    assertEquals(1, gone)
                } finally {
                    model.close()
                    scope.cancel()
                }
            }
        }
}
