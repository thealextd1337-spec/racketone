package eu.advantly.wear.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.SidePair
import eu.advantly.wear.R
import eu.advantly.wear.live.DecisionUi
import eu.advantly.wear.live.LiveUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Restzeit-Anzeige in den Aufschlag-Segmenten (Feldtest 2026-10-16): läuft als Ausblendzeitpunkt − jetzt von 1 auf 0,
 * startet bei neuem Punkt bzw. nach einer Auswahl (verkürzte Restzeit) neu, stimmt auch beim Wiedereinstieg zwischen
 * zwei Controller-Ticks (Review PR #46) und ist in allen Segmenten gleich. Die monotone Uhr ist die Test-Uhr.
 * Galaxy Watch8 40 mm (206 dp, 340 dpi) als Pflichtgröße.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
class ServeCountdownUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var base = 0L

    /** Jetzt (Test-Uhr) relativ zum Teststart. */
    private fun now(): Long = rule.mainClock.currentTime - base

    @Composable
    private fun Screen(live: LiveUi) {
        CompositionLocalProvider(LocalElapsedClock provides ::now) {
            WearTestFrame {
                LiveScreen(live, activeWorkout(), heartRate = null, isAmbient = false, toast = null, actions = RecordingActions().actions)
            }
        }
    }

    private fun start() {
        rule.mainClock.autoAdvance = false
        base = rule.mainClock.currentTime
    }

    @Test
    fun countdownFollowsTheRemainingTimeAndRestartsOnANewPointOrChoice() {
        start()
        var bar by mutableStateOf(serveBar(hideAtMillis = 5_000))
        rule.setContent { Screen(liveUi(serveBar = bar)) }
        rule.mainClock.advanceTimeByFrame()
        assertCountdown(1f)
        rule.screenshot("serve-countdown-de-galaxy8-full")
        rule.mainClock.advanceTimeBy(2_500 - now())
        assertCountdown(0.5f)
        rule.screenshot("serve-countdown-de-galaxy8-half")
        // Auswahl bei 3,5 s: Ausblendzeitpunkt verkürzt (min(Rest, 1,5 s) → 4,5 s) → Anzeige läuft von dort weiter
        rule.mainClock.advanceTimeBy(3_500 - now())
        bar = bar.copy(selected = ServeOutcome.FIRST_SERVE_IN, hideAtMillis = 4_500)
        rule.mainClock.advanceTimeByFrame()
        assertCountdown(0.2f)
        rule.mainClock.advanceTimeBy(1_200)
        assertCountdown(0f)
        // neuer Punkt: wieder voll
        bar = serveBar(hideAtMillis = now() + 5_000, point = 100)
        rule.waitForIdle() // Zustandswechsel übernehmen (die alte Anzeige ist abgelaufen, kein Frame steht an)
        rule.mainClock.advanceTimeBy(100)
        assertCountdown(0.98f)
    }

    /** Wiedereinstieg (z. B. zurück von der Steuerseite) zwischen zwei Ticks: Restzeit aus hideAt − jetzt, nicht 60 %. */
    @Test
    fun reenteringBetweenTwoTicksStartsAtTheTrueRemainingTime() {
        start()
        var shown by mutableStateOf(true)
        // Zustand vom letzten Controller-Tick (bei 2,0 s veröffentlicht); Ausblenden bei 5,0 s
        val bar = serveBar(hideAtMillis = 5_000)
        rule.setContent { if (shown) Screen(liveUi(serveBar = bar)) }
        rule.mainClock.advanceTimeByFrame()
        shown = false // Steuerseite
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_900 - now())
        shown = true // zurück zum Score, noch vor dem nächsten Tick
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        assertCountdown(0.42f)
    }

    @Test
    fun setEndPageShowsTheSameCountdown() {
        start()
        val decision = DecisionUi(setNumber = 1, score = SidePair(6, 4), winner = Side.A, options = listOf(NextSetKind.REGULAR))
        rule.setContent { Screen(liveUi(serveBar = serveBar(hideAtMillis = 4_000), decision = decision)) }
        rule.mainClock.advanceTimeByFrame()
        assertCountdown(0.8f)
        rule.mainClock.advanceTimeBy(2_000)
        assertCountdown(0.4f)
    }

    @Test
    fun stripFitsOnGalaxyWatch8WithCountdown() {
        RuntimeEnvironment.setFontScale(1.3f)
        start()
        rule.setContent { Screen(liveUi(serveBar = serveBar(hideAtMillis = 3_000))) }
        rule.mainClock.advanceTimeByFrame()
        assertCountdown(0.6f)
        rule.screenshot("serve-countdown-de-galaxy8-font130")
    }

    private fun assertCountdown(expected: Float) {
        val labels =
            listOf(
                R.string.serve_first_in,
                R.string.serve_second_in,
                R.string.serve_double_fault,
            ).map(RuntimeEnvironment.getApplication()::getString)
        val values =
            labels.mapNotNull { label ->
                rule
                    .onAllNodesWithText(label)
                    .fetchSemanticsNodes()
                    .firstOrNull()
                    ?.countdown()
            }
        assertTrue("Segmente gefunden", values.isNotEmpty())
        values.forEach { assertEquals("Restzeit", expected, it, 0.03f) }
    }

    private fun SemanticsNode.countdown(): Float? = config.getOrElseNullable(ServeCountdownKey) { null }
}
