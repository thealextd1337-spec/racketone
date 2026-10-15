package eu.advantly.device.link

/**
 * Annahme von Frames aus der DataItem-Queue des [WearDataLayerTransport] – ohne Android-/Play-Typen, damit
 * per Host-Test prüfbar.
 *
 * Regeln (Review S3):
 * - Jeder [start] eröffnet eine neue **Generation**. Asynchrone Ergebnisse (z. B. ein verspätetes
 *   `DataClient.getDataItems`) tragen die Generation ihres Starts; nach [stop] oder einem neuen Start werden sie
 *   ignoriert – das Item bleibt liegen und wird beim nächsten Start erneut gelesen.
 * - Gelöscht wird ein Item erst, wenn der Link den Frame **verarbeitet** hat (`onConsumed`), nie bei der bloßen
 *   Übergabe. Ein `stop()` zwischen Übergabe und Verarbeitung lässt das Item also ebenfalls liegen.
 */
internal class WearQueueInbox(
    /** Löscht das Item (Data Layer: `deleteDataItems(uri)`, wirkt auf allen Knoten). */
    private val delete: (itemKey: String) -> Unit,
) {
    private var generation = 0
    private var listener: LinkTransport.Listener? = null
    private var localNodeId: String? = null

    /** Aktuelle Generation; asynchrone Aufrufe merken sie sich beim Auslösen. */
    @get:Synchronized
    val currentGeneration: Int get() = generation

    @Synchronized
    fun start(listener: LinkTransport.Listener): Int {
        generation++
        this.listener = listener
        localNodeId = null
        return generation
    }

    @Synchronized
    fun stop() {
        generation++
        listener = null
        localNodeId = null
    }

    @Synchronized
    fun setLocalNode(
        generation: Int,
        nodeId: String,
    ) {
        if (generation == this.generation) localNodeId = nodeId
    }

    /**
     * Bietet ein Queue-Item an. [pathSegments] = `[advantly, link, q, <ziel-node>, <id>]`, [sourceNodeId] = Host der
     * DataItem-URI. Liefert `true`, wenn der Frame an den Link übergeben wurde.
     */
    fun offer(
        generation: Int,
        itemKey: String,
        sourceNodeId: String?,
        pathSegments: List<String>,
        data: ByteArray?,
    ): Boolean {
        val target: LinkTransport.Listener
        synchronized(this) {
            if (generation != this.generation) return false // verspätetes Ergebnis eines alten Starts
            val local = localNodeId ?: return false // ohne eigene Node-ID kein sicheres Zielfiltern
            target = listener ?: return false
            if (pathSegments.size != QUEUE_SEGMENTS || pathSegments.take(3) != QUEUE_PATH) return false
            if (pathSegments[3] != local) return false // nicht für uns (z. B. eigenes, noch unzugestelltes Item)
        }
        if (sourceNodeId == null || data == null) return false
        target.onFrame(sourceNodeId, data) { delete(itemKey) }
        return true
    }

    private companion object {
        val QUEUE_PATH = listOf("advantly", "link", "q")
        const val QUEUE_SEGMENTS = 5
    }
}
