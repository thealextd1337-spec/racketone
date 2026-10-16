package eu.advantly.wear.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.GameKind
import eu.advantly.match.session.ServerChoiceContext
import eu.advantly.match.session.ServerChoicePhase
import eu.advantly.wear.live.LiveOverlay
import eu.advantly.wear.live.ServerChoiceUi
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

/** Auswahl auf dem Zielgerät: Text im Kreis, große Schrift, scrollbar und kein Punkt durch Auswahl. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
class ServerChoiceUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun longDuplicateNamesKeepFullNameTeamAndSlotAccessible() {
        RuntimeEnvironment.setFontScale(1.3f)
        val base = liveUi(doubles = true)
        val name = "Maximilian Meyer-Langenberg"
        val players = (base.teams.a + base.teams.b).map { it.copy(name = name, isSelf = false) }
        val choice = ServerChoiceUi(1, Side.A, players, null, true)
        rule.setContent { WearTestFrame { ServerChoiceScreen(base, choice, RecordingActions().actions) } }
        players.forEachIndexed { i, player ->
            rule.onNode(hasScrollToNodeAction()).performScrollToIndex(1 + i)
            val team =
                RuntimeEnvironment.getApplication().getString(
                    if (player.side == Side.A) eu.advantly.wear.R.string.label_us else eu.advantly.wear.R.string.label_them,
                )
            val label = rule.onNodeWithContentDescription("$name · $team · ${player.index + 1}", useUnmergedTree = true)
            assertTrue(label.textLayout().lineCount <= 2)
            rule.screenshot("choice-long-names-galaxy8-font130-player$i")
        }
    }

    @Test
    fun firstChoiceFitsWithLargeFontAndNames() = checkChoice(1, false, 1.3f, "choice-first-de-galaxy8-font130")

    @Test
    fun placeholdersFitOnGalaxyWatch8() = checkChoice(1, true, 1f, "choice-first-de-galaxy8")

    @Test
    fun secondChoiceFitsWithLargeFont() = checkChoice(2, true, 1.3f, "choice-second-de-galaxy8-font130")

    @Test
    @Config(qualifiers = "en-w206dp-h206dp-round-watch-340dpi")
    fun englishTiebreakChoiceFits() = checkChoice(2, true, 1.3f, "choice-tiebreak-en-galaxy8", true)

    @Test
    fun contextDefaultWinsOverLiveServerAndMissingServe() {
        val live = liveUi(doubles = true, names = false)
        val players = live.teams.b
        val context =
            ServerChoiceContext(
                live.matchId,
                0,
                ServerChoicePhase.SECOND,
                players.map { it.id },
                Side.B,
                players[0].id,
                null,
                GameKind.STANDARD,
            )
        val choice = ServerChoiceUi(2, Side.B, players, null, true, context)
        var missingServe by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent {
            WearTestFrame { ServerChoiceScreen(if (missingServe) live.copy(serve = null) else live, choice, RecordingActions().actions) }
        }
        val text = "Aktuell: Gegner 1. Du kannst bis zum nächsten Punkt wählen."
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        rule.onNodeWithText(text).assertExists()
        rule.runOnIdle { missingServe = true }
        rule.onNodeWithText(text).assertExists()
    }

    private fun checkChoice(
        phase: Int,
        placeholders: Boolean,
        font: Float,
        shot: String,
        tiebreak: Boolean = false,
    ) {
        RuntimeEnvironment.setFontScale(font)
        val live = liveUi(doubles = true, names = !placeholders, serving = if (phase == 1) Side.A else Side.B)
        val players = if (phase == 1) live.teams.a + live.teams.b else live.teams.b
        val context =
            ServerChoiceContext(
                live.matchId,
                0,
                if (phase == 1) ServerChoicePhase.FIRST else ServerChoicePhase.SECOND,
                players.map { it.id },
                if (phase == 1) Side.A else Side.B,
                players[0].id,
                null,
                if (tiebreak) GameKind.MATCH_TIEBREAK else GameKind.STANDARD,
            )
        val choice = ServerChoiceUi(phase, Side.B, players, null, true, context)
        val recorder = RecordingActions()
        rule.setContent { WearTestFrame { ServerChoiceScreen(live.copy(overlay = LiveOverlay.SERVER_CHOICE), choice, recorder.actions) } }
        rule.screenshot("$shot-start")
        val app = RuntimeEnvironment.getApplication()
        val labels =
            players.map { p ->
                p.name ?: app.getString(
                    when {
                        p.side == Side.A && p.index == 0 -> eu.advantly.wear.R.string.player_you
                        p.side == Side.A -> eu.advantly.wear.R.string.player_partner
                        p.index == 0 -> eu.advantly.wear.R.string.player_opponent_1
                        else -> eu.advantly.wear.R.string.player_opponent_2
                    },
                )
            }
        labels.forEachIndexed { i, text ->
            rule.onNode(hasScrollToNodeAction()).performScrollToIndex(2 + i)
            rule.waitForIdle()
            val target = rule.onNode(hasText(text) and hasClickAction()).getUnclippedBoundsInRoot()
            assertTrue("Touchziel mindestens 48 dp", (target.bottom - target.top).value >= 48f)
            val node = rule.onNodeWithText(text, useUnmergedTree = true)
            assertFalse("Name abgeschnitten: $text", node.textLayout().hasVisualOverflow)
            val r = node.fetchSemanticsNode().boundsInRoot
            val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
            for (corner in listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight)) {
                assertTrue("$text außerhalb: $corner", hypot(corner.x - root.center.x, corner.y - root.center.y) <= root.width / 2 + 1f)
            }
            if (i == 1 || i == labels.lastIndex) rule.screenshot("$shot-player$i")
        }
        val current = app.getString(eu.advantly.wear.R.string.server_choice_current, labels.first())
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(current))
        rule.onNodeWithText(current).assertExists()
        if (phase == 2 && placeholders && !tiebreak) {
            rule.onNodeWithText("Aktuell: Gegner 1. Du kannst bis zum nächsten Punkt wählen.").assertExists()
        }
        rule.screenshot("$shot-current")
        val later = app.getString(eu.advantly.wear.R.string.action_later)
        rule.onNode(hasScrollToNodeAction()).performScrollToIndex(players.size + 3)
        rule.screenshot("$shot-later")
        rule.onNodeWithText(later).performClick()
        assertFalse(recorder.calls.toString(), recorder.calls.any { it.startsWith("point") })
    }
}
