package eu.advantly.wear.sync

import eu.advantly.match.session.PeerInfo
import eu.advantly.presentation.live.LiveTelemetryGate
import eu.advantly.sync.protocol.LiveView
import eu.advantly.sync.protocol.LiveViewSubscription
import eu.advantly.sync.protocol.LiveViewSubscriptionCodec
import eu.advantly.sync.protocol.LiveViewsCodec
import eu.advantly.sync.protocol.PeerKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Zuletzt vom eigenen Handy empfangene Live-Matches; [receivedAtMs] in `elapsedRealtime`. */
data class ReceivedLiveViews(
    val matches: List<LiveView>,
    val receivedAtMs: Long,
)

/**
 * Uhr-Seite des Zuschauens (plan-live-mitspieler §3.2): Abo an das eigene Handy senden und dessen Antwort nur im RAM
 * halten (keine Persistenz, kein Replikat, keine Zählrechte).
 */
class WatchLiveViews {
    private val state = MutableStateFlow<ReceivedLiveViews?>(null)
    val received: StateFlow<ReceivedLiveViews?> = state

    /** Antwort des Handys; nur vom bekannten, kompatiblen eigenen Handy. */
    fun receive(
        bytes: ByteArray,
        nodeId: String,
        peers: List<PeerInfo>,
        nowMs: Long,
    ): Boolean {
        if (!LiveTelemetryGate.compatiblePeer(peers, nodeId, PeerKind.PHONE)) return false
        val views = LiveViewsCodec.decode(bytes) ?: return false
        state.value = ReceivedLiveViews(views.matches, nowMs)
        return true
    }

    /** Ein Abo an das kompatible Handy; ohne eindeutiges Handy nichts. */
    fun subscribe(
        peers: List<PeerInfo>,
        messages: TelemetryMessageClient,
    ): Boolean {
        val phone = peers.singleOrNull { LiveTelemetryGate.compatiblePeer(peers, it.peer, PeerKind.PHONE) } ?: return false
        messages.send(
            phone.peer,
            LiveViewSubscription.MESSAGE_PATH,
            LiveViewSubscriptionCodec.encode(LiveViewSubscription(LiveViewSubscription.CURRENT_VERSION)),
        )
        return true
    }

    companion object {
        val Watch = WatchLiveViews()
    }
}
