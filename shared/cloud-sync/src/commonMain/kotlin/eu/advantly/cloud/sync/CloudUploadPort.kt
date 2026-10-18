package eu.advantly.cloud.sync

import eu.advantly.data.CloudUploadInventory

/** Nur Upload. Keine Pull-Implementierung, Gerätebindung oder automatische Anmeldung. */
interface CloudUploadPort {
    fun captureUpload(limit: Int = PushBatch.MAX_EVENTS_PER_PUSH): CloudUploadAttempt

    /** Prüft Auth, Zustimmung, lokale Auswahl und dieselbe ausdrücklich autorisierte Gerätebindung. */
    fun requireCurrentUpload(attempt: CloudUploadAttempt)

    /** Neue Auswahl nur innerhalb des noch gültigen vorherigen Versuchs; ein Refresh darf den Bearer erneuern. */
    fun nextUpload(
        attempt: CloudUploadAttempt,
        limit: Int = PushBatch.MAX_EVENTS_PER_PUSH,
    ): CloudUploadAttempt

    /** Aktueller Restbestand derselben Sitzung; verändert einen aufbewahrten Retry-Versuch nicht. */
    fun uploadInventory(attempt: CloudUploadAttempt): CloudUploadInventory

    /** Direkt lesen, ohne neue Event-/Metadatenauswahl; ein Retry bleibt an seinen alten Versuch gebunden. */
    fun readUploadStatus(attempt: CloudUploadAttempt? = null): CloudUploadStatus

    suspend fun pushSelected(
        attempt: CloudUploadAttempt,
        batch: PushBatch,
    ): PushResponse
}
