package eu.advantly.presentation.auth

import kotlinx.coroutines.flow.StateFlow

enum class PhoneUploadPhase { IDLE, BINDING, UPLOADING, CLOSED }

enum class PhoneUploadResult { DRAINED, PARTIAL, LIMIT_REACHED, BLOCKED, FAILED, CANCELLED }

enum class PhoneUploadAction { NONE, UPLOAD, RETRY }

enum class PhoneUploadError { CONNECTION, PERMISSION, RESPONSE, LOCAL }

/** Ausschließlich eine datierte Momentaufnahme der diesem Konto zugeordneten lokalen Daten. */
data class PhoneUploadState(
    val available: Boolean = false,
    val busy: Boolean = false,
    val phase: PhoneUploadPhase = PhoneUploadPhase.IDLE,
    val pendingEvents: Long? = null,
    val rejectedEvents: Long? = null,
    val pendingMetadata: Long? = null,
    val observedAtMs: Long? = null,
    val lastResponseAtMs: Long? = null,
    val stale: Boolean = false,
    val result: PhoneUploadResult? = null,
    val error: PhoneUploadError? = null,
    val action: PhoneUploadAction = PhoneUploadAction.NONE,
)

/** Phone-Präsentation ohne SDK-, Netzwerk- oder Datenbanktypen. Alle Aufrufe auf dem seriellen Host-Kontext. */
interface PhoneUploadPort {
    val state: StateFlow<PhoneUploadState>

    fun uploadNow()

    /** Ausschließlich lokal lesen; weder Gerätebindung noch Uploadauswahl oder Versand. */
    fun refreshStatus()

    /**
     * Sperrt synchron neue Aktionen und cancelt den gesamten Upload-/Bindungs-Child.
     * [onDrained] läuft erst nach dessen vollständigem Ende auf dem Host-Kontext, unabhängig vom ViewModelScope.
     */
    fun close(onDrained: () -> Unit)
}
