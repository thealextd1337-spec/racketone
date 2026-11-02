package eu.advantly.presentation.friends

import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.PulledMatch
import eu.advantly.cloud.sync.PulledRole
import eu.advantly.cloud.sync.ScorerRolesPort
import eu.advantly.presentation.auth.FakePhoneAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MemoryScorerStore : PendingScorerStore {
    val invites = mutableMapOf<String, Map<String, ScorerInvite>>()

    override fun scorerInvites(profileId: String) = invites[profileId].orEmpty()

    override fun setScorerInvites(
        profileId: String,
        invites: Map<String, ScorerInvite>,
    ) {
        this.invites[profileId] = invites
    }
}

internal class FakeScorerRoles : ScorerRolesPort {
    val calls = mutableListOf<String>()
    var failure: CloudException? = null

    private fun call(text: String) {
        failure?.let { throw it }
        calls += text
    }

    override suspend fun inviteScorer(
        profileId: String,
        matchId: String,
        scorerId: String,
    ) = call("invite $matchId $scorerId")

    override suspend fun respondScorerInvitation(
        profileId: String,
        matchId: String,
        accept: Boolean,
        deviceId: String?,
    ) = call("respond $matchId $accept $deviceId")

    override suspend fun revokeScorer(
        profileId: String,
        matchId: String,
        scorerId: String,
    ) = call("revoke $matchId $scorerId")

    override suspend fun registerWatch(
        profileId: String,
        watchId: String,
        phoneId: String,
    ) = call("register $watchId $phoneId")
}

internal class FakeDevices : ScorerDevices {
    override val phoneId = "phone-1"
    var watch: String? = "watch-1"
    val scoring = mutableSetOf<String>()
    val ready = mutableSetOf<String>()
    val handedOver = mutableListOf<Pair<String, String>>()
    val handedBack = mutableListOf<Pair<String, String>>()

    /** Server-Antwort je Event (fehlt: ausstehend). */
    val uploads = mutableMapOf<String, Boolean>()

    override fun watchId() = watch

    override fun scoring(matchId: String) = matchId in scoring

    override fun handOver(
        matchId: String,
        deviceId: String,
    ): Handover? {
        if (matchId !in scoring) return null
        scoring -= matchId
        handedOver += matchId to deviceId
        return Handover("sc-$matchId", phoneId, deviceId)
    }

    override fun uploaded(eventId: String) = uploads[eventId]

    override fun handBack(
        matchId: String,
        handover: Handover,
    ): Boolean {
        scoring += matchId
        handedBack += matchId to handover.from
        return true
    }

    override fun canTakeOver(matchId: String) = matchId in ready && matchId !in scoring

    override fun takeOver(matchId: String): Boolean {
        if (!canTakeOver(matchId)) return false
        scoring += matchId
        return true
    }
}

internal fun memberMatch(
    id: String,
    vararg roles: PulledRole,
) = PulledMatch(
    id = id,
    access = "member",
    createdBy = "alex",
    sport = "tennis",
    discipline = "singles",
    format = JsonObject(emptyMap()),
    meta = JsonObject(emptyMap()),
    status = "in_progress",
    serverVersion = 1,
    roles = roles.toList(),
)

class ScorerHandoverTest {
    private val store = MemoryScorerStore()
    private val port = FakeScorerRoles()
    private val devices = FakeDevices()
    private val onServer = mutableSetOf<String>()
    private val pulled = mutableMapOf<String, PulledMatch>()
    private var syncs = 0
    private val handovers = ScorerHandovers(store, port, { _, m -> m in onServer }, { _, m -> pulled[m] }, devices) { syncs++ }

    @Test fun invitationWaitsForTheUploadAndHandsOverOnceAccepted() =
        runBlocking {
            devices.scoring += "m1"
            handovers.invite("alex", "m1", "jonas")
            assertEquals(HandoverPhase.QUEUED, handovers.status("alex", "m1")?.phase)
            assertTrue(port.calls.isEmpty())
            assertEquals(1, syncs)

            handovers.flush("alex")
            assertTrue(port.calls.isEmpty(), "noch nicht hochgeladen")
            onServer += "m1"
            handovers.flush("alex")
            assertEquals(listOf("invite m1 jonas"), port.calls)
            assertEquals(HandoverPhase.INVITED, handovers.status("alex", "m1")?.phase)

            // eingeladen, noch nicht angenommen: Alex zählt weiter, Abfrage läuft
            pulled["m1"] = memberMatch("m1", PulledRole("jonas", "Jonas", "scorer", "invited"))
            assertTrue(handovers.reconcile("alex"))
            assertTrue(devices.handedOver.isEmpty())

            pulled["m1"] = memberMatch("m1", PulledRole("jonas", "Jonas", "scorer", "accepted", "jonas-watch"))
            assertEquals(HandoverPhase.ACCEPTED, handovers.status("alex", "m1")?.phase)
            assertTrue(handovers.reconcile("alex"), "abgegeben, Antwort des Servers steht aus")
            assertEquals(listOf("m1" to "jonas-watch"), devices.handedOver)
            assertEquals(2, syncs, "ScorerChanged sofort hochladen")
            assertTrue(handovers.reconcile("alex"))
            assertEquals(listOf("m1" to "jonas-watch"), devices.handedOver, "nur einmal abgeben")

            devices.uploads["sc-m1"] = true
            assertFalse(handovers.reconcile("alex"), "Abgabe vom Server angenommen, keine Abfrage mehr")
            assertNull(handovers.status("alex", "m1"))
            assertTrue(devices.handedBack.isEmpty())
        }

    @Test fun aRejectedHandoverHandsScoringBackAndShowsAHintUntilDismissed() =
        runBlocking {
            onServer += "m1"
            devices.scoring += "m1"
            handovers.invite("alex", "m1", "jonas")
            pulled["m1"] = memberMatch("m1", PulledRole("jonas", "Jonas", "scorer", "accepted", "jonas-phone"))
            assertTrue(handovers.reconcile("alex"))
            val before = syncs

            // z. B. scorer_target_forbidden: Jonas hat inzwischen das Gerät gewechselt
            devices.uploads["sc-m1"] = false
            assertFalse(handovers.reconcile("alex"))
            assertEquals(listOf("m1" to "phone-1"), devices.handedBack, "das vorige eigene Gerät zählt wieder")
            assertTrue(devices.scoring("m1"))
            assertEquals(before + 1, syncs, "Rückgabe sofort hochladen")
            assertTrue(store.scorerInvites("alex").isEmpty())
            assertEquals(mapOf("m1" to HandoverStatus("jonas", HandoverPhase.FAILED)), handovers.statuses("alex"))
            assertFalse(handovers.reconcile("alex"))
            assertEquals(1, devices.handedBack.size, "nur einmal zurückgeben")

            handovers.cancel("alex", "m1")
            assertNull(handovers.status("alex", "m1"))
            assertTrue(port.calls.none { it.startsWith("revoke") }, "Wegklicken entzieht keine Rolle")
        }

    @Test fun declinedRevokedOrNoLongerScoredInvitationsEnd() =
        runBlocking {
            onServer += setOf("m1", "m2", "m3")
            devices.scoring += setOf("m1", "m2")
            handovers.invite("alex", "m1", "jonas")
            handovers.invite("alex", "m2", "jonas")
            handovers.invite("alex", "m3", "jonas")
            pulled["m1"] = memberMatch("m1", PulledRole("jonas", null, "scorer", "declined"))
            pulled["m2"] = memberMatch("m2", PulledRole("jonas", null, "scorer", "revoked"))
            pulled["m3"] = memberMatch("m3", PulledRole("jonas", null, "scorer", "accepted", "jonas-phone"))

            assertFalse(handovers.reconcile("alex"))
            assertTrue(handovers.statuses("alex").isEmpty())
            assertTrue(devices.handedOver.isEmpty(), "m3 zählt keines meiner Geräte mehr")
        }

    @Test fun finalRejectionsDropAQueuedInvitationOthersStay() =
        runBlocking {
            handovers.invite("alex", "m1", "eve")
            onServer += "m1"
            port.failure = CloudException("connection_failed", false)
            handovers.flush("alex")
            assertEquals(HandoverPhase.QUEUED, handovers.status("alex", "m1")?.phase)
            port.failure = CloudException("scorer_not_allowed", false)
            handovers.flush("alex")
            assertNull(handovers.status("alex", "m1"))
        }

    @Test fun acceptingOnTheWatchRegistersItFirstAndWaitsForTheChange() =
        runBlocking {
            handovers.accept("jonas", "m1", onWatch = true)
            assertEquals(listOf("register watch-1 phone-1", "respond m1 true watch-1"), port.calls)
            assertTrue(handovers.awaiting("m1"))
            assertTrue(handovers.reconcile("jonas"), "bis der Wechsel gezogen ist")
            devices.scoring += "m1"
            assertFalse(handovers.reconcile("jonas"))
            assertFalse(handovers.awaiting("m1"))

            port.calls.clear()
            handovers.accept("jonas", "m2", onWatch = false)
            handovers.decline("jonas", "m3")
            assertEquals(listOf("respond m2 true phone-1", "respond m3 false null"), port.calls)

            devices.watch = null
            assertEquals("watch_unavailable", assertFailsWith<CloudException> { handovers.accept("jonas", "m4", onWatch = true) }.reason)
        }

    private class Local(
        val matches: Map<String, PulledMatch>,
    ) : SharedMatchesLocal {
        override fun feed(profileId: String) = emptyList<PulledMatch>()

        override fun match(
            profileId: String,
            matchId: String,
        ) = matches[matchId]

        override fun linkNotes(
            profileId: String,
            matchId: String,
        ) = emptyMap<String, String>()
    }

    @Test fun liveViewOffersAcceptanceAndTheEmergencyTakeOverOnlyWithAcceptedRoleAndCompleteState() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val auth = FakePhoneAuth().apply { session.value = CloudSession("jonas", true, consentStatus = ConsentStatus.CURRENT) }
        val live = FakeLive().apply { matches = listOf(liveMatch("m1", relation = "scorer"), liveMatch("m2", relation = "scorer")) }
        val roles =
            mutableMapOf(
                "m1" to memberMatch("m1", PulledRole("jonas", "Jonas", "scorer", "invited")),
                "m2" to memberMatch("m2", PulledRole("jonas", "Jonas", "scorer", "accepted", "phone-1")),
            )
        val handovers = ScorerHandovers(store, port, { _, _ -> true }, { _, m -> roles[m] }, devices) { syncs++ }
        val model =
            FriendsViewModel(
                auth,
                NoFriendsPort,
                { emptyList() },
                null,
                scope,
                local = Local(roles),
                liveMatches = live,
                scorers = handovers,
            )
        try {
            model.loadLive()
            val (invited, accepted) = model.state.value.live!!
            assertEquals("invited", invited.scorerRole)
            assertEquals("accepted", accepted.scorerRole)
            assertFalse(accepted.canTakeOver, "ohne vollständigen Stand keine Übernahme")
            assertTrue(model.hasHandover)
            assertTrue(model.hasWatch())

            model.takeOverScoring("m2")
            assertTrue(devices.scoring.isEmpty())
            devices.ready += setOf("m1", "m2")
            model.loadLive()
            assertTrue(
                model.state.value.live!![1]
                    .canTakeOver,
            )
            assertFalse(
                model.state.value.live!![0]
                    .canTakeOver,
                "eingeladen ist nicht angenommen",
            )
            model.takeOverScoring("m1")
            assertTrue(devices.scoring.isEmpty())
            model.takeOverScoring("m2")
            assertEquals(setOf("m2"), devices.scoring)
            model.loadLive()
            assertTrue(
                model.state.value.live!![1]
                    .scoring,
            )

            // Fremd-Match (Ersteller alex): Jonas lädt niemanden ein, auch nicht zurück an den Ersteller
            assertFalse(model.canHandOver("m2"))
            assertTrue(model.canHandOver("eigenes-neues"), "noch nicht gezogen: eigenes Match")

            model.acceptScoring("m1", onWatch = true)
            assertEquals(listOf("register watch-1 phone-1", "respond m1 true watch-1"), port.calls)
            model.loadLive()
            assertTrue(
                model.state.value.live!![0]
                    .awaiting,
            )

            port.failure = CloudException("no_pending_invitation", false)
            model.declineScoring("m1")
            assertEquals("no_pending_invitation", model.state.value.handoverError)
            model.dismissHandoverError()
            assertNull(model.state.value.handoverError)
        } finally {
            model.close()
            scope.cancel()
        }
    }

    @Test fun countingPhoneSeesItsHandoverState() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val auth = FakePhoneAuth().apply { session.value = CloudSession("alex", true, consentStatus = ConsentStatus.CURRENT) }
        val model = FriendsViewModel(auth, NoFriendsPort, { emptyList() }, null, scope, scorers = handovers)
        try {
            model.handOver("m1", "jonas")
            assertEquals(mapOf("m1" to HandoverStatus("jonas", HandoverPhase.QUEUED)), model.state.value.handovers)
            onServer += "m1"
            model.cancelHandover("m1")
            assertTrue(
                model.state.value.handovers
                    .isEmpty(),
            )
            assertTrue(port.calls.isEmpty(), "vorgemerkt: nur lokal zurückgenommen")

            model.handOver("m1", "jonas")
            assertEquals(
                HandoverPhase.INVITED,
                model.state.value.handovers["m1"]
                    ?.phase,
            )
            model.cancelHandover("m1")
            assertEquals(listOf("invite m1 jonas", "revoke m1 jonas"), port.calls)
        } finally {
            model.close()
            scope.cancel()
        }
    }
}
