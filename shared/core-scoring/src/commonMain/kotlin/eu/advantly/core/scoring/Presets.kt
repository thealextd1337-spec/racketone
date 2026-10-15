package eu.advantly.core.scoring

import eu.advantly.core.model.DeuceMode
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.EndChange
import eu.advantly.core.model.GameFormat
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchSettings
import eu.advantly.core.model.MatchTiebreakFormat
import eu.advantly.core.model.MidGameEndChange
import eu.advantly.core.model.NextGameFirstServer
import eu.advantly.core.model.PickleballFormat
import eu.advantly.core.model.PickleballScoring
import eu.advantly.core.model.RallyFormat
import eu.advantly.core.model.RegularSet
import eu.advantly.core.model.ServeRotation
import eu.advantly.core.model.SetFormat
import eu.advantly.core.model.Sport
import eu.advantly.core.model.TiebreakFormat

/**
 * Benanntes, vollständig ausgefülltes Format (02 §4). Presets mit „SINGLES/DOUBLES“ liefern je Disziplin ein Format;
 * bei Tischtennis hängt `receiverSwapAtMidGameEndChange` von der Disziplin ab (02 §4.5).
 *
 * @property disciplines erlaubte Disziplinen; die erste ist der Default
 */
class Preset internal constructor(
    val id: String,
    val sport: Sport,
    val disciplines: List<Discipline>,
    private val build: (Discipline) -> MatchFormat,
) {
    /** Default-Disziplin des Presets. */
    val defaultDiscipline: Discipline get() = disciplines.first()

    /** Format für [discipline] (muss in [disciplines] enthalten sein). */
    fun format(discipline: Discipline = defaultDiscipline): MatchFormat {
        require(discipline in disciplines) { "$id unterstützt $discipline nicht" }
        return build(discipline)
    }

    override fun toString(): String = id
}

/** Alle Presets aus 02 §4 als Konstanten. Die UI-Anzeigetexte liegen in den UI-Modulen (DE/EN), nicht hier. */
object Presets {
    private val SINGLES = listOf(Discipline.SINGLES)
    private val DOUBLES = listOf(Discipline.DOUBLES)
    private val BOTH = listOf(Discipline.SINGLES, Discipline.DOUBLES)

    private val TB7 = TiebreakFormat.TB7
    private val MTB10 = MatchTiebreakFormat.MTB10
    private val MTB7 = MatchTiebreakFormat.MTB7

    private fun set(
        games: Int,
        tbAt: Int?,
        tb: TiebreakFormat = TB7,
    ) = RegularSet(games, tbAt, tb)

    private fun tennisLike(
        id: String,
        sport: Sport,
        disciplines: List<Discipline>,
        match: MatchSettings,
        deuce: DeuceMode,
        set: RegularSet,
        decidingSet: SetFormat? = null,
        matchTiebreak: MatchTiebreakFormat? = null,
    ) = Preset(id, sport, disciplines) { d ->
        MatchFormat(
            presetId = id,
            sport = sport,
            discipline = d,
            match = match,
            game = GameFormat(deuce, if (deuce == DeuceMode.STAR_POINT) GameFormat.DEFAULT_MAX_ADVANTAGES else null),
            set = set,
            decidingSet = decidingSet,
            matchTiebreak = matchTiebreak,
        )
    }

    // --- 4.1 Tennis ---
    val TENNIS_SINGLES_BO3 =
        tennisLike("TENNIS_SINGLES_BO3", Sport.TENNIS, SINGLES, MatchSettings.bestOf(3), DeuceMode.ADVANTAGE, set(6, 6))
    val TENNIS_SINGLES_BO5_FS10 =
        tennisLike(
            "TENNIS_SINGLES_BO5_FS10",
            Sport.TENNIS,
            SINGLES,
            MatchSettings.bestOf(5),
            DeuceMode.ADVANTAGE,
            set(6, 6),
            decidingSet = set(6, 6, TiebreakFormat(10, 2, EndChange(6, 6))),
        )
    val TENNIS_BO3_MTB10 =
        tennisLike("TENNIS_BO3_MTB10", Sport.TENNIS, BOTH, MatchSettings.bestOf(3), DeuceMode.ADVANTAGE, set(6, 6), decidingSet = MTB10)
    val TENNIS_DOUBLES_MTB10 =
        tennisLike(
            "TENNIS_DOUBLES_MTB10",
            Sport.TENNIS,
            DOUBLES,
            MatchSettings.bestOf(3),
            DeuceMode.DECIDING_POINT,
            set(6, 6),
            decidingSet = MTB10,
        )
    val TENNIS_BO3_ADV_FINAL =
        tennisLike(
            "TENNIS_BO3_ADV_FINAL",
            Sport.TENNIS,
            BOTH,
            MatchSettings.bestOf(3),
            DeuceMode.ADVANTAGE,
            set(6, 6),
            decidingSet = set(6, null),
        )
    val TENNIS_SHORT_SETS =
        tennisLike("TENNIS_SHORT_SETS", Sport.TENNIS, BOTH, MatchSettings.bestOf(3), DeuceMode.ADVANTAGE, set(4, 4), decidingSet = MTB10)
    val TENNIS_SHORT_SETS_3ALL =
        tennisLike(
            "TENNIS_SHORT_SETS_3ALL",
            Sport.TENNIS,
            BOTH,
            MatchSettings.bestOf(3),
            DeuceMode.DECIDING_POINT,
            set(4, 3, TiebreakFormat(5, 1, EndChange(4, null))),
        )
    val TENNIS_PRO_SET_8 = tennisLike("TENNIS_PRO_SET_8", Sport.TENNIS, BOTH, MatchSettings.bestOf(1), DeuceMode.ADVANTAGE, set(8, 8))
    val TENNIS_PRO_SET_10 = tennisLike("TENNIS_PRO_SET_10", Sport.TENNIS, BOTH, MatchSettings.bestOf(1), DeuceMode.ADVANTAGE, set(10, 10))
    val TENNIS_ONE_SET = tennisLike("TENNIS_ONE_SET", Sport.TENNIS, BOTH, MatchSettings.bestOf(1), DeuceMode.ADVANTAGE, set(6, 6))
    val TENNIS_MATCH_TB_ONLY =
        tennisLike("TENNIS_MATCH_TB_ONLY", Sport.TENNIS, BOTH, MatchSettings.bestOf(1), DeuceMode.ADVANTAGE, set(6, 6), decidingSet = MTB10)

    /**
     * „Nur Tiebreak“ (02 §4.1, Entscheidung 2026-10-15): Das Match ist ein einziger Tiebreak bis 7 (2 Vorsprung,
     * Wechsel alle 6 Punkte). Modelliert als Best of 1 mit `decidingSet = MATCH_TIEBREAK` – ohne neues Format-Feld,
     * damit auch ältere Geräte das Match falten können.
     */
    val TENNIS_TIEBREAK_ONLY_7 =
        tennisLike(
            "TENNIS_TIEBREAK_ONLY_7",
            Sport.TENNIS,
            BOTH,
            MatchSettings.bestOf(1),
            DeuceMode.ADVANTAGE,
            set(6, 6),
            decidingSet = MTB7,
        )

    /** „Nur Champions-Tiebreak“ bis 10 (wie [TENNIS_MATCH_TB_ONLY], eigene ID für den Wizard). */
    val TENNIS_TIEBREAK_ONLY_10 =
        tennisLike(
            "TENNIS_TIEBREAK_ONLY_10",
            Sport.TENNIS,
            BOTH,
            MatchSettings.bestOf(1),
            DeuceMode.ADVANTAGE,
            set(6, 6),
            decidingSet = MTB10,
        )
    val TENNIS_OPEN =
        tennisLike("TENNIS_OPEN", Sport.TENNIS, BOTH, MatchSettings.OPEN, DeuceMode.ADVANTAGE, set(6, 6), matchTiebreak = MTB10)

    // --- 4.2 Padel (immer DOUBLES) ---
    val PADEL_PREMIER_2026 =
        tennisLike("PADEL_PREMIER_2026", Sport.PADEL, DOUBLES, MatchSettings.bestOf(3), DeuceMode.STAR_POINT, set(6, 6))
    val PADEL_GOLDEN_BO3 =
        tennisLike("PADEL_GOLDEN_BO3", Sport.PADEL, DOUBLES, MatchSettings.bestOf(3), DeuceMode.DECIDING_POINT, set(6, 6))
    val PADEL_GOLDEN_STB =
        tennisLike(
            "PADEL_GOLDEN_STB",
            Sport.PADEL,
            DOUBLES,
            MatchSettings.bestOf(3),
            DeuceMode.DECIDING_POINT,
            set(6, 6),
            decidingSet = MTB10,
        )
    val PADEL_ADVANTAGE_BO3 =
        tennisLike("PADEL_ADVANTAGE_BO3", Sport.PADEL, DOUBLES, MatchSettings.bestOf(3), DeuceMode.ADVANTAGE, set(6, 6))
    val PADEL_ADV_FINAL_NO_TB =
        tennisLike(
            "PADEL_ADV_FINAL_NO_TB",
            Sport.PADEL,
            DOUBLES,
            MatchSettings.bestOf(3),
            DeuceMode.ADVANTAGE,
            set(6, 6),
            decidingSet = set(6, null),
        )
    val PADEL_MINI_SETS =
        tennisLike(
            "PADEL_MINI_SETS",
            Sport.PADEL,
            DOUBLES,
            MatchSettings.bestOf(3),
            DeuceMode.DECIDING_POINT,
            set(4, 4),
            decidingSet = MTB10,
        )
    val PADEL_OPEN =
        tennisLike("PADEL_OPEN", Sport.PADEL, DOUBLES, MatchSettings.OPEN, DeuceMode.STAR_POINT, set(6, 6), matchTiebreak = MTB10)

    /** Padel „Nur Tiebreak“ bis 7 (wie [TENNIS_TIEBREAK_ONLY_7]). */
    val PADEL_TIEBREAK_ONLY_7 =
        tennisLike(
            "PADEL_TIEBREAK_ONLY_7",
            Sport.PADEL,
            DOUBLES,
            MatchSettings.bestOf(1),
            DeuceMode.DECIDING_POINT,
            set(6, 6),
            decidingSet = MTB7,
        )

    /** Padel „Nur Super-Tiebreak“ bis 10 (wie [TENNIS_TIEBREAK_ONLY_10]). */
    val PADEL_TIEBREAK_ONLY_10 =
        tennisLike(
            "PADEL_TIEBREAK_ONLY_10",
            Sport.PADEL,
            DOUBLES,
            MatchSettings.bestOf(1),
            DeuceMode.DECIDING_POINT,
            set(6, 6),
            decidingSet = MTB10,
        )

    /**
     * „Games pro Satz“ frei 1…12 (Wizard: 1–6, 8, 10; 02 §5.1, Entscheidung 2026-10-15) für Tennis/Padel: `set.gamesToWin = n`
     * und `set.tiebreakAt = n` (Tiebreak bei n:n für **alle** n ≥ 1, z. B. 1 Game: 2:0 oder Tiebreak bei 1:1 → 2:1).
     * Ein regulärer Entscheidungssatz wird mitgezogen (ein Vorteilssatz bleibt ohne Tiebreak), ein Match-Tiebreak bleibt.
     * `presetId` bleibt erhalten (nur Anzeige/Statistik).
     */
    fun withGamesPerSet(
        format: MatchFormat,
        games: Int,
    ): MatchFormat {
        require(format.sport.isTennisLike) { "Games pro Satz gibt es nur bei Tennis/Padel" }
        require(games in 1..12) { "Games pro Satz: 1…12" }
        val set = checkNotNull(format.set)
        val deciding =
            when (val ds = format.decidingSet) {
                is RegularSet -> ds.copy(gamesToWin = games, tiebreakAt = ds.tiebreakAt?.let { games })
                else -> ds
            }
        return format.copy(set = set.copy(gamesToWin = games, tiebreakAt = games), decidingSet = deciding)
    }

    // --- 4.3 Pickleball ---
    private fun pickleball(
        id: String,
        disciplines: List<Discipline>,
        match: MatchSettings,
        scoring: PickleballScoring,
        pointsToWin: Int,
        winBy: Int = 2,
        mid: MidGameEndChange = MidGameEndChange.DECIDING_GAME_ONLY,
    ) = Preset(id, Sport.PICKLEBALL, disciplines) { d ->
        MatchFormat(
            presetId = id,
            sport = Sport.PICKLEBALL,
            discipline = d,
            match = match,
            pickleball = PickleballFormat(scoring = scoring, pointsToWin = pointsToWin, winBy = winBy, midGameEndChange = mid),
        )
    }

    val PICKLEBALL_DOUBLES_11_SIDEOUT =
        pickleball("PICKLEBALL_DOUBLES_11_SIDEOUT", DOUBLES, MatchSettings.bestOf(1), PickleballScoring.SIDE_OUT, 11)
    val PICKLEBALL_DOUBLES_BO3_11 =
        pickleball("PICKLEBALL_DOUBLES_BO3_11", DOUBLES, MatchSettings.bestOf(3), PickleballScoring.SIDE_OUT, 11)
    val PICKLEBALL_DOUBLES_15 = pickleball("PICKLEBALL_DOUBLES_15", DOUBLES, MatchSettings.bestOf(1), PickleballScoring.SIDE_OUT, 15)
    val PICKLEBALL_DOUBLES_21 = pickleball("PICKLEBALL_DOUBLES_21", DOUBLES, MatchSettings.bestOf(1), PickleballScoring.SIDE_OUT, 21)
    val PICKLEBALL_SINGLES_11_SIDEOUT =
        pickleball("PICKLEBALL_SINGLES_11_SIDEOUT", SINGLES, MatchSettings.bestOf(1), PickleballScoring.SIDE_OUT, 11)
    val PICKLEBALL_SINGLES_BO3_11 =
        pickleball("PICKLEBALL_SINGLES_BO3_11", SINGLES, MatchSettings.bestOf(3), PickleballScoring.SIDE_OUT, 11)
    val PICKLEBALL_DOUBLES_RALLY_21 =
        pickleball("PICKLEBALL_DOUBLES_RALLY_21", DOUBLES, MatchSettings.bestOf(1), PickleballScoring.RALLY, 21)
    val PICKLEBALL_RALLY_BO3_15 = pickleball("PICKLEBALL_RALLY_BO3_15", BOTH, MatchSettings.bestOf(3), PickleballScoring.RALLY, 15)
    val PICKLEBALL_RALLY_7_WEATHER = pickleball("PICKLEBALL_RALLY_7_WEATHER", BOTH, MatchSettings.bestOf(1), PickleballScoring.RALLY, 7)
    val PICKLEBALL_TEAM_WIN_BY_1 =
        pickleball("PICKLEBALL_TEAM_WIN_BY_1", DOUBLES, MatchSettings.bestOf(1), PickleballScoring.SIDE_OUT, 11, winBy = 1)
    val PICKLEBALL_OPEN =
        pickleball("PICKLEBALL_OPEN", DOUBLES, MatchSettings.OPEN, PickleballScoring.SIDE_OUT, 11, mid = MidGameEndChange.EVERY_GAME)

    // --- 4.4 Badminton ---

    /** R21: bis 21, 2 Vorsprung, Deckel 30, Wechsel und Pause bei 11. */
    private fun r21() = badmintonRally(21, 2, 30, 11, 11)

    /** R15: bis 15, 2 Vorsprung, Deckel 21, Wechsel und Pause bei 8. */
    private fun r15() = badmintonRally(15, 2, 21, 8, 8)

    private fun badmintonRally(
        pointsToWin: Int,
        winBy: Int,
        cap: Int?,
        midAt: Int,
        intervalAt: Int?,
    ) = RallyFormat(
        pointsToWin = pointsToWin,
        winBy = winBy,
        cap = cap,
        serveRotation = ServeRotation.WINNER_SERVES,
        serveEvery = null,
        serveEveryAtDeuce = null,
        nextGameFirstServer = NextGameFirstServer.GAME_WINNER,
        midGameEndChange = MidGameEndChange.DECIDING_GAME_ONLY,
        midGameEndChangeAt = midAt,
        receiverSwapAtMidGameEndChange = false,
        intervalAt = intervalAt,
    )

    private fun rallyPreset(
        id: String,
        sport: Sport,
        disciplines: List<Discipline>,
        match: MatchSettings,
        rally: (Discipline) -> RallyFormat,
    ) = Preset(id, sport, disciplines) { d ->
        MatchFormat(presetId = id, sport = sport, discipline = d, match = match, rally = rally(d))
    }

    val BADMINTON_SINGLES_BO3_15 = rallyPreset("BADMINTON_SINGLES_BO3_15", Sport.BADMINTON, SINGLES, MatchSettings.bestOf(3)) { r15() }
    val BADMINTON_DOUBLES_BO3_15 = rallyPreset("BADMINTON_DOUBLES_BO3_15", Sport.BADMINTON, DOUBLES, MatchSettings.bestOf(3)) { r15() }
    val BADMINTON_SINGLES_BO3_21 = rallyPreset("BADMINTON_SINGLES_BO3_21", Sport.BADMINTON, SINGLES, MatchSettings.bestOf(3)) { r21() }
    val BADMINTON_DOUBLES_BO3_21 = rallyPreset("BADMINTON_DOUBLES_BO3_21", Sport.BADMINTON, DOUBLES, MatchSettings.bestOf(3)) { r21() }
    val BADMINTON_1GAME_21 = rallyPreset("BADMINTON_1GAME_21", Sport.BADMINTON, BOTH, MatchSettings.bestOf(1)) { r21() }
    val BADMINTON_1GAME_15 = rallyPreset("BADMINTON_1GAME_15", Sport.BADMINTON, BOTH, MatchSettings.bestOf(1)) { r15() }
    val BADMINTON_BO5_11 =
        rallyPreset("BADMINTON_BO5_11", Sport.BADMINTON, BOTH, MatchSettings.bestOf(5)) { badmintonRally(11, 1, null, 6, null) }

    /** `BADMINTON_OPEN` mit R15 (BWF ab 04.01.2027); für R21 siehe [badmintonOpen]. */
    val BADMINTON_OPEN = rallyPreset("BADMINTON_OPEN", Sport.BADMINTON, BOTH, MatchSettings.OPEN) { r15() }

    /** `BADMINTON_OPEN` mit 15 oder 21 Punkten pro Satz (02 §4.4: „R15 bzw. R21 nach Datum“). */
    fun badmintonOpen(
        pointsToWin: Int,
        discipline: Discipline = Discipline.SINGLES,
    ): MatchFormat {
        require(pointsToWin == 15 || pointsToWin == 21) { "Badminton offen: 15 oder 21 Punkte" }
        return BADMINTON_OPEN.format(discipline).copy(rally = if (pointsToWin == 21) r21() else r15())
    }

    /**
     * Badminton-Default nach dem **lokalen Datum der Match-Erstellung** (U11, 02 §4.4): bis einschließlich 03.01.2027
     * 21 Punkte (3×21), ab 04.01.2027 15 Punkte (3×15). Das Datum kommt als Parameter herein (keine Uhr in der Engine).
     */
    fun badmintonDefaultPointsToWin(
        year: Int,
        month: Int,
        day: Int,
    ): Int {
        val key = year * 10_000 + month * 100 + day
        return if (key <= 20270103) 21 else 15
    }

    // --- 4.5 Tischtennis ---
    private fun ttRally(
        pointsToWin: Int,
        serveEvery: Int,
        midAt: Int,
        discipline: Discipline,
    ) = RallyFormat(
        pointsToWin = pointsToWin,
        winBy = 2,
        cap = null,
        serveRotation = ServeRotation.EVERY_N_POINTS,
        serveEvery = serveEvery,
        serveEveryAtDeuce = 1,
        nextGameFirstServer = NextGameFirstServer.PREVIOUS_FIRST_RECEIVER,
        midGameEndChange = MidGameEndChange.DECIDING_GAME_ONLY,
        midGameEndChangeAt = midAt,
        receiverSwapAtMidGameEndChange = discipline == Discipline.DOUBLES,
        intervalAt = null,
    )

    private fun t11(d: Discipline) = ttRally(11, 2, 5, d)

    private fun t21(d: Discipline) = ttRally(21, 5, 10, d)

    val TABLE_TENNIS_SINGLES_BO5 = rallyPreset("TABLE_TENNIS_SINGLES_BO5", Sport.TABLE_TENNIS, SINGLES, MatchSettings.bestOf(5)) { t11(it) }
    val TABLE_TENNIS_SINGLES_BO7 = rallyPreset("TABLE_TENNIS_SINGLES_BO7", Sport.TABLE_TENNIS, SINGLES, MatchSettings.bestOf(7)) { t11(it) }
    val TABLE_TENNIS_SINGLES_BO3 = rallyPreset("TABLE_TENNIS_SINGLES_BO3", Sport.TABLE_TENNIS, SINGLES, MatchSettings.bestOf(3)) { t11(it) }
    val TABLE_TENNIS_DOUBLES_BO5 = rallyPreset("TABLE_TENNIS_DOUBLES_BO5", Sport.TABLE_TENNIS, DOUBLES, MatchSettings.bestOf(5)) { t11(it) }
    val TABLE_TENNIS_DOUBLES_BO3 = rallyPreset("TABLE_TENNIS_DOUBLES_BO3", Sport.TABLE_TENNIS, DOUBLES, MatchSettings.bestOf(3)) { t11(it) }
    val TABLE_TENNIS_LEISURE_21 = rallyPreset("TABLE_TENNIS_LEISURE_21", Sport.TABLE_TENNIS, BOTH, MatchSettings.bestOf(3)) { t21(it) }
    val TABLE_TENNIS_OPEN = rallyPreset("TABLE_TENNIS_OPEN", Sport.TABLE_TENNIS, BOTH, MatchSettings.OPEN) { t11(it) }

    /** Alle Presets in der Reihenfolge von 02 §4. */
    val all: List<Preset> =
        listOf(
            TENNIS_SINGLES_BO3,
            TENNIS_SINGLES_BO5_FS10,
            TENNIS_BO3_MTB10,
            TENNIS_DOUBLES_MTB10,
            TENNIS_BO3_ADV_FINAL,
            TENNIS_SHORT_SETS,
            TENNIS_SHORT_SETS_3ALL,
            TENNIS_PRO_SET_8,
            TENNIS_PRO_SET_10,
            TENNIS_ONE_SET,
            TENNIS_MATCH_TB_ONLY,
            TENNIS_TIEBREAK_ONLY_7,
            TENNIS_TIEBREAK_ONLY_10,
            TENNIS_OPEN,
            PADEL_PREMIER_2026,
            PADEL_GOLDEN_BO3,
            PADEL_GOLDEN_STB,
            PADEL_ADVANTAGE_BO3,
            PADEL_ADV_FINAL_NO_TB,
            PADEL_MINI_SETS,
            PADEL_OPEN,
            PADEL_TIEBREAK_ONLY_7,
            PADEL_TIEBREAK_ONLY_10,
            PICKLEBALL_DOUBLES_11_SIDEOUT,
            PICKLEBALL_DOUBLES_BO3_11,
            PICKLEBALL_DOUBLES_15,
            PICKLEBALL_DOUBLES_21,
            PICKLEBALL_SINGLES_11_SIDEOUT,
            PICKLEBALL_SINGLES_BO3_11,
            PICKLEBALL_DOUBLES_RALLY_21,
            PICKLEBALL_RALLY_BO3_15,
            PICKLEBALL_RALLY_7_WEATHER,
            PICKLEBALL_TEAM_WIN_BY_1,
            PICKLEBALL_OPEN,
            BADMINTON_SINGLES_BO3_15,
            BADMINTON_DOUBLES_BO3_15,
            BADMINTON_SINGLES_BO3_21,
            BADMINTON_DOUBLES_BO3_21,
            BADMINTON_1GAME_21,
            BADMINTON_1GAME_15,
            BADMINTON_BO5_11,
            BADMINTON_OPEN,
            TABLE_TENNIS_SINGLES_BO5,
            TABLE_TENNIS_SINGLES_BO7,
            TABLE_TENNIS_SINGLES_BO3,
            TABLE_TENNIS_DOUBLES_BO5,
            TABLE_TENNIS_DOUBLES_BO3,
            TABLE_TENNIS_LEISURE_21,
            TABLE_TENNIS_OPEN,
        )

    /** Preset zur ID oder `null`. */
    fun byId(id: String): Preset? = all.firstOrNull { it.id == id }

    /** Wizard-Default je Sportart (02 §5.1); Tischtennis hat keinen (U14), Badminton nach Datum ([badmintonDefaultPointsToWin]). */
    fun wizardDefault(sport: Sport): Preset? =
        when (sport) {
            Sport.TENNIS -> TENNIS_SINGLES_BO3
            Sport.PADEL -> PADEL_PREMIER_2026
            Sport.PICKLEBALL -> PICKLEBALL_DOUBLES_11_SIDEOUT
            Sport.BADMINTON, Sport.TABLE_TENNIS -> null
        }
}
