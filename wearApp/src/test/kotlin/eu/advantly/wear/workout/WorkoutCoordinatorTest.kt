package eu.advantly.wear.workout

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Sport
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutCoordinatorTest {
    private val m1 = MatchId("m1")
    private val m2 = MatchId("m2")

    private fun decide(
        enabled: Boolean = true,
        match: MatchId? = m1,
        sport: Sport? = Sport.PADEL,
        phase: WorkoutPhase = WorkoutPhase.IDLE,
        handled: MatchId? = null,
    ) = WorkoutCoordinator.decide(WorkoutInputs(enabled, match, sport, phase, handled))

    @Test
    fun startsOncePerOpenMatchIndependentOfRole() {
        assertEquals(WorkoutAction.Start(m1, WorkoutSport.TENNIS), decide())
        assertEquals(WorkoutAction.None, decide(handled = m1))
        assertEquals(WorkoutAction.Start(m2, WorkoutSport.BADMINTON), decide(match = m2, sport = Sport.BADMINTON, handled = m1))
    }

    @Test
    fun respectsTheSettingAndARunningWorkout() {
        assertEquals(WorkoutAction.None, decide(enabled = false))
        // Folge-Match übernimmt das laufende Workout
        assertEquals(WorkoutAction.None, decide(match = m2, phase = WorkoutPhase.ACTIVE, handled = m1))
    }

    @Test
    fun stopsWhenNoMatchIsOpenAnymore() {
        for (phase in listOf(WorkoutPhase.STARTING, WorkoutPhase.ACTIVE, WorkoutPhase.FAILED, WorkoutPhase.CONFLICT)) {
            assertEquals("$phase", WorkoutAction.Stop, decide(match = null, sport = null, phase = phase))
        }
        assertEquals(WorkoutAction.None, decide(match = null, sport = null, phase = WorkoutPhase.IDLE))
        assertEquals(WorkoutAction.None, decide(match = null, sport = null, phase = WorkoutPhase.ENDING))
    }

    @Test
    fun pendingPermissionDecisionBlocksARestartButNotOtherMatches() {
        fun pending(match: MatchId) = WorkoutCoordinator.decide(WorkoutInputs(true, match, Sport.TENNIS, WorkoutPhase.IDLE, null, m1))
        assertEquals(WorkoutAction.None, pending(m1))
        assertEquals(WorkoutAction.Start(m2, WorkoutSport.TENNIS), pending(m2))
    }

    @Test
    fun recoveryEndsTheExerciseWhenEndWasRequestedOrNoMatchIsOpen() {
        assertEquals(RecoveryIntent.RESUME, WorkoutCoordinator.recovery(endRequested = false, matchOpen = true))
        assertEquals(RecoveryIntent.END, WorkoutCoordinator.recovery(endRequested = true, matchOpen = true))
        assertEquals(RecoveryIntent.END, WorkoutCoordinator.recovery(endRequested = false, matchOpen = false))
    }

    @Test
    fun followingFromAWorkoutMatchToAMatchWithoutWorkoutStopsIt() {
        // A (watchWorkout = true) läuft, Uhr folgt Handy-Match B mit watchWorkout = false
        assertEquals(WorkoutAction.Stop, decide(enabled = false, match = m2, phase = WorkoutPhase.ACTIVE, handled = m1))
        assertEquals(WorkoutAction.Stop, decide(enabled = false, match = m2, phase = WorkoutPhase.STARTING, handled = m1))
        // nichts läuft → nichts zu tun, auch kein Start
        assertEquals(WorkoutAction.None, decide(enabled = false, match = m2, phase = WorkoutPhase.IDLE, handled = m1))
    }

    @Test
    fun persistentMarkerIsTheSourceOfTruthAfterProcessLoss() {
        fun marker(
            match: MatchId?,
            enabled: Boolean = true,
            phase: WorkoutPhase = WorkoutPhase.IDLE,
        ) = WorkoutCoordinator.decide(
            WorkoutInputs(enabled, match, match?.let { Sport.TENNIS }, phase, handledMatchId = null, markerActive = true),
        )
        // Match im Hintergrund gelöscht, UI-Phase nach Neustart IDLE → trotzdem beenden
        assertEquals(WorkoutAction.Stop, marker(match = null))
        // neues Match vor der Recovery: erst abgleichen, keinen zweiten Start daneben
        assertEquals(WorkoutAction.Recover, marker(match = m2))
        assertEquals(WorkoutAction.Stop, marker(match = m2, enabled = false))
        assertEquals(WorkoutAction.None, marker(match = null, phase = WorkoutPhase.ENDING))
    }

    @Test
    fun stopIntentIsPersistedBeforeTheServiceIsCalled() {
        val marker =
            object : WorkoutMarker {
                override var sessionActive = true
                override var endRequested = false
            }
        var persistedAtDelivery: Boolean? = null
        WorkoutCoordinator.requestStop(marker) { persistedAtDelivery = marker.endRequested }
        assertEquals(true, persistedAtDelivery)
        // ohne aktives Workout keine Absicht hinterlassen
        val idle =
            object : WorkoutMarker {
                override var sessionActive = false
                override var endRequested = false
            }
        var delivered = false
        WorkoutCoordinator.requestStop(idle) { delivered = true }
        assertEquals(true, delivered)
        assertEquals(false, idle.endRequested)
    }

    @Test
    fun recoveryEndsWhenTheOpenMatchWantsNoWorkout() {
        assertEquals(RecoveryIntent.END, WorkoutCoordinator.recovery(endRequested = false, matchOpen = true, workoutWanted = false))
    }

    @Test
    fun padelAndPickleballRunAsTennis() {
        assertEquals(WorkoutSport.TENNIS, WorkoutSport.of(Sport.PICKLEBALL))
        assertEquals(WorkoutSport.TENNIS, WorkoutSport.of(Sport.PADEL))
        assertEquals(WorkoutSport.TABLE_TENNIS, WorkoutSport.of(Sport.TABLE_TENNIS))
    }
}
