package eu.advantly.health

/** Datenqualität einer Pulsserie (05 §5.5); `toString` enthält keine Messwerte. */
enum class HealthQuality {
    OK,

    /** Weniger als 1 Pulswert je 30 s: nur Ø und Max, keine Punkt-Auswertung. */
    SPARSE_HR,

    /** Kein einziger gültiger Pulswert. */
    NO_HR,

    /** Mindestens 10 % der Serie liegen in Pulslücken von 30 s oder länger. */
    GAPS,
    ;

    companion object {
        private const val SPARSE_MS = 30_000L
        private const val GAP_MIN_MS = 30_000L
        private const val GAP_SHARE_PERCENT = 10

        /** Qualität aus den Pulsbuckets; Reihenfolge der Prüfung: NO_HR, SPARSE_HR, GAPS, OK. */
        fun of(
            bpm: List<Int?>,
            intervalMs: Long,
        ): HealthQuality {
            val filled = bpm.count { it != null }
            if (filled == 0) return NO_HR
            if (filled * SPARSE_MS < bpm.size * intervalMs) return SPARSE_HR
            var gapBuckets = 0
            var run = 0
            // Wächterwert am Ende schließt eine Lücke bis zum Serienende ab.
            for (value in bpm + 0) {
                if (value == null) {
                    run++
                } else {
                    if (run * intervalMs >= GAP_MIN_MS) gapBuckets += run
                    run = 0
                }
            }
            return if (gapBuckets * 100 >= bpm.size * GAP_SHARE_PERCENT) GAPS else OK
        }
    }
}

/**
 * Puls und Schritte eines Matches von einer Quelle im festen Raster ab [startMs] (Plan Puls §3.1).
 * Bucket `i` deckt `[startMs + i·intervalMs, startMs + (i+1)·intervalMs)` ab; `null` = keine Daten.
 *
 * @property bpm ganzzahliger Mittelwert der Pulswerte je Bucket
 * @property steps kumulierte Schritte seit dem ersten Schrittwert der Aufnahme, letzter Wert im Bucket (nie fallend)
 * @property clockOffsetMs Uhr der Quelle minus Referenzuhr (Handy); [shift] rechnet die Serie auf die Referenzuhr um
 */
data class HealthSeries(
    val startMs: Long,
    val intervalMs: Long,
    val bpm: List<Int?>,
    val steps: List<Long?>,
    val clockOffsetMs: Long,
    val quality: HealthQuality,
) {
    init {
        require(intervalMs > 0 && bpm.size == steps.size)
    }

    /** Index des Buckets, der [atMs] enthält (auch außerhalb der Serie: negativ bzw. ≥ size). */
    internal fun indexOf(atMs: Long): Long = (atMs - startMs).floorDiv(intervalMs)

    // Gesundheitsdaten nie in Logs (Plan Puls §3.4).
    override fun toString(): String = "HealthSeries(<redacted>)"
}

/** Höchster Puls-Bucket der Serie, `null` ohne Pulswerte. */
fun maxBpm(series: HealthSeries): Int? = series.bpm.filterNotNull().maxOrNull()

/**
 * Schritte in den ganzen Buckets ab dem Bucket von [fromMs] bis vor den Bucket von [toMs] (beide Enden auf das Raster
 * abgerundet); `null`, wenn in diesem Bereich kein Schrittwert liegt.
 */
fun stepsBetween(
    series: HealthSeries,
    fromMs: Long,
    toMs: Long,
): Long? {
    val from = series.indexOf(fromMs).coerceIn(0, series.steps.size.toLong()).toInt()
    val to = series.indexOf(toMs).coerceIn(from.toLong(), series.steps.size.toLong()).toInt()
    val last = series.steps.subList(from, to).lastOrNull { it != null } ?: return null
    val before = series.steps.subList(0, from).lastOrNull { it != null } ?: 0L
    return last - before
}

/**
 * Verschiebt die Serie um [clockOffsetMs] (Uhr der Quelle minus Referenzuhr) auf die Referenzuhr; der verbleibende
 * Offset sinkt entsprechend. `shift(s, s.clockOffsetMs)` ergibt die Serie in Handyzeit mit Offset 0 (Plan Puls §3.6).
 */
fun shift(
    series: HealthSeries,
    clockOffsetMs: Long,
): HealthSeries = series.copy(startMs = series.startMs - clockOffsetMs, clockOffsetMs = series.clockOffsetMs - clockOffsetMs)

/** Fenster um ein Punktende (05 §5.5): 20 s davor bis 10 s danach. */
const val PULSE_WINDOW_BEFORE_MS: Long = 20_000
const val PULSE_WINDOW_AFTER_MS: Long = 10_000

/**
 * Ganzzahliger Ø-Puls (gerundet) der Buckets, die im Fenster `[atMs − 20 s, atMs + 10 s)` beginnen; `null` ohne Werte.
 * Nur als „Trend“ anzeigen (05 §5.5).
 */
fun pulseAround(
    series: HealthSeries,
    atMs: Long,
): Int? {
    // erster Bucket, der bei oder nach [ms] beginnt (aufgerundete Division)
    fun firstStartingAtOrAfter(ms: Long) = -(series.startMs - ms).floorDiv(series.intervalMs)
    val size = series.bpm.size.toLong()
    val from = firstStartingAtOrAfter(atMs - PULSE_WINDOW_BEFORE_MS).coerceIn(0, size).toInt()
    val to = firstStartingAtOrAfter(atMs + PULSE_WINDOW_AFTER_MS).coerceIn(from.toLong(), size).toInt()
    val values = series.bpm.subList(from, to).filterNotNull()
    return if (values.isEmpty()) null else roundedMean(values.sumOf { it.toLong() }, values.size)
}

internal fun roundedMean(
    sum: Long,
    count: Int,
): Int = ((sum + count / 2) / count).toInt()
