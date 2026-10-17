package eu.advantly.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.IdTokenProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AuthStep { AGE, PROVIDER, CODE, ALIAS, ACCOUNT }

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
)

/** Nur Speicherzustand: weder Geburtstag, Ticket noch OTP kommen in SavedState oder lokale Matchdaten. */
class AuthViewModel(
    private val auth: CloudAuthPort,
    private val workScope: CoroutineScope? = null,
    val support: eu.advantly.presentation.support.SupportViewModel? = null,
) : ViewModel() {
    override fun onCleared() {
        support?.close()
        auth.close()
        super.onCleared()
    }

    private val mutable = MutableStateFlow(AuthState())
    val state = mutable.asStateFlow()
    private var ticket: String? = null
    private var email: String = ""
    private var adoptionGeneration: Long? = null
    private val scope get() = workScope ?: viewModelScope

    init {
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
        auth.completeOnboarding(alias, username.ifBlank { null })
        showSession()
    }

    fun signOut() =
        runAction {
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

    private fun showSession() {
        val session = auth.session.value
        val candidates = if (session?.onboarded == true && !session.deletionRequested) auth.adoptionSources() else emptyList()
        adoptionGeneration = session?.generation
        mutable.value =
            AuthState(
                step =
                    when {
                        session == null -> AuthStep.AGE
                        session.onboarded || session.deletionRequested -> AuthStep.ACCOUNT
                        else -> AuthStep.ALIAS
                    },
                deletionRequested = session?.deletionRequested == true,
                adoptionSources = candidates.map { AdoptionChoice(it.key, it.kind == "WATCH", it.eventCount) },
            )
    }

    private fun runAction(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
                mutable.value = mutable.value.copy(busy = false)
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
