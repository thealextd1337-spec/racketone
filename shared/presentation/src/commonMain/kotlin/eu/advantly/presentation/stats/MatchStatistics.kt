package eu.advantly.presentation.stats

import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EventLog
import eu.advantly.core.scoring.FlagLevel
import eu.advantly.core.scoring.GameKind
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.Paused
import eu.advantly.core.scoring.PointRecord
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Resumed
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.SetKind
import eu.advantly.core.scoring.SidePair
import eu.advantly.core.scoring.StatusFlag
import eu.advantly.presentation.match.MatchRecord

/** Zähler „x von y“ (z. B. Breakbälle verwertet 2/5). */
data class Ratio(
    val won: Int,
    val total: Int,
) {
    /** Prozent (ganzzahlig, abgerundet) oder `null` bei 0 Versuchen. */
    val percent: Int? get() = if (total == 0) null else won * 100 / total
}

/**
 * Einfache Kennzahlen aus den wirksamen Punkten (02 §11.1) – nur, was die Engine ohne Zusatzeingaben liefert.
 *
 * @property servicePoints Punkte bei eigenem Aufschlag gewonnen / gespielt
 * @property returnPoints Punkte bei gegnerischem Aufschlag gewonnen / gespielt
 * @property breakPoints Breakbälle verwertet / erspielt (Tennis/Padel; jede Chance zählt einzeln)
 * @property setPoints Satzbälle verwertet / erspielt (Pickleball: Spielbälle)
 * @property decidingPoints Entscheidungspunkte (Golden/Star/No-Ad bzw. Deckel) gewonnen / gespielt
 * @property games gewonnene Spiele (nur Tennis/Padel, ohne Match-Tiebreak), sonst `null`
 * @property netDurationMs Spielzeit ohne Pausen (Genesis bis letztes Event)
 */
data class MatchStats(
    val pointsWon: SidePair<Int>,
    val servicePoints: SidePair<Ratio>,
    val returnPoints: SidePair<Ratio>,
    val breakPoints: SidePair<Ratio>,
    val setPoints: SidePair<Ratio>,
    val decidingPoints: SidePair<Ratio>,
    val longestStreak: SidePair<Int>,
    val games: SidePair<Int>?,
    val netDurationMs: Long,
) {
    val totalPoints: Int get() = pointsWon.a + pointsWon.b
}

/** Berechnet [MatchStats] über `ScoringEngine.pointHistory`. */
object MatchStatistics {
    fun compute(record: MatchRecord): MatchStats {
        val genesis = record.events.first()
        val created = genesis.payload as MatchCreated
        val events = record.events.drop(1)
        val points = ScoringEngine.pointHistory(created.config, created.lineup, events)
        return MatchStats(
            pointsWon = count(points) { p, side -> p.point.side == side },
            servicePoints = ratio(points, { p, side -> p.before.serve?.side == side }) { p, side -> p.point.side == side },
            returnPoints = ratio(points, { p, side -> p.before.serve?.side == side.other }) { p, side -> p.point.side == side },
            breakPoints = flagged(points) { it.breakPoint },
            setPoints = flagged(points) { it.level == FlagLevel.SET_POINT || it.level == FlagLevel.MATCH_POINT },
            decidingPoints =
                ratio(points, { p, _ -> p.before.current?.decidingPoint == true }) { p, side -> p.point.side == side },
            longestStreak = streaks(points),
            games = games(record, created.config.sport.isTennisLike),
            netDurationMs = netDuration(record),
        )
    }

    private fun count(
        points: List<PointRecord>,
        predicate: (PointRecord, Side) -> Boolean,
    ): SidePair<Int> = SidePair.of { side -> points.count { predicate(it, side) } }

    private fun ratio(
        points: List<PointRecord>,
        played: (PointRecord, Side) -> Boolean,
        won: (PointRecord, Side) -> Boolean,
    ): SidePair<Ratio> =
        SidePair.of { side ->
            val relevant = points.filter { played(it, side) }
            Ratio(relevant.count { won(it, side) }, relevant.size)
        }

    /** Chancen mit einem Flag von X, das [filter] erfüllt; verwertet = X gewinnt den Punkt. */
    private fun flagged(
        points: List<PointRecord>,
        filter: (StatusFlag) -> Boolean,
    ): SidePair<Ratio> =
        ratio(points, { p, side -> p.before.statusFlags.any { it.side == side && filter(it) } }) { p, side -> p.point.side == side }

    private fun streaks(points: List<PointRecord>): SidePair<Int> {
        var best = SidePair(0, 0)
        var run = 0
        var last: Side? = null
        for (p in points) {
            run = if (p.point.side == last) run + 1 else 1
            last = p.point.side
            if (run > best[p.point.side]) best = best.with(p.point.side, run)
        }
        return best
    }

    private fun games(
        record: MatchRecord,
        tennisLike: Boolean,
    ): SidePair<Int>? {
        if (!tennisLike) return null
        val state = record.finalState
        val done = state.sets.filter { it.kind == SetKind.REGULAR }.mapNotNull { it.games }
        val current = state.current?.takeIf { it.gameKind != GameKind.MATCH_TIEBREAK }?.games
        return (done + listOfNotNull(current)).fold(SidePair(0, 0)) { acc, g -> SidePair(acc.a + g.a, acc.b + g.b) }
    }

    /**
     * Spielzeit ohne Pausen (02 §9.3): Eine Pause endet mit `Resumed` **oder** dem nächsten wirksamen `PointWon`
     * (implizite Fortsetzung); eine bis zum Schluss offene Pause zählt bis zum letzten Event nicht mit.
     * Rückgängig gemachte und übersprungene Events werden ignoriert.
     */
    private fun netDuration(record: MatchRecord): Long {
        val events = record.events
        if (events.size < 2) return 0
        val end = events.last().wallClockMs
        var total = end - events.first().wallClockMs
        val log = EventLog.analyze(events.drop(1))
        val skipped = log.undone + log.rejected + log.absorbed + record.finalState.ignoredEvents
        var pausedAt: Long? = null
        for (e in log.ordered) {
            if (e.id in skipped) continue
            when (e.payload) {
                Paused -> {
                    if (pausedAt == null) pausedAt = e.wallClockMs
                }

                Resumed, is PointWon -> {
                    pausedAt?.let { total -= e.wallClockMs - it }
                    pausedAt = null
                }

                else -> {
                    Unit
                }
            }
        }
        pausedAt?.let { total -= end - it }
        return total.coerceAtLeast(0)
    }
}
