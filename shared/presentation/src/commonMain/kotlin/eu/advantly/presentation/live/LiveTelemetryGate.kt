package eu.advantly.presentation.live

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.match.session.PeerInfo
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.LiveTelemetry
import eu.advantly.sync.protocol.LiveTelemetrySubscription
import eu.advantly.sync.protocol.PeerKind

/** Reine Annahmeprüfung; Peer-Liste stammt vom eigenen Geräte-Link, nicht aus dem Payload. */
object LiveTelemetryGate {
    fun compatiblePeer(
        peers: List<PeerInfo>,
        nodeId: String,
        kind: PeerKind,
    ): Boolean = peers.any { it.peer == nodeId && it.kind == kind && it.compatibility == CompatibilityStatus.COMPATIBLE }

    /** [visibility] „Gar nicht“ (P2-J) verwirft jeden Wert, auch von einer älteren Uhr, die die Einstellung nicht kennt. */
    fun accept(
        value: LiveTelemetry,
        sourceNodeId: String,
        matchId: MatchId?,
        workoutEnabled: Boolean,
        peers: List<PeerInfo>,
        visibility: TelemetryVisibility,
    ): Boolean =
        visibility.allowsLive && workoutEnabled && value.matchId == matchId &&
            value.v in LiveTelemetry.MIN_VERSION..LiveTelemetry.CURRENT_VERSION &&
            compatiblePeer(peers, sourceNodeId, PeerKind.WATCH)
}

/** Abo bindet die lokale Empfangszeit an Match und eigene Phone-Node; Zeit kommt monoton als Parameter. */
data class ReceivedTelemetrySubscription(
    val matchId: MatchId,
    val phoneNodeId: String,
    val receivedAtMs: Long,
) {
    fun isFresh(
        matchId: MatchId,
        phoneNodeId: String,
        nowMs: Long,
    ): Boolean =
        this.matchId == matchId && this.phoneNodeId == phoneNodeId &&
            nowMs - receivedAtMs in 0..LiveTelemetrySubscription.FRESH_FOR_MS

    override fun toString(): String = "ReceivedTelemetrySubscription(<redacted>)"
}
