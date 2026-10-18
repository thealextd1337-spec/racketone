package eu.advantly.presentation.auth

import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.ConsentDocument
import eu.advantly.cloud.sync.ConsentDocumentType
import eu.advantly.cloud.sync.ConsentSnapshot
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.SupabasePhoneAuth
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.SessionConfig
import eu.advantly.sync.protocol.PeerKind
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryIntentIntegrationTest {
    private enum class Outcome(
        val committed: Boolean,
        val succeeds: Boolean,
    ) {
        SUCCESS(true, true),
        LOST_COMMITTED(true, false),
        LOST_UNCOMMITTED(false, false),
    }

    private class Scenario(
        val scope: CoroutineScope,
    ) {
        val data = LocalData(createInMemoryJvmDriver())
        val phone = MatchSession(data, null, ManualScheduler(), SessionConfig(MatchSessions.deviceId(data), PeerKind.PHONE))
        var deleted = false
        var owner = "alice"
        var commitRecovery = true
        var recoverySucceeds = false
        var commitRequest = false
        var requestSucceeds = false
        var consentStatus = ConsentStatus.CURRENT
        var commitDecision = false
        var decisionSucceeds = false
        var delayDecision = false
        var delayRecovery = false
        var delayProfile = false
        val sent = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val paths = mutableListOf<String>()
        val sdk =
            createSupabaseClient("https://auth.test", "public-key") {
                defaultLogLevel = LogLevel.NONE
                install(Auth) { minimalConfig() }
                httpEngine =
                    MockEngine { request ->
                        val path = request.url.encodedPath.substringAfterLast('/')
                        paths += path
                        val (body, status) =
                            when (path) {
                                "my_auth_profile" -> {
                                    val profile =
                                        """
                                        {"profileId":"$owner","onboarded":${!deleted},"deletionRequested":$deleted,
                                         "consentStatus":"$consentStatus"}
                                        """.trimIndent()
                                    if (delayProfile) {
                                        delayProfile = false
                                        sent.complete(Unit)
                                        release.await()
                                    }
                                    profile to HttpStatusCode.OK
                                }

                                "my_consent_v1" -> {
                                    consentBody() to HttpStatusCode.OK
                                }

                                "cancel_account_deletion" -> {
                                    if (deleted) {
                                        if (commitRecovery) deleted = false
                                        if (delayRecovery) {
                                            sent.complete(Unit)
                                            release.await()
                                        }
                                        if (recoverySucceeds) {
                                            "{}" to HttpStatusCode.OK
                                        } else {
                                            "lost-recovery-response" to
                                                HttpStatusCode.ServiceUnavailable
                                        }
                                    } else {
                                        """{"message":"no_deletion_pending"}""" to HttpStatusCode.BadRequest
                                    }
                                }

                                "request_account_deletion" -> {
                                    if (commitRequest) deleted = true
                                    if (requestSucceeds) "{}" to HttpStatusCode.OK else "offline" to HttpStatusCode.ServiceUnavailable
                                }

                                "record_consent_v1" -> {
                                    val accept =
                                        Json
                                            .parseToJsonElement((request.body as TextContent).text)
                                            .jsonObject
                                            .getValue("p_accept")
                                            .jsonPrimitive.boolean
                                    if (commitDecision) consentStatus = if (accept) ConsentStatus.CURRENT else ConsentStatus.DECLINED
                                    val result =
                                        consentBody() to if (decisionSucceeds) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
                                    if (delayDecision) {
                                        delayDecision = false
                                        sent.complete(Unit)
                                        release.await()
                                    }
                                    result
                                }

                                "export_my_data" -> {
                                    "exported" to HttpStatusCode.OK
                                }

                                else -> {
                                    error(path)
                                }
                            }
                        respond(body, status, headersOf("Content-Type", "application/json"))
                    }
            }
        lateinit var auth: SupabasePhoneAuth
        lateinit var model: AuthViewModel

        private fun consentBody() =
            Json.encodeToString(
                ConsentSnapshot(
                    owner,
                    consentStatus,
                    listOf(
                        ConsentDocument(ConsentDocumentType.TERMS, "v2", "en", "https://advantly.eu/terms.html", approved = true),
                        ConsentDocument(
                            ConsentDocumentType.PRIVACY,
                            "v2",
                            "en",
                            "https://advantly.eu/en/app-privacy.html",
                            approved = true,
                        ),
                    ),
                ),
            )

        suspend fun start() {
            data.players.ensureSelf("Alex", 0) { PlayerId("self") }
            phone.start()
            sdk.auth.awaitInitialization()
            signIn()
            auth = SupabasePhoneAuth(sdk, "secret", uploads = data.cloudUploads, scope = scope, ownership = phone)
            model = AuthViewModel(auth, scope)
            settled()
        }

        suspend fun signIn(session: String = "session-a") {
            val token =
                "h." +
                    Base64.UrlSafe
                        .withPadding(Base64.PaddingOption.ABSENT)
                        .encode("""{"sub":"$owner","session_id":"$session"}""".encodeToByteArray()) + ".s"
            sdk.auth.importAuthToken(token, autoRefresh = false)
        }

        suspend fun settled() = withTimeout(5_000) { model.state.first { !it.busy } }

        suspend fun enterGrace() {
            deleted = true
            model.refresh()
            assertTrue(settled().deletionRequested)
            assertNull(data.accountIdentities.profile)
            assertBlocked()
        }

        fun assertBlocked() = assertFailsWith<CloudException> { auth.adoptionSources() }

        fun create() =
            phone
                .create(
                    Presets.TENNIS_SINGLES_BO3.format(),
                    Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a")),
                ).also { assertTrue(phone.recordPoint(Side.A)) }

        fun close() {
            release.complete(Unit)
            auth.close()
            phone.close()
            scope.cancel()
        }
    }

    private fun scenario(block: suspend Scenario.() -> Unit): Unit =
        runBlocking {
            val fixture = Scenario(CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob()))
            try {
                fixture.start()
                fixture.block()
            } finally {
                fixture.close()
            }
        }

    private suspend fun Scenario.decide(
        accept: Boolean,
        outcome: Outcome,
    ) {
        commitDecision = outcome.committed
        decisionSucceeds = outcome.succeeds
        model.decideConsent(accept)
        assertEquals(if (outcome.succeeds) null else "connection_failed", settled().error)
    }

    private suspend fun Scenario.verifyPermission(allowed: Boolean) {
        model.refresh()
        val state = settled()
        assertNull(state.error)
        assertTrue(state.authenticated)
        assertEquals(deleted, state.deletionRequested)
        assertEquals(consentStatus, state.consent?.status)
        if (allowed) {
            auth.adoptionSources()
            assertEquals("alice", data.accountIdentities.profile)
        } else {
            assertBlocked()
            assertNull(data.accountIdentities.profile)
        }
    }

    private fun intentConsentMatrix(
        recovery: Boolean,
        accept: Boolean,
    ) {
        for (intentOutcome in Outcome.entries) {
            for (consentOutcome in Outcome.entries) {
                scenario {
                    consentStatus = ConsentStatus.REQUIRED
                    if (recovery) {
                        enterGrace()
                        commitRecovery = intentOutcome.committed
                        recoverySucceeds = intentOutcome.succeeds
                        model.cancelDeletion()
                    } else {
                        model.refresh()
                        settled()
                        commitRequest = intentOutcome.committed
                        requestSucceeds = intentOutcome.succeeds
                        model.requestDeletion()
                    }
                    assertEquals(if (intentOutcome.succeeds) null else "connection_failed", settled().error)
                    assertBlocked()
                    decide(accept, consentOutcome)
                    if (!consentOutcome.succeeds) assertBlocked()
                    val allowed = recovery && intentOutcome.committed && accept && consentOutcome.committed
                    repeat(2) { verifyPermission(allowed) }
                    model.exportData()
                    assertEquals("exported", settled().exportedData)
                    if (!accept && consentOutcome == Outcome.LOST_UNCOMMITTED) {
                        // REQUIRED bestätigt die verlorene Ablehnung nicht; ein späteres CURRENT darf sie nicht umgehen.
                        consentStatus = ConsentStatus.CURRENT
                        verifyPermission(false)
                    }
                    if (!accept || !consentOutcome.committed) {
                        decide(true, Outcome.SUCCESS)
                        verifyPermission(recovery && intentOutcome.committed)
                    }
                }
            }
        }
    }

    @Test fun recoveryAcceptOutcomeMatrix() = intentConsentMatrix(recovery = true, accept = true)

    @Test fun recoveryDeclineOutcomeMatrix() = intentConsentMatrix(recovery = true, accept = false)

    @Test fun requestAcceptOutcomeMatrix() = intentConsentMatrix(recovery = false, accept = true)

    @Test fun requestDeclineOutcomeMatrix() = intentConsentMatrix(recovery = false, accept = false)

    private fun recoveryThenAcceptance(refreshBeforeAcceptance: Boolean) =
        scenario {
            val originalDevice = phone.deviceId
            val match = create()
            val historical = data.events.eventsFor(match.value)
            consentStatus = ConsentStatus.REQUIRED
            enterGrace()
            model.cancelDeletion()
            assertEquals("connection_failed", settled().error)
            assertFalse(deleted)
            assertBlocked()
            if (refreshBeforeAcceptance) verifyPermission(false)
            commitDecision = true
            decisionSucceeds = true
            delayProfile = true
            model.decideConsent(true)
            withTimeout(5_000) { sent.await() }
            assertEquals(ConsentStatus.CURRENT, consentStatus)
            assertTrue(model.state.value.busy)
            assertBlocked()
            assertNull(data.accountIdentities.profile)
            release.complete(Unit)
            assertNull(settled().error)
            auth.adoptionSources()
            assertEquals(originalDevice, phone.deviceId)
            assertEquals(historical, data.events.eventsFor(match.value))
            assertEquals("alice", data.accountIdentities.profile)
            repeat(2) { verifyPermission(true) }
        }

    @Test fun acceptanceAfterLostRecoveryUsesFreshProofBeforeRestoringOwnership() = recoveryThenAcceptance(false)

    @Test fun refreshBeforeAcceptanceStillReconcilesRecoveryWithoutCloudPermission() = recoveryThenAcceptance(true)

    @Test fun pendingRecoveryCannotBeConfirmedByNewAcceptanceUntilANewPostCompletionProfile() =
        scenario {
            consentStatus = ConsentStatus.REQUIRED
            enterGrace()
            delayRecovery = true
            val recovery = scope.async { runCatching { auth.cancelDeletion() } }
            withTimeout(5_000) { sent.await() }
            decide(true, Outcome.SUCCESS)
            assertFalse(deleted)
            assertBlocked()
            assertNull(data.accountIdentities.profile)
            release.complete(Unit)
            assertEquals("connection_failed", (recovery.await().exceptionOrNull() as CloudException).reason)
            assertBlocked()
            verifyPermission(true)
        }

    private fun olderAcceptanceCannotClearNewIntent(decline: Boolean) =
        scenario {
            consentStatus = ConsentStatus.REQUIRED
            enterGrace()
            model.cancelDeletion()
            assertEquals("connection_failed", settled().error)
            commitDecision = true
            decisionSucceeds = true
            delayDecision = true
            val snapshot = model.state.value.consent!!
            val acceptance = scope.async { runCatching { auth.decideConsent(snapshot, true) } }
            withTimeout(5_000) { sent.await() }
            if (decline) {
                decide(false, Outcome.LOST_UNCOMMITTED)
            } else {
                model.requestDeletion()
                assertEquals("connection_failed", settled().error)
            }
            val profiles = paths.count { it == "my_auth_profile" }
            release.complete(Unit)
            assertEquals("cloud_session_changed", (acceptance.await().exceptionOrNull() as CloudException).reason)
            assertEquals(profiles, paths.count { it == "my_auth_profile" })
            assertBlocked()
            repeat(2) { verifyPermission(false) }
        }

    @Test fun oldAcceptanceCannotClearNewDeleteAfterUnconfirmedRecovery() = olderAcceptanceCannotClearNewIntent(false)

    @Test fun oldAcceptanceCannotClearNewDeclineAfterUnconfirmedRecovery() = olderAcceptanceCannotClearNewIntent(true)

    @Test fun requiredAndUnavailableProfilesDoNotAcknowledgeUnconfirmedDecline() {
        for (status in listOf(ConsentStatus.REQUIRED, ConsentStatus.UNAVAILABLE)) {
            scenario {
                consentStatus = status
                enterGrace()
                model.cancelDeletion()
                assertEquals("connection_failed", settled().error)
                decide(false, Outcome.LOST_UNCOMMITTED)
                repeat(2) { verifyPermission(false) }
                consentStatus = ConsentStatus.CURRENT
                verifyPermission(false)
                decide(true, Outcome.SUCCESS)
                verifyPermission(true)
            }
        }
    }

    @Test fun olderDeclinedProfileCannotAcknowledgeNewerUnconfirmedDecline() =
        scenario {
            consentStatus = ConsentStatus.DECLINED
            enterGrace()
            model.cancelDeletion()
            assertEquals("connection_failed", settled().error)
            decide(false, Outcome.LOST_UNCOMMITTED)
            delayProfile = true
            val olderProfile = scope.async { runCatching { auth.refreshProfile() } }
            withTimeout(5_000) { sent.await() }
            consentStatus = ConsentStatus.CURRENT
            decide(false, Outcome.LOST_UNCOMMITTED)
            release.complete(Unit)
            obsolete(olderProfile.await().exceptionOrNull())
            verifyPermission(false)
            decide(true, Outcome.SUCCESS)
            verifyPermission(true)
        }

    @Test fun freshDeclinedProfileCanConfirmLostDeclineWithoutErasingRecovery() =
        scenario {
            consentStatus = ConsentStatus.REQUIRED
            enterGrace()
            model.cancelDeletion()
            assertEquals("connection_failed", settled().error)
            decide(false, Outcome.LOST_COMMITTED)
            verifyPermission(false)
            assertEquals(
                ConsentStatus.DECLINED,
                model.state.value.consent
                    ?.status,
            )
            consentStatus = ConsentStatus.CURRENT
            verifyPermission(true)
        }

    @Test fun lostCommittedRecoveryAndNoPendingRetryAreReconciledByFreshProfile() =
        scenario {
            val originalDevice = phone.deviceId
            val match = create()
            val historical = data.events.eventsFor(match.value)
            enterGrace()
            assertNotEquals(originalDevice, phone.deviceId)
            model.cancelDeletion()
            assertEquals("connection_failed", settled().error)
            assertFalse(deleted)
            assertBlocked()
            model.exportData()
            assertEquals("exported", settled().exportedData)
            model.cancelDeletion()
            assertEquals("no_deletion_pending", settled().error)
            assertBlocked()
            model.refresh()
            val state = settled()
            assertNull(state.error)
            assertFalse(state.deletionRequested)
            assertEquals(ConsentStatus.CURRENT, state.consent?.status)
            assertEquals(AuthStep.ACCOUNT, state.step)
            auth.adoptionSources()
            assertEquals(originalDevice, phone.deviceId)
            assertEquals("alice", data.accountIdentities.profile)
            assertEquals(historical, data.events.eventsFor(match.value))
            model.refresh()
            assertNull(settled().error)
        }

    @Test fun failedDeleteCannotBeConfirmedByActiveProfileAndDoesNotHideAccountConsent() =
        scenario {
            model.requestDeletion()
            assertEquals("connection_failed", settled().error)
            assertBlocked()
            model.refresh()
            val state = settled()
            assertNull(state.error)
            assertFalse(state.deletionRequested)
            assertTrue(state.authenticated)
            assertEquals(ConsentStatus.CURRENT, state.consent?.status)
            assertEquals(AuthStep.ACCOUNT, state.step)
            assertTrue(state.adoptionSources.isEmpty())
            assertBlocked()
            model.exportData()
            assertEquals("exported", settled().exportedData)
            model.cancelDeletion()
            assertEquals("no_deletion_pending", settled().error)
            model.refresh()
            assertNull(settled().error)
            auth.adoptionSources()
        }

    @Test fun profileDuringPendingRecoveryCannotConfirmItButLaterFreshProfileCan() =
        scenario {
            enterGrace()
            delayRecovery = true
            val recovery = scope.async { runCatching { auth.cancelDeletion() } }
            sent.await()
            assertFalse(deleted)
            model.refresh()
            assertNull(settled().error)
            assertEquals(
                ConsentStatus.CURRENT,
                model.state.value.consent
                    ?.status,
            )
            assertBlocked()
            release.complete(Unit)
            assertEquals("connection_failed", (recovery.await().exceptionOrNull() as CloudException).reason)
            assertBlocked()
            model.refresh()
            assertNull(settled().error)
            auth.adoptionSources()
        }

    @Test fun recoveryThatDidNotCommitStaysInGraceUntilServerConfirmsRestoration() =
        scenario {
            enterGrace()
            commitRecovery = false
            model.cancelDeletion()
            assertEquals("connection_failed", settled().error)
            model.refresh()
            assertTrue(settled().deletionRequested)
            assertBlocked()
            assertNull(data.accountIdentities.profile)
            deleted = false
            model.refresh()
            assertFalse(settled().deletionRequested)
            auth.adoptionSources()
        }

    private fun obsolete(failure: Throwable?) {
        assertTrue(failure is CancellationException || (failure is CloudException && failure.reason == "cloud_session_changed"))
    }

    @Test fun profileStartedBeforeRecoveryIntentCannotConfirmIt() =
        scenario {
            enterGrace()
            deleted = false
            delayProfile = true
            val olderProfile = scope.async { runCatching { auth.refreshProfile() } }
            sent.await()
            model.cancelDeletion()
            assertEquals("no_deletion_pending", settled().error)
            release.complete(Unit)
            obsolete(olderProfile.await().exceptionOrNull())
            assertBlocked()
            model.refresh()
            assertNull(settled().error)
            auth.adoptionSources()
        }

    private fun newerIntentBlocksRecovery(
        decline: Boolean,
        holdProfile: Boolean = false,
    ) = scenario {
        enterGrace()
        model.cancelDeletion()
        assertEquals("connection_failed", settled().error)
        val olderProfile =
            if (holdProfile) {
                delayProfile = true
                scope.async { runCatching { auth.refreshProfile() } }.also { sent.await() }
            } else {
                null
            }
        if (decline) model.decideConsent(false) else model.requestDeletion()
        assertEquals("connection_failed", settled().error)
        if (olderProfile != null) {
            release.complete(Unit)
            obsolete(olderProfile.await().exceptionOrNull())
        }
        assertBlocked()
        repeat(2) {
            model.refresh()
            val state = settled()
            assertNull(state.error)
            assertEquals(ConsentStatus.CURRENT, state.consent?.status)
            assertTrue(state.authenticated)
            assertTrue(state.adoptionSources.isEmpty())
            assertBlocked()
            assertNull(data.accountIdentities.profile)
        }
        model.exportData()
        assertEquals("exported", settled().exportedData)
    }

    @Test fun laterFailedDeleteCannotBeAcknowledgedByRecoveryProof() = newerIntentBlocksRecovery(false)

    @Test fun laterFailedDeclineCannotBeAcknowledgedByRecoveryProof() = newerIntentBlocksRecovery(true)

    @Test fun lateRecoveryProfileCannotAcknowledgeNewerDelete() = newerIntentBlocksRecovery(false, true)

    @Test fun lateRecoveryProfileCannotAcknowledgeNewerDecline() = newerIntentBlocksRecovery(true, true)

    private fun authChangeInvalidatesRecoveryProof(newOwner: String) =
        scenario {
            enterGrace()
            model.cancelDeletion()
            assertEquals("connection_failed", settled().error)
            val previous = auth.accountSession.value!!
            delayProfile = true
            val olderProfile = scope.async { runCatching { auth.refreshProfile() } }
            sent.await()
            owner = newOwner
            signIn("session-new")
            assertFailsWith<CloudException> { auth.requireAccountSession(previous) }
            release.complete(Unit)
            obsolete(olderProfile.await().exceptionOrNull())
            assertBlocked()
            assertNull(data.accountIdentities.profile)
            model.refresh()
            assertNull(settled().error)
            assertEquals(
                newOwner,
                model.state.value.consent
                    ?.profileId,
            )
            assertNotEquals(previous.generation, auth.accountSession.value!!.generation)
            assertEquals(newOwner, data.accountIdentities.profile)
        }

    @Test fun sameAccountReloginRejectsOldRecoveryProfileUntilNewSessionIsVerified() = authChangeInvalidatesRecoveryProof("alice")

    @Test fun otherAccountRejectsOldRecoveryProfileUntilNewSessionIsVerified() = authChangeInvalidatesRecoveryProof("bob")

    @Test fun unexpectedAdoptionFailureIsReportedWithoutLosingVerifiedAccountOrConsent() =
        scenario {
            val failingAdoption =
                object : CloudAuthPort by auth {
                    override fun adoptionSources(): Nothing = throw CloudException("connection_failed", false)
                }
            val otherModel = AuthViewModel(failingAdoption, scope)
            val state = withTimeout(5_000) { otherModel.state.first { !it.busy } }
            assertEquals("connection_failed", state.error)
            assertTrue(state.authenticated)
            assertEquals(ConsentStatus.CURRENT, state.consent?.status)
            assertEquals(AuthStep.ACCOUNT, state.step)
            otherModel.exportData()
            val exported = withTimeout(5_000) { otherModel.state.first { !it.busy } }
            assertEquals("exported", exported.exportedData)
        }
}
