package eu.advantly.presentation.friends

import eu.advantly.cloud.sync.BlockedProfile
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.FoundProfile
import eu.advantly.cloud.sync.FriendRequestResult
import eu.advantly.cloud.sync.FriendsPort
import eu.advantly.cloud.sync.PulledFriendship
import eu.advantly.presentation.auth.FakePhoneAuth
import eu.advantly.presentation.auth.PhonePullPort
import eu.advantly.presentation.auth.PhonePullResult
import eu.advantly.presentation.auth.PhonePullState
import kotlinx.coroutines.CompletableDeferred
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

private class FakeFriends : FriendsPort {
    val calls = mutableListOf<String>()
    var failure: String? = null
    var gate: CompletableDeferred<Unit>? = null
    var code = "K7QF92MX"
    var found: FoundProfile? = FoundProfile("bob", "Bob", "bob_99")
    var requestResult = FriendRequestResult.REQUESTED
    val blocked = mutableListOf<BlockedProfile>()

    private suspend fun call(name: String) {
        calls += name
        gate?.await()
        failure?.let { throw CloudException(it, true) }
    }

    override suspend fun friendCode(): String {
        call("code")
        return code
    }

    var profileFailure: String? = null

    override suspend fun ownProfile(): FoundProfile {
        profileFailure?.let { throw CloudException(it, false) }
        return FoundProfile("alice", "Alice", "alice_1")
    }

    override suspend fun findProfile(query: String): FoundProfile? {
        call("find:$query")
        return found
    }

    override suspend fun sendFriendRequest(
        profileId: String,
        viaCode: Boolean,
    ): FriendRequestResult {
        call("send:$profileId:$viaCode")
        return requestResult
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

    override suspend fun blockedProfiles(): List<BlockedProfile> {
        call("blocked")
        return blocked.toList()
    }
}

private class FakePull : PhonePullPort {
    override val state = MutableStateFlow(PhonePullState(available = true))
    var started = 0

    // Wie der Host: ein laufender Pull wird nicht doppelt gestartet.
    override fun pullNow() {
        if (state.value.busy) return
        started++
        state.value = state.value.copy(busy = true, result = null, metadataComplete = false)
    }

    fun finish(
        result: PhonePullResult = PhonePullResult.COMPLETE,
        metadataComplete: Boolean = result == PhonePullResult.COMPLETE,
    ) {
        state.value = state.value.copy(busy = false, result = result, metadataComplete = metadataComplete)
    }

    override fun close(onDrained: () -> Unit) = onDrained()
}

class FriendsViewModelTest {
    private class Fixture(
        val model: FriendsViewModel,
        val auth: FakePhoneAuth,
        val friends: FakeFriends,
        val pull: FakePull,
        val local: MutableMap<String, List<PulledFriendship>>,
    ) {
        val state get() = model.state.value
    }

    private fun alice(generation: Long = 1) = CloudSession("alice", true, generation = generation, consentStatus = ConsentStatus.CURRENT)

    private fun fixture(test: Fixture.() -> Unit) {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val auth = FakePhoneAuth().apply { session.value = alice() }
        val local =
            mutableMapOf(
                "alice" to
                    listOf(
                        PulledFriendship("zed", "zed", "accepted", outgoing = false, serverVersion = 1),
                        PulledFriendship("anna", "Anna", "accepted", outgoing = true, serverVersion = 2),
                        PulledFriendship("carl", "Carl", "pending", outgoing = false, serverVersion = 3),
                        PulledFriendship("dora", "Dora", "pending", outgoing = true, serverVersion = 4),
                        PulledFriendship("emil", "Emil", "declined", outgoing = true, serverVersion = 5),
                    ),
                "bob" to listOf(PulledFriendship("frank", "Frank", "accepted", outgoing = true, serverVersion = 1)),
            )
        val friends = FakeFriends()
        val pull = FakePull()
        val model = FriendsViewModel(auth, friends, { local[it].orEmpty() }, pull, scope)
        try {
            Fixture(model, auth, friends, pull, local).test()
        } finally {
            model.close()
            scope.cancel()
        }
    }

    private fun List<FriendEntry>.ids() = map { it.profileId }

    @Test fun accessFollowsAccountStateAndHidesDataUnlessReady() =
        fixture {
            assertEquals(FriendsAccess.READY, state.access)
            assertEquals(listOf("anna", "zed"), state.friends.ids())
            assertEquals(listOf("carl"), state.incoming.ids())
            assertEquals(listOf("dora"), state.outgoing.ids())
            val cases =
                listOf(
                    null to FriendsAccess.SIGNED_OUT,
                    alice().copy(onboarded = false) to FriendsAccess.SETUP_REQUIRED,
                    alice().copy(consentStatus = ConsentStatus.DECLINED) to FriendsAccess.CONSENT_REQUIRED,
                    alice().copy(deletionRequested = true) to FriendsAccess.PAUSED,
                    alice().copy(suspended = true) to FriendsAccess.PAUSED,
                )
            for ((session, expected) in cases) {
                auth.session.value = session
                assertEquals(expected, state.access)
                assertTrue(state.friends.isEmpty() && state.incoming.isEmpty() && state.outgoing.isEmpty())
                model.open()
                assertNull(state.friendCode)
            }
            assertTrue(friends.calls.isEmpty())
        }

    @Test fun openSearchAndSendRequestShowOutgoingImmediatelyAndPull() =
        fixture {
            model.open()
            assertEquals("K7QF92MX", state.friendCode)
            assertEquals(FoundProfile("alice", "Alice", "alice_1"), state.me, "Profilkopf: Alias und Benutzername")
            model.open()
            assertEquals(1, friends.calls.count { it == "code" })
            model.search("  ")
            model.search(" bob_99 ")
            assertEquals(FoundProfile("bob", "Bob", "bob_99"), state.found)
            model.sendRequest(" BOB_99 ")
            assertEquals("send:bob:false", friends.calls.last())
            assertEquals(FriendRequestResult.REQUESTED, state.requestResult)
            assertNull(state.found)
            assertEquals(listOf("bob", "dora"), state.outgoing.ids())
            assertEquals(1, pull.started)
            friends.found = null
            model.search("K7QF-92MX")
            assertTrue(state.notFound)
            friends.found = FoundProfile("gina", "Gina", null)
            model.search("K7QF-92MX")
            friends.requestResult = FriendRequestResult.ACCEPTED
            model.sendRequest("K7QF-92MX")
            assertEquals("send:gina:true", friends.calls.last())
            assertTrue("gina" in state.friends.ids())
        }

    @Test fun friendCodeShowsEvenIfOwnProfileFailsAndProfileIsRetriedLater() =
        fixture {
            friends.profileFailure = "connection_failed"
            model.open()
            assertEquals("K7QF92MX", state.friendCode)
            assertNull(state.me)
            assertNull(state.error, "der Code ist kein Fehlerfall")
            friends.profileFailure = null
            model.open()
            assertEquals(FoundProfile("alice", "Alice", "alice_1"), state.me)
            assertEquals(1, friends.calls.count { it == "code" }, "Code wird nicht erneut geladen")
        }

    @Test fun errorsAreShownAndMissingBindingTriggersPullThenCodeReload() =
        fixture {
            friends.failure = "rate_limit_exceeded"
            model.search("bob_99")
            assertEquals("rate_limit_exceeded", state.error)
            assertFalse(state.busy)
            model.dismissError()
            friends.failure = "device_not_authorized"
            model.open()
            assertEquals("device_not_authorized", state.error)
            assertEquals(1, pull.started)
            friends.failure = null
            pull.finish()
            assertEquals("K7QF92MX", state.friendCode)
        }

    @Test fun lateAnswerForAccountAIsDiscardedAfterSwitchToB() =
        fixture {
            friends.gate = CompletableDeferred()
            model.search("bob_99")
            assertTrue(state.busy)
            auth.session.value = CloudSession("bob", true, generation = 2, consentStatus = ConsentStatus.CURRENT)
            assertFalse(state.busy)
            assertEquals(listOf("frank"), state.friends.ids())
            friends.gate!!.complete(Unit)
            assertNull(state.found)
            assertFalse(state.notFound)
            assertNull(state.error)
            // A → B → A mit neuer Generation: frühere Antworten bleiben verworfen.
            friends.gate = CompletableDeferred()
            model.open()
            auth.session.value = alice(generation = 3)
            friends.gate!!.complete(Unit)
            assertNull(state.friendCode)
            assertEquals(listOf("anna", "zed"), state.friends.ids())
        }

    @Test fun logoutDuringRequestDropsOverlayAndAnswer() =
        fixture {
            model.search("bob_99")
            friends.gate = CompletableDeferred()
            model.sendRequest("bob_99")
            auth.session.value = null
            friends.gate!!.complete(Unit)
            assertEquals(FriendsState(access = FriendsAccess.SIGNED_OUT), state)
            auth.session.value = alice(generation = 2)
            assertEquals(listOf("dora"), state.outgoing.ids())
        }

    @Test fun respondRemoveBlockAndUnblockUpdateListsUntilPullDelivers() =
        fixture {
            model.respond("carl", accept = true)
            assertTrue(state.incoming.isEmpty())
            assertEquals(listOf("anna", "carl", "zed"), state.friends.ids())
            // Laufender Pull begann vor der Änderung: nach seinem Ende einen neuen starten, Überlagerung behalten.
            model.remove("zed")
            assertEquals(listOf("anna", "carl"), state.friends.ids())
            assertEquals(1, pull.started)
            pull.finish()
            assertEquals(2, pull.started)
            assertEquals(listOf("anna", "carl"), state.friends.ids())
            // Der neue Pull liefert den Serverstand; die lokale Liste ist ab jetzt maßgeblich.
            local["alice"] = listOf(PulledFriendship("carl", "Carl", "accepted", outgoing = false, serverVersion = 9))
            pull.finish()
            assertEquals(listOf("carl"), state.friends.ids())

            model.showBlocked()
            assertEquals(emptyList(), state.blocked)
            model.block("carl")
            assertTrue(state.friends.isEmpty())
            assertEquals(listOf("carl"), state.blocked!!.map { it.profileId })
            model.unblock("carl")
            assertEquals(emptyList(), state.blocked)
            model.respond("dora", accept = false)
            assertEquals(
                listOf(
                    "respond:carl:true",
                    "remove:zed",
                    // Vollständige Metadaten ohne Code: automatischer Nachladeversuch.
                    "code",
                    "blocked",
                    "block:carl",
                    "blocked",
                    "unblock:carl",
                    "blocked",
                    "respond:dora:false",
                ),
                friends.calls,
            )
        }

    @Test fun failedPullKeepsConfirmedChanges() =
        fixture {
            model.remove("zed")
            pull.finish(PhonePullResult.CONNECTION_FAILED)
            assertEquals(listOf("anna"), state.friends.ids())
        }

    @Test fun blockedOrFailedPullWithCompleteMetadataReplacesOverlay() =
        fixture {
            model.remove("zed")
            // Metadaten unvollständig: die bestätigte Änderung bleibt sichtbar.
            pull.finish(PhonePullResult.BLOCKED, metadataComplete = false)
            assertEquals(listOf("anna"), state.friends.ids())
            // Ausstehende Redaktionen o. Ä. machen das Ergebnis FAILED/BLOCKED, Freundschaften sind aber vollständig.
            local["alice"] = local.getValue("alice").filter { it.profileId != "zed" } +
                PulledFriendship("hugo", "Hugo", "pending", outgoing = false, serverVersion = 9)
            model.respond("carl", accept = true)
            pull.finish(PhonePullResult.FAILED, metadataComplete = true)
            // Überlagerung verworfen: der Server kennt Carls Annahme hier (noch) nicht, die lokale Liste gilt.
            assertEquals(listOf("carl", "hugo"), state.incoming.ids())
            assertEquals(listOf("anna"), state.friends.ids())
        }

    @Test fun openingTheTabStartsAPullAndCodeFailureCanBeRetried() =
        fixture {
            friends.failure = "connection_failed"
            model.open()
            assertEquals(1, pull.started)
            assertEquals("connection_failed", state.error)
            assertNull(state.friendCode)
            friends.failure = null
            model.loadCode()
            assertEquals("K7QF92MX", state.friendCode)
            // Ohne Fehler nach dem nächsten vollständigen Pull automatisch.
            friends.failure = "connection_failed"
            auth.session.value = alice(generation = 2)
            model.open()
            friends.failure = null
            pull.finish()
            assertEquals("K7QF92MX", state.friendCode)
            assertEquals(4, friends.calls.count { it == "code" })
        }

    @Test fun laggingAccountFlowNeverSendsUnderTheNewSdkAccount() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val fake = FakePhoneAuth().apply { session.value = alice() }
        // SDK steht schon auf Bob, der Sitzungs-Flow zeigt noch Alice.
        var sdkProfile = "alice"
        val auth =
            object : eu.advantly.cloud.sync.CloudAuthPort by fake {
                override fun requireAccountSession(expected: CloudSession) {
                    if (expected.profileId != sdkProfile) throw CloudException("cloud_session_changed", true)
                }
            }
        val friends = FakeFriends()
        val model = FriendsViewModel(auth, friends, { emptyList() }, null, scope)
        try {
            sdkProfile = "bob"
            model.block("zed")
            model.remove("zed")
            model.search("bob_99")
            model.loadCode()
            assertTrue(friends.calls.isEmpty())
            assertEquals("cloud_session_changed", model.state.value.error)
            assertFalse(model.state.value.busy)
        } finally {
            model.close()
            scope.cancel()
        }
    }
}
