package eu.advantly.presentation.stats

import eu.advantly.core.scoring.MatchMoment
import eu.advantly.core.scoring.MatchState
import eu.advantly.health.HealthQuality
import eu.advantly.health.HealthSeries
import eu.advantly.health.pulseAround

/**
 * Spielzeit-Achse der Zeitleiste: Wanduhr ab Genesis bis Matchende, Pausen herausgeschnitten (wie
 * [MatchStats.netDurationMs], 02 §9.3).
 *
 * @property pauses Pausen als Wanduhr-Intervalle `start..end`, in Log-Reihenfolge
 */
data class PlayClock(
    val startMs: Long,
    val endMs: Long,
    val pauses: List<LongRange> = emptyList(),
) {
    /** Spielzeit ohne Pausen. */
    val durationMs: Long get() = (endMs - startMs - pauses.sumOf { it.last - it.first }).coerceAtLeast(0)

    /** Spielzeit zum Wanduhr-Zeitpunkt [wallMs]; `null` vor Beginn, nach Ende oder in einer Pause (Lücke der Pulskurve). */
    fun at(wallMs: Long): Long? {
        if (wallMs < startMs || wallMs > endMs) return null
        if (pauses.any { wallMs > it.first && wallMs < it.last }) return null
        return position(wallMs)
    }

    /** Wie [at], aber nie `null`: in einer Pause deren Beginn, außerhalb des Matches der nächste Rand. */
    fun position(wallMs: Long): Long {
        val clamped = wallMs.coerceIn(startMs, maxOf(startMs, endMs))
        val paused = pauses.filter { it.last > it.first && it.first < clamped }.sumOf { minOf(it.last, clamped) - it.first }
        return (clamped - startMs - paused).coerceIn(0, durationMs)
    }
}

/**
 * Highlight ([MatchMoment]) mit dem Stand **vor** dem Punkt (Karte beim Antippen) und seiner Position auf der
 * Spielzeit-Achse ([PlayClock.position]).
 */
data class TimelineMoment(
    val moment: MatchMoment,
    val before: MatchState,
    val playMs: Long,
)

/**
 * Pulsverlauf eines Matches für die Zeitleiste, schon auf Handyzeit abgeglichen ([HealthTracks.pulseTrack]).
 * Wert `i` gilt ab `startMs + i * intervalMs` (Wanduhr); `null` ist eine Lücke. [quality] steuert den Hinweis unter
 * der Kurve.
 */
data class PulseTrack(
    val startMs: Long,
    val intervalMs: Long,
    val bpm: List<Int?>,
    val quality: HealthQuality = HealthQuality.OK,
) {
    /**
     * Ø-Puls um den Wanduhr-Zeitpunkt [atMs] (Fenster −20 s … +10 s, nur als „Trend“ zeigen); `null` ohne Werte und bei
     * [HealthQuality.SPARSE_HR] (dann keine Punkt-Auswertung, 05 §5.5).
     */
    fun averageAround(atMs: Long): Int? =
        if (quality == HealthQuality.SPARSE_HR) {
            null
        } else {
            pulseAround(HealthSeries(startMs, intervalMs, bpm, bpm.map { null }, 0, quality), atMs)
        }

    // Gesundheitsdaten nie in Logs (Plan Puls §3.4).
    override fun toString(): String = "PulseTrack(<redacted>)"
}

/** Ein Punkt der Pulskurve auf der Spielzeit-Achse. */
data class PulsePoint(
    val playMs: Long,
    val bpm: Int,
)

/**
 * Zusammenhängende Abschnitte der Pulskurve auf der Spielzeit-Achse: Lücken (`null`) sowie Werte in Pausen oder
 * außerhalb des Matches unterbrechen die Linie.
 */
fun PlayClock.pulseSegments(track: PulseTrack): List<List<PulsePoint>> {
    val segments = mutableListOf<List<PulsePoint>>()
    var current = mutableListOf<PulsePoint>()
    track.bpm.forEachIndexed { index, bpm ->
        val playMs = at(track.startMs + index * track.intervalMs)
        if (bpm == null || playMs == null) {
            if (current.isNotEmpty()) segments += current
            current = mutableListOf()
        } else {
            current += PulsePoint(playMs, bpm)
        }
    }
    if (current.isNotEmpty()) segments += current
    return segments
}
