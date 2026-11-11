package eu.advantly.presentation.friends

import androidx.lifecycle.SavedStateHandle
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.InviteKind
import eu.advantly.cloud.sync.InvitePreview
import eu.advantly.cloud.sync.InviteRedemption
import eu.advantly.cloud.sync.InviteStatus
import eu.advantly.cloud.sync.InvitesPort
import eu.advantly.cloud.sync.OpenInvite
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.FakePhoneAuth
import eu.advantly.presentation.auth.PhonePullPort
import eu.advantly.presentation.auth.PhonePullState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TOKEN = "7f".repeat(32)
private val LINK = "https://advantly.eu/i/#$TOKEN"
private val OTHER = "a1".repeat(32)

private class FakeInvites : InvitesPort {
    val calls = mutableListOf<String>()
    var failure: String? = null
    var redeemFailure: String? = null
    var preview = InvitePreview(InviteStatus.OK, InviteKind.FRIEND, "alex", "Alex")
    var redemption = InviteRedemption(InviteKind.FRIEND, "alex")
    var open = emptyList<OpenInvite>()
    var revokeFailure: String? = null
    private var created = 0

    private fun call(name: String) {
        calls += name
        failure?.let { throw CloudException(it, false) }
    }

    override suspend fun createInvite(
        profileId: String,
        kind: InviteKind,
        playerId: String?,
        ttlMinutes: Int,
    ): String {
        call("$profileId:create:${kind.wire}:$playerId:$ttlMinutes")
        created++
        return created.toString(16).padStart(64, '0')
    }

    override suspend fun previewInvite(
        profileId: String,
        token: String,
    ): InvitePreview {
        call("$profileId:preview:${token.take(4)}")
        return preview
    }

    override suspend fun redeemInvite(
        profileId: String,
        token: String,
    ): InviteRedemption {
        call("$profileId:redeem:${token.take(4)}")
        redeemFailure?.let { throw CloudException(it, false) }
        return redemption
    }

    override suspend fun revokeInvite(
        profileId: String,
        inviteId: String,
    ) {
        call("$profileId:revoke:$inviteId")
        revokeFailure?.let { throw CloudException(it, false) }
    }

    override suspend fun openInvites(profileId: String): List<OpenInvite> {
        call("$profileId:open")
        return open
    }
}

private class InvitePull : PhonePullPort {
    override val state = MutableStateFlow(PhonePullState(available = true))
    var started = 0

    override fun pullNow() {
        started++
    }

    override fun close(onDrained: () -> Unit) = onDrained()
}

/** plan-einladungen §3.4/§6 „ViewModel-Tests“: Lebenszyklus des wartenden Links, Vorschau, Einlösen, QR, Gäste-Links. */
class InvitationsViewModelTest {
    private fun ready(profile: String) = CloudSession(profile, true, consentStatus = ConsentStatus.CURRENT)

    private class Fixture(
        val auth: FakePhoneAuth,
        val port: FakeInvites,
        val pull: InvitePull,
        var saved: SavedStateHandle,
        val scope: CoroutineScope,
        var time: Long,
    ) {
        var model = create()

        fun create() = InvitationsViewModel(auth, port, saved, scope, { time }, pull)

        /** Prozessende: nur der SavedState (Bundle) bleibt, ViewModel und alles andere sind neu. */
        fun processDeath() {
            model.close()
            saved = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            model = create()
        }

        val state get() = model.state.value
    }

    private fun fixture(
        session: CloudSession? = null,
        test: Fixture.() -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val auth = FakePhoneAuth().apply { this.session.value = session }
        val fixture = Fixture(auth, FakeInvites(), InvitePull(), SavedStateHandle(), scope, 1_000_000)
        try {
            fixture.test()
        } finally {
            fixture.model.close()
            scope.cancel()
        }
    }

    @Test fun linkWaitsThroughSignInOnboardingConsentAndProcessDeathThenIsRedeemedAndDiscarded() =
        fixture {
            assertTrue(model.receive("  $LINK "))
            assertTrue(state.pending)
            assertEquals(FriendsAccess.SIGNED_OUT, state.access)
            auth.session.value = CloudSession("anja", false)
            assertEquals(FriendsAccess.SETUP_REQUIRED, state.access)
            processDeath()
            assertTrue(state.pending, "SavedStateHandle stellt den Link wieder her")
            auth.session.value = CloudSession("anja", true, consentStatus = ConsentStatus.REQUIRED)
            assertEquals(FriendsAccess.CONSENT_REQUIRED, state.access)
            assertTrue(port.calls.isEmpty(), "ohne nutzbares Konto kein Netzaufruf")
            processDeath()
            auth.session.value = ready("anja")
            assertEquals(listOf("anja:preview:7f7f"), port.calls)
            assertEquals("Alex", state.preview?.inviterAlias)
            model.accept()
            assertEquals("anja:redeem:7f7f", port.calls.last())
            assertEquals(InviteResult(InviteKind.FRIEND, "Alex"), state.result)
            assertFalse(state.pending)
            assertNull(state.preview)
            assertTrue(saved.keys().isEmpty(), "Token verworfen")
            assertEquals(1, pull.started)
            processDeath()
            assertFalse(state.pending)
            assertEquals(1, port.calls.count { it.contains("preview") })
        }

    @Test fun claimPreviewAndResultCarryGuestAndMatchCount() =
        fixture(ready("anja")) {
            port.preview = InvitePreview(InviteStatus.OK, InviteKind.CLAIM_PLAYER, "alex", "Alex", "Test1", 7)
            port.redemption = InviteRedemption(InviteKind.CLAIM_PLAYER, "alex", "guest-1", 7)
            model.receive(TOKEN)
            assertEquals("Test1", state.preview?.guestName)
            model.accept()
            assertEquals(InviteResult(InviteKind.CLAIM_PLAYER, "Alex", 7), state.result)
            model.dismissResult()
            assertNull(state.result)
        }

    @Test fun declineDiscardsWithoutRedeeming() =
        fixture(ready("anja")) {
            model.receive(LINK)
            model.decline()
            assertFalse(state.pending)
            assertNull(state.preview)
            assertEquals(listOf("anja:preview:7f7f"), port.calls)
            assertTrue(saved.keys().isEmpty())
            model.accept()
            assertEquals(1, port.calls.size)
        }

    @Test fun foreignTextIsNoInvitation() =
        fixture(ready("anja")) {
            assertFalse(model.receive("https://evil.example/i/#$TOKEN"))
            assertFalse(model.receive("K7QF92MX"))
            assertFalse(state.pending)
            assertTrue(port.calls.isEmpty())
        }

    @Test fun finalStatusesDiscardTheLinkAlreadyFriendsAfterOk() =
        fixture(ready("anja")) {
            for (status in listOf(
                InviteStatus.INVALID,
                InviteStatus.EXPIRED,
                InviteStatus.USED,
                InviteStatus.REVOKED,
                InviteStatus.OWN,
                InviteStatus.ALREADY_CLAIMED,
            )) {
                port.preview = InvitePreview(status)
                model.receive(LINK)
                assertEquals(InviteProblem(status.wire), state.problem, status.wire)
                assertFalse(state.pending, status.wire)
                assertTrue(saved.keys().isEmpty(), status.wire)
                model.dismissProblem()
            }
            port.preview = InvitePreview(InviteStatus.ALREADY_FRIENDS, InviteKind.FRIEND, "alex", "Alex")
            model.receive(LINK)
            assertEquals(InviteStatus.ALREADY_FRIENDS, state.preview?.status)
            assertTrue(state.pending, "bleibt bis OK")
            model.accept()
            assertTrue(port.calls.none { it.contains("redeem") }, "schon befreundet: nichts einlösen")
            model.decline()
            assertFalse(state.pending)
        }

    @Test fun redeemFailuresAreFinalOrLeaveTheLinkWaiting() =
        fixture(ready("anja")) {
            port.preview = InvitePreview(InviteStatus.OK, InviteKind.CLAIM_PLAYER, "alex", "Alex", "Test1", 7)
            for ((code, status) in listOf(
                "invalid_invite" to "invalid",
                "own_invite" to "own",
                "already_assigned" to "already_claimed",
                "player_redacted" to "invalid",
            )) {
                port.redeemFailure = code
                model.receive(LINK)
                model.accept()
                assertEquals(InviteProblem(status, "Test1"), state.problem, code)
                assertFalse(state.pending, code)
            }
            for (code in listOf("rate_limit_exceeded", "cloud_connection_failed", "consent_required", "device_revoked")) {
                port.redeemFailure = code
                model.receive(LINK)
                model.accept()
                assertEquals(InviteProblem(code), state.problem, code)
                assertTrue(state.pending, code)
                assertTrue(saved.contains(InvitationsViewModel.KEY_TOKEN), code)
            }
            // Wieder online: erneut versuchen lädt die Vorschau, danach klappt das Einlösen.
            port.redeemFailure = null
            model.retry()
            assertNull(state.problem)
            assertEquals("Test1", state.preview?.guestName)
            model.accept()
            assertFalse(state.pending)
        }

    @Test fun offlinePreviewKeepsTheLinkUntilRetry() =
        fixture(ready("anja")) {
            port.failure = "cloud_connection_failed"
            model.receive(LINK)
            assertEquals(InviteProblem("cloud_connection_failed"), state.problem)
            assertTrue(state.pending)
            port.failure = "rate_limit_exceeded"
            model.retry()
            assertEquals(InviteProblem("rate_limit_exceeded"), state.problem)
            assertTrue(state.pending)
            port.failure = null
            model.retry()
            assertEquals(InviteStatus.OK, state.preview?.status)
        }

    @Test fun accountSwitchDiscardsTheLinkAlsoAcrossProcessDeath() =
        fixture(ready("anja")) {
            port.failure = "cloud_connection_failed"
            model.receive(LINK)
            assertTrue(state.pending)
            auth.session.value = ready("bob")
            assertFalse(state.pending)
            assertTrue(saved.keys().isEmpty())

            auth.session.value = ready("anja")
            model.receive(LINK)
            processDeath()
            auth.session.value = null
            assertTrue(state.pending, "eine verlorene Sitzung (offline) verwirft nichts")
            auth.session.value = ready("bob")
            assertFalse(state.pending, "Besitzer im SavedState: Kontowechsel über Prozessende")
        }

    /** Die UI merkt sich `received` über Sitzungen hinweg; fiele der Zähler zurück, bliebe der nächste Link unsichtbar. */
    @Test fun receivedKeepsCountingAcrossAccountSwitches() =
        fixture(ready("anja")) {
            port.failure = "cloud_connection_failed"
            model.receive(LINK)
            assertEquals(1, state.received)
            auth.session.value = ready("bob")
            assertEquals(1, state.received)
            model.receive(LINK)
            assertEquals(2, state.received)
        }

    @Test fun signOutAndAgeGateDiscardTheLink() =
        fixture {
            val auth = auth
            val account = AuthViewModel(auth, scope, invitations = model)
            model.receive(LINK)
            auth.failure = "age_below_minimum"
            account.checkAge("AT", "2016-01-01")
            assertFalse(state.pending)
            assertEquals(InviteProblem(InvitationsViewModel.NEEDS_ACCOUNT), state.problem)

            model.receive(LINK)
            auth.failure = "country_not_supported"
            account.checkAge("XX", "1990-01-01")
            assertFalse(state.pending)

            auth.failure = null
            port.failure = "cloud_connection_failed"
            auth.session.value = ready("anja")
            model.receive(LINK)
            assertTrue(state.pending)
            account.signOut()
            assertFalse(state.pending)
            assertNull(state.problem)
            assertTrue(saved.keys().isEmpty())
        }

    @Test fun qrRenewsOnlyOnRequestOrAfterARedemption() =
        fixture(ready("alex")) {
            model.onFriends(setOf("bob"))
            model.openQr()
            assertEquals(listOf("alex:create:friend:null:10"), port.calls)
            val first = checkNotNull(state.qr)
            assertEquals(1_000_000 + 10 * 60_000L, first.expiresAtMillis)
            assertTrue(first.link.startsWith(InviteLinks.PREFIX))
            assertFalse(first.toString().contains(first.link.takeLast(64)))
            // Zeit vergeht, auch über den Ablauf hinaus: kein Erneuern im Takt.
            time += 11 * 60_000L
            model.onFriends(setOf("bob"))
            assertEquals(1, port.calls.size)
            model.newQr()
            assertEquals(2, port.calls.size)
            assertEquals(time + 10 * 60_000L, state.qr?.expiresAtMillis)
            // Jemand hat den Code eingelöst (friend_accepted → Pull → neue Freundin): neuer Code.
            model.onFriends(setOf("bob", "anja"))
            assertEquals(3, port.calls.size)
            model.closeQr()
            assertNull(state.qr)
            model.onFriends(setOf("bob", "anja", "carl"))
            assertEquals(3, port.calls.size)
            // Link teilen: 14 Tage.
            model.shareLink()
            assertEquals("alex:create:friend:null:20160", port.calls.last())
            assertTrue(state.share != null)
            model.shareHandled()
            assertNull(state.share)
            port.failure = "too_many_open_invites"
            model.shareLink()
            assertEquals("too_many_open_invites", state.inviteError)
        }

    @Test fun guestLinksListReplaceAndRevoke() =
        fixture(ready("alex")) {
            port.open =
                listOf(
                    OpenInvite("i1", InviteKind.CLAIM_PLAYER, "test1", 2_000_000),
                    OpenInvite("i0", InviteKind.CLAIM_PLAYER, "old", 999_999),
                    OpenInvite("f1", InviteKind.FRIEND, null, 5_000_000),
                )
            model.loadGuestInvites()
            assertEquals(mapOf("test1" to GuestInvite(listOf("i1"), 2_000_000)), state.guestInvites)
            // Neuer Link ersetzt den alten (der Server hat ihn zurückgezogen): Liste neu gelesen, Teilen bereit.
            port.open = listOf(OpenInvite("i2", InviteKind.CLAIM_PLAYER, "test1", 3_000_000))
            model.sendGuestInvite("test1")
            assertEquals(listOf("alex:open", "alex:create:claim_player:test1:20160", "alex:open"), port.calls)
            assertEquals("test1", state.guestShare?.playerId)
            assertEquals(mapOf("test1" to GuestInvite(listOf("i2"), 3_000_000)), state.guestInvites)
            model.guestShareHandled()
            assertNull(state.guestShare)
            port.revokeFailure = "invite_not_found"
            model.revokeGuestInvite("test1")
            assertEquals("alex:revoke:i2", port.calls.last())
            assertEquals(emptyMap(), state.guestInvites)
            assertNull(state.guestError)
            port.failure = "rate_limit_exceeded"
            model.sendGuestInvite("test1")
            assertEquals("rate_limit_exceeded", state.guestError)
        }

    @Test fun friendLinksAreCountedAndRevokedAllAtOnceButGuestLinksStay() =
        fixture(ready("alex")) {
            port.open =
                listOf(
                    OpenInvite("f1", InviteKind.FRIEND, null, 5_000_000),
                    OpenInvite("f2", InviteKind.FRIEND, null, 1_100_000),
                    OpenInvite("old", InviteKind.FRIEND, null, 999_999),
                    OpenInvite("g1", InviteKind.CLAIM_PLAYER, "test1", 2_000_000),
                )
            model.loadFriendLinks()
            assertEquals(2, state.friendLinks)
            model.shareLink()
            assertEquals(3, state.friendLinks)
            // Grenze erreicht: „Alle zurückziehen“ löst sie; ein inzwischen benutzter Link gilt als erledigt.
            port.failure = "too_many_open_invites"
            model.shareLink()
            assertEquals("too_many_open_invites", state.inviteError)
            port.failure = null
            port.revokeFailure = "invite_not_found"
            port.calls.clear()
            model.revokeFriendLinks()
            assertEquals(listOf("alex:open", "alex:revoke:f1", "alex:revoke:f2"), port.calls)
            assertEquals(0, state.friendLinks)
            assertNull(state.inviteError)
            // Andere Fehler bleiben sichtbar, der Zähler bleibt.
            port.revokeFailure = "rate_limit_exceeded"
            model.loadFriendLinks()
            model.revokeFriendLinks()
            assertEquals("rate_limit_exceeded", state.inviteError)
            assertEquals(2, state.friendLinks)
        }

    @Test fun accountSwitchDropsOwnLinksAndLateAnswers() =
        fixture(ready("alex")) {
            model.openQr()
            port.open = listOf(OpenInvite("i1", InviteKind.CLAIM_PLAYER, "test1", 2_000_000))
            model.loadGuestInvites()
            auth.session.value = ready("bob")
            assertNull(state.qr)
            assertFalse(state.qrOpen)
            assertNull(state.guestInvites)
        }
}
