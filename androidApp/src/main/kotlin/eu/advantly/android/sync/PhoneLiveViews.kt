package eu.advantly.android.sync

import eu.advantly.device.link.LiveViewsProvider
import eu.advantly.match.session.PeerInfo
import eu.advantly.presentation.live.LiveTelemetryGate
import eu.advantly.sync.protocol.LiveViewSubscriptionCodec
import eu.advantly.sync.protocol.LiveViews
import eu.advantly.sync.protocol.LiveViewsCodec
import eu.advantly.sync.protocol.PeerKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Uhr-Zuschauen auf dem Handy (plan-live-mitspieler §3.2): Jede [eu.advantly.sync.protocol.LiveViewSubscription] der
 * eigenen, kompatiblen Uhr löst genau einen Abruf über [provider] aus; die Antwort geht als [LiveViews] zurück.
 * Kein Abo-Zustand, kein Cache, kein Hintergrunddienst.
 */
object PhoneLiveViews {
    /** Das Konto-Paket setzt hier seine Implementierung (`cloud_my_live_matches_v1`); ohne Konto nie etwas live. */
    @Volatile var provider: LiveViewsProvider = LiveViewsProvider.Empty

    const val TIMEOUT_MS = 5_000L

    /**
     * Blockiert den aufrufenden Worker-Thread höchstens [timeoutMs]: So bleibt der vom System gestartete Prozess für
     * den Abruf am Leben. Fehler oder Zeitüberschreitung: keine Antwort (die Uhr behält ihren Stand und fragt in 10 s neu).
     *
     * @return gesendet?
     */
    fun respond(
        sourceNodeId: String,
        data: ByteArray,
        peers: List<PeerInfo>,
        send: (nodeId: String, path: String, payload: ByteArray) -> Unit,
        provider: LiveViewsProvider = this.provider,
        timeoutMs: Long = TIMEOUT_MS,
    ): Boolean {
        LiveViewSubscriptionCodec.decode(data) ?: return false
        if (!LiveTelemetryGate.compatiblePeer(peers, sourceNodeId, PeerKind.WATCH)) return false
        val matches =
            try {
                runBlocking { withTimeout(timeoutMs) { provider.current() } }
            } catch (e: CancellationException) {
                return false // Zeitüberschreitung
            } catch (e: Exception) {
                return false
            }
        send(sourceNodeId, LiveViews.MESSAGE_PATH, LiveViewsCodec.encode(LiveViews(LiveViews.CURRENT_VERSION, matches)))
        return true
    }
}
