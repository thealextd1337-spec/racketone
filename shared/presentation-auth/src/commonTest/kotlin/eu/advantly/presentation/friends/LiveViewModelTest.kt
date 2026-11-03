package eu.advantly.presentation.friends

import eu.advantly.cloud.sync.BlockedProfile
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.FoundProfile
import eu.advantly.cloud.sync.FriendRequestResult
import eu.advantly.cloud.sync.FriendsPort
import eu.advantly.cloud.sync.LiveMatch
import eu.advantly.cloud.sync.LiveMatchesPort
import eu.advantly.cloud.sync.VisitorTeamMember
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.presentation.auth.FakePhoneAuth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal object NoFriendsPort : FriendsPort {
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

internal class FakeLive : LiveMatchesPort {
    val calls = mutableListOf<String>()
    var matches = emptyList<LiveMatch>()
    var gate: CompletableDeferred<Unit>? = null
    var failure: CloudException? = null

    override suspend fun liveMatches(profileId: String): List<LiveMatch> {
        calls += profileId
        gate?.await()
        failure?.let { throw it }
        return matches
    }
}

internal fun liveMatch(
    id: String,
    relation: String = "player",
    projection: String? = """{"sets":[[6,4]],"games":[2,1],"points":["30","15"],"serving":"B","tiebreak":false}""",
    connected: Boolean = true,
) = LiveMatch(
    id,
    relation,
    "tennis",
    "singles",
    listOf(VisitorTeamMember("A", 1, "Bob"), VisitorTeamMember("B", 1, "Alice")),
    projection?.let { Json.parseToJsonElement(it) as JsonObject },
    connected,
    "2026-11-02T11:00:05Z",
)

class LiveViewModelTest {
    private fun alice() = CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT)

    private fun withModel(test: (FriendsViewModel, FakeLive, FakePhoneAuth, FakeVisitors) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val auth = FakePhoneAuth().apply { session.value = alice() }
        val live = FakeLive()
        val visitors = FakeVisitors()
        val queue = VisitorQueue(MemoryVisitorStore(), visitors, { _, _ -> true }) {}
        val model = FriendsViewModel(auth, NoFriendsPort, { emptyList() }, null, scope, visitorQueue = queue, liveMatches = live)
        try {
            test(model, live, auth, visitors)
        } finally {
            model.close()
            scope.cancel()
        }
    }

    @Test fun mapsTheSanitizedProjectionAndDropsUnknownRelations() {
        val entry = liveMatch("m1").toLiveEntry()!!
        assertEquals(LiveRelation.PLAYER, entry.relation)
        assertEquals(Sport.TENNIS, entry.score.match.sport)
        assertEquals(listOf("Bob") to listOf("Alice"), entry.score.match.teamA to entry.score.match.teamB)
        assertEquals(listOf(6 to 4), entry.score.match.sets)
        assertEquals(2 to 1, entry.score.games)
        assertEquals("30" to "15", entry.score.points)
        assertEquals(Side.B, entry.serving)
        assertEquals(1_793_617_205_000L, entry.updatedAtMs)

        val bare = liveMatch("m2", relation = "visitor", projection = null, connected = false).toLiveEntry()!!
        assertEquals(LiveRelation.VISITOR, bare.relation)
        assertNull(bare.serving)
        assertNull(bare.score.points)
        assertNull(liveMatch("m3", relation = "referee").toLiveEntry())
        assertNull(liveMatch("m4").copy(updatedAt = "gestern").toLiveEntry()!!.updatedAtMs)
    }

    @Test fun loadsLiveForTheSignedInAccountAndKeepsTheListOnError() =
        withModel { model, live, _, _ ->
            assertTrue(model.hasLive)
            assertNull(model.state.value.live)
            live.matches = listOf(liveMatch("m1"), liveMatch("m2", relation = "visitor"), liveMatch("m3", relation = "referee"))
            model.loadLive()
            assertEquals(
                listOf("m1", "m2"),
                model.state.value.live!!
                    .map { it.matchId },
            )

            live.failure = CloudException("cloud_connection_failed", false)
            model.loadLive()
            assertEquals("cloud_connection_failed", model.state.value.liveError)
            assertEquals(
                2,
                model.state.value.live!!
                    .size,
                "letzter Stand bleibt sichtbar",
            )
            assertNull(model.state.value.error, "Fehler gehört nicht dem Freunde-Tab")
            live.failure = null
            model.loadLive()
            assertNull(model.state.value.liveError)
        }

    @Test fun aSlowRequestCoversTheNextPollAndAnAccountSwitchDiscardsIt() =
        withModel { model, live, auth, _ ->
            live.matches = listOf(liveMatch("m1"))
            live.gate = CompletableDeferred()
            model.loadLive()
            model.loadLive()
            assertEquals(listOf("alice"), live.calls, "keine gestapelten Abfragen")
            auth.session.value = CloudSession("bob", true, generation = 2, consentStatus = ConsentStatus.CURRENT)
            live.gate!!.complete(Unit)
            live.gate = null
            assertNull(model.state.value.live, "Alices Antwort nicht unter Bob")
            model.loadLive()
            assertEquals(listOf("alice", "bob"), live.calls)
            assertEquals(
                listOf("m1"),
                model.state.value.live!!
                    .map { it.matchId },
            )
        }

    @Test fun stopWatchingRemovesTheVisitorEntryEvenAgainstALatePoll() =
        withModel { model, live, _, visitors ->
            live.matches = listOf(liveMatch("m1"), liveMatch("m2", relation = "visitor"))
            model.loadLive()
            live.gate = CompletableDeferred()
            model.loadLive()
            model.stopWatching("m2")
            assertEquals("alice:revoke:m2:alice", visitors.calls.last())
            live.gate!!.complete(Unit)
            assertEquals(
                listOf("m1"),
                model.state.value.live!!
                    .map { it.matchId },
            )
        }

    @Test fun withoutPortThereIsNoLiveArea() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val auth = FakePhoneAuth().apply { session.value = alice() }
        val model = FriendsViewModel(auth, NoFriendsPort, { emptyList() }, null, scope)
        model.loadLive()
        assertEquals(false, model.hasLive)
        assertNull(model.state.value.live)
        scope.cancel()
    }
}
