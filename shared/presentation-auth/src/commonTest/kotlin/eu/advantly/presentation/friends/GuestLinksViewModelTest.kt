package eu.advantly.presentation.friends

import eu.advantly.cloud.sync.BlockedProfile
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.FoundProfile
import eu.advantly.cloud.sync.FriendRequestResult
import eu.advantly.cloud.sync.FriendsPort
import eu.advantly.cloud.sync.GuestLinksPort
import eu.advantly.cloud.sync.PulledMatch
import eu.advantly.cloud.sync.PulledParticipant
import eu.advantly.presentation.auth.FakePhoneAuth
import eu.advantly.presentation.auth.PhonePullPort
import eu.advantly.presentation.auth.PhonePullState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object Friendless : FriendsPort {
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

private class FakeGuests : GuestLinksPort {
    val calls = mutableListOf<String>()
    var failure: String? = null

    private fun call(name: String) {
        calls += name
        failure?.let { throw CloudException(it, true) }
    }

    override suspend fun assignGuest(
        profileId: String,
        playerId: String,
        friendId: String,
    ): Int {
        call("$profileId:assign:$playerId:$friendId")
        return 3
    }

    override suspend fun unassignGuest(
        profileId: String,
        playerId: String,
    ) = call("$profileId:unassign:$playerId")

    override suspend fun rejectGuestAssignment(
        profileId: String,
        playerId: String,
    ) = call("$profileId:reject:$playerId")
}

private class GuestLocal : SharedMatchesLocal {
    val links = mutableMapOf<String, GuestLink>()
    var assigned: PulledMatch? = null

    override fun feed(profileId: String) = emptyList<PulledMatch>()

    override fun match(
        profileId: String,
        matchId: String,
    ) = assigned?.takeIf { profileId == "anja" && it.id == matchId }

    override fun linkNotes(
        profileId: String,
        matchId: String,
    ) = emptyMap<String, String>()

    override fun guestLinks(profileId: String) = if (profileId == "alice") links.toMap() else emptyMap()
}

private class GuestPull : PhonePullPort {
    override val state = MutableStateFlow(PhonePullState(available = true))
    var started = 0

    override fun pullNow() {
        started++
        state.value = state.value.copy(busy = true, metadataComplete = false)
    }

    fun finish() {
        state.value = state.value.copy(busy = false, metadataComplete = true)
    }

    override fun close(onDrained: () -> Unit) = onDrained()
}

/** plan-0.6.3 §3.5/§3.6: Gast mit Freund verknüpfen, Verknüpfung lösen, „Das war ich nicht“, Fehler und Redaktion. */
class GuestLinksViewModelTest {
    private fun session(profile: String) = CloudSession(profile, true, consentStatus = ConsentStatus.CURRENT)

    private fun fixture(
        profile: String = "alice",
        test: (FriendsViewModel, FakeGuests, GuestLocal, GuestPull, FakePhoneAuth) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val auth = FakePhoneAuth().apply { session.value = session(profile) }
        val guests = FakeGuests()
        val pull = GuestPull()
        val local =
            GuestLocal().apply {
                links["test1"] = GuestLink()
                links["gone"] = GuestLink(redacted = true)
            }
        val model = FriendsViewModel(auth, Friendless, { emptyList() }, pull, scope, local = local, guests = guests)
        try {
            test(model, guests, local, pull, auth)
        } finally {
            model.close()
            scope.cancel()
        }
    }

    @Test fun linkShowsTheMatchCountAndTheLinkUntilThePullDeliversThenUnlinks() =
        fixture { model, guests, local, pull, _ ->
            model.linkGuest("test1", "anja")
            assertEquals(listOf("alice:assign:test1:anja"), guests.calls)
            assertEquals(GuestLinked("test1", "anja", 3), model.state.value.guestLinked)
            assertEquals(GuestLink("anja"), model.state.value.guestLinks["test1"])
            assertEquals(1, pull.started)
            // Schon verknüpft: kein zweiter Aufruf.
            model.linkGuest("test1", "bob")
            assertEquals(1, guests.calls.size)
            model.dismissGuestLinked()
            assertNull(model.state.value.guestLinked)
            // Der Pull bringt den Server-Stand: die Überlagerung entfällt.
            local.links["test1"] = GuestLink("anja")
            pull.finish()
            assertEquals(GuestLink("anja"), model.state.value.guestLinks["test1"])
            model.unlinkGuest("test1")
            assertEquals("alice:unassign:test1", guests.calls.last())
            assertEquals(GuestLink(), model.state.value.guestLinks["test1"])
        }

    @Test fun redactedOrUnknownGuestsCannotBeLinked() =
        fixture { model, guests, local, pull, _ ->
            model.linkGuest("gone", "anja")
            model.linkGuest("not-uploaded", "anja")
            model.unlinkGuest("gone")
            assertTrue(guests.calls.isEmpty())
            // Freund löscht sein Konto nach dem Verknüpfen: redigiert schlägt die noch offene Bestätigung.
            model.linkGuest("test1", "anja")
            local.links["test1"] = GuestLink(redacted = true)
            pull.state.value = pull.state.value.copy(busy = false)
            assertEquals(GuestLink(redacted = true), model.state.value.guestLinks["test1"])
        }

    @Test fun failuresLandInTheGuestErrorOnly() =
        fixture { model, guests, _, _, _ ->
            for (reason in listOf("assignee_must_be_friend", "already_assigned", "assignment_rejected_before", "cloud_connection_failed")) {
                guests.failure = reason
                model.linkGuest("test1", "anja")
                assertEquals(reason, model.state.value.guestError)
                assertNull(model.state.value.error)
                assertNull(model.state.value.guestLinked)
                assertEquals(GuestLink(), model.state.value.guestLinks["test1"])
            }
            model.dismissError()
            assertNull(model.state.value.guestError)
        }

    @Test fun withoutCurrentConsentNothingIsOffered() =
        fixture { model, guests, _, _, auth ->
            auth.session.value = session("alice").copy(consentStatus = ConsentStatus.DECLINED)
            assertTrue(
                model.state.value.guestLinks
                    .isEmpty(),
            )
            model.linkGuest("test1", "anja")
            auth.session.value = null
            assertTrue(
                model.state.value.guestLinks
                    .isEmpty(),
            )
            assertTrue(guests.calls.isEmpty())
        }

    @Test fun theFriendRejectsAnAssignedGuestFromTheMatchDetail() =
        fixture("anja") { model, guests, local, _, _ ->
            local.assigned =
                PulledMatch(
                    id = "m1",
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
                            PulledParticipant("s2", "B", 1, "test1", "anja", "assigned", "Anja"),
                        ),
                )
            model.openMatch("m1")
            assertTrue(
                model.state.value.match!!
                    .canRejectAssignment,
            )
            assertFalse(
                model.state.value.match!!
                    .canReject,
            )
            model.rejectAssignment()
            assertEquals(listOf("anja:reject:test1"), guests.calls)
            assertTrue(
                model.state.value.match!!
                    .gone,
            )
        }
}
