package eu.advantly.wear.live

/**
 * Pulsanzeige im Ambient-Modus höchstens einmal je [intervalMs] aktualisieren (04 §4.3.6: „≤ 1×/min“), interaktiv
 * sofort. Zeiten monoton (z. B. `SystemClock.elapsedRealtime()`).
 */
class HeartRateThrottle(
    private val intervalMs: Long = AMBIENT_INTERVAL_MS,
) {
    private var shown: Int? = null
    private var shownAtMs: Long? = null

    /** Anzuzeigender Wert für den neuesten Messwert [bpm]. */
    fun display(
        bpm: Int?,
        nowMs: Long,
        ambient: Boolean,
    ): Int? {
        val last = shownAtMs
        if (!ambient || last == null || nowMs - last >= intervalMs) {
            shown = bpm
            shownAtMs = nowMs
        }
        return shown
    }

    companion object {
        const val AMBIENT_INTERVAL_MS: Long = 60_000
    }
}
