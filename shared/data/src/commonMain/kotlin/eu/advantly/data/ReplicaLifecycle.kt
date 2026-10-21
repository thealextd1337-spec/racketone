package eu.advantly.data

import kotlinx.serialization.Serializable

/** Handle eines Stores; Auswahlwechsel invalidieren auch A → B → A. */
class ReplicaContext internal constructor(
    val accountKey: String,
    val scopeId: String,
    val contextRevision: Long,
    internal val store: Any,
)

enum class ReplicaAccessLevel { UNKNOWN, MEMBER, FEED, ABSENT }

data class ReplicaAccess(
    val matchId: String,
    val generation: Long?,
    val access: ReplicaAccessLevel,
    val ready: Boolean,
    val redactionEpoch: Long,
)

data class RedactionTransfer(
    val id: String,
    val matchId: String,
    val generation: Long,
    val epoch: Long,
    val highWatermark: Long,
)

@Serializable
data class RedactionCursor(
    val afterPlayerId: String?,
    val afterServerSeq: Long,
)

/** Persistierter Fortsetzungsstand; Seitenindex und Abschlussflags werden niemals aus einem Cursor geraten. */
data class RedactionProgress(
    val transfer: RedactionTransfer,
    val cursor: RedactionCursor,
    val nextPageIndex: Long,
    val idsDone: Boolean,
    val eventsDone: Boolean,
    val completed: Boolean,
)

@Serializable
data class ReplicaRedactionEvent(
    val event: StoredEvent,
    val serverSeq: Long,
)

@Serializable
data class RedactionPage(
    val index: Long,
    val from: RedactionCursor,
    val next: RedactionCursor,
    val playerIds: List<String>,
    val events: List<ReplicaRedactionEvent>,
    val idsDone: Boolean,
    val eventsDone: Boolean,
)

enum class TransitionResult { APPLIED, UNCHANGED, IGNORED }

data class ReplicaDelivery(
    val id: String,
    val matchId: String,
    val generation: Long,
    val access: ReplicaAccessLevel,
    val redactionEpoch: Long,
    val accessAcknowledged: Boolean,
    val redactionAcknowledged: Boolean,
)

data class DeliveryPage(
    val deliveries: List<ReplicaDelivery>,
    val nextId: String?,
    val hasMore: Boolean,
)
