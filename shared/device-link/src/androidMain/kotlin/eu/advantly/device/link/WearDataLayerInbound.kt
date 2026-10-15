package eu.advantly.device.link

/**
 * Prozessweiter Eingang für Data-Layer-Nachrichten, die ein manifestregistrierter `WearableListenerService` empfängt
 * (App im Hintergrund oder vom System gestartet, W1-I). Der laufende [WearDataLayerTransport] meldet sich hier an;
 * der Service reicht Nachrichten weiter, die sonst nur er bekäme.
 *
 * Doppelte Zustellung (dynamischer Listener UND Service bei offener App) ist unkritisch: `ReliableDeviceLink`
 * verwirft Wiederholungen derselben Nachrichten-ID, der Event-Store ist idempotent.
 */
object WearDataLayerInbound {
    @Volatile
    private var sink: ((sourceNodeId: String, frame: ByteArray) -> Unit)? = null

    internal fun attach(target: (sourceNodeId: String, frame: ByteArray) -> Unit) {
        sink = target
    }

    internal fun detach(target: (sourceNodeId: String, frame: ByteArray) -> Unit) {
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
        target(sourceNodeId, data)
        return true
    }
}
