package eu.advantly.wear.scoring

/**
 * Entprellung nach 04 §4.3.3: Nach jedem gezählten Punkt sind **beide** Flächen [lockoutMillis] gesperrt
 * (Anti-Doppel-Tap). Nach dem Aufwachen aus Ambient zählen Taps erst nach [wakeDelayMillis],
 * damit der „Aufweck-Tap“ nicht als Punkt gilt. Zeiten kommen als Parameter (monotone Uhr, z. B. `SystemClock.uptimeMillis()`).
 */
class TapGuard(
    private val lockoutMillis: Long = DEFAULT_LOCKOUT_MILLIS,
    private val wakeDelayMillis: Long = DEFAULT_WAKE_DELAY_MILLIS,
) {
    private var blockedUntil: Long = Long.MIN_VALUE

    /** Prüft einen Tap. Ist er erlaubt, startet die Sperrzeit und es kommt `true` zurück. */
    fun tryAccept(nowMillis: Long): Boolean {
        if (nowMillis < blockedUntil) return false
        blockedUntil = nowMillis + lockoutMillis
        return true
    }

    /** Ist gerade eine Sperre aktiv (für die visuelle Rückmeldung)? */
    fun isLocked(nowMillis: Long): Boolean = nowMillis < blockedUntil

    /** Nach Ambient → interaktiv aufrufen: kurze Sperre gegen den Aufweck-Tap. */
    fun onWake(nowMillis: Long) {
        blockedUntil = maxOf(blockedUntil, nowMillis + wakeDelayMillis)
    }

    companion object {
        const val DEFAULT_LOCKOUT_MILLIS = 600L
        const val DEFAULT_WAKE_DELAY_MILLIS = 300L
    }
}
