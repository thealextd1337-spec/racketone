package eu.advantly.presentation.wizard

import eu.advantly.core.model.Sport
import eu.advantly.presentation.match.LocalDay
import eu.advantly.presentation.match.ScorerOption
import eu.advantly.presentation.match.StartOptions
import eu.advantly.presentation.match.testStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Schritt „Wer zählt?“ (04 §3.1): Standard Meine Uhr nur mit verbundener Uhr, sonst Director; Workout Standard an. */
class ScorerStepTest {
    private val store = testStore()
    private val watch = MutableStateFlow(false)

    private fun wizardAtScorerStep(): MatchWizard {
        val w = MatchWizard(store, today = { LocalDay(2026, 10, 15) }, random = Random(1), watchAvailable = watch)
        w.selectSport(Sport.PICKLEBALL)
        repeat(3) { w.addGuest("Gast $it") }
        assertTrue(w.next())
        assertEquals(WizardStep.FORMAT, w.state.value.step)
        assertTrue(w.next())
        assertEquals(WizardStep.SCORER, w.state.value.step)
        return w
    }

    @Test
    fun scorerIsTheLastStepForEverySport() {
        Sport.entries.forEach { sport -> assertEquals(WizardStep.SCORER, MatchWizard.stepsFor(sport).last(), sport.name) }
    }

    @Test
    fun withoutWatchOnlyThisPhoneCanScore() {
        val w = wizardAtScorerStep()
        assertEquals(StartOptions(ScorerOption.THIS_PHONE, watchWorkout = true), w.state.value.startOptions)
        w.setScorer(ScorerOption.MY_WATCH)
        assertEquals(ScorerOption.THIS_PHONE, w.state.value.input.effectiveScorer, "ohne Uhr nicht wählbar")
        assertTrue(w.state.value.canStart)
        assertNotNull(w.finish())
    }

    @Test
    fun connectedWatchIsTheDefaultAndChoiceSurvives() {
        watch.value = true
        val w = wizardAtScorerStep()
        assertEquals(ScorerOption.MY_WATCH, w.state.value.startOptions.scorer)
        w.setScorer(ScorerOption.THIS_PHONE)
        w.setWatchWorkout(false)
        assertEquals(StartOptions(ScorerOption.THIS_PHONE, watchWorkout = false), w.state.value.startOptions)
        // Uhr trennt sich und kommt wieder: die explizite Wahl bleibt
        w.setWatchAvailable(false)
        w.setWatchAvailable(true)
        assertEquals(ScorerOption.THIS_PHONE, w.state.value.startOptions.scorer)
    }

    @Test
    fun watchDisconnectFallsBackToDirector() {
        watch.value = true
        val w = wizardAtScorerStep()
        assertEquals(ScorerOption.MY_WATCH, w.state.value.startOptions.scorer)
        w.setWatchAvailable(false)
        assertEquals(ScorerOption.THIS_PHONE, w.state.value.startOptions.scorer)
        assertFalse(w.state.value.input.watchAvailable)
    }
}
