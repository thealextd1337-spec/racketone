package eu.advantly.presentation.wizard

import eu.advantly.core.model.Sport
import eu.advantly.presentation.match.LocalDay
import eu.advantly.presentation.match.ScorerOption
import eu.advantly.presentation.match.StartOptions
import eu.advantly.presentation.match.WatchStatus
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
    private val watch = MutableStateFlow(WatchStatus.UNKNOWN)
    private var refreshes = 0

    private fun wizardAtScorerStep(): MatchWizard {
        val w =
            MatchWizard(store, today = { LocalDay(2026, 10, 15) }, random = Random(1), watchStatus = watch, refreshWatch = { refreshes++ })
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
        watch.value = WatchStatus.AVAILABLE
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

    /** Feldtest W1-I: Stand wechselt im Assistenten von „nicht verfügbar“ auf „verfügbar“, ohne ihn zu verlassen. */
    @Test
    fun availabilityTurnsOnWithoutLeavingTheWizard() {
        watch.value = WatchStatus.APP_NOT_INSTALLED
        val w = wizardAtScorerStep()
        assertTrue(refreshes >= 1, "Betreten von „Wer zählt?“ fragt neu ab")
        assertEquals(WatchStatus.APP_NOT_INSTALLED, w.state.value.input.watchStatus)
        assertEquals(ScorerOption.THIS_PHONE, w.state.value.startOptions.scorer)
        // Uhr-App installiert/geöffnet → „Erneut prüfen“ (bzw. Flow) bringt „Meine Uhr“ als Standard
        watch.value = WatchStatus.AVAILABLE
        val before = refreshes
        w.refreshWatchStatus()
        assertEquals(before + 1, refreshes)
        assertEquals(WatchStatus.AVAILABLE, w.state.value.input.watchStatus)
        assertEquals(ScorerOption.MY_WATCH, w.state.value.startOptions.scorer)
        assertEquals(WizardStep.SCORER, w.state.value.step)
    }

    @Test
    fun watchDisconnectFallsBackToDirector() {
        watch.value = WatchStatus.AVAILABLE
        val w = wizardAtScorerStep()
        assertEquals(ScorerOption.MY_WATCH, w.state.value.startOptions.scorer)
        w.setWatchAvailable(false)
        assertEquals(ScorerOption.THIS_PHONE, w.state.value.startOptions.scorer)
        assertFalse(w.state.value.input.watchAvailable)
    }

    /** F22: Zählt die Uhr schon ein anderes Match, ist „Meine Uhr“ nicht mehr Standard, aber bewusst wählbar (Wechsel). */
    @Test
    fun busyWatchIsNotTheDefaultButCanBeChosen() {
        watch.value = WatchStatus.AVAILABLE
        var busy = true
        val w =
            MatchWizard(
                store,
                today = { LocalDay(2026, 10, 15) },
                random = Random(1),
                watchStatus = watch,
                watchBusy = { busy },
            )
        w.selectSport(Sport.TENNIS)
        w.addGuest("Gast")
        while (w.state.value.step != WizardStep.SCORER) assertTrue(w.next())
        assertTrue(w.state.value.input.watchBusy)
        assertEquals(ScorerOption.THIS_PHONE, w.state.value.startOptions.scorer)
        w.setScorer(ScorerOption.MY_WATCH)
        assertEquals(ScorerOption.MY_WATCH, w.state.value.startOptions.scorer, "Uhr-Match wechseln")
        busy = false
        w.setScorer(ScorerOption.THIS_PHONE)
        w.refreshWatchStatus()
        assertFalse(w.state.value.input.watchBusy)
    }
}
