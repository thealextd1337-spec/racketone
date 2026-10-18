package eu.advantly.presentation.auth

import kotlinx.coroutines.flow.StateFlow

enum class PhoneExportPhase {
    UNAVAILABLE,
    IDLE,
    REQUESTING,
    OUTCOME_UNKNOWN,
    PENDING,
    SERVER_BUSY,
    CHECKING,
    READY,
    DOWNLOADING,
    LOCAL_FILE,
    FAILED,
    CLOSED,
}

enum class PhoneExportAction { REQUEST, RETRY, REFRESH_STATUS, DOWNLOAD, CANCEL, DISCARD, SHARE }

enum class PhoneExportError { CONNECTION, SESSION_REJECTED, NOT_FOUND, EXPIRED, TOO_LARGE, INVALID_RESPONSE, INTEGRITY, LOCAL_IO, TIMEOUT }

/** Reine Präsentationsdaten; niemals Request-ID, Konto, Credentials oder lokale Dateireferenzen. */
data class PhoneExportState(
    val phase: PhoneExportPhase = PhoneExportPhase.UNAVAILABLE,
    val allowedActions: Set<PhoneExportAction> = emptySet(),
    val error: PhoneExportError? = null,
    val exportedAtMs: Long? = null,
    val expiresAtMs: Long? = null,
    val byteLength: Long? = null,
    val retryAtMs: Long? = null,
) {
    val busy: Boolean
        get() = phase == PhoneExportPhase.REQUESTING || phase == PhoneExportPhase.CHECKING || phase == PhoneExportPhase.DOWNLOADING
}

/**
 * Unabhängiger Exportzustand auf dem seriellen Host-Kontext; Aktionen starten dort eigene Jobs.
 * Der Host prüft Konto, Exportrevision, Ablauf und Wartefrist erneut bei jeder Aktion und Freigabe.
 * Retry und Status behalten die ursprüngliche Request-ID. Keine automatische Wiederholung oder Statusabfrage.
 * Der Host veröffentlicht neue Aktionen nach einer Wartefrist, ohne dadurch Netzwerkverkehr auszulösen.
 */
interface PhoneExportPort {
    val state: StateFlow<PhoneExportState>

    fun requestExport()

    fun retry()

    fun refreshStatus()

    fun download()

    /** Beendet nur lokale Arbeit; ein bereits gestarteter Serverexport kann weiterlaufen. */
    fun cancel()

    /** Entwertet die lokale Ausgabe und verwirft den lokalen Exportkontext. */
    fun discard()

    /**
     * Sperrt synchron alle Aktionen, entwertet Dateien und bricht laufende Arbeit ab.
     * [onDrained] erst nach tatsächlichem Ende aller Jobs und Bereinigung aufrufen,
     * auch bei abgebrochenem aufrufendem Scope. Wiederholte Aufrufe bleiben sicher.
     */
    fun close(onDrained: () -> Unit)
}
