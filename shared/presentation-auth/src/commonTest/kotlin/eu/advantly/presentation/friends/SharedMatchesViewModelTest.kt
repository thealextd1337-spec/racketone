package eu.advantly.presentation.friends

import eu.advantly.cloud.sync.BlockedProfile
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.FoundProfile
import eu.advantly.cloud.sync.FriendRequestResult
import eu.advantly.cloud.sync.FriendsPort
import eu.advantly.cloud.sync.PulledMatch
import eu.advantly.cloud.sync.PulledParticipant
import eu.advantly.cloud.sync.SharedMatchesPort
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.presentation.auth.FakePhoneAuth
import eu.advantly.presentation.auth.PhonePullPort
import eu.advantly.presentation.auth.PhonePullState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object NoFriends : FriendsPort {
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

private class FakeShared : SharedMatchesPort {
    val calls = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var failure: String? = null
    var shareDefault = false

    private suspend fun call(name: String) {
        calls += name
        gate?.await()
        failure?.let { throw CloudException(it, true) }
    }

    // Das erwartete Konto steht vorn in jedem Aufruf: der Port bindet daran die Lease.
    override suspend fun setMyParticipation(
        profileId: String,
        matchId: String,
        shareWithFriends: Boolean?,
        hidden: Boolean?,
    ) = call("$profileId:participation:$matchId:$shareWithFriends:$hidden")

    override suspend fun rejectMatchParticipation(
        profileId: String,
        matchId: String,
    ) = call("$profileId:reject:$matchId")

    override suspend fun shareMatchesDefault(profileId: String): Boolean {
        call("$profileId:default")
        return shareDefault
    }

    override suspend fun setShareMatchesDefault(
        profileId: String,
        share: Boolean,
    ) {
        call("$profileId:default:$share")
        shareDefault = share
    }
}

private class FakeLocal : SharedMatchesLocal {
    val feeds = mutableMapOf<String, List<PulledMatch>>()
    val matches = mutableMapOf<Pair<String, String>, PulledMatch>()
    val notes = mutableMapOf<Pair<String, String>, Map<String, String>>()

    override fun feed(profileId: String) = feeds[profileId].orEmpty()

    override fun match(
        profileId: String,
        matchId: String,
    ) = matches[profileId to matchId]

    override fun linkNotes(
        profileId: String,
        matchId: String,
    ) = notes[profileId to matchId].orEmpty()
}

private class Pull : PhonePullPort {
    override val state = MutableStateFlow(PhonePullState(available = true))
    var started = 0

    override fun pullNow() {
        if (state.value.busy) return
        started++
        state.value = state.value.copy(busy = true, metadataComplete = false)
    }

    fun finish() {
        state.value = state.value.copy(busy = false, metadataComplete = true)
    }

    override fun close(onDrained: () -> Unit) = onDrained()
}

private fun participant(
    slot: String,
    team: String,
    name: String,
    profileId: String? = null,
    link: String? = null,
    share: Boolean? = null,
) = PulledParticipant(slot, team, 1, profileId = profileId, linkStatus = link, displayName = name, shareWithFriends = share)

private fun match(
    id: String,
    access: String,
    vararg participants: PulledParticipant,
    summary: JsonObject? = null,
) = PulledMatch(
    id = id,
    access = access,
    createdBy = "creator-id",
    sport = "tennis",
    discipline = "singles",
    format = JsonObject(emptyMap()),
    meta = JsonObject(emptyMap()),
    startedAt = "2026-10-26T09:00:00Z",
    status = "finished",
    winner = "B",
    summary = summary,
    serverVersion = 1,
    participants = participants.toList(),
)

class SharedMatchesViewModelTest {
    private class Fixture(
        val model: FriendsViewModel,
        val auth: FakePhoneAuth,
        val shared: FakeShared,
        val local: FakeLocal,
        val pull: Pull,
    ) {
        val state get() = model.state.value
    }

    private fun alice(generation: Long = 1) = CloudSession("alice", true, generation = generation, consentStatus = ConsentStatus.CURRENT)

    private fun bob() = CloudSession("bob", true, generation = 2, consentStatus = ConsentStatus.CURRENT)

    /** SDK-Konto, das [CloudAuthPort.requireAccountSession] prüft; hinkt der Sitzungs-Flow nach, weicht es ab. */
    private var sdkProfile: String? = null

    private fun fixture(test: Fixture.() -> Unit) {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val auth = FakePhoneAuth().apply { session.value = alice() }
        val checked =
            object : CloudAuthPort by auth {
                override fun requireAccountSession(expected: CloudSession) {
                    sdkProfile?.let { if (it != expected.profileId) throw CloudException("cloud_session_changed", true) }
                    auth.requireAccountSession(expected)
                }
            }
        val shared = FakeShared()
        val pull = Pull()
        val local =
            FakeLocal().apply {
                feeds["alice"] =
                    listOf(
                        match(
                            "feed-1",
                            "feed",
                            participant("s1", "A", "Carla", profileId = "carla-id"),
                            participant("s2", "B", ""),
                            summary = Json.parseToJsonElement("""{"sets":[[6,4],[3,6]]}""") as JsonObject,
                        ),
                    )
                feeds["bob"] = listOf(match("feed-b", "feed", participant("s1", "A", "Dora"), participant("s2", "B", "Eve")))
                matches["alice" to "own"] =
                    match("own", "member", participant("me", "A", "Alice", "alice", "self", share = false), participant("x", "B", "Bob"))
                matches["alice" to "linked"] =
                    match(
                        "linked",
                        "member",
                        participant("c", "A", "Carla", "carla-id", "self"),
                        participant("me", "B", "Alice", "alice", "linked"),
                    )
                matches["bob" to "own"] = match("own", "member", participant("b", "A", "Bob", "bob", "self", share = true))
                notes["alice" to "own"] = mapOf("x" to "not_friend")
            }
        val model = FriendsViewModel(checked, NoFriends, { emptyList() }, pull, scope, shared, local)
        try {
            Fixture(model, auth, shared, local, pull).test()
        } finally {
            model.close()
            scope.cancel()
        }
    }

    @Test fun feedShowsOnlyServerSanitizedFieldsOfTheCurrentAccount() =
        fixture {
            val entry = state.feed.single()
            assertEquals(
                FeedMatch(
                    "feed-1",
                    Sport.TENNIS,
                    doubles = false,
                    status = "finished",
                    winner = Side.B,
                    resultReason = null,
                    startedAt = "2026-10-26T09:00:00Z",
                    endedAt = null,
                    teamA = listOf("Carla"),
                    teamB = listOf(""),
                    sets = listOf(6 to 4, 3 to 6),
                ),
                entry,
            )
            // Keine Profil-, Ersteller- oder Geräte-IDs im Präsentationsmodell.
            assertFalse(entry.toString().contains("carla-id") || entry.toString().contains("creator-id"))
            auth.session.value = bob()
            assertEquals(listOf("feed-b"), state.feed.map { it.id })
            auth.session.value = null
            assertTrue(state.feed.isEmpty())
            auth.session.value = alice(generation = 3)
            assertEquals(listOf("feed-1"), state.feed.map { it.id })
            auth.session.value = alice(generation = 3).copy(consentStatus = ConsentStatus.DECLINED)
            assertTrue(state.feed.isEmpty())
        }

    @Test fun ownMatchSharingToggleOverlaysUntilAFreshPullDelivers() =
        fixture {
            model.openMatch("own")
            assertEquals(MatchSharing("own", shared = false, linkNotes = mapOf("x" to "not_friend")), state.match)
            model.setShared(true)
            assertEquals("alice:participation:own:true:null", shared.calls.single())
            assertEquals(true, state.match?.shared)
            assertEquals(1, pull.started)
            // Der Pull liefert den Serverstand: ab jetzt gilt wieder der lokale Snapshot.
            local.matches["alice" to "own"] =
                match("own", "member", participant("me", "A", "Alice", "alice", "self", share = true))
            pull.finish()
            assertEquals(true, state.match?.shared)
            model.openMatch("unknown")
            assertEquals(MatchSharing("unknown"), state.match)
            model.setShared(true)
            model.hideMatch()
            model.rejectParticipation()
            assertEquals(1, shared.calls.size)
            model.openMatch(null)
            assertNull(state.match)
        }

    @Test fun linkedMatchOffersRejectAndHideAndThenIsGone() =
        fixture {
            model.openMatch("linked")
            assertEquals(MatchSharing("linked", shared = false, canReject = true, canHide = true), state.match)
            model.rejectParticipation()
            assertEquals("alice:reject:linked", shared.calls.single())
            assertEquals(MatchSharing("linked", gone = true), state.match)
            assertEquals(1, pull.started)
            model.openMatch("own")
            model.hideMatch()
            assertEquals(1, shared.calls.size)
        }

    @Test fun lateAnswerForAIsDiscardedAfterSwitchToBAndBackToA() =
        fixture {
            model.openMatch("own")
            shared.gate = CompletableDeferred()
            model.setShared(true)
            assertTrue(state.busy)
            auth.session.value = bob()
            assertFalse(state.busy)
            // B sieht die eigene Freigabe, nie die Überlagerung von A.
            assertEquals(MatchSharing("own", shared = true), state.match)
            shared.gate!!.complete(Unit)
            assertEquals(MatchSharing("own", shared = true), state.match)
            assertNull(state.error)
            auth.session.value = alice(generation = 3)
            assertEquals(false, state.match?.shared)
            assertEquals(0, pull.started)
        }

    @Test fun actionIsNotSentWhenTheSdkAlreadyCarriesAnotherAccount() =
        fixture {
            model.openMatch("linked")
            sdkProfile = "bob"
            model.rejectParticipation()
            assertTrue(shared.calls.isEmpty())
            assertEquals("cloud_session_changed", state.error)
            assertFalse(state.match?.gone == true)
        }

    @Test fun shareDefaultLoadsSavesShowsErrorsAndIsDroppedOnLogout() =
        fixture {
            model.loadShareDefault()
            assertEquals(false, state.shareDefault)
            model.setShareDefault(true)
            assertEquals(listOf("alice:default", "alice:default:true"), shared.calls)
            assertEquals(true, state.shareDefault)
            shared.failure = "no_participation"
            model.openMatch("own")
            model.setShared(true)
            assertEquals("no_participation", state.error)
            assertEquals(false, state.match?.shared)
            auth.session.value = null
            assertNull(state.shareDefault)
            assertNull(state.match)
        }

    @Test fun setsAreReadFromFeedArraysAndMemberObjectsWithServerBounds() {
        fun sets(json: String) = match("m", "feed", summary = Json.parseToJsonElement(json) as JsonObject).toFeed().sets
        assertEquals(listOf(6 to 4, 3 to 6), sets("""{"sets":[[6,4],[3,6]]}"""))
        // Mitglieder: rohe Client-Summary mit Objekten (Tiebreak-Felder werden ignoriert).
        assertEquals(listOf(7 to 6, 2 to 6), sets("""{"sets":[{"a":7,"b":6,"tiebreakA":7,"tiebreakB":3},{"a":2,"b":6}]}"""))
        // Wie der Server: ein ungültiger Satz verwirft alle; höchstens 9 Sätze; Werte 0..99, keine Texte.
        assertEquals(emptyList(), sets("""{"sets":[[6,4],"x"]}"""))
        assertEquals(emptyList(), sets("""{"sets":[[6,4],[100,1]]}"""))
        assertEquals(emptyList(), sets("""{"sets":[{"a":"6","b":4}]}"""))
        assertEquals(emptyList(), sets("""{"sets":[${List(10) { "[1,0]" }.joinToString(",")}]}"""))
        assertEquals(emptyList(), sets("""{"games":[1,0]}"""))
    }

    @Test fun hidingAlsoEndsSharingSoFriendsNoLongerSeeItInTheirFeed() =
        fixture {
            model.openMatch("linked")
            model.hideMatch()
            assertEquals("alice:participation:linked:false:true", shared.calls.single())
            assertEquals(MatchSharing("linked", gone = true), state.match)
        }

    @Test fun shareDefaultErrorsStaySeparateFromOtherFriendsErrors() =
        fixture {
            shared.failure = "connection_failed"
            model.loadShareDefault()
            assertEquals("connection_failed", state.shareDefaultError)
            assertNull(state.error)
            model.openMatch("own")
            model.setShared(true)
            assertEquals("connection_failed", state.error)
            assertNull(state.shareDefaultError)
            shared.failure = null
            model.loadShareDefault()
            shared.failure = "rate_limit_exceeded"
            model.setShareDefault(true)
            assertEquals("rate_limit_exceeded", state.shareDefaultError)
            assertEquals(false, state.shareDefault)
            model.dismissError()
            assertNull(state.shareDefaultError)
        }
}
