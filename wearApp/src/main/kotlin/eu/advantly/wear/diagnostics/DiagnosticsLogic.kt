package eu.advantly.wear.diagnostics

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Zeilenformat des Feldtest-Logs (Spike S5), eine Zeile pro Ereignis, Tab-getrennt:
 * `<UTC-ISO-Zeit>\t<elapsedRealtime ms>\t<EVENT>\t<key=value key=value …>`
 *
 * Werte enthalten keine Leerzeichen/Tabs (werden durch `_` ersetzt), damit `tools/s5_analyze.py` einfach parsen kann.
 */
object DiagLine {
    private val ISO = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    fun format(
        wallMillis: Long,
        elapsedMillis: Long,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
    ): String {
        val details = fields.entries.joinToString(separator = " ") { (k, v) -> "$k=${sanitize(v.toString())}" }
        return "${ISO.format(Instant.ofEpochMilli(wallMillis))}\t$elapsedMillis\t$event\t$details"
    }

    fun isoUtc(epochMillis: Long): String = ISO.format(Instant.ofEpochMilli(epochMillis))

    private fun sanitize(value: String): String = value.replace(Regex("[\\s]+"), "_")
}

/**
 * Erkennt Lücken in der Pulsserie: Liegen zwischen zwei aufeinanderfolgenden Samples (Messzeitpunkt, nicht Lieferzeit)
 * mehr als [thresholdMillis], wird eine Lücke gemeldet. Samples dürfen gebündelt und leicht ungeordnet kommen
 * (Ambient-Batching, 05 §2.1); ältere Samples als das letzte werden ignoriert.
 */
class HeartRateGapDetector(
    private val thresholdMillis: Long = DEFAULT_THRESHOLD_MILLIS,
) {
    data class Gap(
        val fromEpochMillis: Long,
        val toEpochMillis: Long,
    ) {
        val durationMillis: Long get() = toEpochMillis - fromEpochMillis
    }

    private var lastSampleMillis: Long? = null

    /** Meldet eine Lücke **vor** diesem Sample oder `null`. */
    fun onSample(sampleEpochMillis: Long): Gap? {
        val last = lastSampleMillis
        if (last != null && sampleEpochMillis <= last) return null
        lastSampleMillis = sampleEpochMillis
        if (last == null) return null
        return if (sampleEpochMillis - last > thresholdMillis) Gap(last, sampleEpochMillis) else null
    }

    /** Neustart der Serie (z. B. neues Workout) – die Zeit davor zählt nicht als Lücke. */
    fun reset() {
        lastSampleMillis = null
    }

    companion object {
        const val DEFAULT_THRESHOLD_MILLIS = 10_000L
    }
}

/**
 * Löst höchstens alle [intervalMillis] aus (z. B. Akkustand alle 5 min). Wird bei jedem Exercise-Update **und**
 * von einem Ticker gefragt, weil Ticker bei schlafender CPU nicht pünktlich laufen; maßgeblich ist die monotone
 * Zeit `elapsedRealtime` (läuft im Deep Sleep weiter).
 */
class PeriodicTrigger(
    private val intervalMillis: Long,
) {
    private var lastFiredMillis: Long? = null

    fun shouldFire(nowElapsedMillis: Long): Boolean {
        val last = lastFiredMillis
        if (last != null && nowElapsedMillis - last < intervalMillis) return false
        lastFiredMillis = nowElapsedMillis
        return true
    }
}
