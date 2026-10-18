package eu.advantly.cloud.sync

import kotlinx.serialization.Serializable

/** Ausschließlich vom Server bestätigter Stand; fehlende/alte Serverantworten geben nichts frei. */
@Serializable
enum class ConsentStatus { UNAVAILABLE, REQUIRED, DECLINED, CURRENT }

@Serializable
enum class ConsentDocumentType { TERMS, PRIVACY }

@Serializable
data class ConsentDocument(
    val type: ConsentDocumentType,
    val version: String,
    val language: String,
    val url: String? = null,
    val approved: Boolean = false,
    val accepted: Boolean = false,
    val decidedAt: String? = null,
)

@Serializable
data class ConsentSnapshot(
    val profileId: String,
    val status: ConsentStatus = ConsentStatus.UNAVAILABLE,
    val documents: List<ConsentDocument> = emptyList(),
) {
    val canAccept: Boolean
        get() = documents.size == 2 && documents.map { it.type }.toSet().size == 2 && documents.all { it.approved && it.url != null }
}
