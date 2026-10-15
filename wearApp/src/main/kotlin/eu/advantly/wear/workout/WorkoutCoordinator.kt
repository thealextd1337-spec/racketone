package eu.advantly.wear.workout

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Sport

/** Eingaben für die Entscheidung, ob das Workout zum Match laufen soll. */
data class WorkoutInputs(
    /** „Workout mitlaufen lassen“ (Uhr-Einstellung, Standard an). */
    val enabled: Boolean,
    /** Offenes Match auf dieser Uhr (laufend oder beendet, aber noch nicht geschlossen), sonst `null`. */
    val matchId: MatchId?,
    val sport: Sport?,
    val phase: WorkoutPhase,
    /** Match, für das das Workout schon gestartet, abgelehnt oder bewusst beendet wurde. */
    val handledMatchId: MatchId?,
    /** Match, für das gerade die Rechte-Abfrage läuft (Entscheidung steht noch aus). */
    val pendingMatchId: MatchId? = null,
)

/** Was mit dem Workout passieren soll. */
sealed interface WorkoutAction {
    data class Start(
        val matchId: MatchId,
        val sport: WorkoutSport,
    ) : WorkoutAction

    data object Stop : WorkoutAction

    data object None : WorkoutAction
}

/**
 * Kopplung Workout ↔ Match (05 E2, 04 §3.1 „Workout-Option“): Das Workout läuft auf der **eigenen** Uhr, solange dort
 * ein Match offen ist – unabhängig davon, ob die Uhr zählt oder nur anzeigt. Es startet einmal je Match (ein Folge-Match
 * übernimmt das laufende Workout), endet, wenn das Match geschlossen oder gelöscht wird, und wird in P1 verworfen
 * (keine Speicherung, Health erst P3).
 */
object WorkoutCoordinator {
    fun decide(inputs: WorkoutInputs): WorkoutAction {
        val matchId = inputs.matchId
        if (matchId == null) {
            return if (inputs.phase == WorkoutPhase.IDLE || inputs.phase == WorkoutPhase.ENDING) WorkoutAction.None else WorkoutAction.Stop
        }
        val sport = inputs.sport ?: return WorkoutAction.None
        if (inputs.pendingMatchId == matchId) return WorkoutAction.None // Rechte-Dialog läuft noch
        return if (inputs.enabled && inputs.phase == WorkoutPhase.IDLE && inputs.handledMatchId != matchId) {
            WorkoutAction.Start(matchId, WorkoutSport.of(sport))
        } else {
            WorkoutAction.None
        }
    }

    /**
     * Recovery nach Prozess-/Dienst-Neustart mit aktivem Workout-Marker: Wurde das Beenden schon verlangt oder ist nach
     * dem Neustart kein Match mehr offen (inzwischen geschlossen/gelöscht), wird die eigene Exercise beendet statt
     * wieder angebunden bzw. neu gestartet.
     */
    fun recovery(
        endRequested: Boolean,
        matchOpen: Boolean,
    ): RecoveryIntent = if (endRequested || !matchOpen) RecoveryIntent.END else RecoveryIntent.RESUME
}

/** Was die Recovery mit einem als aktiv markierten Workout tut. */
enum class RecoveryIntent { RESUME, END }
