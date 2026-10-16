package eu.advantly.wear.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import eu.advantly.core.model.Sport
import eu.advantly.wear.live.CourtLetter
import eu.advantly.wear.live.LiveUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Aufschlagrichtung (Feldtest 2026-10-16): Im Tennis-/Padel-Doppel steht der Aufschläger je Game mit dem Feld
 * „Rechts“/„Links“ – nie, auf wen aufgeschlagen wird (wer wo steht, ist offen). Andere Doppel (Badminton/Tischtennis)
 * behalten „Aufschläger → Rückschläger“ (regelgebunden). Galaxy Watch8 40 mm (206 dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
class ServeDirectionUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val receiverName = "Bartholomäus"

    private fun doubles(
        sport: Sport,
        court: CourtLetter?,
    ): LiveUi {
        val live = liveUi(doubles = true, sport = sport)
        return live.copy(serve = live.serve!!.copy(court = court))
    }

    private fun render(live: LiveUi) {
        rule.setContent {
            WearTestFrame {
                LiveScreen(
                    live,
                    activeWorkout(),
                    heartRate = null,
                    isAmbient = false,
                    toast = null,
                    actions = RecordingActions().actions,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun allText(): String =
        rule
            .onRoot(useUnmergedTree = true)
            .fetchSemanticsNode()
            .let { root -> generateSequence(listOf(root)) { l -> l.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten() }
            .flatMap { node ->
                (
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .map { it.text }
                ) +
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.joinToString(" | ")

    @Test
    fun tennisDoublesShowsServerAndRightCourtWithoutReceiver() {
        render(doubles(Sport.TENNIS, CourtLetter.R))
        rule.onNodeWithTag(LiveTags.SERVE_COURT, useUnmergedTree = true).assertExists()
        assertEquals(1, rule.onAllNodesWithText("Du", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithText(" · Rechts", useUnmergedTree = true).fetchSemanticsNodes().size)
        val text = allText()
        assertFalse("kein Rückschläger: $text", receiverName in text)
        assertFalse("kein Pfeil „auf wen“: $text", "→" in text)
        assertTrue("TalkBack: Feld ausgeschrieben: $text", "Aufschlag von rechts" in text)
        assertTrue("TalkBack: Aufschläger: $text", "Du schlägt auf" in text)
        rule.screenshot("serve-direction-tennis-doubles-galaxy8")
    }

    @Test
    fun padelDoublesFromTheLeft() {
        render(doubles(Sport.PADEL, CourtLetter.L))
        assertEquals(1, rule.onAllNodesWithText(" · Links", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertFalse(receiverName in allText())
    }

    @Test
    fun decidingPointWithoutCourtShowsOnlyTheServer() {
        render(doubles(Sport.TENNIS, null))
        assertTrue(rule.onAllNodesWithText(" · Rechts", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText(" · Links", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertFalse(receiverName in allText())
    }

    @Test
    fun badmintonDoublesKeepsServerToReceiver() {
        render(doubles(Sport.BADMINTON, CourtLetter.R))
        val text = allText()
        assertTrue("Badminton unverändert: $text", "Du → $receiverName" in text)
    }
}
