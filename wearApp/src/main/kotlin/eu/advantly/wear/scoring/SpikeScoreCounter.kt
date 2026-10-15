package eu.advantly.wear.scoring

/** Seite, die einen Punkt bekommt. Oben = [US] („Wir“), unten = [THEM] („Gegner“), 04 §4.3.1. */
enum class Side { US, THEM }

/** Unveränderlicher Zählerstand des Spikes. */
data class SpikeScore(
    val us: Int = 0,
    val them: Int = 0,
) {
    /** Kurzform für Ongoing Activity und Log, z. B. „3:2“. */
    fun format(): String = "$us:$them"
}

/**
 * Einfacher Punktezähler „Wir/Gegner“ mit Undo-Stack – nur für Spike S5 (Workout-Laufzeit).
 *
 * Bewusst **keine** Fachlogik aus `core-scoring` (keine Games/Sätze): Der Spike prüft Laufzeit,
 * Puls und Akku, nicht Regeln. Der Zustand ist die Event-Liste; der Stand wird daraus abgeleitet,
 * damit Undo beliebig oft möglich ist (04 §4.3.3 Punkt 6) und der Zustand als String persistierbar bleibt.
 */
class SpikeScoreCounter(
    initialEvents: List<Side> = emptyList(),
) {
    private val events = initialEvents.toMutableList()

    val score: SpikeScore
        get() = SpikeScore(us = events.count { it == Side.US }, them = events.count { it == Side.THEM })

    val canUndo: Boolean
        get() = events.isNotEmpty()

    /** Alle Punkte in Reihenfolge (für Persistenz). */
    val history: List<Side>
        get() = events.toList()

    fun addPoint(side: Side): SpikeScore {
        events += side
        return score
    }

    /** Nimmt den letzten Punkt zurück. Liefert die zurückgenommene Seite oder `null`, wenn nichts da ist. */
    fun undo(): Side? = if (events.isEmpty()) null else events.removeAt(events.lastIndex)

    fun reset() {
        events.clear()
    }

    /** Kompakte Serialisierung, z. B. „UUT“ (U = Wir, T = Gegner). */
    fun encode(): String = events.joinToString(separator = "") { if (it == Side.US) "U" else "T" }

    companion object {
        /** Gegenstück zu [encode]; unbekannte Zeichen werden ignoriert (robust gegen alte Stände). */
        fun decode(encoded: String?): SpikeScoreCounter =
            SpikeScoreCounter(
                encoded.orEmpty().mapNotNull {
                    when (it) {
                        'U' -> Side.US
                        'T' -> Side.THEM
                        else -> null
                    }
                },
            )
    }
}

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
