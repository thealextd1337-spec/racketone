package eu.advantly.presentation.wizard

import eu.advantly.core.model.DeuceMode
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Environment
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchSettings
import eu.advantly.core.model.MidGameEndChange
import eu.advantly.core.model.PickleballScoring
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Surface
import eu.advantly.core.scoring.FormatValidation
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.match.ScorerOption
import eu.advantly.presentation.match.StartOptions
import eu.advantly.presentation.match.WatchStatus

/** Schritte des Match-Assistenten (04 §3.1, 02 §5.1). */
enum class WizardStep {
    SPORT,

    /** Tennis: Belag + Halle/Draußen; Badminton: nur Halle/Draußen (02 §5.1.1). */
    SURFACE,
    PLAYERS,
    FORMAT,

    /** „Wer zählt?“ (04 §3.1): Meine Uhr / Dieses Handy (Director) + Workout-Option, danach Start. */
    SCORER,
}

/** Satzanzahl: Best of n oder „Offen“ (Satz für Satz, 02 §5.2). */
sealed interface SetsChoice {
    data class BestOf(
        val n: Int,
    ) : SetsChoice

    data object Open : SetsChoice

    fun toSettings(): MatchSettings =
        when (this) {
            is BestOf -> MatchSettings.bestOf(n)
            Open -> MatchSettings.OPEN
        }
}

/** Ein normaler Satz oder ein einzelner Tiebreak als komplettes Match. */
enum class TennisMatchChoice { REGULAR, TIEBREAK_7, TIEBREAK_10 }

/** Entscheidungssatz bei Best-of (Tennis/Padel, 02 §5.1 „Erweitert“). */
enum class DecidingSetChoice {
    /** Wie die anderen Sätze. */
    NORMAL,

    /** Match-Tiebreak bis 10 statt Entscheidungssatz (Padel: „Super-Tiebreak“). */
    MATCH_TIEBREAK,

    /** Vorteilssatz ohne Tiebreak. */
    ADVANTAGE_SET,

    /** Tiebreak bis 10 bei n:n (nur Tennis). */
    TIEBREAK_TO_10,
}

/** Tiebreak im Satz (Tennis, 02 §5.1 „Tiebreak bei n:n“). */
enum class TiebreakAtChoice {
    /** Bei n:n mit n = Games pro Satz (Standard). */
    AT_GAMES,

    /** Bei (n−1):(n−1), z. B. 3:3 bei Kurzsätzen. */
    AT_GAMES_MINUS_ONE,

    /** Kein Tiebreak (Vorteilssatz). */
    NONE,
}

/**
 * Eingaben des Assistenten. [teamA]/[teamB] sind Slots (Länge = Spieler je Side), `null` = leer; Slot A0 ist der
 * eigene Spieler. Die Reihenfolge ist die Lineup-Reihenfolge (02 §3.3).
 */
data class WizardInput(
    val step: WizardStep = WizardStep.SPORT,
    val sport: Sport? = null,
    val surface: Surface? = null,
    val environment: Environment? = null,
    val discipline: Discipline = Discipline.SINGLES,
    val teamA: List<Player?> = listOf(null),
    val teamB: List<Player?> = listOf(null),
    val firstServer: PlayerId? = null,
    val mixed: Boolean = false,
    val sets: SetsChoice? = null,
    val gamesPerSet: Int = 6,
    val tennisMatch: TennisMatchChoice = TennisMatchChoice.REGULAR,
    val serveStats: Boolean = true,
    val tiebreakAt: TiebreakAtChoice = TiebreakAtChoice.AT_GAMES,
    val deuceMode: DeuceMode = DeuceMode.ADVANTAGE,
    val decidingSet: DecidingSetChoice = DecidingSetChoice.NORMAL,
    val pointsToWin: Int = 11,
    val pickleballScoring: PickleballScoring = PickleballScoring.SIDE_OUT,
    val pickleballWinBy: Int = 2,
    val pickleballMidGame: MidGameEndChange = MidGameEndChange.DECIDING_GAME_ONLY,
    val advancedExpanded: Boolean = false,
    val saveAsFavorite: Boolean = false,
    /** Gewählter Zähler; `null` = Standard (Meine Uhr, wenn eine verbunden ist, sonst dieses Handy). */
    val scorer: ScorerOption? = null,
    /** „Workout auf meiner Uhr mitlaufen lassen“ (Standard an, auch im Director-Modus, 04 §3.1). */
    val watchWorkout: Boolean = true,
    /** Stand der eigenen Uhr; nur `AVAILABLE` macht „Meine Uhr“ wählbar (sonst Hinweis, warum nicht). */
    val watchStatus: WatchStatus = WatchStatus.UNKNOWN,
    /** Nutzer hat den Einstand selbst gewählt (dann kein automatischer No-Ad-Vorschlag, 02 §5.1). */
    internal val deuceTouched: Boolean = false,
    /** Nutzer hat den Pickleball-Seitenwechsel selbst gewählt (sonst Freizeit-Default je Modus, U4). */
    internal val midGameTouched: Boolean = false,
) {
    fun team(side: Side): List<Player?> = if (side == Side.A) teamA else teamB

    /** Die eigene Uhr ist als Zähler wählbar. */
    val watchAvailable: Boolean get() = watchStatus == WatchStatus.AVAILABLE

    /** Tatsächlicher Zähler: ohne verbundene Uhr immer dieses Handy, sonst die Wahl (Standard: Meine Uhr). */
    val effectiveScorer: ScorerOption
        get() = if (!watchAvailable) ScorerOption.THIS_PHONE else scorer ?: ScorerOption.MY_WATCH

    /** Alle belegten Slots. */
    val players: List<Player> get() = (teamA + teamB).filterNotNull()
}

/** Auswahlmöglichkeiten des Format-Schritts je Sportart (die UI rendert nur diese Listen). */
data class FormatOptions(
    val sets: List<SetsChoice>,
    /** Games pro Satz (Tennis/Padel), sonst leer. */
    val gamesPerSet: List<Int>,
    /** Punkte pro Spiel/Satz (Pickleball, Badminton, Tischtennis), sonst leer. */
    val points: List<Int>,
    /** Punkte, die nur unter „Erweitert“ wählbar sind (Badminton 11). */
    val advancedPoints: List<Int>,
    val deuceModes: List<DeuceMode>,
    val decidingSets: List<DecidingSetChoice>,
    val tiebreakAt: List<TiebreakAtChoice>,
    val pickleballScoring: List<PickleballScoring>,
    val pickleballWinBy: List<Int>,
    val pickleballMidGame: List<MidGameEndChange>,
    /** Disziplin wählbar (Padel: immer Doppel). */
    val disciplineSelectable: Boolean,
    /** Mixed-Schalter anzeigen (Doppel bei Tennis, Padel, Badminton; nur UI-Hinweise, 02 §9.11). */
    val mixedSelectable: Boolean,
)

/**
 * Abgeleiteter Zustand für die UI.
 *
 * @property format fertiges Format oder `null`, solange eine Pflichtauswahl fehlt (Tischtennis: Satzanzahl, U14)
 * @property validation Ergebnis von `MatchFormatValidator` + `LineupValidator` ([lineupErrors])
 * @property presetId erkanntes Preset aus 02 §4 (nur Anzeige), sonst `null`
 */
data class WizardUiState(
    val input: WizardInput,
    val steps: List<WizardStep>,
    val options: FormatOptions,
    val format: MatchFormat?,
    val validation: FormatValidation?,
    val lineupErrors: List<String>,
    val presetId: String?,
    val setup: MatchSetup?,
    val selfId: PlayerId,
) {
    val step: WizardStep get() = input.step
    val stepIndex: Int get() = steps.indexOf(input.step)

    /** Spieler-Slots vollständig belegt. */
    val playersComplete: Boolean get() = input.teamA.all { it != null } && input.teamB.all { it != null }

    /** Weiter vom aktuellen Schritt möglich. */
    val canContinue: Boolean
        get() =
            when (input.step) {
                WizardStep.SPORT -> input.sport != null
                WizardStep.SURFACE -> true
                WizardStep.PLAYERS -> playersComplete
                WizardStep.FORMAT, WizardStep.SCORER -> canStart
            }

    /** Match kann gestartet werden: Format und Lineup gültig. */
    val canStart: Boolean get() = setup != null

    /** Zähler und Workout-Wunsch für `LiveMatchPort.start`. */
    val startOptions: StartOptions get() = StartOptions(input.effectiveScorer, input.watchWorkout)
}
