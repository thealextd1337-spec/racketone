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
) {
    TENNIS_BO3(Sport.TENNIS),
    TENNIS_BO3_MTB(Sport.TENNIS),
    TENNIS_ONE_SET(Sport.TENNIS),
    TENNIS_SHORT_SETS(Sport.TENNIS),
    TENNIS_OPEN(Sport.TENNIS),
    PADEL_GOLDEN(Sport.PADEL),
    PADEL_STAR(Sport.PADEL),
    PADEL_GOLDEN_STB(Sport.PADEL),
    PADEL_OPEN(Sport.PADEL),
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

/** Spieler-Slots eines Uhr-Matches: Side A = ich (+ Partner), Side B = Gegner (04 §3.1: „Gegner werden als Gegner angelegt“). */
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

    /**
     * Format-Vorschläge, Standard zuerst. Badminton: 3×21 bis 03.01.2027, danach 3×15 (U11, nach lokalem Datum);
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
                )
            }

            Sport.PADEL -> {
                listOf(FormatChoice.PADEL_GOLDEN, FormatChoice.PADEL_STAR, FormatChoice.PADEL_GOLDEN_STB, FormatChoice.PADEL_OPEN)
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

    /** Baut das Format über den Handy-Assistenten (inkl. erkanntem Preset). */
    fun format(
        choice: FormatChoice,
        discipline: Discipline,
        today: LocalDay,
    ): MatchFormat {
        require(discipline in disciplines(choice.sport)) { "${choice.sport} ohne $discipline" }
        val input = input(choice, discipline, today)
        val format = checkNotNull(MatchWizard.buildFormat(input)) { "Format unvollständig: $choice" }
        return format.copy(presetId = MatchWizard.recognizePreset(format))
    }

    /** Metadaten: Badminton in der Halle (Default des Assistenten), sonst ohne Angabe. */
    fun meta(sport: Sport): MatchMeta = if (sport == Sport.BADMINTON) MatchMeta(environment = Environment.INDOOR) else MatchMeta()

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
    ): FormatChoice? {
        val plain = format.copy(presetId = null)
        return FormatChoice.entries.firstOrNull { choice ->
            choice.sport == format.sport &&
                format.discipline in disciplines(choice.sport) &&
                format(choice, format.discipline, today).copy(presetId = null) == plain
        }
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
        }
    }
}
