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
 * Host-Vertrag B4: Bindung, Pull und Upload eines Kontos laufen streng nacheinander, nie parallel.
 * Das Backend hält genau eine Gerätebindung; deshalb teilen beide Hosts auch deren Stand.
 */
internal class PhoneCloudLane {
    val mutex = Mutex()

    /** Zuletzt erfolgreich gebundener Kontext; nur unter [mutex] schreiben. */
    var bound: PhoneCloudContext? = null
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
