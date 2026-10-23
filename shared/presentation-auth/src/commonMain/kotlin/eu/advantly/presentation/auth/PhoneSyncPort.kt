package eu.advantly.presentation.auth

import kotlinx.coroutines.flow.StateFlow

/** Ergebnis des letzten koordinierten Abgleichs (erst Download, dann Upload) dieses Kontos. */
enum class PhoneSyncStatus { BACKED_UP, PENDING, BLOCKED, CONNECTION_FAILED, FAILED }

/** Grund einer pausierten Sicherung: die Download-Gründe aus [PhonePullBlock] und die beiden Upload-Reste. */
enum class PhoneSyncBlock { PERMISSION, REMOVALS, REDACTIONS, MATERIALIZATION, READINESS, UPLOAD_REJECTED, UPLOAD_WAITING }

/** Sicherungsstatus des automatischen Abgleichs; BLOCKED nennt immer einen Grund. */
data class PhoneSyncState(
    val available: Boolean = false,
    val running: Boolean = false,
    val status: PhoneSyncStatus? = null,
    val blocked: PhoneSyncBlock? = null,
    /** Letzter vollständiger Upload dieses Kontos, auch aus Hintergrundläufen und über ein Prozessende hinweg. */
    val lastBackupAtMs: Long? = null,
    /** Frühester geplanter automatischer Versuch nach Fehler (Backoff) oder erreichtem Limit. */
    val nextAttemptAtMs: Long? = null,
)

/** Phone-Präsentation ohne SDK-, Netzwerk- oder Datenbanktypen. Alle Aufrufe auf dem seriellen Host-Kontext. */
interface PhoneSyncPort {
    val state: StateFlow<PhoneSyncState>

    /** „Jetzt synchronisieren“: erst Download, dann Upload, ohne Wartezeit eines Backoffs. */
    fun syncNow()

    /** App im Vordergrund: höchstens ein gedrosselter automatischer Abgleich. */
    fun onForeground()
}
