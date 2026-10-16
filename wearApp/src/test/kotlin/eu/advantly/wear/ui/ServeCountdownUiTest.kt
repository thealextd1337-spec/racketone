package eu.advantly.wear.ui

import androidx.activity.ComponentActivity
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
 * Restzeit-Anzeige in den Aufschlag-Segmenten (Feldtest 2026-10-16): läuft synchron zur echten Restzeit von 1 auf 0,
 * startet bei neuem Punkt bzw. nach einer Auswahl (verkürzte Restzeit) neu und ist in allen Segmenten gleich.
 * Galaxy Watch8 40 mm (206 dp, 340 dpi) als Pflichtgröße.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
class ServeCountdownUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun countdownFollowsTheRemainingTimeAndRestartsOnANewPointOrChoice() {
        var bar by mutableStateOf(serveBar(remainingMillis = 5_000, hideAtMillis = 5_000))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WearTestFrame {
                LiveScreen(
                    liveUi(serveBar = bar),
                    activeWorkout(),
                    heartRate = null,
                    isAmbient = false,
                    toast = null,
                    actions = RecordingActions().actions,
                )
            }
        }
        rule.mainClock.advanceTimeByFrame()
        assertCountdown(1f)
        rule.screenshot("serve-countdown-de-galaxy8-full")
        rule.mainClock.advanceTimeBy(2_500)
        assertCountdown(0.5f)
        rule.screenshot("serve-countdown-de-galaxy8-half")
        // Neuzeichnen mit frischer Restzeit (Tick des Controllers, gleicher Ausblendzeitpunkt): läuft ohne Sprung weiter
        bar = bar.copy(remainingMillis = 2_500)
        rule.mainClock.advanceTimeBy(1_000)
        assertCountdown(0.3f)
        // Auswahl: Ausblendzeitpunkt verkürzt (min(Rest, 1,5 s)) → Anzeige startet bei der verkürzten Restzeit neu
        bar = bar.copy(selected = ServeOutcome.FIRST_SERVE_IN, remainingMillis = 1_000, hideAtMillis = 4_500)
        rule.mainClock.advanceTimeByFrame()
        assertCountdown(0.2f)
        rule.mainClock.advanceTimeBy(1_200)
        assertCountdown(0f)
        // neuer Punkt: wieder voll
        bar = serveBar(remainingMillis = 5_000, hideAtMillis = 12_000, point = 100)
        rule.waitForIdle() // Zustandswechsel übernehmen (die alte Anzeige ist abgelaufen, kein Frame steht an)
        // Zustandswechsel wird mit den nächsten Frames übernommen (≈ 100 ms später noch fast voll)
        rule.mainClock.advanceTimeBy(100)
        assertCountdown(0.98f)
    }

    @Test
    fun setEndPageShowsTheSameCountdown() {
        val decision = DecisionUi(setNumber = 1, score = SidePair(6, 4), winner = Side.A, options = listOf(NextSetKind.REGULAR))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WearTestFrame {
                LiveScreen(
                    liveUi(serveBar = serveBar(remainingMillis = 4_000, hideAtMillis = 4_000), decision = decision),
                    activeWorkout(),
                    heartRate = null,
                    isAmbient = false,
                    toast = null,
                    actions = RecordingActions().actions,
                )
            }
        }
        rule.mainClock.advanceTimeByFrame()
        assertCountdown(0.8f)
        rule.mainClock.advanceTimeBy(2_000)
        assertCountdown(0.4f)
    }

    @Test
    @Config(qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
    fun stripFitsOnGalaxyWatch8WithCountdown() {
        RuntimeEnvironment.setFontScale(1.3f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WearTestFrame {
                LiveScreen(
                    liveUi(serveBar = serveBar(remainingMillis = 3_000, hideAtMillis = 3_000)),
                    activeWorkout(),
                    heartRate = null,
                    isAmbient = false,
                    toast = null,
                    actions = RecordingActions().actions,
                )
            }
        }
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
