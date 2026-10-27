package eu.advantly.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudDevice
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentSnapshot
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.IdTokenProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AuthStep { AGE, PROVIDER, CODE, CONSENT, ALIAS, ACCOUNT }

data class AdoptionChoice(
    val key: String,
    val watch: Boolean,
    val eventCount: Long,
)

data class AuthState(
    val step: AuthStep = AuthStep.AGE,
    val busy: Boolean = false,
    val error: String? = null,
    val deletionRequested: Boolean = false,
    val adoptionSources: List<AdoptionChoice> = emptyList(),
    val selectedSources: Set<String> = emptySet(),
    val authenticated: Boolean = false,
    val consent: ConsentSnapshot? = null,
    val exportedData: String? = null,
    /** K5: Konto gesperrt; Kontorechte bleiben, Cloud-Arbeit ist pausiert. */
    val suspended: Boolean = false,
    /** K4: zuletzt geladene eigene Geräte; `null` = nicht geladen. */
    val devices: List<CloudDevice>? = null,
)

/** Nur Speicherzustand: weder Geburtstag, Ticket noch OTP kommen in SavedState oder lokale Matchdaten. */
class AuthViewModel(
    private val auth: CloudAuthPort,
    private val workScope: CoroutineScope? = null,
    val support: eu.advantly.presentation.support.SupportViewModel? = null,
    val upload: PhoneUploadPort? = null,
    val export: PhoneExportPort? = null,
    val pull: PhonePullPort? = null,
    /** Gehört dem prozessweiten Konto-Host, nicht diesem ViewModel: kein Abbau in [onCleared]. */
    val sync: PhoneSyncPort? = null,
    /** Freunde-Tab (P2-D); Arbeit endet mit diesem ViewModel. */
    val friends: eu.advantly.presentation.friends.FriendsViewModel? = null,
) : ViewModel() {
    override fun onCleared() {
        support?.close()
        friends?.close()
        var closesStarted = false
        var uploadDrained = upload == null
        var exportDrained = export == null
        var pullDrained = pull == null
        var authClosed = false

        fun closeAuthWhenDrained() {
            if (closesStarted && uploadDrained && exportDrained && pullDrained && !authClosed) {
                authClosed = true
                auth.close()
            }
        }
        // Auch synchrone Drain-Callbacks dürfen den zweiten Port nicht überholen.
        upload?.close {
            uploadDrained = true
            closeAuthWhenDrained()
        }
        export?.close {
            exportDrained = true
            closeAuthWhenDrained()
        }
        pull?.close {
            pullDrained = true
            closeAuthWhenDrained()
        }
        closesStarted = true
        closeAuthWhenDrained()
        super.onCleared()
    }

    private val mutable = MutableStateFlow(AuthState())
    val state = mutable.asStateFlow()
    private var ticket: String? = null
    private var email: String = ""
    private var adoptionGeneration: Long? = null
    private var displayedSession: CloudSession? = null
    private var consentLanguage = "en"
    private var actionRevision = 0L
    private val scope get() = workScope ?: viewModelScope

    init {
        scope.launch {
            auth.accountSession.collect { session ->
                if (displayedSession != null && !sameSession(displayedSession, session)) {
                    displayedSession = null
                    mutable.value = AuthState(busy = mutable.value.busy, error = "cloud_session_changed")
                }
            }
        }
        refresh()
    }

    fun refresh() =
        runAction {
            auth.refreshProfile()
            showSession()
        }

    fun checkAge(
        country: String,
        birthDate: String,
    ) = runAction {
        if (country !in setOf("AT", "DE", "CH", "LI")) throw CloudException("country_not_supported", false)
        if (!validBirthDate(birthDate)) throw CloudException("invalid_birth_date", false)
        ticket = auth.issueAgeTicket(country, birthDate)
        mutable.value = AuthState(AuthStep.PROVIDER)
    }

    fun sendCode(address: String) =
        runAction {
            val currentTicket = ticket ?: throw CloudException("age_ticket_expired", false)
            auth.sendEmailCode(address, currentTicket)
            email = address
            ticket = null
            mutable.value = AuthState(AuthStep.CODE)
        }

    fun verifyCode(code: String) =
        runAction {
            auth.verifyEmailCode(email, code)
            email = ""
            showSession()
        }

    /** Zugang zum Credential Manager erst nach bestandener Altersprüfung. */
    fun google(
        getCredential: suspend () -> GoogleCredential,
        link: Boolean = false,
    ) = runAction {
        val currentTicket = ticket
        if (!link && currentTicket == null) throw CloudException("age_ticket_expired", false)
        val credential = getCredential()
        if (link) {
            auth.linkGoogle(credential.idToken, credential.rawNonce)
        } else {
            auth.signInWithIdToken(IdTokenProvider.GOOGLE, credential.idToken, credential.rawNonce, currentTicket!!)
        }
        ticket = null
        showSession()
    }

    fun finish(
        alias: String,
        username: String,
    ) = runAction {
        requireDisplayedSession()
        if (mutable.value.consent?.status != ConsentStatus.CURRENT) throw CloudException("consent_required", true)
        auth.completeOnboarding(alias, username.ifBlank { null })
        showSession()
    }

    fun setConsentLanguage(language: String) {
        if (language !in setOf("de", "en") || language == consentLanguage || mutable.value.busy) return
        consentLanguage = language
        if (auth.accountSession.value != null) refresh()
    }

    fun decideConsent(accept: Boolean) =
        runAction {
            val before = requireDisplayedSession()
            val snapshot = mutable.value.consent ?: throw CloudException("consent_unavailable", false)
            if (accept && !snapshot.canAccept) throw CloudException("consent_unavailable", false)
            val result = auth.decideConsent(snapshot, accept)
            auth.requireAccountSession(before)
            if (result.profileId != before.profileId) {
                throw CloudException("cloud_session_changed", true)
            }
            showSession()
        }

    fun exportData() =
        runAction {
            val before = requireDisplayedSession()
            val data = auth.exportData()
            auth.requireAccountSession(before)
            mutable.value = mutable.value.copy(exportedData = data)
        }

    fun dismissExport() {
        mutable.value = mutable.value.copy(exportedData = null)
    }

    fun requestDeletion() =
        runAction {
            requireDisplayedSession()
            auth.requestDeletion()
            showSession()
        }

    fun cancelDeletion() =
        runAction {
            requireDisplayedSession()
            auth.cancelDeletion()
            showSession()
        }

    fun loadDevices() =
        runAction {
            val before = requireDisplayedSession()
            val devices = auth.devices()
            auth.requireAccountSession(before)
            mutable.value = mutable.value.copy(devices = devices)
        }

    /** Danach Liste neu laden, damit der Serverzustand sichtbar ist. Nie das eigene Handy (Freigabe nur von anderswo). */
    fun revokeDevice(deviceId: String) = changeDevice(deviceId, auth::revokeDevice)

    /** K4-U: Freigabe eines anderen eigenen Geräts; das eigene Handy gibt sich nie selbst frei (der Server lehnt ab). */
    fun restoreDevice(deviceId: String) = changeDevice(deviceId, auth::restoreDevice)

    private fun changeDevice(
        deviceId: String,
        change: suspend (String) -> Unit,
    ) = runAction {
        if (mutable.value.devices?.any { it.deviceId == deviceId && it.current } == true) return@runAction
        val before = requireDisplayedSession()
        change(deviceId)
        val devices = auth.devices()
        auth.requireAccountSession(before)
        mutable.value = mutable.value.copy(devices = devices)
    }

    fun signOut() =
        runAction {
            displayedSession = null
            try {
                auth.signOut()
            } finally {
                ticket = null
                email = ""
                mutable.value = AuthState()
            }
        }

    /** Abbruch verwirft den Anmeldeversuch, lokale P1-Daten bleiben unberührt. */
    fun restart() =
        runAction {
            val abandoned = ticket
            ticket = null
            email = ""
            try {
                if (abandoned != null) auth.cancelAgeTicket(abandoned)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline bleibt die feste Ablaufzeit die Obergrenze; lokal ist der Versuch bereits verworfen.
            }
            showSession()
        }

    fun selectSource(key: String) {
        if (mutable.value.busy || mutable.value.adoptionSources.none { it.key == key }) return
        val selected = mutable.value.selectedSources
        mutable.value = mutable.value.copy(selectedSources = if (key in selected) selected - key else selected + key)
    }

    fun cancelAdoption() {
        if (mutable.value.busy) return
        adoptionGeneration = null
        mutable.value = mutable.value.copy(adoptionSources = emptyList(), selectedSources = emptySet())
    }

    fun acceptAdoption() =
        runAction {
            val generation = adoptionGeneration
            if (generation == null || auth.session.value?.generation != generation) throw CloudException("cloud_session_changed", true)
            auth.adoptSources(mutable.value.selectedSources)
            adoptionGeneration = null
            mutable.value = mutable.value.copy(adoptionSources = emptyList(), selectedSources = emptySet())
        }

    private fun sameSession(
        before: CloudSession?,
        after: CloudSession?,
    ): Boolean = before?.profileId == after?.profileId && before?.generation == after?.generation

    private fun requireDisplayedSession(): CloudSession {
        val session = displayedSession ?: throw CloudException("cloud_session_changed", true)
        auth.requireAccountSession(session)
        return session
    }

    private suspend fun showSession() {
        val session = auth.accountSession.value
        displayedSession = session
        mutable.value =
            AuthState(
                step = if (session == null) AuthStep.AGE else AuthStep.ACCOUNT,
                busy = mutable.value.busy,
                authenticated = session != null,
                deletionRequested = session?.deletionRequested == true,
                suspended = session?.suspended == true,
            )
        val consent = session?.let { auth.consent(consentLanguage) }
        if (session != null) auth.requireAccountSession(session)
        if (!sameSession(session, auth.accountSession.value) || (consent != null && consent.profileId != session?.profileId)) {
            throw CloudException("cloud_session_changed", true)
        }
        displayedSession = session
        adoptionGeneration = null
        mutable.value =
            AuthState(
                step =
                    when {
                        session == null -> AuthStep.AGE
                        session.deletionRequested -> AuthStep.ACCOUNT
                        consent?.status != ConsentStatus.CURRENT -> AuthStep.CONSENT
                        session.onboarded -> AuthStep.ACCOUNT
                        else -> AuthStep.ALIAS
                    },
                busy = mutable.value.busy,
                deletionRequested = session?.deletionRequested == true,
                authenticated = session != null,
                consent = consent,
                suspended = session?.suspended == true,
            )
        if (session?.onboarded == true && !session.deletionRequested && !session.suspended && consent?.status == ConsentStatus.CURRENT) {
            val candidates =
                try {
                    auth.adoptionSources()
                } catch (error: CloudException) {
                    if (!error.permission ||
                        error.reason !in setOf("profile_not_active", "consent_required", "consent_unavailable")
                    ) {
                        throw error
                    }
                    emptyList()
                }
            auth.requireAccountSession(session)
            adoptionGeneration = auth.session.value?.generation
            mutable.value =
                mutable.value.copy(adoptionSources = candidates.map { AdoptionChoice(it.key, it.kind == "WATCH", it.eventCount) })
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        check(actionRevision < Long.MAX_VALUE)
        val revision = ++actionRevision
        mutable.value = mutable.value.copy(busy = true, error = null)
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (revision != actionRevision) return@launch
                val reason = (e as? CloudException)?.reason ?: "connection_failed"
                val reset =
                    reason in setOf("age_ticket_expired", "age_ticket_bound", "age_ticket_required", "age_retry_later", "age_below_minimum")
                if (reset) ticket = null
                mutable.value =
                    mutable.value.copy(
                        step = if (reset) AuthStep.AGE else mutable.value.step,
                        error = reason.takeUnless { it == "auth_cancelled" },
                    )
            } finally {
                if (revision == actionRevision) mutable.value = mutable.value.copy(busy = false)
            }
        }
    }
}

/** Host-spezifischer Credential-Manager/iOS-SDK-Zugang, keine Plattformtypen in der Phone-UI. */
class GoogleCredential(
    val idToken: String,
    val rawNonce: String,
)

/** Kalenderprüfung ohne Clock: das Mindestalter prüft ausschließlich der Server mit Wohnsitzzeitzone. */
internal fun validBirthDate(value: String): Boolean {
    if (!value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return false
    val year = value.substring(0, 4).toInt()
    val month = value.substring(5, 7).toInt()
    val day = value.substring(8, 10).toInt()
    if (year < 1900 || month !in 1..12) return false
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val days = listOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    return day in 1..days[month - 1]
}
