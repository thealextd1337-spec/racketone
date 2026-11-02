package eu.advantly.presentation.stats

import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.scoring.GameKind
import eu.advantly.core.scoring.SidePair
import eu.advantly.match.session.WATCH_HEALTH_RETENTION_MS
import eu.advantly.presentation.match.PulseSetting
import kotlin.math.abs

/** Game-Balken im Spielverlauf: [index] in [MatchStats.progression], Spanne [startMs]…[endMs] auf der Achse. */
data class CourseGame(
    val index: Int,
    val game: GameProgress,
    val startMs: Long,
    val endMs: Long,
)

/** Highlight im Spielverlauf: [index] in [MatchStats.moments], Zeitpunkt [atMs] auf der Achse. */
data class CourseMoment(
    val index: Int,
    val atMs: Long,
)

/**
 * Ein Diagramm des Spielverlaufs (Plan 0.5.1 §7c): ein Satz bei Tennis/Padel, bei Rally-Sportarten das ganze Match
 * (dort ist jeder Satz bzw. jedes Spiel ein Balken, wie in der früheren Momentum-Grafik).
 *
 * @property set Satznummer; `null` = ganzes Match
 * @property timed die Achse ist Spielzeit ([PlayClock.position], ms seit Matchbeginn ohne Pausen); `false` ohne
 *   verwertbare Zeitstempel – dann ist jedes Game eine Einheit (`0…1`, `1…2`, …) und Pulskurve und Zeitachse entfallen
 */
data class CourseSection(
    val set: Int?,
    val startMs: Long,
    val endMs: Long,
    val timed: Boolean,
    val games: List<CourseGame>,
    val moments: List<CourseMoment>,
) {
    val durationMs: Long get() = if (timed) endMs - startMs else 0

    /** Stand des Abschnitts aus den gezählten Punkten: Spiele, beim Match-Tiebreak dessen Punkte, ohne Satz die Sätze. */
    val score: SidePair<Int>
        get() =
            games
                .singleOrNull()
                ?.game
                ?.takeIf { set != null && it.kind == GameKind.MATCH_TIEBREAK }
                ?.pointsWon
                ?: SidePair.of { side -> games.count { it.game.winner == side } }
}

/**
 * Teilt den Spielverlauf in Diagramme ([perSet]: je Satz, sonst eines für das Match). Ein Game reicht vom letzten Punkt
 * davor (beim ersten vom Matchbeginn) bis zu seinem letzten Punkt; Pausen sind wie in der Zeitleiste herausgeschnitten.
 */
fun MatchStats.courseSections(perSet: Boolean): List<CourseSection> {
    if (progression.isEmpty()) return emptyList()
    var previous = playClock.startMs
    val spans =
        progression.map { game ->
            val start = previous
            previous = game.points.lastOrNull()?.wallClockMs ?: start
            playClock.position(start) to playClock.position(previous)
        }
    val gameOfPoint = progression.flatMapIndexed { i, game -> List(game.points.size) { i } }
    val momentsByGame = moments.indices.groupBy { gameOfPoint.getOrNull(moments[it].moment.pointIndex) ?: progression.lastIndex }
    return progression.indices.groupBy { if (perSet) progression[it].set else 0 }.map { (set, indices) ->
        val timed = spans[indices.last()].second > spans[indices.first()].first
        val games =
            indices.mapIndexed { k, i ->
                if (timed) {
                    CourseGame(i, progression[i], spans[i].first, spans[i].second)
                } else {
                    CourseGame(
                        i,
                        progression[i],
                        k.toLong(),
                        k + 1L,
                    )
                }
            }
        val start = games.first().startMs
        val end = games.last().endMs
        val sectionMoments =
            games.flatMap { game ->
                momentsByGame[game.index].orEmpty().map { m ->
                    CourseMoment(m, if (timed) moments[m].playMs.coerceIn(start, end) else game.endMs)
                }
            }
        CourseSection(set.takeIf { perSet }, start, end, timed, games, sectionMoments)
    }
}

/** Pulskurve im Bereich [fromMs]…[toMs] der Spielzeit; Abschnitte bleiben getrennt. */
fun List<List<PulsePoint>>.within(
    fromMs: Long,
    toMs: Long,
): List<List<PulsePoint>> = map { segment -> segment.filter { it.playMs in fromMs..toMs } }.filter { it.isNotEmpty() }

/** Ø (gerundet) und Maximum eines Pulsverlaufs. */
data class PulseSummary(
    val average: Int,
    val max: Int,
)

/** Ø/Max über alle Werte; `null` ohne Werte. */
fun List<List<PulsePoint>>.pulseSummary(): PulseSummary? {
    val values = flatten().map { it.bpm }
    if (values.isEmpty()) return null
    return PulseSummary((values.sum() + values.size / 2) / values.size, values.max())
}

private val TICK_STEPS_MIN = listOf(1L, 2L, 5L, 10L, 15L, 30L, 60L)

/** Beschriftete Zeitpunkte der Achse (volle Minuten-Schritte, höchstens [maxTicks]) strikt innerhalb von [fromMs]…[toMs]. */
fun axisTicks(
    fromMs: Long,
    toMs: Long,
    maxTicks: Int = 6,
): List<Long> {
    val span = toMs - fromMs
    if (span <= 0) return emptyList()
    val step = (TICK_STEPS_MIN.firstOrNull { span / (it * 60_000) <= maxTicks } ?: (span / maxTicks / 3_600_000 + 1) * 60) * 60_000
    return generateSequence((fromMs / step + 1) * step) { it + step }.takeWhile { it < toMs }.toList()
}

/** bpm-Beschriftung rechts: runde Werte (Schritt 10/20/50 je nach Spanne) zwischen [min] und [max]. */
fun pulseTicks(
    min: Int,
    max: Int,
): List<Int> {
    val step =
        when {
            max - min >= 60 -> 50
            max - min >= 20 -> 20
            else -> 10
        }
    return ((min + step - 1) / step * step..max step step).toList()
}

/** Symbol der Highlight-Reihe: [members] (Indizes der Eingabe) teilen sich die Tippfläche um [center]. */
data class SymbolSlot(
    val members: List<Int>,
    val center: Float,
)

/**
 * Platziert Symbole der Breite [size] (Tippfläche) unter einer Achse der Breite [width] möglichst nah an ihren
 * Zeitpunkten [xs] (aufsteigend): Nahe Symbole rücken nach rechts auseinander und werden am rechten Rand nach links
 * zusammengeschoben – nichts läuft über den Rand, keine Flächen überlappen. Passen nicht alle nebeneinander oder rückte
 * eines dabei weiter als eine Symbolbreite von seinem Zeitpunkt weg, teilen sich die nächstgelegenen eine Fläche.
 */
fun placeSymbols(
    xs: List<Float>,
    width: Float,
    size: Float,
): List<SymbolSlot> {
    if (xs.isEmpty()) return emptyList()
    val half = size / 2
    val groups = xs.indices.map { mutableListOf(it) }.toMutableList()
    val capacity = (width / size).toInt().coerceAtLeast(1)
    while (true) {
        val wanted =
            groups.map { group ->
                (group.sumOf { xs[it].toDouble() }.toFloat() / group.size).coerceIn(
                    half,
                    maxOf(
                        half,
                        width - half,
                    ),
                )
            }
        val pos = wanted.toFloatArray()
        for (i in pos.indices) pos[i] = maxOf(pos[i], if (i == 0) half else pos[i - 1] + size)
        for (i in pos.indices.reversed()) pos[i] = minOf(pos[i], if (i == pos.lastIndex) width - half else pos[i + 1] - size)
        val fits = groups.size <= capacity && pos.indices.all { abs(pos[it] - wanted[it]) <= size }
        if (fits || groups.size == 1) return groups.mapIndexed { i, group -> SymbolSlot(group, pos[i]) }
        val k = (0 until groups.lastIndex).minBy { xs[groups[it + 1].first()] - xs[groups[it].last()] }
        groups[k] += groups.removeAt(k + 1)
    }
}

/** Hinweis, solange ein gewählter Pulsverlauf noch nicht von der Uhr da ist (Plan 0.5.1 §7d). */
enum class PulseTransfer {
    /** „Pulsverlauf wird von der Uhr übertragen …“ */
    PENDING,

    /** Länger als [PULSE_OVERDUE_MS] nach Matchende noch nichts da: Uhr in der Nähe des Handys öffnen. */
    OVERDUE,

    /**
     * Länger als die Aufbewahrung auf der Uhr ([WATCH_HEALTH_RETENTION_MS]) nach Matchende: Es kommt keiner mehr, neutral
     * „Kein Pulsverlauf von der Uhr erhalten“ statt einer dauerhaften Aufforderung.
     */
    MISSING,
}

/** Ab dieser Zeit nach Matchende gilt ein ausstehender Pulsverlauf als überfällig. */
const val PULSE_OVERDUE_MS: Long = 10 * 60_000

/** Übertragungsstand eines Matches mit gewählter Aufzeichnung ([PulseSetting.visibility]); `null` = kein Hinweis. */
fun PulseSetting.transfer(
    hasTrack: Boolean,
    finishedAtMs: Long,
    nowMs: Long,
): PulseTransfer? =
    when {
        visibility == TelemetryVisibility.OFF || recorded || hasTrack -> null
        nowMs - finishedAtMs > WATCH_HEALTH_RETENTION_MS -> PulseTransfer.MISSING
        nowMs - finishedAtMs > PULSE_OVERDUE_MS -> PulseTransfer.OVERDUE
        else -> PulseTransfer.PENDING
    }
