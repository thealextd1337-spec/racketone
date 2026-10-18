package eu.advantly.data

/** Kontogebundener Restbestand; blockierte Projektionen zählen mit, Platzhalter dagegen nicht. */
data class CloudUploadInventory(
    val pendingEvents: Long,
    val rejectedEvents: Long,
    val pendingMetadata: Long,
)
