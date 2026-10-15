package eu.advantly.device.link

import kotlin.random.Random

/**
 * [DeviceLink] über einem unzuverlässigen [LinkTransport]: Ack, Timeout mit Backoff, Wiederholung, Dedup.
 * Plattformunabhängig und vollständig per JVM-Test mit [eu.advantly.device.link.testing.InMemoryLinkNetwork]
 * abgedeckt. Siehe [DeviceLink] für Garantien und Threading.
 */
class ReliableDeviceLink(
    private val transport: LinkTransport,
    private val scheduler: LinkScheduler,
    private val policy: RetryPolicy = RetryPolicy(),
    private val newMessageId: () -> String = ::randomMessageId,
) : DeviceLink {
    private class Outgoing(
        val id: String,
        val peer: String,
        val frame: ByteArray,
        val callback: SendCallback?,
        val firstSentAtMs: Long,
    ) {
        var attempts = 0
        var queued = false
        var timer: Cancellable? = null
    }

    private var listener: DeviceLinkListener? = null
    private var running = false
    private val pending = LinkedHashMap<String, Outgoing>()
    private val seen = DedupCache(policy.dedupCapacity)
    private var counters = LinkStats()

    override var state: LinkState = LinkState.DISCONNECTED
        private set

    override var peers: List<String> = emptyList()
        private set

    override val stats: LinkStats get() = counters

    override fun setListener(listener: DeviceLinkListener?) {
        this.listener = listener
    }

    override fun start() {
        if (running) return
        running = true
        transport.start(
            object : LinkTransport.Listener {
                override fun onFrame(
                    peer: String,
                    frame: ByteArray,
                ) = scheduler.post { if (running) handleFrame(peer, frame) }

                override fun onPeersChanged(
                    peers: List<String>,
                    available: Boolean,
                ) = scheduler.post { if (running) handlePeers(peers, available) }
            },
        )
    }

    override fun stop() {
        if (!running) return
        running = false
        transport.stop()
        val open = pending.values.toList()
        pending.clear()
        open.forEach { out ->
            out.timer?.cancel()
            counters = counters.copy(failed = counters.failed + 1)
            out.callback?.onFailed(out.id, out.attempts, SendFailure.STOPPED)
        }
        updateState(emptyList(), available = true)
    }

    override fun send(
        peer: String,
        payload: ByteArray,
        callback: SendCallback?,
    ): String {
        val id = newMessageId()
        val out = Outgoing(id, peer, LinkFrame.data(id, payload).encode(), callback, scheduler.nowMs())
        pending[id] = out
        transmit(out)
        return id
    }

    /** Setzt die Zähler zurück (Feldtest: neue Messreihe). Offene Sendungen bleiben bestehen. */
    fun resetStats() {
        counters = LinkStats()
    }

    // --- Senden -------------------------------------------------------------------------------

    private fun transmit(out: Outgoing) {
        out.attempts++
        val attempt = out.attempts
        counters =
            counters.copy(
                framesSent = counters.framesSent + 1,
                retries = counters.retries + if (attempt > 1) 1 else 0,
            )
        out.timer?.cancel()
        out.timer = scheduler.postDelayed(policy.ackTimeoutFor(attempt)) { onTimeout(out.id, attempt) }
        transport.send(out.peer, out.frame) { result -> scheduler.post { onDelivery(out.id, result) } }
    }

    private fun onDelivery(
        id: String,
        result: DeliveryResult,
    ) {
        if (result == DeliveryResult.FAILED) {
            // Wiederholung übernimmt der Ack-Timer
            counters = counters.copy(transportErrors = counters.transportErrors + 1)
            return
        }
        val out = pending[id] ?: return
        if (result != DeliveryResult.QUEUED || out.queued) return
        // Die OS-Queue garantiert die Zustellung → keine Live-Wiederholungen mehr, nur noch auf das Ack warten
        out.queued = true
        counters = counters.copy(queued = counters.queued + 1)
        out.timer?.cancel()
        val attempt = out.attempts
        out.timer = scheduler.postDelayed(policy.queuedAckTimeoutMs) { onTimeout(out.id, attempt) }
        out.callback?.onQueued(out.id)
    }

    private fun onTimeout(
        id: String,
        attempt: Int,
    ) {
        val out = pending[id] ?: return
        if (out.attempts != attempt) return // veralteter Timer
        when {
            out.queued -> fail(out, SendFailure.QUEUED_NO_ACK)
            out.attempts >= policy.maxAttempts -> fail(out, SendFailure.NO_ACK)
            else -> transmit(out)
        }
    }

    private fun fail(
        out: Outgoing,
        reason: SendFailure,
    ) {
        pending.remove(out.id)
        out.timer?.cancel()
        counters = counters.copy(failed = counters.failed + 1)
        out.callback?.onFailed(out.id, out.attempts, reason)
    }

    // --- Empfangen ----------------------------------------------------------------------------

    private fun handleFrame(
        peer: String,
        bytes: ByteArray,
    ) {
        val frame = LinkFrame.decode(bytes)
        if (frame == null) {
            counters = counters.copy(malformedFrames = counters.malformedFrames + 1)
            return
        }
        when (frame.type) {
            LinkFrame.Type.DATA -> {
                // Immer bestätigen – auch Duplikate: das erste Ack kann verloren gegangen sein
                transport.send(peer, LinkFrame.ack(frame.messageId).encode()) { result ->
                    if (result == DeliveryResult.FAILED) {
                        scheduler.post { counters = counters.copy(transportErrors = counters.transportErrors + 1) }
                    }
                }
                if (seen.add(peer, frame.messageId)) {
                    counters = counters.copy(delivered = counters.delivered + 1)
                    listener?.onMessage(peer, frame.messageId, frame.payload)
                } else {
                    counters = counters.copy(duplicatesDropped = counters.duplicatesDropped + 1)
                }
            }

            LinkFrame.Type.ACK -> {
                val out = pending.remove(frame.messageId)
                if (out == null) {
                    counters = counters.copy(lateAcks = counters.lateAcks + 1)
                    return
                }
                out.timer?.cancel()
                counters = counters.copy(acked = counters.acked + 1)
                out.callback?.onAcked(out.id, out.attempts, scheduler.nowMs() - out.firstSentAtMs)
            }
        }
    }

    private fun handlePeers(
        newPeers: List<String>,
        available: Boolean,
    ) {
        val reconnected = newPeers - peers.toSet()
        updateState(newPeers, available)
        if (reconnected.isEmpty()) return
        // Gegenstelle wieder da → wartende Live-Sendungen sofort wiederholen statt Backoff abzuwarten
        pending.values
            .filter { it.peer in reconnected && !it.queued && it.attempts < policy.maxAttempts }
            .forEach { transmit(it) }
    }

    private fun updateState(
        newPeers: List<String>,
        available: Boolean,
    ) {
        val newState =
            when {
                !available -> LinkState.UNAVAILABLE
                newPeers.isEmpty() -> LinkState.DISCONNECTED
                else -> LinkState.CONNECTED
            }
        if (newState == state && newPeers == peers) return
        state = newState
        peers = newPeers
        listener?.onStateChanged(newState, newPeers)
    }
}

/** Gemerkte Nachrichten-IDs je Gegenstelle, älteste fliegen zuerst raus (FIFO). */
private class DedupCache(
    private val capacityPerPeer: Int,
) {
    private val byPeer = HashMap<String, Pair<ArrayDeque<String>, HashSet<String>>>()

    /** `true`, wenn [id] für [peer] neu ist. */
    fun add(
        peer: String,
        id: String,
    ): Boolean {
        val (order, set) = byPeer.getOrPut(peer) { ArrayDeque<String>() to HashSet() }
        if (!set.add(id)) return false
        order.addLast(id)
        if (order.size > capacityPerPeer) set.remove(order.removeFirst())
        return true
    }
}

/** 128 Bit Zufall als Hex – eindeutig auch über App-Neustarts (ein Zähler wäre nach Neustart doppelt). */
private fun randomMessageId(): String =
    Random
        .nextLong()
        .toULong()
        .toString(16)
        .padStart(16, '0') +
        Random
            .nextLong()
            .toULong()
            .toString(16)
            .padStart(16, '0')
