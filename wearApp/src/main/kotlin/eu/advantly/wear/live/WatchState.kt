package eu.advantly.wear.live

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.ResultReason
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.wear.match.FormatChoice

/** Was die Wear-App gerade zeigt. */
sealed interface WatchScreen {
    /** Startliste (04 W1). */
    data class Home(
        val resume: ResumeUi?,
        val last: SetupSummary?,
        val recent: List<ResultUi>,
        val workoutEnabled: Boolean,
        /** Handy live verbunden und kompatibel (Feldtest: Status auf dem Startbildschirm). */
        val phoneConnected: Boolean = false,
    ) : WatchScreen

    /** Kurz-Wizard (04 W2): Sport → Einzel/Doppel → Format → Erstaufschläger (= Start). */
    data class Setup(
        val step: SetupStep,
        val sport: Sport?,
        val disciplines: List<Discipline>,
        val discipline: Discipline?,
        val formats: List<FormatChoice>,
        /** Vorauswahl; `null` bei Tischtennis (Pflichtauswahl, U14). */
        val defaultFormat: FormatChoice?,
        val format: FormatChoice?,
        val workoutEnabled: Boolean,
    ) : WatchScreen

    /** Live-Score bzw. Anzeige (04 W3). */
    data class Live(
        val live: LiveUi,
    ) : WatchScreen
}

enum class SetupStep { SPORT, DISCIPLINE, FORMAT, SERVER }

/** „Wie letztes Mal“-Beschriftung; [choice] `null`, wenn das Format keinem Uhr-Vorschlag entspricht (z. B. vom Handy). */
data class SetupSummary(
    val sport: Sport,
    val discipline: Discipline,
    val choice: FormatChoice?,
    val format: MatchFormat,
)

/** Fortsetzbares Match (Resume-Karte). */
data class ResumeUi(
    val matchId: MatchId,
    val sport: Sport,
    val score: String,
)

/** Zeile „Letzte Ergebnisse“. */
data class ResultUi(
    val matchId: MatchId,
    val sport: Sport,
    val sets: String,
    val winner: Side?,
    val reason: ResultReason?,
)

/** Gesamtzustand für die UI. */
data class WatchUiState(
    val screen: WatchScreen,
) {
    val live: LiveUi? get() = (screen as? WatchScreen.Live)?.live
}

/** Haptik-Muster der Uhr (04 §4.3.4, §5.8). */
enum class WatchHaptic {
    POINT_US,
    POINT_THEM,
    UNDO,
    GAME_WON,
    GAME_LOST,
    SET_WON,
    SET_LOST,
    CHANGE_ENDS,

    /** Gesperrt-Tap / abgelehnte Eingabe. */
    REJECTED,
}

/** Kurzmeldungen (Mittelband, 3 s). */
sealed interface WatchToast {
    /** „Rückgängig: 30:15 → 15:15“. */
    data class Undone(
        val before: String,
        val after: String,
    ) : WatchToast

    data object Redone : WatchToast

    data object NothingToUndo : WatchToast

    /** Tap bei aktiver Touch-Sperre. */
    data object Locked : WatchToast

    data object Unlocked : WatchToast

    /** Aufschläger/Aufstellung korrigiert. */
    data object Corrected : WatchToast

    /** Rolle hat gewechselt: [nowScorer] = dieses Gerät zählt jetzt, sonst zählt [scorerKind]. */
    data class RoleChanged(
        val nowScorer: Boolean,
        val scorerKind: PeerKind?,
    ) : WatchToast

    /** „N Eingaben nicht übernommen“ (02 §7.5 Punkt 4). */
    data class InputsRejected(
        val count: Int,
    ) : WatchToast

    /** Das offene Match wurde auf einem anderen Gerät gelöscht. */
    data object MatchRemoved : WatchToast

    data object StartFailed : WatchToast
}

/** Einmalige Effekte (Haptik, Toast) – die Activity spielt sie ab. */
sealed interface WatchEffect {
    /** [attention] = Break-/Satz-/Matchball steht an: Aufmerksamkeits-Impuls zu Beginn des nächsten Punktes. */
    data class Haptic(
        val pattern: WatchHaptic,
        val attention: Boolean = false,
    ) : WatchEffect

    data class Toast(
        val toast: WatchToast,
    ) : WatchEffect
}

/** Gerätelokale Einstellungen der Uhr. */
interface WatchPrefs {
    /** „Workout mitlaufen lassen“ (04 §3.1, Standard an). */
    var workoutEnabled: Boolean
}

/** Status-Kürzel für Ongoing Activity und TalkBack. */
internal val MatchStatus.isRunning: Boolean get() = this == MatchStatus.IN_PROGRESS || this == MatchStatus.PAUSED
