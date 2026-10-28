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
import kotlin.test.assertTrue

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

    @Test fun suspendedAccountShowsReasonKeepsRightsAndSkipsAdoption() {
        val auth =
            FakePhoneAuth().also {
                it.session.value = CloudSession("alice", true, suspended = true)
                it.sources = listOf(CloudAdoptionSource("opaque", "WATCH", 8))
            }
        val model = AuthViewModel(auth, scope)
        assertEquals(AuthStep.ACCOUNT, model.state.value.step)
        assertEquals(true, model.state.value.suspended)
        assertFalse(model.state.value.accountRequired, "gesperrt: Zählen bleibt möglich, der Grund steht im Profil")
        assertNull(model.state.value.error)
        assertEquals(emptyList(), auth.adopted, "keine Übernahme in ein gesperrtes Konto")
        model.exportData()
        assertEquals("{\"consentHistory\":[]}", model.state.value.exportedData)
        auth.session.value = CloudSession("alice", true)
        model.refresh()
        assertFalse(model.state.value.suspended)
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

    @Test fun signInAdoptsAllOfferedPhoneAndWatchSourcesWithoutAQuestion() {
        val auth =
            FakePhoneAuth().also {
                it.session.value = CloudSession("alice", true, generation = 4)
                it.sources = listOf(CloudAdoptionSource("old-phone", "PHONE", 3), CloudAdoptionSource("old-watch", "WATCH", 8))
            }
        val model = AuthViewModel(auth, scope)
        assertEquals(listOf(setOf("old-phone", "old-watch")), auth.adopted)
        assertFalse(model.state.value.accountRequired)
        // Später gekoppelte Uhr: die nächste Kontoprüfung übernimmt auch ihre Matches.
        auth.sources = listOf(CloudAdoptionSource("new-watch", "WATCH", 2))
        model.refresh()
        assertEquals(setOf("new-watch"), auth.adopted.last())
        auth.sources = emptyList()
        model.refresh()
        assertEquals(2, auth.adopted.size, "nichts mehr offen: kein leerer Übernahmeaufruf")
    }

    @Test fun noAdoptionBeforeConsentIsCurrent() {
        val auth =
            FakePhoneAuth().also {
                it.session.value = CloudSession("alice", true)
                it.consentSnapshot = ConsentSnapshot("alice", ConsentStatus.REQUIRED, documents())
                it.sources = listOf(CloudAdoptionSource("old-watch", "WATCH", 8))
            }
        val model = AuthViewModel(auth, scope)
        assertEquals(emptyList(), auth.adopted)
        model.decideConsent(true)
        assertEquals(listOf(setOf("old-watch")), auth.adopted)
    }

    @Test fun withoutAccountOnlyTheAccountFlowIsShownAndThereIsNoSkip() {
        val model = AuthViewModel(FakePhoneAuth(), scope)
        assertEquals(AuthStep.AGE, model.state.value.step)
        assertTrue(model.state.value.accountRequired)
        model.checkAge("AT", "2000-01-01")
        model.sendCode("a@example.com")
        model.verifyCode("123456")
        assertEquals(AuthStep.ALIAS, model.state.value.step)
        assertTrue(model.state.value.accountRequired, "ohne Profil kein Zählen")
        model.finish("Alias", "alias")
        assertFalse(model.state.value.accountRequired)
        model.signOut()
        assertTrue(model.state.value.accountRequired, "nach dem Abmelden wieder die Anmeldung")
    }

    @Test fun outdatedConsentBlocksUntilConfirmedAndDeclineStaysVisible() {
        val auth = FakePhoneAuth().also { it.session.value = CloudSession("alice", true) }
        val model = AuthViewModel(auth, scope)
        assertFalse(model.state.value.accountRequired)
        // Neue Fassung freigegeben (wie PRIVACY 3 am 27.10.): beim nächsten Prüfen blockiert der Bestätigungsschirm.
        auth.consentSnapshot = ConsentSnapshot("alice", ConsentStatus.REQUIRED, documents("v2"))
        model.refresh()
        assertEquals(AuthStep.CONSENT, model.state.value.step)
        assertTrue(model.state.value.accountRequired)
        model.decideConsent(false)
        assertEquals(
            ConsentStatus.DECLINED,
            model.state.value.consent
                ?.status,
        )
        assertTrue(model.state.value.accountRequired, "Ablehnen ist kein stilles Weiterzählen ohne Sicherung")
        model.decideConsent(true)
        assertFalse(model.state.value.accountRequired)
    }

    @Test fun deletionRequestShowsTheAccountFlowUntilRecovered() {
        val auth = FakePhoneAuth().also { it.session.value = CloudSession("alice", true) }
        val model = AuthViewModel(auth, scope)
        model.requestDeletion()
        assertTrue(model.state.value.accountRequired)
        model.cancelDeletion()
        assertFalse(model.state.value.accountRequired)
    }

    @Test fun storedSessionKeepsCountingOfflineButPermissionErrorsShowTheAccountFlow() {
        val auth =
            FakePhoneAuth().also {
                it.session.value = CloudSession("alice", true)
                it.failure = "connection_failed"
            }
        val model = AuthViewModel(auth, scope)
        assertEquals("connection_failed", model.state.value.error)
        assertTrue(model.state.value.unconfirmed)
        assertFalse(model.state.value.accountRequired, "offline mit gespeicherter Sitzung weiterzählen")
        auth.failure = null
        model.refresh()
        assertFalse(model.state.value.unconfirmed)
        assertFalse(model.state.value.accountRequired)
        auth.failure = "profile_missing"
        model.refresh()
        assertFalse(model.state.value.unconfirmed)
        assertTrue(model.state.value.accountRequired)
    }

    @Test fun pendingFirstCheckDoesNotBlock() {
        assertFalse(AuthState(unconfirmed = true).accountRequired)
        assertTrue(AuthState().accountRequired)
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

    @Test fun malformedCountryDoesNotCallBackendOrProvider() {
        for (code in listOf("U1", "A", "USA", "")) {
            val auth = FakePhoneAuth()
            val model = AuthViewModel(auth, scope)
            model.checkAge(code, "2000-01-01")
            assertEquals(listOf("refresh"), auth.calls, code)
            assertEquals("country_not_supported", model.state.value.error, code)
        }
    }

    @Test fun anyIsoCountryIsCheckedByBackend() {
        val auth = FakePhoneAuth()
        val model = AuthViewModel(auth, scope)
        model.checkAge("US", "2000-01-01")
        assertEquals(listOf("refresh", "age:US"), auth.calls)
        assertEquals(AuthStep.PROVIDER, model.state.value.step)
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
