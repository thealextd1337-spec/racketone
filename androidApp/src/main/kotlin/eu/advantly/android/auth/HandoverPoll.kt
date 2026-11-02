package eu.advantly.android.auth

/**
 * Abfrage während einer offenen Übergabe (plan-live-mitspieler §3.4, Realtime ist aus): nach jedem Abgleich mit offener
 * Übergabe der nächste in [intervalMs], bis der Wechsel gesehen ist – höchstens [limitMs] lang ab der letzten Aktion
 * ([restart]), damit eine nie beantwortete Einladung nicht dauerhaft Netz und Akku kostet. Danach übernehmen Push und
 * der normale Abgleich. Aufrufe auf Main.
 */
internal class HandoverPoll(
    private val nowMs: () -> Long,
    private val schedule: (delayMs: Long) -> Unit,
    private val intervalMs: Long = INTERVAL_MS,
    private val limitMs: Long = LIMIT_MS,
) {
    private var since: Long? = null

    /** Neue Einladung, Annahme oder Push: Frist neu beginnen. */
    fun restart() {
        since = null
    }

    /** Nach einem Abgleich; [open]: noch offene Übergabe. */
    fun afterSync(open: Boolean) {
        if (!open) {
            since = null
            return
        }
        val now = nowMs()
        val start = since ?: now.also { since = it }
        if (now - start < limitMs) schedule(intervalMs)
    }

    companion object {
        const val INTERVAL_MS = 5_000L
        const val LIMIT_MS = 10 * 60_000L
    }
}
