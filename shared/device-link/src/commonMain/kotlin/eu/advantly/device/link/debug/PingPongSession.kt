package eu.advantly.device.link.debug

import eu.advantly.device.link.Cancellable
import eu.advantly.device.link.DeviceLink
import eu.advantly.device.link.DeviceLinkListener
import eu.advantly.device.link.LinkScheduler
import eu.advantly.device.link.LinkState
import eu.advantly.device.link.LinkStats
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.SendCallback
import eu.advantly.device.link.SendFailure
import kotlin.random.Random

/**
 * Feldtest-Werkzeug (Spike S3): schickt Pings über einen [DeviceLink] und misst Pong-RTT, Ack-Latenz,
 * Verluste und Wiederholungen. Beantwortet eingehende Pings immer – beide Geräte laufen mit derselben Session,
 * jede Seite kann eine Messreihe starten. Liefert nur Zahlen, keine UI-Texte.
 */
class PingPongSession(
    private val link: DeviceLink,
    private val scheduler: LinkScheduler,
    private val onUpdate: (PingPongStats) -> Unit,
) : DeviceLinkListener {
    private var sessionTag = newTag()

    /** Messgeneration: [reset] erhöht sie; Ack-/Fehler-Callbacks älterer Generationen werden ignoriert. */
    private var generation = 0
    private var nextSeq = 1
    private var remaining = 0
    private var planned = 0
    private var intervalMs = 0L
    private var target: String? = null
    private var lastKnownPeer: String? = null
    private var loop: Cancellable? = null

    private val pingSentAt = HashMap<Int, Long>()
    private val rtts = mutableListOf<Long>()
    private val ackLatencies = mutableListOf<Long>()
    private var pingsSent = 0
    private var pingsAcked = 0
    private var pingsQueued = 0
    private var pingsFailed = 0
    private var pingRetries = 0
    private var pongsReceived = 0
    private var pingsAnswered = 0
    private var pongsFailed = 0

    fun start() {
        link.setListener(this)
        link.start()
        lastKnownPeer = link.peers.firstOrNull()
        publish()
    }

    fun stop() {
        stopPings()
        link.stop()
        link.setListener(null)
    }

    /**
     * Startet eine Messreihe: [count] Pings im Abstand [intervalMs] an die erste erreichbare (oder zuletzt
     * gesehene) Gegenstelle. `false`, wenn noch nie eine Gegenstelle sichtbar war.
     */
    fun startPings(
        count: Int,
        intervalMs: Long,
    ): Boolean {
        val peer = link.peers.firstOrNull() ?: lastKnownPeer ?: return false
        stopPings()
        target = peer
        remaining = count
        planned += count
        this.intervalMs = intervalMs.coerceAtLeast(10)
        sendNextPing()
        return true
    }

    fun stopPings() {
        loop?.cancel()
        loop = null
        remaining = 0
        publish()
    }

    /** Neue Messreihe: Zähler auf null, laufende Pings stoppen, alte Pongs werden ignoriert. */
    fun reset() {
        loop?.cancel()
        loop = null
        remaining = 0
        sessionTag = newTag()
        generation++
        nextSeq = 1
        planned = 0
        pingSentAt.clear()
        rtts.clear()
        ackLatencies.clear()
        pingsSent = 0
        pingsAcked = 0
        pingsQueued = 0
        pingsFailed = 0
        pingRetries = 0
        pongsReceived = 0
        pingsAnswered = 0
        pongsFailed = 0
        (link as? ReliableDeviceLink)?.resetStats()
        publish()
    }

    private fun sendNextPing() {
        val peer = target ?: return
        if (remaining <= 0) return
        remaining--
        val seq = nextSeq++
        pingSentAt[seq] = scheduler.nowMs()
        pingsSent++
        link.send(peer, "$PING $sessionTag $seq".encodeToByteArray(), pingCallback(generation))
        loop = if (remaining > 0) scheduler.postDelayed(intervalMs) { sendNextPing() } else null
        publish()
    }

    private fun pingCallback(generation: Int) =
        object : SendCallback {
            private fun current() = generation == this@PingPongSession.generation

            override fun onAcked(
                messageId: String,
                attempts: Int,
                latencyMs: Long,
            ) {
                if (!current()) return
                pingsAcked++
                pingRetries += attempts - 1
                ackLatencies += latencyMs
                publish()
            }

            override fun onQueued(messageId: String) {
                if (!current()) return
                pingsQueued++
                publish()
            }

            override fun onFailed(
                messageId: String,
                attempts: Int,
                reason: SendFailure,
            ) {
                if (!current()) return
                pingsFailed++
                pingRetries += (attempts - 1).coerceAtLeast(0)
                publish()
            }
        }

    private fun pongCallback(generation: Int) =
        object : SendCallback {
            override fun onAcked(
                messageId: String,
                attempts: Int,
                latencyMs: Long,
            ) = Unit

            override fun onFailed(
                messageId: String,
                attempts: Int,
                reason: SendFailure,
            ) {
                if (generation != this@PingPongSession.generation) return
                pongsFailed++
                publish()
            }
        }

    override fun onMessage(
        peer: String,
        messageId: String,
        payload: ByteArray,
    ) {
        val parts = payload.decodeToString().split(' ')
        if (parts.size != 3) return
        val (kind, tag, seqText) = parts
        val seq = seqText.toIntOrNull() ?: return
        when (kind) {
            PING -> {
                pingsAnswered++
                link.send(peer, "$PONG $tag $seq".encodeToByteArray(), pongCallback(generation))
            }

            PONG -> {
                if (tag != sessionTag) return
                val sentAt = pingSentAt.remove(seq) ?: return
                pongsReceived++
                rtts += scheduler.nowMs() - sentAt
            }
        }
        publish()
    }

    override fun onStateChanged(
        state: LinkState,
        peers: List<String>,
    ) {
        peers.firstOrNull()?.let { lastKnownPeer = it }
        publish()
    }

    /** Aktueller Stand (auch ohne Callback abrufbar). */
    fun snapshot(): PingPongStats =
        PingPongStats(
            state = link.state,
            peers = link.peers,
            target = target ?: lastKnownPeer,
            running = remaining > 0,
            pingsPlanned = planned,
            pingsSent = pingsSent,
            pingsAcked = pingsAcked,
            pingsQueued = pingsQueued,
            pingsFailed = pingsFailed,
            pingRetries = pingRetries,
            pongsReceived = pongsReceived,
            pingsAnswered = pingsAnswered,
            pongsFailed = pongsFailed,
            rttP50Ms = percentile(rtts, 50),
            rttP95Ms = percentile(rtts, 95),
            rttMaxMs = rtts.maxOrNull(),
            ackP50Ms = percentile(ackLatencies, 50),
            ackP95Ms = percentile(ackLatencies, 95),
            link = link.stats,
        )

    private fun publish() = onUpdate(snapshot())

    private companion object {
        const val PING = "PING"
        const val PONG = "PONG"

        fun newTag(): String = Random.nextInt(0, Int.MAX_VALUE).toString(36)
    }
}

/** Messwerte einer Ping-Pong-Reihe. Zeiten in ms, `null` = noch keine Messung. */
data class PingPongStats(
    val state: LinkState,
    val peers: List<String>,
    val target: String?,
    val running: Boolean,
    val pingsPlanned: Int,
    val pingsSent: Int,
    val pingsAcked: Int,
    val pingsQueued: Int,
    /** Pings ohne Ack nach allen Wiederholungen = verloren. */
    val pingsFailed: Int,
    /**
     * Wiederholungen der eigenen Pings dieser Messreihe (Versuche − 1, aufsummiert). Für die Anzeige DIESEN Wert nutzen,
     * nicht [LinkStats.retries]: der ist link-weit und zählt nach [PingPongSession.reset] auch weiterlaufende alte Sendungen.
     */
    val pingRetries: Int,
    val pongsReceived: Int,
    val pingsAnswered: Int,
    val pongsFailed: Int,
    /** Ping gesendet → Pong empfangen (zwei Nachrichten + Acks, inkl. Wiederholungen). */
    val rttP50Ms: Long?,
    val rttP95Ms: Long?,
    val rttMaxMs: Long?,
    /** Ping gesendet → Ack empfangen. */
    val ackP50Ms: Long?,
    val ackP95Ms: Long?,
    val link: LinkStats,
) {
    /** Verlorene Nachrichten nach allen Wiederholungen (Pings + Pongs). */
    val lost: Int get() = pingsFailed + pongsFailed

    /** Pings, deren Ergebnis noch aussteht. */
    val inFlight: Int get() = pingsSent - pingsAcked - pingsFailed
}

/** Perzentil nach Nearest-Rank (deterministisch, ohne Interpolation); `null` bei leerer Liste. */
fun percentile(
    values: List<Long>,
    p: Int,
): Long? {
    require(p in 1..100) { "Perzentil 1..100" }
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val rank = (p * sorted.size + 99) / 100 // ceil(p/100 * n)
    return sorted[rank - 1]
}
