package eu.advantly.device.link

/**
 * Prozessweiter Eingang für Data-Layer-Daten, die ein manifestregistrierter `WearableListenerService` empfängt
 * (App im Hintergrund oder vom System gestartet, W1-I). Der laufende [WearDataLayerTransport] meldet sich hier an;
 * der Service reicht Nachrichten und Queue-Items weiter, die sonst nur er bekäme.
 *
 * Doppelte Zustellung (dynamischer Listener UND Service bei offener App, bzw. Queue-Lesen beim Transport-Start) ist
 * unkritisch: `ReliableDeviceLink` verwirft Wiederholungen derselben Nachrichten-ID, der Event-Store ist idempotent.
 */
object WearDataLayerInbound {
    @Volatile
    private var sink: ((sourceNodeId: String, frame: ByteArray, onConsumed: () -> Unit) -> Unit)? = null

    internal fun attach(target: (sourceNodeId: String, frame: ByteArray, onConsumed: () -> Unit) -> Unit) {
        sink = target
    }

    internal fun detach(target: (sourceNodeId: String, frame: ByteArray, onConsumed: () -> Unit) -> Unit) {
        if (sink === target) sink = null
    }

    /** Ist gerade ein Transport angemeldet (Session läuft)? */
    val isAttached: Boolean get() = sink != null

    /**
     * Reicht eine empfangene Nachricht an den laufenden Transport weiter. `false`, wenn kein Transport läuft oder
     * [path] nicht der Link-Pfad ist.
     */
    fun deliverMessage(
        sourceNodeId: String,
        path: String,
        data: ByteArray,
    ): Boolean {
        if (path != WearDataLayerTransport.MESSAGE_PATH) return false
        val target = sink ?: return false
        target(sourceNodeId, data) {}
        return true
    }

    /**
     * Reicht ein (eingefrorenes) Queue-DataItem an den laufenden Transport weiter. [pathSegments] =
     * `[advantly, link, q, <ziel-node>, <id>]`; nur Items für [localNodeId] werden angenommen. [onConsumed] kommt, NACHDEM
     * der Link den Frame verarbeitet (die Session ihn gespeichert) hat – erst dann darf das Item gelöscht werden.
     * `false`, wenn kein Transport läuft oder das Item nicht für dieses Gerät ist.
     */
    fun deliverQueuedItem(
        localNodeId: String,
        sourceNodeId: String?,
        pathSegments: List<String>,
        data: ByteArray?,
        onConsumed: () -> Unit,
    ): Boolean {
        if (pathSegments.size != QUEUE_SEGMENTS || pathSegments.take(3) != QUEUE_PATH) return false
        if (pathSegments[3] != localNodeId || sourceNodeId == null || data == null) return false
        val target = sink ?: return false
        target(sourceNodeId, data, onConsumed)
        return true
    }

    private val QUEUE_PATH = listOf("advantly", "link", "q")
    private const val QUEUE_SEGMENTS = 5
}
