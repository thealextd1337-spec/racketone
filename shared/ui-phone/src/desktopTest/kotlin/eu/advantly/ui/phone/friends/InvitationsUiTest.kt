package eu.advantly.ui.phone.friends

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.SavedStateHandle
import eu.advantly.cloud.sync.BlockedProfile
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.FoundProfile
import eu.advantly.cloud.sync.FriendRequestResult
import eu.advantly.cloud.sync.FriendsPort
import eu.advantly.cloud.sync.InviteKind
import eu.advantly.cloud.sync.InvitePreview
import eu.advantly.cloud.sync.InviteRedemption
import eu.advantly.cloud.sync.InviteStatus
import eu.advantly.cloud.sync.InvitesPort
import eu.advantly.cloud.sync.OpenInvite
import eu.advantly.cloud.sync.PulledMatch
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.presentation.friends.GuestLink
import eu.advantly.presentation.friends.InvitationsViewModel
import eu.advantly.presentation.friends.SharedMatchesLocal
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.match.WallClock
import eu.advantly.ui.phone.app.GuestList
import eu.advantly.ui.phone.auth.FakePhoneAuth
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val TOKEN = "5a".repeat(32)
private const val NOW = 1_794_996_000_000

private object InviteFriends : FriendsPort {
    var finds = 0

    override suspend fun friendCode() = "K7QF92MX"

    override suspend fun findProfile(query: String): FoundProfile? {
        finds++
        return null
    }

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

private class UiInvites : InvitesPort {
    val calls = mutableListOf<String>()
    var preview = InvitePreview(InviteStatus.OK, InviteKind.FRIEND, "alex", "Alex")
    var redemption = InviteRedemption(InviteKind.FRIEND, "alex")
    var open = emptyList<OpenInvite>()
    var createFailure: String? = null

    override suspend fun createInvite(
        profileId: String,
        kind: InviteKind,
        playerId: String?,
        ttlMinutes: Int,
    ): String {
        calls += "create:${kind.wire}:$ttlMinutes"
        createFailure?.let { throw CloudException(it, false) }
        if (playerId != null) open = listOf(OpenInvite("inv-$playerId", kind, playerId, NOW + ttlMinutes * 60_000L))
        return TOKEN
    }

    override suspend fun previewInvite(
        profileId: String,
        token: String,
    ): InvitePreview {
        calls += "preview"
        return preview
    }

    override suspend fun redeemInvite(
        profileId: String,
        token: String,
    ): InviteRedemption {
        calls += "redeem"
        return redemption
    }

    override suspend fun revokeInvite(
        profileId: String,
        inviteId: String,
    ) {
        calls += "revoke:$inviteId"
        open = emptyList()
    }

    override suspend fun openInvites(profileId: String): List<OpenInvite> {
        calls += "open"
        return open
    }
}

private class InviteGuests(
    val links: Map<String, GuestLink>,
) : SharedMatchesLocal {
    override fun feed(profileId: String) = emptyList<PulledMatch>()

    override fun match(
        profileId: String,
        matchId: String,
    ): PulledMatch? = null

    override fun linkNotes(
        profileId: String,
        matchId: String,
    ) = emptyMap<String, String>()

    override fun guestLinks(profileId: String) = links
}

/** plan-einladungen §6 „UI-Tests“ (DE und EN): Block, QR-Sheet, Einlöse-Dialoge, Fehlertexte, Hinweisleiste, Gastmenü. */
@OptIn(ExperimentalTestApi::class)
class InvitationsUiTest {
    private class Rig(
        val scope: CoroutineScope,
        val auth: FakePhoneAuth,
        val port: UiInvites,
        val friends: FriendsViewModel,
        val invitations: InvitationsViewModel,
        var time: Long,
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

    private fun rig(
        session: CloudSession? = CloudSession("anja", true, consentStatus = ConsentStatus.CURRENT),
        links: Map<String, GuestLink> = emptyMap(),
        test: Rig.() -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val auth = FakePhoneAuth().apply { this.session.value = session }
        val port = UiInvites()
        var rig: Rig? = null
        val invitations = InvitationsViewModel(auth, port, SavedStateHandle(), scope, { rig?.time ?: NOW })
        val friends = FriendsViewModel(auth, InviteFriends, { emptyList() }, null, scope, local = InviteGuests(links))
        rig = Rig(scope, auth, port, friends, invitations, NOW)
        try {
            rig.test()
        } finally {
            invitations.close()
            friends.close()
            scope.cancel()
        }
    }

    private fun ComposeUiTest.click(tag: String) = onNodeWithTag(tag).performScrollTo().performClick()

    @Test fun inviteBlockSharesALinkAndShowsTheQrSheetInEnglish() =
        withLocale(Locale.ENGLISH) {
            rig {
                runDesktopComposeUiTest(width = 400, height = 1400) {
                    val shared = mutableListOf<String>()
                    setContent {
                        AdvantlyTheme(darkTheme = true) {
                            FriendsScreen(friends, {}, { shared += it }, clock = WallClock { time }, invitations = invitations)
                        }
                    }
                    onNodeWithText("Invite friends").assertExists()
                    click("invite_share_link")
                    waitForIdle()
                    assertEquals(listOf("Let's be friends on Advantly: https://advantly.eu/i/#$TOKEN"), shared)
                    assertEquals(listOf("create:friend:20160"), port.calls)
                    click("invite_show_qr")
                    waitForIdle()
                    onNodeWithTag("invite_qr_code").assertContentDescriptionEquals("QR code for your invitation")
                    onNodeWithTag("invite_qr_remaining").assertTextEquals("10:00 left")
                    onNodeWithText("Scan this code with the camera. Works once.").assertExists()
                    assertEquals("create:friend:10", port.calls.last())
                    click("invite_qr_new")
                    waitForIdle()
                    assertEquals(3, port.calls.size)
                }
            }
        }

    @Test fun expiredQrIsGreyedOutInGerman() =
        withLocale(Locale.GERMAN) {
            rig {
                runDesktopComposeUiTest(width = 400, height = 1400) {
                    setContent {
                        AdvantlyTheme(darkTheme = false) {
                            FriendsScreen(friends, {}, null, clock = WallClock { time }, invitations = invitations)
                        }
                    }
                    onNodeWithTag("invite_share_link").assertDoesNotExist()
                    click("invite_show_qr")
                    waitForIdle()
                    onNodeWithTag("invite_qr_remaining").assertTextEquals("Noch 10:00")
                    onNodeWithTag("invite_qr_code").assertContentDescriptionEquals("QR-Code für deine Einladung")
                    invitations.closeQr()
                    time = NOW + 11 * 60_000L
                    invitations.openQr()
                    time = NOW + 22 * 60_000L
                    waitForIdle()
                    mainClock.advanceTimeBy(2_000)
                    onNodeWithTag("invite_qr_remaining").assertTextEquals("Abgelaufen")
                    onNodeWithText("Neuer Code").assertExists()
                    assertEquals(2, port.calls.size, "kein Erneuern im Takt")
                    port.createFailure = "too_many_open_invites"
                    click("invite_qr_new")
                    waitForIdle()
                    onNodeWithTag("invite_error")
                        .assertTextEquals("Du hast zu viele offene Einladungen. Warte, bis ältere ablaufen.")
                }
            }
        }

    @Test fun friendInvitationPastedIntoAddFriendOpensThePreviewInGerman() =
        withLocale(Locale.GERMAN) {
            rig {
                runDesktopComposeUiTest(width = 400, height = 1400) {
                    setContent { AdvantlyTheme(darkTheme = true) { FriendsScreen(friends, {}, null, invitations = invitations) } }
                    onNodeWithTag("friends_query").performScrollTo().performTextInput("https://advantly.eu/i/#$TOKEN")
                    click("friends_search")
                    waitForIdle()
                    assertEquals(0, InviteFriends.finds, "keine Suche, sondern Vorschau")
                    onNodeWithText("Freundschaft mit Alex?").assertExists()
                    onNodeWithTag("invite_dialog_text").assertTextEquals(
                        "Alex hat dich eingeladen. Als Freunde seht ihr die Matches, die ihr teilt, und könnt euch in Matches eintragen.",
                    )
                    onNodeWithTag("invite_decline").assertTextEquals("Nicht jetzt")
                    onNodeWithTag("invite_accept").performClick()
                    waitForIdle()
                    onNodeWithText("Du bist jetzt mit Alex befreundet.").assertExists()
                    onNodeWithTag("invite_result_ok").performClick()
                    onNodeWithTag("invite_result").assertDoesNotExist()
                }
            }
        }

    @Test fun claimDialogResultAndErrorsInEnglish() =
        withLocale(Locale.ENGLISH) {
            rig {
                port.preview = InvitePreview(InviteStatus.OK, InviteKind.CLAIM_PLAYER, "alex", "Alex", "Test1", 7)
                port.redemption = InviteRedemption(InviteKind.CLAIM_PLAYER, "alex", "test1", 7)
                runDesktopComposeUiTest(width = 400, height = 1400) {
                    setContent { AdvantlyTheme(darkTheme = true) { FriendsScreen(friends, {}, null, invitations = invitations) } }
                    invitations.receive(TOKEN)
                    waitForIdle()
                    onNodeWithText("Are you Test1?").assertExists()
                    onNodeWithTag("invite_dialog_text").assertTextEquals(
                        "Alex added you to 7 matches as “Test1”. If you accept, they appear in your history and count towards " +
                            "your stats. You'll become friends, and Alex will see that you took them over.",
                    )
                    onNodeWithTag("invite_decline").assertTextEquals("No")
                    onNodeWithTag("invite_accept").assertTextEquals("Yes, that's me").performClick()
                    waitForIdle()
                    onNodeWithText("7 matches taken over. You're now friends with Alex.").assertExists()
                    invitations.dismissResult()
                    for ((status, text) in listOf(
                        InviteStatus.INVALID to "This invitation is no longer valid.",
                        InviteStatus.EXPIRED to "This invitation has expired. Ask for a new one.",
                        InviteStatus.USED to "This invitation has already been used.",
                        InviteStatus.REVOKED to "This invitation was withdrawn.",
                        InviteStatus.OWN to "This is your own invitation.",
                        InviteStatus.ALREADY_CLAIMED to "This guest has already been taken over.",
                    )) {
                        port.preview = InvitePreview(status)
                        invitations.receive(TOKEN)
                        waitForIdle()
                        onNodeWithTag("invite_problem").assertTextEquals(text)
                    }
                    port.preview = InvitePreview(InviteStatus.ALREADY_FRIENDS, InviteKind.FRIEND, "alex", "Alex")
                    invitations.receive(TOKEN)
                    waitForIdle()
                    onNodeWithText("You're already friends.").assertExists()
                    onNodeWithTag("invite_accept").assertDoesNotExist()
                    onNodeWithTag("invite_ok").performClick()
                    waitForIdle()
                    assertTrue(port.calls.count { it == "redeem" } == 1)
                }
            }
        }

    @Test fun pendingBannerWhileSignedOutInBothLanguages() {
        for ((locale, text) in listOf(
            Locale.ENGLISH to "An invitation is waiting. Sign in to accept it.",
            Locale.GERMAN to "Eine Einladung wartet. Melde dich an, um sie anzunehmen.",
        )) {
            withLocale(locale) {
                rig(session = null) {
                    runDesktopComposeUiTest(width = 400, height = 900) {
                        invitations.receive("https://advantly.eu/i/#$TOKEN")
                        setContent { AdvantlyTheme(darkTheme = true) { FriendsScreen(friends, {}, null, invitations = invitations) } }
                        onNodeWithText(text).assertExists()
                        invitations.needsAccount()
                        waitForIdle()
                        onNodeWithText(
                            if (locale == Locale.GERMAN) {
                                "Einladungen kannst du nur mit Konto annehmen."
                            } else {
                                "You need an account to accept invitations."
                            },
                        ).assertExists()
                        assertTrue(port.calls.isEmpty())
                    }
                }
            }
        }
    }

    @Test fun guestMenuOffersInvitationOnlyForKnownUnlinkedGuestsInGerman() =
        withLocale(Locale.GERMAN) {
            val store = InMemoryMatchStore(selfName = "Alex")
            val test1 = store.addGuest("Test1").id.value
            val linked = store.addGuest("Bea").id.value
            val gone = store.addGuest("Carl").id.value
            val local = store.addGuest("Dana").id.value
            rig(
                session = CloudSession("alex", true, consentStatus = ConsentStatus.CURRENT),
                links = mapOf(test1 to GuestLink(), linked to GuestLink("anja"), gone to GuestLink(redacted = true)),
            ) {
                runDesktopComposeUiTest(width = 400, height = 1400) {
                    val shared = mutableListOf<String>()
                    setContent {
                        AdvantlyTheme(darkTheme = true) {
                            Column { GuestList(store, friends = friends, invitations = invitations, onShareText = { shared += it }) }
                        }
                    }
                    // Verknüpft, redigiert oder nie hochgeladen: kein Einladungslink (Menü über „Umbenennen“ wieder schließen).
                    for (id in listOf(linked, gone, local)) {
                        onNodeWithTag("guest_actions_$id").performClick()
                        onNodeWithTag("invite_$id").assertDoesNotExist()
                        onNodeWithTag("rename_$id").performClick()
                        onNodeWithTag("rename_cancel").performClick()
                    }
                    onNodeWithTag("guest_actions_$test1").performClick()
                    onNodeWithTag("invite_$test1").assertTextEquals("Einladungslink senden").performClick()
                    onNodeWithText(
                        "Wer den Link öffnet, übernimmt alle Matches von Test1 und wird dein Freund. Schick ihn nur an die Person, die Test1 ist.",
                    ).assertExists()
                    onNodeWithTag("guest_invite_confirm").performClick()
                    waitForIdle()
                    assertEquals("create:claim_player:20160", port.calls[1])
                    val text = shared.single()
                    assertTrue(text.startsWith("Ich habe dich auf Advantly als „Test1“ eingetragen."), text)
                    assertTrue(text.endsWith(": https://advantly.eu/i/#$TOKEN"), text)
                    onNodeWithTag(
                        "guest_invite_open_$test1",
                        useUnmergedTree = true,
                    ).assertTextContains("Einladung offen bis", substring = true)
                    onNodeWithTag("guest_actions_$test1").performClick()
                    onNodeWithTag("revoke_invite_$test1").assertTextEquals("Einladung zurückziehen").performClick()
                    waitForIdle()
                    assertEquals("revoke:inv-$test1", port.calls.last())
                    onNodeWithTag("guest_invite_open_$test1", useUnmergedTree = true).assertDoesNotExist()
                }
            }
        }

    @Test fun withoutShareTheGuestInvitationIsHidden() =
        withLocale(Locale.ENGLISH) {
            val store = InMemoryMatchStore(selfName = "Alex")
            val test1 = store.addGuest("Test1").id.value
            rig(session = CloudSession("alex", true, consentStatus = ConsentStatus.CURRENT), links = mapOf(test1 to GuestLink())) {
                runDesktopComposeUiTest(width = 400, height = 900) {
                    setContent {
                        AdvantlyTheme(
                            darkTheme = true,
                        ) { Column { GuestList(store, friends = friends, invitations = invitations) } }
                    }
                    onNodeWithTag("guest_actions_$test1").performClick()
                    onNodeWithTag("invite_$test1").assertDoesNotExist()
                    onNodeWithTag("link_$test1").assertExists()
                }
            }
        }
}
