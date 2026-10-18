package eu.advantly.cloud.sync

import eu.advantly.data.CloudUploadInventory

/** Direkt gelesener Restbestand mit geprüfter Kontositzung, ohne Auswahl eines Upload-Batches. */
data class CloudUploadStatus(
    val profileId: String,
    val generation: Long,
    val inventory: CloudUploadInventory,
)

enum class CloudUploadOutcome { IDLE, RUNNING, DRAINED, BLOCKED, LIMIT_REACHED, PARTIAL, FAILED, CANCELLED, CLOSED }

enum class CloudUploadFailureKind { PERMISSION, TRANSIENT, RESPONSE, LOCAL }

/** Ausschließlich stabile Schlüssel, keine Exception-Texte, Tokens oder Server-Payloads. */
data class CloudUploadFailure(
    val kind: CloudUploadFailureKind,
    val reason: String,
)

/** Momentaufnahme von [observedAtMs], keine Aussage über später neu hinzugekommene lokale Daten. */
data class CloudUploadState(
    val outcome: CloudUploadOutcome = CloudUploadOutcome.IDLE,
    val profileId: String? = null,
    val generation: Long? = null,
    val inventory: CloudUploadInventory? = null,
    val observedAtMs: Long? = null,
    /** Nur eine tatsächlich erhaltene und innerhalb derselben Sitzung angewandte Antwort setzt diesen Wert. */
    val lastSuccessAtMs: Long? = null,
    val completedBatches: Int = 0,
    val missingEventResponseIds: Set<String> = emptySet(),
    val failure: CloudUploadFailure? = null,
) {
    val running: Boolean get() = outcome == CloudUploadOutcome.RUNNING
    val fullyUploaded: Boolean
        get() =
            outcome == CloudUploadOutcome.DRAINED && inventory == CloudUploadInventory(0, 0, 0)
}
