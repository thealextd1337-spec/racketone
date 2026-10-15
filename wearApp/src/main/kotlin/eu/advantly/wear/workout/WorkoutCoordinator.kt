package eu.advantly.wear.workout

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
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
    /**
     * Persistenter Workout-Marker (`SessionStore.sessionActive`) – Quelle der Wahrheit nach Prozessverlust, wenn die
     * UI-Phase wieder `IDLE` ist, die Exercise in Health Services aber womöglich weiterläuft.
     */
    val markerActive: Boolean = false,
)

/** Was mit dem Workout passieren soll. */
sealed interface WorkoutAction {
    data class Start(
        val matchId: MatchId,
        val sport: WorkoutSport,
    ) : WorkoutAction

    data object Stop : WorkoutAction

    /** Marker aktiv, Phase `IDLE` (Prozess verloren): erst über die Recovery abgleichen, nie daneben neu starten. */
    data object Recover : WorkoutAction

    data object None : WorkoutAction
}

/**
 * Kopplung Workout ↔ Match (05 E2, 04 §3.1 „Workout-Option“): Das Workout läuft auf der **eigenen** Uhr, solange dort
 * ein Match offen ist – unabhängig davon, ob die Uhr zählt oder nur anzeigt. Es startet einmal je Match (ein Folge-Match
 * übernimmt das laufende Workout), endet, wenn das Match geschlossen oder gelöscht wird, und wird in P1 verworfen
 * (keine Speicherung, Health erst P3).
 */
object WorkoutCoordinator {
    /**
     * Soll für das offene Match ein Workout laufen? Handy-Matches tragen die Wahl des Handy-Assistenten, Uhr-Matches die
     * Uhr-Einstellung – beides in `MatchMeta.watchWorkout` (Default `true`). Ohne Match gilt die Uhr-Einstellung.
     */
    fun enabled(
        meta: MatchMeta?,
        watchSetting: Boolean,
    ): Boolean = meta?.watchWorkout ?: watchSetting

    /**
     * - Kein offenes Match oder Workout für das offene Match **nicht** gewünscht (z. B. Folge von Uhr-Match A zu
     *   Handy-Match B mit `watchWorkout = false`): laufende Exercise beenden.
     * - Marker aktiv bei Phase `IDLE`: Recovery (gleicht mit Match/Beendigungsabsicht ab), kein zweiter Start.
     * - Sonst einmal je Match starten; ein laufendes Workout übernimmt das Folge-Match.
     */
    fun decide(inputs: WorkoutInputs): WorkoutAction {
        if (inputs.phase == WorkoutPhase.ENDING) return WorkoutAction.None
        val markerOnly = inputs.markerActive && inputs.phase == WorkoutPhase.IDLE
        val running = markerOnly || inputs.phase != WorkoutPhase.IDLE
        val matchId = inputs.matchId
        if (matchId == null || !inputs.enabled) return if (running) WorkoutAction.Stop else WorkoutAction.None
        if (markerOnly) return WorkoutAction.Recover
        val sport = inputs.sport ?: return WorkoutAction.None
        if (inputs.pendingMatchId == matchId) return WorkoutAction.None // Rechte-Dialog läuft noch
        return if (inputs.enabled && inputs.phase == WorkoutPhase.IDLE && inputs.handledMatchId != matchId) {
            WorkoutAction.Start(matchId, WorkoutSport.of(sport))
        } else {
            WorkoutAction.None
        }
    }

    /**
     * Recovery nach Prozess-/Dienst-Neustart mit aktivem Workout-Marker: Wurde das Beenden schon verlangt, ist nach dem
     * Neustart kein Match mehr offen (inzwischen geschlossen/gelöscht) oder will das jetzt offene Match kein Workout,
     * wird die eigene Exercise beendet statt wieder angebunden bzw. neu gestartet.
     */
    fun recovery(
        endRequested: Boolean,
        matchOpen: Boolean,
        workoutWanted: Boolean = true,
    ): RecoveryIntent = if (endRequested || !matchOpen || !workoutWanted) RecoveryIntent.END else RecoveryIntent.RESUME

    /**
     * Stopp anfordern: Die Beendigungsabsicht wird **vor** dem Service-Aufruf persistiert, damit ein Prozessabbruch
     * dazwischen (oder ein im Hintergrund abgelehnter Service-Start) die Exercise nicht wieder anbinden lässt.
     */
    fun requestStop(
        marker: WorkoutMarker,
        deliver: () -> Unit,
    ) {
        if (marker.sessionActive) marker.endRequested = true
        deliver()
    }
}

/** Persistenter Workout-Zustand (Android: [SessionStore]). */
interface WorkoutMarker {
    val sessionActive: Boolean
    var endRequested: Boolean
}

/** Was die Recovery mit einem als aktiv markierten Workout tut. */
enum class RecoveryIntent { RESUME, END }
