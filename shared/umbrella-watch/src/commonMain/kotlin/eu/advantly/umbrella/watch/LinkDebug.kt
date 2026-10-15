package eu.advantly.umbrella.watch

import eu.advantly.device.link.DeviceLink
import eu.advantly.device.link.LinkScheduler
import eu.advantly.device.link.LinkState
import eu.advantly.device.link.debug.PingPongSession
import eu.advantly.device.link.debug.PingPongStats

/**
 * Swift-Facade für den Ping-Pong-Feldtest (Spike S3) auf der Apple Watch.
 * Callbacks kommen auf der Main-Queue. Swift: `let debug = AdvantlyWatch.shared.linkDebug(); debug.start(listener: self)`.
 */
class LinkDebug internal constructor(
    link: DeviceLink,
    scheduler: LinkScheduler,
) {
    private var listener: LinkDebugListener? = null
    private val session = PingPongSession(link, scheduler) { stats -> listener?.onUpdate(stats.toSnapshot()) }

    fun start(listener: LinkDebugListener) {
        this.listener = listener
        session.start()
    }

    /** `false`, wenn noch keine Gegenstelle bekannt ist. */
    fun sendPings(
        count: Int,
        intervalMs: Long,
    ): Boolean = session.startPings(count, intervalMs)

    fun stopPings() = session.stopPings()

    fun reset() = session.reset()

    fun stop() {
        session.stop()
        listener = null
    }

    fun snapshot(): LinkDebugSnapshot = session.snapshot().toSnapshot()
}

/** Callback statt Flow (CLAUDE.md Regel 4). */
interface LinkDebugListener {
    fun onUpdate(snapshot: LinkDebugSnapshot)
}

/**
 * Messwerte für die Anzeige. Zeiten in ms, `-1` = noch keine Messung.
 * [state] ist `CONNECTED`, `DISCONNECTED` oder `UNAVAILABLE` (Swift übersetzt über Localizable.strings).
 */
class LinkDebugSnapshot(
    val state: String,
    val connected: Boolean,
    val running: Boolean,
    val pingsSent: Int,
    val pingsAcked: Int,
    val pongsReceived: Int,
    val pingsAnswered: Int,
    val queued: Int,
    val lost: Int,
    /** Wiederholungen der eigenen Pings dieser Messreihe. */
    val retries: Int,
    /** Verworfene Duplikate, link-weit seit Start/Reset (Empfangsseite, keiner Messreihe zuordenbar). */
    val duplicates: Int,
    val rttP50Ms: Long,
    val rttP95Ms: Long,
    val rttMaxMs: Long,
    val ackP95Ms: Long,
)

internal fun PingPongStats.toSnapshot() =
    LinkDebugSnapshot(
        state = state.name,
        connected = state == LinkState.CONNECTED,
        running = running,
        pingsSent = pingsSent,
        pingsAcked = pingsAcked,
        pongsReceived = pongsReceived,
        pingsAnswered = pingsAnswered,
        queued = pingsQueued,
        lost = lost,
        // Wiederholungen der Pings DIESER Messreihe – link.retries ist link-weit und enthält nach einem Reset
        // noch Wiederholungen alter, weiterlaufender Sendungen
        retries = pingRetries,
        duplicates = link.duplicatesDropped,
        rttP50Ms = rttP50Ms ?: -1,
        rttP95Ms = rttP95Ms ?: -1,
        rttMaxMs = rttMaxMs ?: -1,
        ackP95Ms = ackP95Ms ?: -1,
    )
