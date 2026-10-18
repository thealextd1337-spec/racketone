package eu.advantly.cloud.sync

import kotlinx.coroutines.flow.StateFlow

/** Verifiziertes Konto mit eigener Exportrevision; keine Cloud-Freigabe oder Credentials. */
data class CloudDataExportContext(
    val profileId: String,
    val generation: Long,
    val revision: Long,
)

sealed interface CloudDataExportStatus {
    data class Pending(
        val requestId: String,
        val retryAfterSeconds: Long,
    ) : CloudDataExportStatus

    data class Ready(
        val requestId: String,
        val exportedAtMs: Long,
        val expiresAtMs: Long,
        val byteLength: Long,
        val sha256: String,
    ) : CloudDataExportStatus

    data class Busy(
        val retryAfterSeconds: Long,
    ) : CloudDataExportStatus
}

/** Nur waehrend write gueltige Frames bis 64 KiB; der Empfaenger darf sie nicht ungeprueft teilen. */
fun interface CloudDataExportSink {
    suspend fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )
}

/** Auf dem seriellen Auth-Host verwenden; jeder Aufruf prueft zusaetzlich die aktuelle SDK-Identitaet. */
interface CloudDataExportPort {
    val context: StateFlow<CloudDataExportContext?>

    fun requireCurrent(context: CloudDataExportContext)

    suspend fun request(
        context: CloudDataExportContext,
        requestId: String,
    ): CloudDataExportStatus

    suspend fun status(
        context: CloudDataExportContext,
        requestId: String,
    ): CloudDataExportStatus

    /** Prueft Transport und Laenge; SHA-256 muss der Dateihost vor der Promotion selbst verifizieren. */
    suspend fun download(
        context: CloudDataExportContext,
        ready: CloudDataExportStatus.Ready,
        sink: CloudDataExportSink,
    )
}
