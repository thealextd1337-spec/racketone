package eu.advantly.device.link

/**
 * Zuverlässiger Nachrichtenkanal zwischen Uhr und Handy (gleichwertige Geräte, 01 §4).
 *
 * Transportiert nur Bytes (in der Praxis JSON aus `sync-protocol`). Garantien:
 * - **Ack**: jede Nachricht wird vom Empfänger bestätigt; [SendCallback.onAcked] meldet Versuche und Latenz.
 * - **Timeout + Retry** nach [RetryPolicy]; ohne Ack nach allen Versuchen → [SendCallback.onFailed].
 * - **Dedup (best effort, begrenzt)**: der Empfänger verwirft Wiederholungen und Doppelzustellungen (Live- und
 *   Queue-Kanal) und bestätigt sie erneut – aber nur innerhalb seines **Speicherfensters**: die letzten
 *   [RetryPolicy.dedupCapacity] IDs je Gegenstelle, nur im RAM. Ältere IDs und alles vor einem App-Neustart sind
 *   vergessen; eine späte Wiederholung wird dann erneut ausgeliefert (at-least-once).
 *   **Exactly-once-Wirkung** entsteht erst eine Ebene höher: Events tragen eine global eindeutige ID, und
 *   `EventStore.append` ist darüber idempotent (`AppendResult.DUPLICATE`). Empfänger müssen Nachrichten deshalb
 *   idempotent verarbeiten.
 * - **Queue-Fallback**: ist die Gegenseite nicht live erreichbar, übergibt der Transport die Nachricht einer
 *   Betriebssystem-Warteschlange (Wear `DataClient`, Apple `transferUserInfo`) → [SendCallback.onQueued].
 *
 * Endgültige Zustellung aller Events sichert NICHT dieser Kanal, sondern die Anti-Entropie aus `sync-protocol`
 * (01 §4.3, Abgleich bei jedem Wiederverbinden und alle 30 s).
 *
 * Threading: Alle Methoden und Callbacks laufen auf dem Thread des [LinkScheduler]
 * (Android: Main-Looper, Apple: Main-Queue, Tests: [eu.advantly.device.link.testing.ManualScheduler]).
 */
interface DeviceLink {
    /** Plattformbeleg f?r den direkten App-Kanal; generische Transporte bleiben unvertraut. */
    fun isTrustedCounterpart(peer: String): Boolean = false

    /** Aktueller Verbindungsstatus. */
    val state: LinkState

    /** Live erreichbare Gegenstellen (Wear: Node-IDs, Apple: [WATCH_PEER] bzw. [PHONE_PEER]). */
    val peers: List<String>

    /** Zähler für Diagnose und Feldtest. */
    val stats: LinkStats

    fun start()

    /** Beendet den Kanal; offene Sendungen enden mit [SendFailure.STOPPED]. */
    fun stop()

    fun setListener(listener: DeviceLinkListener?)

    /**
     * Sendet [payload] an [peer] und liefert die Nachrichten-ID (global eindeutig, auch über App-Neustarts).
     * Das Ergebnis kommt asynchron über [callback].
     */
    fun send(
        peer: String,
        payload: ByteArray,
        callback: SendCallback? = null,
    ): String
}

/** Empfangsseite eines [DeviceLink]. */
interface DeviceLinkListener {
    /** Neue (nicht doppelte) Nachricht von [peer]. */
    fun onMessage(
        peer: String,
        messageId: String,
        payload: ByteArray,
    )

    fun onStateChanged(
        state: LinkState,
        peers: List<String>,
    ) {}
}

/** Ergebnis einer einzelnen Sendung. */
interface SendCallback {
    /** Empfänger hat bestätigt. [latencyMs] = erste Übertragung bis Ack (inkl. Wiederholungen). */
    fun onAcked(
        messageId: String,
        attempts: Int,
        latencyMs: Long,
    )

    /** Nachricht liegt in der OS-Warteschlange (Gegenseite nicht live erreichbar); Ack folgt später. */
    fun onQueued(messageId: String) {}

    fun onFailed(
        messageId: String,
        attempts: Int,
        reason: SendFailure,
    )
}

enum class LinkState {
    /** Mindestens eine Gegenstelle ist live erreichbar. */
    CONNECTED,

    /** Keine Gegenstelle erreichbar – Nachrichten gehen in die Queue bzw. werden wiederholt. */
    DISCONNECTED,

    /** Plattform-API fehlt (z. B. keine Wear-OS-App/Play Services, WCSession nicht unterstützt). */
    UNAVAILABLE,
}

enum class SendFailure {
    /** Kein Ack nach [RetryPolicy.maxAttempts] Live-Versuchen. */
    NO_ACK,

    /** Nachricht lag in der OS-Queue, Ack kam nicht innerhalb von [RetryPolicy.queuedAckTimeoutMs]. */
    QUEUED_NO_ACK,

    /** [DeviceLink.stop] vor dem Ack. */
    STOPPED,
}

/** Gegenstellen-IDs bei WatchConnectivity (genau ein Gegenüber je Gerät). */
const val WATCH_PEER: String = "watch"
const val PHONE_PEER: String = "phone"

/** Zähler eines [DeviceLink] (monoton seit Start bzw. [ReliableDeviceLink.resetStats]). */
data class LinkStats(
    /** Gesendete Datenframes inkl. Wiederholungen. */
    val framesSent: Int = 0,
    /** Davon Wiederholungen. */
    val retries: Int = 0,
    val acked: Int = 0,
    val queued: Int = 0,
    val failed: Int = 0,
    /** Empfangene, ausgelieferte Nachrichten. */
    val delivered: Int = 0,
    /** Empfangene Duplikate (verworfen, erneut bestätigt). */
    val duplicatesDropped: Int = 0,
    /** Acks für unbekannte/abgeschlossene Nachrichten (z. B. Ack auf eine Wiederholung). */
    val lateAcks: Int = 0,
    val malformedFrames: Int = 0,
    val transportErrors: Int = 0,
)
