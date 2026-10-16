package eu.advantly.wear.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.SidePair
import eu.advantly.wear.live.DecisionUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

/**
 * Satzende-Dialog (Feldtest 2026-10-16): Die Frage ist nummeriert – „2. Satz?“ statt „Noch ein Satz?“, Pickleball
 * „2. Spiel?“, Englisch „Set 2?“ – und auf der Galaxy Watch8 (206 dp) auch mit großer Schrift vollständig im Kreis.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
class DecisionQuestionUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(
        sport: Sport,
        finishedSet: Int,
        withServeBar: Boolean = false,
    ) {
        val decision =
            DecisionUi(
                setNumber = finishedSet,
                score = SidePair(6, 4),
                winner = Side.A,
                options =
                    if (sport.isTennisLike) {
                        listOf(
                            NextSetKind.REGULAR,
                            NextSetKind.MATCH_TIEBREAK_10,
                        )
                    } else {
                        listOf(NextSetKind.REGULAR)
                    },
            )
        val live = liveUi(sport = sport, decision = decision, serveBar = if (withServeBar) serveBar() else null)
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
    }

    @Test
    fun afterTheFirstSetItAsksForTheSecond() {
        render(Sport.TENNIS, finishedSet = 1, withServeBar = true)
        assertQuestionFits("2. Satz?")
        rule.screenshot("decision-de-galaxy8")
    }

    @Test
    fun afterTheSecondSetItAsksForTheThird() {
        render(Sport.BADMINTON, finishedSet = 2)
        assertQuestionFits("3. Satz?")
    }

    @Test
    fun pickleballAsksForTheNextGame() {
        render(Sport.PICKLEBALL, finishedSet = 1)
        assertQuestionFits("2. Spiel?")
    }

    @Test
    @Config(qualifiers = "en-w206dp-h206dp-round-watch-340dpi")
    fun englishNumbersTheSet() {
        render(Sport.TENNIS, finishedSet = 1)
        assertQuestionFits("Set 2?")
    }

    @Test
    fun largeFontOnGalaxyWatch8() {
        RuntimeEnvironment.setFontScale(1.3f)
        render(Sport.TENNIS, finishedSet = 3, withServeBar = true)
        assertQuestionFits("4. Satz?")
        rule.screenshot("decision-de-galaxy8-font130")
    }

    /** Frage vorhanden, nach dem Scrollen im Kreis, nicht abgeschnitten; die alte Frage gibt es nicht mehr. */
    private fun assertQuestionFits(text: String) {
        // Liste (ScalingLazyColumn) bis zur Frage scrollen – mit Aufschlag-Leiste steht sie weiter unten
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        rule.waitForIdle()
        val node = rule.onNodeWithText(text)
        val layout = node.textLayout()
        assertFalse("$text abgeschnitten", layout.hasVisualOverflow)
        val r = node.fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        for (corner in listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight)) {
            assertTrue("$text: Ecke $corner außerhalb", hypot(corner.x - root.center.x, corner.y - root.center.y) <= root.width / 2 + 1f)
        }
        assertTrue(
            rule
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText("Noch ein", substring = true),
                ).fetchSemanticsNodes()
                .isEmpty(),
        )
    }
}
