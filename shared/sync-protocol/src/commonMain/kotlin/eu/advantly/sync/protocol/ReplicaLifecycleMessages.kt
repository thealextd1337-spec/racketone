package eu.advantly.sync.protocol

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Rechte gelten je Account-Scope; Generationen ordnet und autorisiert die empfangende Datenschicht. */
@Serializable
sealed interface ReplicaControlMessage : SyncMessage

@Serializable
enum class MatchAccessLevel {
    MEMBER,
    FEED,
    ABSENT,
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("AccessState")
data class AccessState(
    val matchId: MatchId,
    val generation: Long,
    val access: MatchAccessLevel,
    @EncodeDefault override val v: Int = 4,
) : ReplicaControlMessage {
    init {
        require(v == 4)
        requireWireId(matchId.value)
        require(generation >= 0)
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("AccessAck")
data class AccessAck(
    val matchId: MatchId,
    val generation: Long,
    val access: MatchAccessLevel,
    @EncodeDefault override val v: Int = 4,
) : ReplicaControlMessage {
    init {
        require(v == 4)
        requireWireId(matchId.value)
        require(generation >= 0)
    }
}

/** Kumulativer Redaktionsstand, unabhängig von Event-Zustellbestätigungen. */
@Serializable
data class LifecycleSummary(
    val generation: Long,
    val access: MatchAccessLevel,
    val redactionApplied: Long,
) {
    init {
        require(generation >= 0 && redactionApplied >= 0)
    }
}

@Serializable
sealed interface MatchDataStamp

/** Zu prüfender Herkunftsbeleg, keine Rechtevergabe und niemals Fallback für bekannte Cloud-Matches. */
@Serializable
@SerialName("LocalMatchStamp")
data class LocalMatchStamp(
    val sourceId: DeviceId,
    val genesisId: EventId,
    val scope: String,
) : MatchDataStamp {
    init {
        requireWireId(sourceId.value)
        requireWireId(genesisId.value)
        requireWireId(scope)
    }
}

@Serializable
@SerialName("CloudMatchStamp")
data class CloudMatchStamp(
    val generation: Long,
    val redactionEpoch: Long,
) : MatchDataStamp {
    init {
        require(generation >= 0 && redactionEpoch >= 0)
    }
}

@Serializable
data class RedactionCursor(
    val afterPlayerId: PlayerId? = null,
    val afterServerSeq: Long = 0,
) {
    init {
        afterPlayerId?.let { requireWireId(it.value) }
        require(afterServerSeq >= 0)
    }
}

/** Rohe unveränderliche Zeile: [payload] bleibt einschließlich unbekannter Felder und Whitespace opak. */
@Serializable
data class RedactionEvent(
    val id: EventId,
    val matchId: MatchId,
    val deviceId: DeviceId,
    val deviceSeq: Long,
    val lamport: Long,
    val wallClockMs: Long,
    val parentId: EventId?,
    val type: String,
    val payload: String,
    val serverSeq: Long,
) {
    init {
        requireWireId(id.value)
        requireWireId(matchId.value)
        requireWireId(deviceId.value)
        parentId?.let { requireWireId(it.value) }
        requireWireId(type)
        require(deviceSeq > 0 && serverSeq > 0 && lamport >= 0)
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("RedactedChunk")
data class RedactedChunk(
    val transferId: String,
    val matchId: MatchId,
    val generation: Long,
    val epoch: Long,
    val highWatermark: Long,
    val index: Long,
    val from: RedactionCursor,
    val next: RedactionCursor,
    val playerIds: List<PlayerId>,
    val events: List<RedactionEvent>,
    val idsDone: Boolean,
    val eventsDone: Boolean,
    @EncodeDefault override val v: Int = 4,
) : ReplicaControlMessage {
    init {
        require(v == 4)
        requireTransfer(transferId, matchId, generation, epoch)
        require(highWatermark >= 0 && index >= 0)
        require(playerIds.size <= 200 && events.size <= 500)
        playerIds.forEach { requireWireId(it.value) }
        require(playerIds.zipWithNext().all { (a, b) -> a.value < b.value })
        require(playerIds.firstOrNull()?.let { from.afterPlayerId == null || it.value > from.afterPlayerId.value } != false)
        require(events.all { it.matchId == matchId && it.serverSeq > from.afterServerSeq && it.serverSeq <= highWatermark })
        require(events.zipWithNext().all { (a, b) -> a.serverSeq < b.serverSeq })
        require(events.map { it.id }.distinct().size == events.size)
        require(next.afterPlayerId == (playerIds.lastOrNull() ?: from.afterPlayerId))
        require(next.afterServerSeq == (events.lastOrNull()?.serverSeq ?: from.afterServerSeq))
        require(from.afterServerSeq <= highWatermark && next.afterServerSeq <= highWatermark)
        require(playerIds.isNotEmpty() || events.isNotEmpty() || (idsDone && eventsDone))
    }
}

/** Bestätigt nur diese Transportseite, niemals implizit Events oder den angewandten Redaktionsstand. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("RedactedChunkAck")
data class RedactedChunkAck(
    val transferId: String,
    val matchId: MatchId,
    val generation: Long,
    val epoch: Long,
    val index: Long,
    @EncodeDefault override val v: Int = 4,
) : ReplicaControlMessage {
    init {
        require(v == 4)
        requireTransfer(transferId, matchId, generation, epoch)
        require(index >= 0)
    }
}

/** Bestätigt die angewandte kumulative Redaktion, unabhängig vom Event-Ack. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("RedactionAck")
data class RedactionAck(
    val transferId: String,
    val matchId: MatchId,
    val generation: Long,
    val epoch: Long,
    @EncodeDefault override val v: Int = 4,
) : ReplicaControlMessage {
    init {
        require(v == 4)
        requireTransfer(transferId, matchId, generation, epoch)
    }
}

internal fun requireWireId(value: String) {
    require(value.isNotBlank() && value.length <= 128) { "Ungültige Wire-ID" }
}

private fun requireTransfer(
    transferId: String,
    matchId: MatchId,
    generation: Long,
    epoch: Long,
) {
    requireWireId(transferId)
    requireWireId(matchId.value)
    require(generation >= 0 && epoch >= 0)
}
