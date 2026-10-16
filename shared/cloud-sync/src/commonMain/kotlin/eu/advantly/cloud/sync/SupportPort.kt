package eu.advantly.cloud.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Versandidentität nur im Speicher; das Token wird weder im Entwurf noch in Diagnosen gespeichert. */
class SupportIdentity(
    val ownerId: String?,
    internal val accessToken: String?,
)

interface SupportPort {
    val owners: Flow<String?>

    suspend fun identity(): SupportIdentity

    suspend fun submit(
        draft: SupportDraft,
        identity: SupportIdentity,
    ): SupportReceipt

    suspend fun tickets(identity: SupportIdentity): List<SupportTicket>

    fun close() {}
}

interface SupportDraftStorage {
    fun loadDraft(ownerId: String?): String?

    fun loadReceipt(ownerId: String?): String?

    fun saveDraft(
        ownerId: String?,
        value: String?,
    )

    fun saveReceipt(
        ownerId: String?,
        value: String,
    )
}

/** Nach dem ersten Versandversuch bleibt der Inhalt bis Bestätigung/Verwerfen unverändert. */
@Serializable
data class SupportDraft(
    val id: String,
    val receiptSecret: String,
    val category: String = "feedback",
    val title: String = "",
    val body: String = "",
    val contactEmail: String = "",
    val diagnostics: Map<String, String> = emptyMap(),
    val submitted: Boolean = false,
    val ownerId: String? = null,
)

@Serializable
data class SupportReceipt(
    val id: String,
    val receipt: String,
)

@Serializable
data class SupportMessage(
    val id: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class SupportTicket(
    val id: String,
    val title: String,
    val body: String,
    val category: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    val messages: List<SupportMessage> = emptyList(),
)
