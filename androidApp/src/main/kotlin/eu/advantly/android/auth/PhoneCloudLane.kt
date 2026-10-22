package eu.advantly.android.auth

import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.match.session.MatchSession
import kotlinx.coroutines.sync.Mutex

/** Konto, Auth-Generation und eigenes Phone eines Cloud-Laufs; jede Abweichung entwertet Bindung und Ergebnisse. */
internal data class PhoneCloudContext(
    val profile: String,
    val generation: Long,
    val device: String,
)

/**
 * Host-Vertrag B4: Bindung, Pull und Upload eines Kontos laufen streng nacheinander, nie parallel –
 * prozessweit, also auch über mehrere Konto-ViewModels (Multi-Window, alte VM im Drain neben der neuen).
 */
private val accountLocks = HashMap<String, Mutex>()

/** Je Konto-ViewModel und Backend: Pull- und Uploadhost teilen dessen eine Gerätebindung. */
internal class PhoneCloudLane {
    /** Zuletzt erfolgreich gebundener Kontext dieses Backends; nur unter [mutex] des Kontos schreiben. */
    var bound: PhoneCloudContext? = null

    fun mutex(profile: String): Mutex = synchronized(accountLocks) { accountLocks.getOrPut(profile) { Mutex() } }
}

/** Gemeinsame Gates von Pull und Upload: Restore-Gate, Anmeldung, Einwilligung, kein Löschantrag, eigenes Phone. */
internal fun readPhoneCloudContext(
    auth: CloudAuthPort,
    session: MatchSession,
    hostAccessAllowed: () -> Boolean,
): PhoneCloudContext? {
    if (!hostAccessAllowed()) return null
    val active = auth.session.value ?: return null
    if (!active.onboarded || active.deletionRequested || active.consentStatus != ConsentStatus.CURRENT) return null
    val identities = session.data.accountIdentities
    val device = session.deviceId.value
    if (identities.profile != active.profileId ||
        identities.ownedSources(active.profileId).none {
            it.deviceId == device && it.kind == "PHONE"
        }
    ) {
        return null
    }
    return PhoneCloudContext(active.profileId, active.generation, device)
}
