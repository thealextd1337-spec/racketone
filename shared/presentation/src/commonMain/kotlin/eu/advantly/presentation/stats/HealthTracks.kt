package eu.advantly.presentation.stats

import eu.advantly.core.scoring.Effect
import eu.advantly.core.scoring.PointRecord
import eu.advantly.health.HealthQuality
import eu.advantly.health.HealthSeries
import eu.advantly.health.shift
import eu.advantly.health.stepsBetween
import kotlin.math.absoluteValue

/**
 * Pulsverlauf und Schritt-Rekorde aus gespeicherten [HealthSeries] (Plan Puls §3.6, H8): Auswahl der Quelle, Zeitabgleich,
 * Plausibilität und Satzfenster. Rein, ohne Datenbank – der Session-Adapter liefert Serien und Punktverlauf.
 */
object HealthTracks {
    /** Erst ab dieser Abweichung wird verschoben; bei 5-s-Raster ist weniger ohne Bedeutung (05 §5.1). */
    const val CLOCK_OFFSET_THRESHOLD_MS: Long = 2_000

    /** Liegt weniger als dieser Anteil der Serie zwischen Genesis und Matchende, gilt sie als [HealthQuality.GAPS]. */
    const val MIN_SHARE_IN_MATCH_PERCENT: Int = 50

    private val QUALITY_ORDER = listOf(HealthQuality.OK, HealthQuality.GAPS, HealthQuality.SPARSE_HR, HealthQuality.NO_HR)

    /** Serie in Handyzeit: verschoben um ihren Offset, wenn er die Schwelle übersteigt, sonst unverändert. */
    fun aligned(series: HealthSeries): HealthSeries =
        if (series.clockOffsetMs.absoluteValue > CLOCK_OFFSET_THRESHOLD_MS) shift(series, series.clockOffsetMs) else series

    /** Beste Quelle: zuerst nach Qualität, dann nach Anzahl der Pulswerte. */
    fun best(candidates: List<HealthSeries>): HealthSeries? =
        candidates.minWithOrNull(
            compareBy<HealthSeries> { QUALITY_ORDER.indexOf(it.quality) }.thenByDescending { s -> s.bpm.count { it != null } },
        )

    /**
     * Pulsverlauf für die Zeitleiste aus der besten Quelle, auf Handyzeit abgeglichen; liegt zu wenig davon im Match
     * ([startMs]…[endMs]), wird die Qualität [HealthQuality.GAPS] (Hinweis in der UI). `null` ohne Serie.
     */
    fun pulseTrack(
        candidates: List<HealthSeries>,
        startMs: Long,
        endMs: Long,
    ): PulseTrack? {
        val series = best(candidates)?.let(::aligned) ?: return null
        val inMatch = series.bpm.indices.count { series.startMs + it * series.intervalMs in startMs..endMs }
        val implausible = inMatch * 100 < series.bpm.size * MIN_SHARE_IN_MATCH_PERCENT
        val quality = if (implausible && series.quality != HealthQuality.NO_HR) HealthQuality.GAPS else series.quality
        return PulseTrack(series.startMs, series.intervalMs, series.bpm, quality)
    }

    /**
     * Satzfenster (Wanduhr) aus dem wirksamen Punktverlauf: Beginn = erster Punkt des Satzes, Ende = Punkt mit
     * `SET_WON`. Bei Pickleball, Badminton und Tischtennis ist ein „Satz“ ein Spiel. Nicht beendete Sätze fehlen.
     */
    fun setWindows(points: List<PointRecord>): List<LongRange> {
        val windows = mutableListOf<LongRange>()
        var start: Long? = null
        for (p in points) {
            val begun = start ?: p.event.wallClockMs
            start = begun
            if (Effect.SET_WON in p.after.effects) {
                windows += begun..p.event.wallClockMs
                start = null
            }
        }
        return windows
    }

    /** Meiste Schritte in einem der [windows] laut [series] (bereits abgeglichen); `null` ohne Schrittwerte darin. */
    fun maxStepsInSet(
        series: HealthSeries,
        windows: List<LongRange>,
    ): Long? = windows.mapNotNull { stepsBetween(series, it.first, it.last) }.maxOrNull()
}
