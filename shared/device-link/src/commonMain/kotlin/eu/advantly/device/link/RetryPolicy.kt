package eu.advantly.device.link

/**
 * Zeitverhalten von [ReliableDeviceLink].
 * Defaults für Bluetooth zwischen Uhr und Handy: typische RTT 50–300 ms, gelegentliche Aussetzer.
 */
data class RetryPolicy(
    /** Wartezeit auf das Ack des ersten Versuchs. */
    val ackTimeoutMs: Long = 1_500,
    /** Faktor je weiterem Versuch (exponentielles Backoff). */
    val backoffFactor: Int = 2,
    /** Obergrenze der Wartezeit je Versuch. */
    val maxAckTimeoutMs: Long = 8_000,
    /** Live-Versuche insgesamt (inkl. dem ersten). */
    val maxAttempts: Int = 5,
    /** Wartezeit auf das Ack einer Nachricht in der OS-Queue (Gegenseite kann lange offline sein). */
    val queuedAckTimeoutMs: Long = 15 * 60_000L,
    /**
     * Gemerkte Nachrichten-IDs je Gegenstelle für die Dedup (nur RAM, FIFO). Muss größer sein als die Zahl der
     * Nachrichten, die während der längsten Wiederholungsspanne eintreffen können; jenseits davon gilt at-least-once.
     */
    val dedupCapacity: Int = 2_048,
) {
    init {
        require(ackTimeoutMs > 0 && maxAckTimeoutMs >= ackTimeoutMs) { "ungültige Timeouts" }
        require(backoffFactor >= 1 && maxAttempts >= 1 && dedupCapacity >= 1) { "ungültige RetryPolicy" }
    }

    /** Timeout für Versuch [attempt] (1-basiert). */
    fun ackTimeoutFor(attempt: Int): Long {
        var timeout = ackTimeoutMs
        repeat(attempt - 1) {
            timeout = (timeout * backoffFactor).coerceAtMost(maxAckTimeoutMs)
        }
        return timeout
    }
}
