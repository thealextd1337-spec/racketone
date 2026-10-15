package eu.advantly.presentation.stats

import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Effect
import eu.advantly.core.scoring.EventLog
import eu.advantly.core.scoring.FlagLevel
import eu.advantly.core.scoring.GameKind
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.Paused
import eu.advantly.core.scoring.PointRecord
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Resumed
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.ServeOutcome
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
 * @property breakPointsSaved Breakbälle abgewehrt / zugelassen (Tennis/Padel)
 * @property progression wirksamer Ballwechsel-/Spielverlauf mit Break-Markern
 * @property setPoints Satzbälle verwertet / erspielt (Pickleball: Spielbälle)
 * @property decidingPoints Entscheidungspunkte (Golden/Star/No-Ad bzw. Deckel) gewonnen / gespielt
 * @property games gewonnene Spiele (nur Tennis/Padel, ohne Match-Tiebreak), sonst `null`
 * @property netDurationMs Spielzeit ohne Pausen (Genesis bis letztes Event)
 * @property serve Aufschlag-Kennzahlen je Team (02 §11.1/§11.2)
 * @property servers Aufschlag-Kennzahlen je Spieler (Doppel: nach Aufschlägerwahl bzw. Rotation), Lineup-Reihenfolge
 *   (erst A, dann B); Spieler ohne eigenen Aufschlag fehlen
 * @property serveInfoRecorded mindestens ein Punkt hat eine Aufschlag-Info – sonst zeigt die UI die Aufschlag-Quoten als „—“
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
    val serve: SidePair<ServeStats> = SidePair(ServeStats.EMPTY, ServeStats.EMPTY),
    val servers: List<PlayerServeStats> = emptyList(),
    val serveInfoRecorded: Boolean = false,
    val breakPointsSaved: SidePair<Ratio> = SidePair(Ratio(0, 0), Ratio(0, 0)),
    val progression: List<GameProgress> = emptyList(),
) {
    val totalPoints: Int get() = pointsWon.a + pointsWon.b
}

/**
 * Aufschlag-Kennzahlen eines Teams oder Spielers (Feldtest 2026-10-15). Alle Quoten aus Aufschlag-Info zählen nur Punkte,
 * für die sie erfasst wurde ([recorded]); ohne Erfassung ist `total = 0`, `Ratio.percent = null` → Anzeige „—“.
 *
 * @property servicePoints Punkte bei eigenem Aufschlag gewonnen / gespielt (immer verfügbar)
 * @property serviceGames Aufschlagspiele (Standardspiele) gewonnen / gespielt (Tennis/Padel, immer verfügbar)
 * @property firstServeIn 1. Aufschlag gut / Punkte mit Aufschlag-Info („1.-Aufschlag-Quote“)
 * @property firstServeWon nach gutem 1. Aufschlag gewonnen / gute 1. Aufschläge
 * @property secondServeWon nach 2. Aufschlag gewonnen / Punkte mit 2. Aufschlag (inklusive Doppelfehler, Tour-Konvention)
 * @property doubleFaults Doppelfehler
 * @property recorded Aufschlagpunkte mit Aufschlag-Info
 */
data class ServeStats(
    val servicePoints: Ratio,
    val serviceGames: Ratio,
    val firstServeIn: Ratio,
    val firstServeWon: Ratio,
    val secondServeWon: Ratio,
    val doubleFaults: Int,
    val recorded: Int,
) {
    companion object {
        private val NONE = Ratio(0, 0)

        /** Keine Aufschlagpunkte. */
        val EMPTY: ServeStats = ServeStats(NONE, NONE, NONE, NONE, NONE, 0, 0)
    }
}

/** [ServeStats] eines Spielers der Side [side]. */
data class PlayerServeStats(
    val player: PlayerId,
    val side: Side,
    val stats: ServeStats,
)

/** Berechnet [MatchStats] über `ScoringEngine.pointHistory`. */
object MatchStatistics {
    fun compute(record: MatchRecord): MatchStats {
        val genesis = record.events.first()
        val created = genesis.payload as MatchCreated
        val setup = record.summary.setup
        // Vollständiges Log inkl. Genesis: Zähler-Rolle aus MatchCreated (Single-Scorer, 02 §7.1a).
        val points = ScoringEngine.pointHistory(record.events)
        return MatchStats(
            pointsWon = count(points) { p, side -> p.point.side == side },
            servicePoints = ratio(points, { p, side -> p.before.serve?.side == side }) { p, side -> p.point.side == side },
            returnPoints = ratio(points, { p, side -> p.before.serve?.side == side.other }) { p, side -> p.point.side == side },
            breakPoints = flagged(points) { it.breakPoint },
            setPoints = flagged(points) { it.level == FlagLevel.SET_POINT || it.level == FlagLevel.MATCH_POINT },
            decidingPoints =
                ratio(points, { p, _ -> p.before.current?.decidingPoint == true }) { p, side -> p.point.side == side },
            breakPointsSaved =
                ratio(points, { p, side ->
                    p.before.statusFlags.any { it.side == side.other && it.breakPoint }
                }) { p, side -> p.point.side == side },
            progression = matchProgression(points),
            longestStreak = streaks(points),
            games = games(record, created.config.sport.isTennisLike),
            netDurationMs = netDuration(record),
            serve = SidePair.of { side -> serveStats(points) { it.before.serve?.side == side } },
            servers =
                (created.lineup.a + created.lineup.b)
                    .filter { player ->
                        val assigned = player in setup.assignedPlayers
                        setup.player(player)?.isPlaceholder != true && (assigned || !Placeholders.isPlaceholder(player))
                    }.filter { player -> points.any { it.server == player } }
                    .map { player ->
                        PlayerServeStats(
                            setup.assignedPlayers[player]
                                ?.id ?: player,
                            checkNotNull(created.lineup.sideOf(player)),
                            serveStats(points) {
                                it.server ==
                                    player
                            },
                        )
                    },
            serveInfoRecorded = points.any { it.serve != null },
        )
    }

    /**
     * Aufschlag-Kennzahlen über die Punkte, die [served] auswählt (Team oder Spieler als Aufschläger). Ein Aufschlagspiel
     * wird dem Aufschläger seines letzten Punktes zugerechnet (Standardspiel mit `GAME_WON`).
     */
    private fun serveStats(
        points: List<PointRecord>,
        served: (PointRecord) -> Boolean,
    ): ServeStats {
        val mine = points.filter(served)

        fun wonByServer(p: PointRecord) = p.point.side == p.before.serve?.side
        val games =
            mine.filter { it.before.current?.gameKind == GameKind.STANDARD && Effect.GAME_WON in it.after.effects }
        val withInfo = mine.filter { it.serve != null }
        val first = withInfo.filter { it.serve == ServeOutcome.FIRST_SERVE_IN }
        val second = withInfo.filter { it.serve != ServeOutcome.FIRST_SERVE_IN }
        return ServeStats(
            servicePoints = Ratio(mine.count(::wonByServer), mine.size),
            serviceGames = Ratio(games.count(::wonByServer), games.size),
            firstServeIn = Ratio(first.size, withInfo.size),
            firstServeWon = Ratio(first.count(::wonByServer), first.size),
            secondServeWon = Ratio(second.count(::wonByServer), second.size),
            doubleFaults = withInfo.count { it.serve == ServeOutcome.DOUBLE_FAULT },
            recorded = withInfo.size,
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
        val events = record.events.filterNot { it.payload is eu.advantly.core.scoring.PlayerAssigned }
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
