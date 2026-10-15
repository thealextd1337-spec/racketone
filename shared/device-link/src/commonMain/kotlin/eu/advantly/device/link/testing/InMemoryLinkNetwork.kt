package eu.advantly.device.link.testing

import eu.advantly.device.link.DeliveryResult
import eu.advantly.device.link.LinkTransport
import kotlin.random.Random

/**
 * In-Memory-Netz für Tests und Sync-Simulationen (01 §8): mehrere Knoten, steuerbare Latenz, Verlust,
 * Duplikate und Partitionen; Zeit über [ManualScheduler]. Mit [queueWhenDisconnected] verhält es sich wie
 * Wear `DataClient` bzw. Apple `transferUserInfo`: Frames an getrennte Knoten werden gepuffert und beim
 * Wiederverbinden zugestellt.
 */
class InMemoryLinkNetwork(
    private val scheduler: ManualScheduler,
    /** Einweg-Latenz in ms. */
    var latencyMs: Long = 20,
    /** Wahrscheinlichkeit 0..1, dass ein Live-Frame verloren geht. */
    var lossRate: Double = 0.0,
    /** Wahrscheinlichkeit 0..1, dass ein Live-Frame doppelt ankommt. */
    var duplicateRate: Double = 0.0,
    var queueWhenDisconnected: Boolean = false,
    seed: Int = 42,
) {
    private val random = Random(seed)
    private val nodes = LinkedHashMap<String, Node>()
    private val queued = mutableListOf<Triple<String, String, ByteArray>>()

    /** Anzahl übertragener Frames (inkl. verlorener) – für Assertions. */
    var framesOnWire: Int = 0
        private set

    /** Optionaler Filter: `false` verwirft den Frame (z. B. „nur Acks verlieren“). */
    var dropFilter: ((from: String, to: String, frame: ByteArray) -> Boolean)? = null

    fun transport(nodeId: String): LinkTransport = nodes.getOrPut(nodeId) { Node(nodeId) }

    /** Trennt bzw. verbindet einen Knoten (Bluetooth weg, Flugmodus). */
    fun setConnected(
        nodeId: String,
        connected: Boolean,
    ) {
        val node = nodes.getValue(nodeId)
        if (node.connected == connected) return
        node.connected = connected
        if (connected) flushQueue()
        publishPeers()
    }

    private inner class Node(
        val id: String,
    ) : LinkTransport {
        var listener: LinkTransport.Listener? = null
        var connected = true

        override fun start(listener: LinkTransport.Listener) {
            this.listener = listener
            publishPeers()
        }

        override fun stop() {
            listener = null
            publishPeers()
        }

        override fun send(
            peer: String,
            frame: ByteArray,
            onResult: (DeliveryResult) -> Unit,
        ) {
            val target = nodes[peer]
            val live = connected && target != null && target.connected && target.listener != null
            when {
                live -> {
                    onResult(DeliveryResult.SENT)
                    transmit(id, peer, frame)
                }

                queueWhenDisconnected && target != null -> {
                    queued += Triple(id, peer, frame.copyOf())
                    onResult(DeliveryResult.QUEUED)
                }

                else -> {
                    onResult(DeliveryResult.FAILED)
                }
            }
        }
    }

    private fun transmit(
        from: String,
        to: String,
        frame: ByteArray,
    ) {
        framesOnWire++
        if (random.nextDouble() < lossRate) return
        if (dropFilter?.invoke(from, to, frame) == false) return
        val copies = if (random.nextDouble() < duplicateRate) 2 else 1
        repeat(copies) { copy ->
            scheduler.postDelayed(latencyMs + copy) {
                val target = nodes[to]
                // Während der Übertragung getrennt → Frame verloren
                if (target != null && target.connected) target.listener?.onFrame(from, frame.copyOf())
            }
        }
    }

    private fun flushQueue() {
        val deliverable =
            queued.filter { (from, to, _) ->
                nodes[from]?.connected == true && nodes[to]?.connected == true && nodes[to]?.listener != null
            }
        queued.removeAll(deliverable)
        // Queue-Kanäle sind zuverlässig: kein Verlust, keine Duplikate
        deliverable.forEach { (from, to, frame) ->
            framesOnWire++
            scheduler.postDelayed(latencyMs) { nodes[to]?.listener?.onFrame(from, frame) }
        }
    }

    private fun publishPeers() {
        nodes.values.forEach { node ->
            val peers =
                if (!node.connected) {
                    emptyList()
                } else {
                    nodes.values.filter { it !== node && it.connected && it.listener != null }.map { it.id }
                }
            node.listener?.let { listener -> scheduler.post { listener.onPeersChanged(peers, available = true) } }
        }
    }
}
