package eu.advantly.presentation.friends

import eu.advantly.cloud.sync.BlockedProfile
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.FoundProfile
import eu.advantly.cloud.sync.FriendRequestResult
import eu.advantly.cloud.sync.FriendsPort
import eu.advantly.cloud.sync.MatchVisitor
import eu.advantly.cloud.sync.PulledFriendship
import eu.advantly.cloud.sync.VisitorTeamMember
import eu.advantly.cloud.sync.VisitorView
import eu.advantly.cloud.sync.VisitorsPort
import eu.advantly.presentation.auth.FakePhoneAuth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object Friendly : FriendsPort {
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

internal class MemoryVisitorStore : PendingVisitorStore {
    val pending = mutableMapOf<String, Map<String, Set<String>>>()
    val watched = mutableMapOf<String, Set<String>>()

    override fun pending(profileId: String) = pending[profileId].orEmpty()

    override fun setPending(
        profileId: String,
        pending: Map<String, Set<String>>,
    ) {
        this.pending[profileId] = pending
    }

    override fun watched(profileId: String) = watched[profileId].orEmpty()

    override fun setWatched(
        profileId: String,
        matchIds: Set<String>,
    ) {
        watched[profileId] = matchIds
    }
}

internal class FakeVisitors : VisitorsPort {
    val calls = mutableListOf<String>()
    val visitors = mutableMapOf<String, MutableList<MatchVisitor>>()
    var watching = emptyList<VisitorView>()
    var gate: CompletableDeferred<Unit>? = null

    /** Fehler je Besucher-ID (bzw. "list"/"watching"). */
    val failures = mutableMapOf<String, CloudException>()

    private suspend fun call(
        name: String,
        key: String,
    ) {
        calls += name
        gate?.await()
        failures[key]?.let { throw it }
    }

    override suspend fun addVisitor(
        profileId: String,
        matchId: String,
        visitorId: String,
    ) {
        call("$profileId:add:$matchId:$visitorId", visitorId)
        visitors.getOrPut(matchId) { mutableListOf() } += MatchVisitor(visitorId, visitorId.uppercase(), profileId, true)
    }

    override suspend fun revokeVisitor(
        profileId: String,
        matchId: String,
        visitorId: String,
    ) {
        call("$profileId:revoke:$matchId:$visitorId", visitorId)
        visitors[matchId]?.removeAll { it.profileId == visitorId }
    }

    override suspend fun matchVisitors(
        profileId: String,
        matchId: String,
    ): List<MatchVisitor> {
        call("$profileId:list:$matchId", "list")
        return visitors[matchId].orEmpty().toList()
    }

    override suspend fun myVisitorMatches(profileId: String): List<VisitorView> {
        call("$profileId:watching", "watching")
        return watching
    }
}

class VisitorsViewModelTest {
    private class Fixture(
        val model: FriendsViewModel,
        val auth: FakePhoneAuth,
        val port: FakeVisitors,
        val store: MemoryVisitorStore,
        val queue: VisitorQueue,
        val onServer: MutableSet<String>,
    ) {
        val state get() = model.state.value
        var syncs = 0
    }

    private fun alice(generation: Long = 1) = CloudSession("alice", true, generation = generation, consentStatus = ConsentStatus.CURRENT)

    private fun fixture(test: Fixture.() -> Unit) {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val auth = FakePhoneAuth().apply { session.value = alice() }
        val port = FakeVisitors()
        val store = MemoryVisitorStore()
        val onServer = mutableSetOf<String>()
        lateinit var fixture: Fixture
        val queue = VisitorQueue(store, port, { _, match -> match in onServer }) { fixture.syncs++ }
        val friends = { profile: String ->
            if (profile == "alice") {
                listOf(PulledFriendship("vera", "Vera", "accepted", false, 1), PulledFriendship("carol", "Carol", "accepted", false, 1))
            } else {
                emptyList()
            }
        }
        val model = FriendsViewModel(auth, Friendly, friends, null, scope, visitorQueue = queue)
        fixture = Fixture(model, auth, port, store, queue, onServer)
        try {
            fixture.test()
        } finally {
            model.close()
            scope.cancel()
        }
    }

    @Test fun matchNotYetUploadedQueuesVisitorsAndRequestsASync() =
        fixture {
            model.openVisitors("m1")
            assertEquals(MatchVisitors("m1"), state.visitors)
            model.addVisitors(setOf("vera"))
            assertEquals(1, syncs)
            assertTrue(port.calls.isEmpty())
            assertEquals(listOf(VisitorEntry("vera", "Vera", canRevoke = true, pending = true)), state.visitors?.visitors)
            // Vorgemerkt heißt auch: der Live-Upload während des Spiels ist schon aktiv.
            assertTrue(queue.isWatched("alice", "m1"))
            model.removeVisitor("vera")
            assertTrue(state.visitors!!.visitors.isEmpty())
            assertTrue(store.pending("alice").isEmpty())
            assertTrue(port.calls.isEmpty())
        }

    @Test fun uploadedMatchListsAddsAndRevokesOnTheServer() =
        fixture {
            onServer += "m1"
            port.visitors["m1"] = mutableListOf(MatchVisitor("carol", "Carol", "bob", canRevoke = false))
            model.openVisitors("m1")
            assertEquals(listOf(VisitorEntry("carol", "Carol", canRevoke = false)), state.visitors?.visitors)
            assertTrue(queue.isWatched("alice", "m1"))
            model.addVisitors(setOf("vera"))
            assertEquals(listOf("alice:list:m1", "alice:add:m1:vera", "alice:list:m1"), port.calls)
            assertEquals(listOf("carol", "vera"), state.visitors?.visitors?.map { it.profileId })
            // Fremde Benennung ohne Ersteller-Recht: kein Aufruf.
            model.removeVisitor("carol")
            assertEquals(3, port.calls.size)
            model.removeVisitor("vera")
            assertEquals("alice:revoke:m1:vera", port.calls[3])
            assertEquals(listOf("carol"), state.visitors?.visitors?.map { it.profileId })
            port.visitors["m1"]!!.clear()
            model.loadVisitors()
            assertFalse(queue.isWatched("alice", "m1"))
        }

    @Test fun serverErrorsStayInTheVisitorSectionNotTheFriendsTab() =
        fixture {
            onServer += "m1"
            model.openVisitors("m1")
            port.failures["vera"] = CloudException("visitor_must_be_friend", true)
            model.addVisitors(setOf("vera"))
            assertEquals("visitor_must_be_friend", state.visitors?.error)
            assertNull(state.error)
            model.dismissVisitorError()
            assertNull(state.visitors?.error)
        }

    @Test fun accountSwitchDiscardsALateVisitorList() =
        fixture {
            onServer += "m1"
            port.visitors["m1"] = mutableListOf(MatchVisitor("vera", "Vera", "alice", true))
            port.gate = CompletableDeferred()
            model.openVisitors("m1")
            auth.session.value = CloudSession("bob", true, generation = 2, consentStatus = ConsentStatus.CURRENT)
            val gate = port.gate!!
            port.gate = null
            gate.complete(Unit)
            // Bob lädt seine eigene Liste; Alices verspätete Antwort ändert weder Anzeige noch Alices Stand.
            assertEquals(listOf("alice:list:m1", "bob:list:m1"), port.calls)
            assertNull(store.watched["alice"])
            assertEquals(setOf("m1"), store.watched["bob"])
        }

    @Test fun wizardQueueOnlyForTheSignedInAccount() =
        fixture {
            model.queueVisitors("m2", setOf("vera", "carol"))
            assertEquals(mapOf("m2" to setOf("vera", "carol")), store.pending("alice"))
            auth.session.value = null
            model.queueVisitors("m3", setOf("vera"))
            assertEquals(setOf("m2"), store.pending("alice").keys)
        }

    @Test fun watchingShowsLiveScoreAndStopWatchingRevokesSelf() =
        fixture {
            port.watching =
                listOf(
                    VisitorView(
                        "m9",
                        "tennis",
                        "singles",
                        "in_progress",
                        channel = "match-live:m9:1",
                        teams = listOf(VisitorTeamMember("A", 1, "Bob"), VisitorTeamMember("B", 1, "Alice")),
                        live = json("""{"sets":[[6,4]],"games":[2,1],"points":["30","15"],"tiebreak":false}"""),
                    ),
                    VisitorView(
                        "m8",
                        "padel",
                        "doubles",
                        "finished",
                        winner = "B",
                        channel = "match-live:m8:1",
                        result = json("""{"sets":[[3,6],[4,6]],"winner":"B"}"""),
                    ),
                )
            model.loadWatching()
            val (running, finished) = state.watching!!
            assertEquals(listOf("Bob"), running.match.teamA)
            assertEquals(listOf(6 to 4), running.match.sets)
            assertEquals(2 to 1, running.games)
            assertEquals("30" to "15", running.points)
            assertEquals(listOf(3 to 6, 4 to 6), finished.match.sets)
            assertNull(finished.games)
            assertNull(finished.points)

            // Eine Abfrage, die vor dem Beenden begann, bringt das Match nicht zurück.
            port.gate = CompletableDeferred()
            model.loadWatching()
            val gate = port.gate!!
            port.gate = null
            model.stopWatching("m9")
            assertEquals("alice:revoke:m9:alice", port.calls.last())
            gate.complete(Unit)
            assertEquals(listOf("m8"), state.watching!!.map { it.match.id })

            port.failures["watching"] = CloudException("cloud_connection_failed", false)
            model.loadWatching()
            assertEquals("cloud_connection_failed", state.watchingError)
            assertNull(state.error)
        }

    @Test fun flushSendsAfterUploadRetriesTransientAndDropsFinalErrors(): Unit =
        runBlocking {
            val port = FakeVisitors()
            val store = MemoryVisitorStore()
            val onServer = mutableSetOf<String>()
            val queue = VisitorQueue(store, port, { _, match -> match in onServer }) {}
            queue.enqueue("alice", "m1", setOf("vera", "carol", "eve"))
            assertFalse(queue.flush("alice"))
            assertTrue(port.calls.isEmpty(), "vor dem Upload des Kopfes kein Aufruf (Rate-Limit)")

            onServer += "m1"
            port.failures["vera"] = CloudException("visitor_must_be_friend", true)
            port.failures["carol"] = CloudException("already_member", true)
            port.failures["eve"] = CloudException("cloud_connection_failed", false) // offline
            assertFalse(queue.flush("alice"))
            assertEquals(mapOf("m1" to setOf("eve")), store.pending("alice"))

            port.failures.remove("eve")
            port.failures["eve"] = CloudException("rate_limit_exceeded", true)
            assertFalse(queue.flush("alice"))
            assertEquals(mapOf("m1" to setOf("eve")), store.pending("alice"))

            port.failures.clear()
            assertTrue(queue.flush("alice"))
            assertTrue(store.pending("alice").isEmpty())
            assertEquals(setOf("m1"), store.watched("alice"))
        }

    @Test fun permissionFailureDropsOnlyAfterRepeatedAttempts(): Unit =
        runBlocking {
            val port = FakeVisitors()
            val store = MemoryVisitorStore()
            val queue = VisitorQueue(store, port, { _, _ -> true }) {}
            queue.enqueue("alice", "m1", setOf("dave"))
            // forbidden und abgelaufenes Token (401) sehen gleich aus: nicht beim ersten Mal verwerfen.
            port.failures["dave"] = CloudException("cloud_connection_failed", true)
            repeat(VisitorQueue.PERMISSION_ATTEMPTS - 1) {
                queue.flush("alice")
                assertEquals(mapOf("m1" to setOf("dave")), store.pending("alice"))
            }
            queue.flush("alice")
            assertTrue(store.pending("alice").isEmpty())
        }

    @Test fun liveUploadIsThrottledAndOnlyForWatchedMatches() {
        val store = MemoryVisitorStore().apply { watched["alice"] = setOf("m1") }
        val queue = VisitorQueue(store, FakeVisitors(), { _, _ -> true }) {}
        var now = 0L
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        var syncs = 0
        val upload = LiveVisitorUpload(queue, { now }, { delay, run -> scheduled += delay to run }) { syncs++ }

        upload.onScore("alice", "m1", 3) // erster Stand: keine Änderung
        upload.onScore("alice", "m2", 3)
        upload.onScore("alice", "m2", 4) // ohne Visitor
        upload.onScore(null, "m1", 4) // abgemeldet
        assertTrue(scheduled.isEmpty())

        upload.onScore("alice", "m1", 5)
        assertEquals(0L, scheduled.single().first)
        upload.onScore("alice", "m1", 6) // schon geplant
        assertEquals(1, scheduled.size)
        now = 1_000
        scheduled.removeAt(0).second()
        assertEquals(1, syncs)

        now = 11_000
        upload.onScore("alice", "m1", 7)
        assertEquals(20_000L, scheduled.single().first, "höchstens alle 30 s")
    }

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
}
