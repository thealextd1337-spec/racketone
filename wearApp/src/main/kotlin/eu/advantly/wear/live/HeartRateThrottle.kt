package eu.advantly.wear.live

/**
 * Pulsanzeige im Ambient-Modus höchstens einmal je [intervalMs] aktualisieren (04 §4.3.6: „≤ 1×/min“), interaktiv
 * sofort. Zeiten monoton (z. B. `SystemClock.elapsedRealtime()`).
 */
class HeartRateThrottle(
    private val intervalMs: Long = AMBIENT_INTERVAL_MS,
) {
    private val values = ValueThrottle<Int>(intervalMs)

    fun display(
        bpm: Int?,
        nowMs: Long,
        ambient: Boolean,
    ): Int? = values.display(bpm, nowMs, ambient)

    companion object {
        const val AMBIENT_INTERVAL_MS: Long = 60_000
    }
}

/** Dieselbe Ambient-Drosselung für die gemeinsame Puls-/Schrittanzeige. */
class ValueThrottle<T>(
    private val intervalMs: Long = HeartRateThrottle.AMBIENT_INTERVAL_MS,
) {
    private var shown: T? = null
    private var shownAtMs: Long? = null

    fun display(
        value: T?,
        nowMs: Long,
        ambient: Boolean,
    ): T? {
        val last = shownAtMs
        if (!ambient || last == null || nowMs - last >= intervalMs) {
            shown = value
            shownAtMs = nowMs
        }
        return shown
    }
}
