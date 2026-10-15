package eu.advantly.presentation.stats

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Effect
import eu.advantly.core.scoring.GameKind
import eu.advantly.core.scoring.PointRecord
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.match.MatchRecord
import eu.advantly.presentation.match.MatchSetup

/** Ballwechsel mit Stand vor dem Punkt; Side-Outs sind ausdrücklich keine Zählpunkte. */
data class PointProgress(
    val number: Int,
    val winner: Side,
    val before: SidePair<String>,
    val scored: Boolean,
)

/** Ein Spiel bzw. Rally-Satz, einschließlich eines bei Abbruch noch laufenden Spiels. */
data class GameProgress(
    val set: Int,
    val game: Int,
    val kind: GameKind,
    val pointsWon: SidePair<Int>,
    val winner: Side?,
    val isBreak: Boolean,
    val points: List<PointProgress>,
    val server: Side? = null,
) {
    val margin: Int get() = pointsWon.a - pointsWon.b
}

/** Nutzt ausschließlich wirksame Ballwechsel aus pointHistory; Tiebreaks sind niemals Breaks. */
fun matchProgression(points: List<PointRecord>): List<GameProgress> =
    points
        .groupBy { p ->
            val current = checkNotNull(p.before.current)
            Triple(p.before.sets.size + 1, current.games?.let { it.a + it.b + 1 } ?: 1, current.gameKind)
        }.map { (key, gamePoints) ->
            val last = gamePoints.last()
            val complete = Effect.GAME_WON in last.after.effects || Effect.SET_WON in last.after.effects
            GameProgress(
                set = key.first,
                game = key.second,
                kind = key.third,
                pointsWon = SidePair.of { side -> gamePoints.count { it.point.side == side } },
                winner = last.point.side.takeIf { complete },
                isBreak = complete && key.third == GameKind.STANDARD && last.before.serve?.side == last.point.side.other,
                server =
                    last.before.serve
                        ?.side
                        .takeIf { key.third == GameKind.STANDARD },
                points =
                    gamePoints.mapIndexed { index, p ->
                        PointProgress(index + 1, p.point.side, checkNotNull(p.before.current).display, Effect.POINT in p.after.effects)
                    },
            )
        }

/** Kleiner, UI-lokaler Cache: kein pointHistory-Replay bei Recomposition oder erneutem Öffnen. */
class MatchStatisticsCache(
    private val compute: (MatchRecord) -> MatchStats = MatchStatistics::compute,
) {
    private data class Entry(
        val updatedAtMs: Long,
        val setup: MatchSetup,
        val eventCount: Int,
        val stats: MatchStats,
    )

    private val entries = LinkedHashMap<MatchId, Entry>()

    fun get(record: MatchRecord): MatchStats {
        val summary = record.summary
        entries[summary.matchId]?.let {
            if (it.updatedAtMs == summary.updatedAtMs && it.setup == summary.setup && it.eventCount == record.events.size) return it.stats
        }
        val stats = compute(record)
        entries.remove(summary.matchId)
        entries[summary.matchId] = Entry(summary.updatedAtMs, summary.setup, record.events.size, stats)
        if (entries.size > 8) entries.remove(entries.keys.first())
        return stats
    }
}
