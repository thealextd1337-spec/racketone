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
    fun padelAndPickleballRunAsTennis() {
        assertEquals(WorkoutSport.TENNIS, WorkoutSport.of(Sport.PICKLEBALL))
        assertEquals(WorkoutSport.TENNIS, WorkoutSport.of(Sport.PADEL))
        assertEquals(WorkoutSport.TABLE_TENNIS, WorkoutSport.of(Sport.TABLE_TENNIS))
    }
}
