package eu.advantly.wear.match

import eu.advantly.core.model.DeuceMode
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Environment
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.MidGameEndChange
import eu.advantly.core.model.PickleballScoring
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Preset
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.match.LocalDay
import eu.advantly.presentation.wizard.DecidingSetChoice
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.presentation.wizard.SetsChoice
import eu.advantly.presentation.wizard.WizardInput

/**
 * Format-Vorschläge des Uhr-Kurz-Wizards (04 §3.1 „Watch-Kurz-Wizard“: 3–5 Vorschläge je Sport). Jede Wahl ist eine
 * Änderung an den Eingaben des Handy-Assistenten ([WizardInput]); das fertige `MatchFormat` baut [MatchWizard.buildFormat]
 * – Uhr und Handy erzeugen damit für dieselbe Wahl dasselbe Format (02 §5.1/§5.1.1).
 */
enum class FormatChoice(
    val sport: Sport,
    /** „Nur Tiebreak“ (W2, 02 §5.1 K16): ein einziger Tiebreak, keine Games pro Satz. */
    val tiebreakOnly: Boolean = false,
) {
    TENNIS_BO3(Sport.TENNIS),
    TENNIS_BO3_MTB(Sport.TENNIS),
    TENNIS_ONE_SET(Sport.TENNIS),
    TENNIS_SHORT_SETS(Sport.TENNIS),
    TENNIS_OPEN(Sport.TENNIS),
    TENNIS_TIEBREAK_7(Sport.TENNIS, tiebreakOnly = true),
    TENNIS_TIEBREAK_10(Sport.TENNIS, tiebreakOnly = true),
    PADEL_GOLDEN(Sport.PADEL),
    PADEL_STAR(Sport.PADEL),
    PADEL_GOLDEN_STB(Sport.PADEL),
    PADEL_OPEN(Sport.PADEL),
    PADEL_TIEBREAK_7(Sport.PADEL, tiebreakOnly = true),
    PADEL_TIEBREAK_10(Sport.PADEL, tiebreakOnly = true),
    PICKLEBALL_11(Sport.PICKLEBALL),
    PICKLEBALL_BO3_11(Sport.PICKLEBALL),
    PICKLEBALL_15(Sport.PICKLEBALL),
    PICKLEBALL_RALLY_21(Sport.PICKLEBALL),
    PICKLEBALL_OPEN(Sport.PICKLEBALL),
    BADMINTON_BO3_21(Sport.BADMINTON),
    BADMINTON_BO3_15(Sport.BADMINTON),
    BADMINTON_ONE_21(Sport.BADMINTON),
    BADMINTON_ONE_15(Sport.BADMINTON),
    BADMINTON_OPEN(Sport.BADMINTON),
    TABLE_TENNIS_BO3(Sport.TABLE_TENNIS),
    TABLE_TENNIS_BO5(Sport.TABLE_TENNIS),
    TABLE_TENNIS_BO7(Sport.TABLE_TENNIS),
    TABLE_TENNIS_OPEN(Sport.TABLE_TENNIS),
}

/** Fertig gewähltes Match: Genesis-Inhalt für `MatchSession.create`. */
data class WatchSetup(
    val format: MatchFormat,
    val lineup: Lineup,
    val meta: MatchMeta,
)

/** Erkannter Uhr-Vorschlag eines Formats; [gamesPerSet] nur, wenn vom Standard des Vorschlags abweichend. */
data class RecognizedFormat(
    val choice: FormatChoice,
    val gamesPerSet: Int?,
)

/** Plätze eines Uhr-Matches (F20): Side A = ich (+ Partner), Side B = Gegner; Person oder neutraler Platzhalter. */
data class LineupPlayers(
    val self: PlayerId,
    val partner: PlayerId,
    val opponent1: PlayerId,
    val opponent2: PlayerId,
)

/** Reine Regeln des Uhr-Wizards (ohne Android, JVM-getestet). */
object SetupCatalog {
    /** Reihenfolge der Sportarten in der Auswahl. */
    val sports: List<Sport> = listOf(Sport.TENNIS, Sport.PADEL, Sport.PICKLEBALL, Sport.BADMINTON, Sport.TABLE_TENNIS)

    /** Wählbare Disziplinen; Padel ist immer Doppel (02 §4.2). */
    fun disciplines(sport: Sport): List<Discipline> =
        if (sport == Sport.PADEL) listOf(Discipline.DOUBLES) else listOf(Discipline.SINGLES, Discipline.DOUBLES)

    /** Vorauswahl Einzel/Doppel (Pickleball und Padel: Doppel, wie der Handy-Assistent). */
    fun defaultDiscipline(sport: Sport): Discipline =
        if (sport == Sport.PADEL || sport == Sport.PICKLEBALL) Discipline.DOUBLES else Discipline.SINGLES

    /** „Games pro Satz“ im Uhr-Assistenten (W2-Vertrag §5: 1–6; 8/10 nur am Handy). */
    val GAMES_PER_SET: List<Int> = (1..6).toList()

    /**
     * Format-Vorschläge, Standard zuerst; Tennis/Padel zusätzlich „Nur Tiebreak“ bis 7 bzw. 10. Badminton: 3×21 bis 03.01.2027, danach 3×15 (U11, nach lokalem Datum);
     * beide Systeme bleiben wählbar.
     */
    fun formats(
        sport: Sport,
        today: LocalDay,
    ): List<FormatChoice> =
        when (sport) {
            Sport.TENNIS -> {
                listOf(
                    FormatChoice.TENNIS_BO3,
                    FormatChoice.TENNIS_BO3_MTB,
                    FormatChoice.TENNIS_ONE_SET,
                    FormatChoice.TENNIS_SHORT_SETS,
                    FormatChoice.TENNIS_OPEN,
                    FormatChoice.TENNIS_TIEBREAK_7,
                    FormatChoice.TENNIS_TIEBREAK_10,
                )
            }

            Sport.PADEL -> {
                listOf(
                    FormatChoice.PADEL_GOLDEN,
                    FormatChoice.PADEL_STAR,
                    FormatChoice.PADEL_GOLDEN_STB,
                    FormatChoice.PADEL_OPEN,
                    FormatChoice.PADEL_TIEBREAK_7,
                    FormatChoice.PADEL_TIEBREAK_10,
                )
            }

            Sport.PICKLEBALL -> {
                listOf(
                    FormatChoice.PICKLEBALL_11,
                    FormatChoice.PICKLEBALL_BO3_11,
                    FormatChoice.PICKLEBALL_15,
                    FormatChoice.PICKLEBALL_RALLY_21,
                    FormatChoice.PICKLEBALL_OPEN,
                )
            }

            Sport.BADMINTON -> {
                if (badmintonPoints(today) == 21) {
                    listOf(
                        FormatChoice.BADMINTON_BO3_21,
                        FormatChoice.BADMINTON_BO3_15,
                        FormatChoice.BADMINTON_ONE_21,
                        FormatChoice.BADMINTON_OPEN,
                    )
                } else {
                    listOf(
                        FormatChoice.BADMINTON_BO3_15,
                        FormatChoice.BADMINTON_BO3_21,
                        FormatChoice.BADMINTON_ONE_15,
                        FormatChoice.BADMINTON_OPEN,
                    )
                }
            }

            Sport.TABLE_TENNIS -> {
                listOf(
                    FormatChoice.TABLE_TENNIS_BO3,
                    FormatChoice.TABLE_TENNIS_BO5,
                    FormatChoice.TABLE_TENNIS_BO7,
                    FormatChoice.TABLE_TENNIS_OPEN,
                )
            }
        }

    /** Vorausgewählter Vorschlag; Tischtennis hat **keinen** (Pflichtauswahl der Satzanzahl, U14). */
    fun defaultFormat(
        sport: Sport,
        today: LocalDay,
    ): FormatChoice? = if (sport == Sport.TABLE_TENNIS) null else formats(sport, today).first()

    /** Punkte pro Satz im Badminton-Default nach Datum (02 §4.4, U11). */
    fun badmintonPoints(today: LocalDay): Int = Presets.badmintonDefaultPointsToWin(today.year, today.month, today.day)

    /** Wählbare Games pro Satz zu [choice]: Tennis/Padel mit Sätzen 1–6, sonst (auch „Nur Tiebreak“) keine. */
    fun gamesOptions(choice: FormatChoice): List<Int> =
        if (choice.sport.isTennisLike && !choice.tiebreakOnly) GAMES_PER_SET else emptyList()

    /** Games pro Satz, die [choice] ohne Auswahl hat (z. B. 6, „Kurze Sätze“ 4), sonst `null`. */
    fun defaultGames(choice: FormatChoice): Int? =
        if (gamesOptions(choice).isEmpty()) {
            null
        } else {
            format(choice, disciplines(choice.sport).first(), LocalDay(2026, 1, 1)).set?.gamesToWin
        }

    /** Aufschlag-Statistik ist nur bei Tennis/Padel erfassbar (02 §7.2, K19). */
    fun serveStatsAvailable(sport: Sport): Boolean = sport.isTennisLike

    /**
     * Baut das Format über den Handy-Assistenten (inkl. erkanntem Preset); „Nur Tiebreak“ direkt aus den Presets
     * (`*_TIEBREAK_ONLY_7/10`, K16). [gamesPerSet] (Tennis/Padel 1–6) über `Presets.withGamesPerSet` – Tiebreak bei n:n
     * (K17); `null` bzw. der Standard des Vorschlags lässt das Format unverändert.
     */
    fun format(
        choice: FormatChoice,
        discipline: Discipline,
        today: LocalDay,
        gamesPerSet: Int? = null,
    ): MatchFormat {
        require(discipline in disciplines(choice.sport)) { "${choice.sport} ohne $discipline" }
        tiebreakOnlyPreset(choice)?.let { preset ->
            require(gamesPerSet == null) { "$choice hat keine Games pro Satz" }
            return preset.format(discipline)
        }
        val input = input(choice, discipline, today)
        val base = checkNotNull(MatchWizard.buildFormat(input)) { "Format unvollständig: $choice" }
        val format =
            if (gamesPerSet == null || gamesPerSet == base.set?.gamesToWin) {
                base
            } else {
                require(gamesPerSet in gamesOptions(choice)) { "$choice: $gamesPerSet Games pro Satz nicht wählbar" }
                Presets.withGamesPerSet(base, gamesPerSet)
            }
        return format.copy(presetId = MatchWizard.recognizePreset(format))
    }

    /**
     * Metadaten: Badminton in der Halle (Default des Assistenten), sonst ohne Angabe; Tennis/Padel mit Option
     * „Aufschlag-Statistik erfassen“ ([serveStats], Default an – `MatchMeta.serveStats`).
     */
    fun meta(
        sport: Sport,
        serveStats: Boolean = true,
    ): MatchMeta {
        val base = if (sport == Sport.BADMINTON) MatchMeta(environment = Environment.INDOOR) else MatchMeta()
        return if (serveStatsAvailable(sport)) base.copy(serveStats = serveStats) else base
    }

    /**
     * Besetzung: [server]/[serverIndex] = Spieler, der das Match eröffnet (im Doppel konkret gewählt: Du/Partner bzw.
     * Gegner 1/2). Steht er nicht an Index 0 seines Teams, dreht die Engine die Teamreihenfolge (02 §3.3) – bei
     * Badminton steht der Aufschläger damit zu Satzbeginn rechts.
     */
    fun lineup(
        discipline: Discipline,
        players: LineupPlayers,
        server: Side,
        serverIndex: Int = 0,
    ): Lineup {
        val a = if (discipline == Discipline.DOUBLES) listOf(players.self, players.partner) else listOf(players.self)
        val b = if (discipline == Discipline.DOUBLES) listOf(players.opponent1, players.opponent2) else listOf(players.opponent1)
        val team = if (server == Side.A) a else b
        require(serverIndex in team.indices) { "Aufschläger $serverIndex gibt es in $discipline nicht" }
        return Lineup(a = a, b = b, firstServer = team[serverIndex])
    }

    /** Vorschlag, der genau [format] erzeugt (für „Wie letztes Mal“-Beschriftung), sonst `null`. */
    fun choiceFor(
        format: MatchFormat,
        today: LocalDay,
    ): FormatChoice? = recognize(format, today)?.choice

    /**
     * Vorschlag plus Games pro Satz, die genau [format] erzeugen, sonst `null`. Vorschläge ohne geänderte Games haben
     * Vorrang (z. B. „Kurze Sätze“ statt „Match-Tiebreak mit 4 Games“).
     */
    fun recognize(
        format: MatchFormat,
        today: LocalDay,
    ): RecognizedFormat? {
        val plain = format.copy(presetId = null)
        val candidates =
            FormatChoice.entries.filter { it.sport == format.sport && format.discipline in disciplines(it.sport) }

        fun matches(
            choice: FormatChoice,
            games: Int?,
        ) = format(choice, format.discipline, today, games).copy(presetId = null) == plain
        candidates.firstOrNull { matches(it, null) }?.let { return RecognizedFormat(it, null) }
        for (choice in candidates) {
            val default = defaultGames(choice)
            gamesOptions(choice).firstOrNull { it != default && matches(choice, it) }?.let { return RecognizedFormat(choice, it) }
        }
        return null
    }

    private fun tiebreakOnlyPreset(choice: FormatChoice): Preset? =
        when (choice) {
            FormatChoice.TENNIS_TIEBREAK_7 -> Presets.TENNIS_TIEBREAK_ONLY_7
            FormatChoice.TENNIS_TIEBREAK_10 -> Presets.TENNIS_TIEBREAK_ONLY_10
            FormatChoice.PADEL_TIEBREAK_7 -> Presets.PADEL_TIEBREAK_ONLY_7
            FormatChoice.PADEL_TIEBREAK_10 -> Presets.PADEL_TIEBREAK_ONLY_10
            else -> null
        }

    private fun input(
        choice: FormatChoice,
        discipline: Discipline,
        today: LocalDay,
    ): WizardInput {
        val base = WizardInput(sport = choice.sport, discipline = discipline)
        val bo1 = SetsChoice.BestOf(1)
        val bo3 = SetsChoice.BestOf(3)
        val open = SetsChoice.Open
        val doubles = discipline == Discipline.DOUBLES
        return when (choice) {
            FormatChoice.TENNIS_BO3 -> {
                base.copy(sets = bo3)
            }

            // Tennis-Doppel mit Match-Tiebreak: No-Ad wie im Handy-Assistenten vorgeschlagen (02 §5.1)
            FormatChoice.TENNIS_BO3_MTB -> {
                base.copy(
                    sets = bo3,
                    decidingSet = DecidingSetChoice.MATCH_TIEBREAK,
                    deuceMode = if (doubles) DeuceMode.DECIDING_POINT else DeuceMode.ADVANTAGE,
                )
            }

            FormatChoice.TENNIS_ONE_SET -> {
                base.copy(sets = bo1)
            }

            FormatChoice.TENNIS_SHORT_SETS -> {
                base.copy(sets = bo3, gamesPerSet = 4, decidingSet = DecidingSetChoice.MATCH_TIEBREAK)
            }

            FormatChoice.TENNIS_OPEN -> {
                base.copy(sets = open)
            }

            // Padel-Default Golden Point (U1, wie der Handy-Assistent)
            FormatChoice.PADEL_GOLDEN -> {
                base.copy(sets = bo3, deuceMode = DeuceMode.DECIDING_POINT)
            }

            FormatChoice.PADEL_STAR -> {
                base.copy(sets = bo3, deuceMode = DeuceMode.STAR_POINT)
            }

            FormatChoice.PADEL_GOLDEN_STB -> {
                base.copy(sets = bo3, deuceMode = DeuceMode.DECIDING_POINT, decidingSet = DecidingSetChoice.MATCH_TIEBREAK)
            }

            FormatChoice.PADEL_OPEN -> {
                base.copy(sets = open, deuceMode = DeuceMode.DECIDING_POINT)
            }

            FormatChoice.PICKLEBALL_11 -> {
                base.copy(sets = bo1, pointsToWin = 11)
            }

            FormatChoice.PICKLEBALL_BO3_11 -> {
                base.copy(sets = bo3, pointsToWin = 11)
            }

            FormatChoice.PICKLEBALL_15 -> {
                base.copy(sets = bo1, pointsToWin = 15)
            }

            FormatChoice.PICKLEBALL_RALLY_21 -> {
                base.copy(sets = bo1, pointsToWin = 21, pickleballScoring = PickleballScoring.RALLY)
            }

            // Freies Spiel: Seitenwechsel in jedem Spiel (Freizeit-Default U4)
            FormatChoice.PICKLEBALL_OPEN -> {
                base.copy(sets = open, pointsToWin = 11, pickleballMidGame = MidGameEndChange.EVERY_GAME)
            }

            FormatChoice.BADMINTON_BO3_21 -> {
                base.copy(sets = bo3, pointsToWin = 21)
            }

            FormatChoice.BADMINTON_BO3_15 -> {
                base.copy(sets = bo3, pointsToWin = 15)
            }

            FormatChoice.BADMINTON_ONE_21 -> {
                base.copy(sets = bo1, pointsToWin = 21)
            }

            FormatChoice.BADMINTON_ONE_15 -> {
                base.copy(sets = bo1, pointsToWin = 15)
            }

            FormatChoice.BADMINTON_OPEN -> {
                base.copy(sets = open, pointsToWin = badmintonPoints(today))
            }

            FormatChoice.TABLE_TENNIS_BO3 -> {
                base.copy(sets = bo3, pointsToWin = 11)
            }

            FormatChoice.TABLE_TENNIS_BO5 -> {
                base.copy(sets = SetsChoice.BestOf(5), pointsToWin = 11)
            }

            FormatChoice.TABLE_TENNIS_BO7 -> {
                base.copy(sets = SetsChoice.BestOf(7), pointsToWin = 11)
            }

            FormatChoice.TABLE_TENNIS_OPEN -> {
                base.copy(sets = open, pointsToWin = 11)
            }

            // nicht über den Assistenten – „Nur Tiebreak“ kommt direkt aus den Presets (tiebreakOnlyPreset)
            FormatChoice.TENNIS_TIEBREAK_7,
            FormatChoice.TENNIS_TIEBREAK_10,
            FormatChoice.PADEL_TIEBREAK_7,
            FormatChoice.PADEL_TIEBREAK_10,
            -> {
                base.copy(sets = bo1)
            }
        }
    }
}
