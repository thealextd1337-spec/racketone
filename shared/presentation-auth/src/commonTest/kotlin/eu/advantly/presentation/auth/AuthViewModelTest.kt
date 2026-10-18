package eu.advantly.presentation.auth

import eu.advantly.cloud.sync.CloudAdoptionSource
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentDocument
import eu.advantly.cloud.sync.ConsentDocumentType
import eu.advantly.cloud.sync.ConsentSnapshot
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.IdTokenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FakePhoneAuth : CloudAuthPort {
    override val session = MutableStateFlow<CloudSession?>(null)
    val calls = mutableListOf<String>()
    var failure: String? = null
    var sources: List<CloudAdoptionSource> = emptyList()
    val adopted = mutableListOf<Set<String>>()
    var consentStatus = ConsentStatus.CURRENT
    var consentSnapshot: ConsentSnapshot? = null
    var duringConsent: (() -> Unit)? = null

    override suspend fun consent(language: String): ConsentSnapshot =
        consentSnapshot ?: ConsentSnapshot(session.value!!.profileId, consentStatus)

    override suspend fun decideConsent(
        snapshot: ConsentSnapshot,
        accept: Boolean,
    ): ConsentSnapshot {
        call("consent:$accept")
        duringConsent?.invoke()
        consentStatus = if (accept) ConsentStatus.CURRENT else ConsentStatus.DECLINED
        consentSnapshot = snapshot.copy(status = consentStatus)
        return consentSnapshot!!
    }

    override suspend fun exportData(): String {
        call("export")
        return "{\"consentHistory\":[]}"
    }

    override suspend fun requestDeletion() {
        call("delete")
        session.value = session.value!!.copy(deletionRequested = true)
    }

    override suspend fun cancelDeletion() {
        call("recover")
        session.value = session.value!!.copy(deletionRequested = false)
    }

    override fun adoptionSources(): List<CloudAdoptionSource> = sources

    override fun adoptSources(ids: Set<String>) {
        adopted += ids
    }

    private fun call(name: String) {
        calls += name
        failure?.let { throw CloudException(it, false) }
    }

    override suspend fun issueAgeTicket(
        country: String,
        birthDate: String,
    ): String {
        call("age:$country")
        return "ticket"
    }

    override suspend fun signInWithIdToken(
        provider: IdTokenProvider,
        idToken: String,
        rawNonce: String?,
        ticket: String,
    ) {
        call("google:$ticket")
        session.value = CloudSession("id", false)
    }

    override suspend fun cancelAgeTicket(ticket: String) {
        call("cancel:$ticket")
    }

    override suspend fun linkGoogle(
        idToken: String,
        rawNonce: String?,
    ) {
        call("link")
    }

    override suspend fun sendEmailCode(
        email: String,
        ticket: String,
    ) {
        call("send:$ticket")
    }

    override suspend fun verifyEmailCode(
        email: String,
        code: String,
    ) {
        call("verify")
        session.value = CloudSession("id", false)
    }

    override suspend fun completeOnboarding(
        alias: String,
        username: String?,
    ) {
        call("finish")
        session.value = CloudSession("id", true)
    }

    override suspend fun refreshProfile() {
        call("refresh")
    }

    override suspend fun signOut() {
        session.value = null
        call("logout")
    }
}

class AuthViewModelTest {
    @Test fun failedDeclineDoesNotPreventIndependentAccountRights() {
        val auth =
            FakePhoneAuth().also {
                it.session.value = CloudSession("alice", true)
                it.consentSnapshot = ConsentSnapshot("alice", ConsentStatus.REQUIRED, documents())
            }
        val model = AuthViewModel(auth, scope)
        auth.failure = "connection_failed"
        model.decideConsent(false)
        assertEquals("connection_failed", model.state.value.error)
        auth.failure = null
        model.exportData()
        assertEquals("export", auth.calls.last())
        assertEquals("{\"consentHistory\":[]}", model.state.value.exportedData)
        model.requestDeletion()
        model.cancelDeletion()
        assertEquals("recover", auth.calls.last())
    }

    private fun documents(version: String = "v1") =
        ConsentDocumentType.entries.map { ConsentDocument(it, version, "en", "https://advantly.eu/document", approved = true) }

    @Test fun missingReleaseCannotBeAcceptedAndRightsRemainAvailable() {
        val auth =
            FakePhoneAuth().also {
                it.session.value = CloudSession("alice", true)
                it.consentStatus = ConsentStatus.UNAVAILABLE
            }
        val model = AuthViewModel(auth, scope)
        assertEquals(AuthStep.CONSENT, model.state.value.step)
        model.decideConsent(true)
        assertEquals("consent_unavailable", model.state.value.error)
        assertFalse(auth.calls.contains("consent:true"))
        model.exportData()
        assertEquals("{\"consentHistory\":[]}", model.state.value.exportedData)
        model.requestDeletion()
        assertEquals(AuthStep.ACCOUNT, model.state.value.step)
        model.cancelDeletion()
        assertEquals(AuthStep.CONSENT, model.state.value.step)
    }

    @Test fun requiredVersionNeedsExplicitDecisionAndDeclineDoesNotFinishOnboarding() {
        val auth =
            FakePhoneAuth().also {
                it.session.value = CloudSession("alice", false)
                it.consentSnapshot = ConsentSnapshot("alice", ConsentStatus.REQUIRED, documents())
            }
        val model = AuthViewModel(auth, scope)
        model.finish("Alias", "alias")
        assertEquals("consent_required", model.state.value.error)
        assertFalse(auth.calls.contains("finish"))
        model.decideConsent(false)
        assertEquals(
            ConsentStatus.DECLINED,
            model.state.value.consent
                ?.status,
        )
        assertEquals(AuthStep.CONSENT, model.state.value.step)
        model.decideConsent(true)
        assertEquals(AuthStep.ALIAS, model.state.value.step)
        model.finish("Alias", "alias")
        assertEquals(AuthStep.ACCOUNT, model.state.value.step)
        auth.consentSnapshot = ConsentSnapshot("id", ConsentStatus.REQUIRED, documents("v2"))
        model.refresh()
        assertEquals(AuthStep.CONSENT, model.state.value.step)
        assertEquals(
            "v2",
            model.state.value.consent!!
                .documents
                .first()
                .version,
        )
    }

    @Test fun delayedDecisionCannotPublishAcrossSessionChangeEvenForSameAccount() {
        val auth =
            FakePhoneAuth().also {
                it.session.value = CloudSession("alice", true, generation = 1)
                it.consentSnapshot = ConsentSnapshot("alice", ConsentStatus.REQUIRED, documents())
                it.duringConsent = { it.session.value = CloudSession("alice", true, generation = 2) }
            }
        val model = AuthViewModel(auth, scope)
        model.decideConsent(true)
        assertEquals("cloud_session_changed", model.state.value.error)
        assertNull(model.state.value.consent)
        assertNull(model.state.value.exportedData)
    }

    @Test fun calendarValidationRejectsInvalidDatesBeforeTransport() {
        assertFalse(validBirthDate("2001-02-29"))
        assertFalse(validBirthDate("2000-13-01"))
        assertFalse(validBirthDate("2000-01-00"))
        assertFalse(validBirthDate("not-a-date"))
        assertEquals(true, validBirthDate("2000-02-29"))
    }

    @Test fun adoptionRequiresSelectionAndCancelLeavesSourcesUnbound() {
        val auth =
            FakePhoneAuth().also {
                it.session.value = CloudSession("alice", true, generation = 4)
                it.sources = listOf(CloudAdoptionSource("opaque", "WATCH", 8))
            }
        val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(1, model.state.value.adoptionSources.size)
        model.selectSource("opaque")
        model.cancelAdoption()
        assertEquals(emptyList(), auth.adopted)
        assertEquals(emptySet(), model.state.value.selectedSources)
        model.refresh()
        model.selectSource("opaque")
        auth.session.value = CloudSession("bob", true, generation = 5)
        model.acceptAdoption()
        assertEquals("cloud_session_changed", model.state.value.error)
        assertEquals(emptyList(), auth.adopted)
        model.refresh()
        model.selectSource("opaque")
        model.acceptAdoption()
        assertEquals(listOf(setOf("opaque")), auth.adopted)
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test fun otpOnboardingRequiresAgeThenAlias() {
        val auth = FakePhoneAuth()
        val model = AuthViewModel(auth, scope)
        model.sendCode("a@example.com")
        assertEquals("age_ticket_expired", model.state.value.error)
        model.checkAge("AT", "2000-01-01")
        assertEquals(AuthStep.PROVIDER, model.state.value.step)
        model.sendCode("a@example.com")
        assertEquals(AuthStep.CODE, model.state.value.step)
        model.verifyCode("123456")
        assertEquals(AuthStep.ALIAS, model.state.value.step)
        model.finish("Alias", "alias")
        assertEquals(AuthStep.ACCOUNT, model.state.value.step)
        model.signOut()
        assertEquals(AuthStep.AGE, model.state.value.step)
        assertNull(auth.session.value)
    }

    @Test fun unsupportedCountryDoesNotCallBackendOrProvider() {
        val auth = FakePhoneAuth()
        val model = AuthViewModel(auth, scope)
        model.checkAge("US", "2000-01-01")
        assertEquals(listOf("refresh"), auth.calls)
        assertEquals("country_not_supported", model.state.value.error)
    }

    @Test fun ageRejectionAndExpiredTicketReturnToGate() {
        for (reason in listOf("age_below_minimum", "age_retry_later", "age_ticket_expired", "age_ticket_required")) {
            val auth = FakePhoneAuth()
            val model = AuthViewModel(auth, scope)
            model.checkAge("DE", "2000-01-01")
            auth.failure = reason
            model.sendCode("a@example.com")
            assertEquals(AuthStep.AGE, model.state.value.step)
            assertEquals(reason, model.state.value.error)
            assertFalse(model.state.value.busy)
        }
    }

    @Test fun offlineAndUsernameCollisionPreserveRetryStep() {
        val auth = FakePhoneAuth()
        auth.session.value = CloudSession("id", false)
        val model = AuthViewModel(auth, scope)
        auth.failure = "username_taken"
        model.finish("Alias", "taken")
        assertEquals(AuthStep.ALIAS, model.state.value.step)
        auth.failure = "connection_failed"
        model.finish("Alias", "free")
        assertEquals(AuthStep.ALIAS, model.state.value.step)
        assertEquals("connection_failed", model.state.value.error)
    }

    @Test fun staleProfileOffersReauthenticationAndOfflineLogoutClearsLocalSession() {
        val auth = FakePhoneAuth()
        val model = AuthViewModel(auth, scope)
        auth.failure = "profile_missing"
        model.refresh()
        assertEquals("profile_missing", model.state.value.error)
        auth.session.value = CloudSession("id", false)
        auth.failure = "connection_failed"
        model.signOut()
        assertNull(auth.session.value)
        assertEquals(AuthStep.AGE, model.state.value.step)
    }

    @Test fun googleAndManualLinkAreSeparateActions() {
        val auth = FakePhoneAuth()
        val model = AuthViewModel(auth, scope)
        model.google({ error("Must not open credential manager before gate") })
        assertEquals("age_ticket_expired", model.state.value.error)
        model.checkAge("CH", "2000-01-01")
        model.google({ GoogleCredential("token", "nonce") })
        assertEquals(AuthStep.ALIAS, model.state.value.step)
        model.finish("Alias", "alias")
        model.google({ GoogleCredential("token", "nonce") }, link = true)
        assertEquals("link", auth.calls.last())
        assertEquals(AuthStep.ACCOUNT, model.state.value.step)
    }

    @Test fun restartDiscardsTicket() {
        val auth = FakePhoneAuth()
        val model = AuthViewModel(auth, scope)
        model.checkAge("LI", "2000-01-01")
        model.restart()
        assertEquals("cancel:ticket", auth.calls.last())
        model.sendCode("a@example.com")
        assertEquals("age_ticket_expired", model.state.value.error)
    }

    @Test fun offlineCancellationStillDiscardsTicket() {
        val auth = FakePhoneAuth()
        val model = AuthViewModel(auth, scope)
        model.checkAge("AT", "2000-01-01")
        auth.failure = "connection_failed"
        model.restart()
        assertEquals(AuthStep.AGE, model.state.value.step)
        assertNull(model.state.value.error)
        model.google({ error("Discarded ticket must not open credentials") })
        assertEquals("age_ticket_expired", model.state.value.error)
    }

    @Test fun leavingAccountKeepsSessionAndCredentialCancellationCanRetry() {
        val auth = FakePhoneAuth()
        val model = AuthViewModel(auth, scope)
        model.checkAge("AT", "2000-01-01")
        model.google({ throw CloudException("auth_cancelled", false) })
        assertEquals(AuthStep.PROVIDER, model.state.value.step)
        assertNull(model.state.value.error)
        model.google({ GoogleCredential("token", "nonce") })
        model.restart()
        assertEquals(AuthStep.ALIAS, model.state.value.step)
    }
}
