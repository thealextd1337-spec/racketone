package eu.advantly.ui.phone.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Presets
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.SessionRig
import eu.advantly.ui.phone.common.LocalSafeInsets
import eu.advantly.ui.phone.preview.DemoData
import eu.advantly.ui.phone.startWith
import eu.advantly.ui.phone.testModel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Edge-to-Edge (Feldtest Pixel 9 Pro XL): Mit simulierten Insets (Statusleiste oben, Navigationsleiste bzw. Tastatur
 * unten) liegt in keinem Screen Inhalt unter den Systemleisten.
 */
@OptIn(ExperimentalTestApi::class)
class WindowInsetsUiTest {
    private val height = 892f

    private fun ComposeUiTest.showApp(
        model: AdvantlyAppModel,
        top: Int = STATUS_BAR,
        bottom: Int = NAV_BAR,
    ) {
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalSafeInsets provides WindowInsets(0.dp, top.dp, 0.dp, bottom.dp),
            ) {
                Box(Modifier.size(412.dp, height.dp)) { AdvantlyApp(model = model, darkTheme = true) }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.assertBelowStatusBar(
        tag: String,
        top: Int = STATUS_BAR,
    ) {
        val bounds = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.top >= top, "$tag beginnt bei ${bounds.top}, Statusleiste bis $top")
    }

    private fun ComposeUiTest.assertAboveBottomInset(
        tag: String,
        bottom: Int = NAV_BAR,
    ) {
        val bounds = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.bottom <= height - bottom, "$tag endet bei ${bounds.bottom}, frei nur bis ${height - bottom}")
    }

    @Test
    fun homeAndTabsStayInsideTheSafeArea() =
        runComposeUiTest {
            showApp(testModel().apply { DemoData.seed(this) })
            assertBelowStatusBar("home")
            assertAboveBottomInset("tab_home")
            assertAboveBottomInset("tab_profile")
        }

    @Test
    fun wizardPlayersStepDoublesStaysBelowTheStatusBar() =
        runComposeUiTest {
            val model = testModel()
            showApp(model)
            onNodeWithTag("tab_play").performClick()
            onNodeWithTag("sport_${Sport.PADEL.name}").performClick()
            assertBelowStatusBar("top_back")
            assertBelowStatusBar("slot_A_0")
            assertAboveBottomInset("wizard_next")
        }

    @Test
    fun keyboardPushesTheWizardFooterUp() =
        runComposeUiTest {
            val model = testModel()
            showApp(model, bottom = KEYBOARD)
            onNodeWithTag("tab_play").performClick()
            onNodeWithTag("sport_${Sport.PADEL.name}").performClick()
            assertBelowStatusBar("top_back")
            assertAboveBottomInset("wizard_next", bottom = KEYBOARD)
        }

    @Test
    fun liveScreenContentAvoidsBothBars() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format(), "AB") }
            model.navigator.push(Overlay.Live)
            showApp(model)
            assertBelowStatusBar("live_minimize")
            assertBelowStatusBar("sync_pill")
            assertAboveBottomInset("undo_button")
        }

    @Test
    fun resultScreenStaysBelowTheStatusBar() =
        runComposeUiTest {
            val model = testModel().apply { DemoData.seed(this) }
            model.navigator.push(
                Overlay.Result(
                    model.history.matches.value
                        .first()
                        .matchId,
                ),
            )
            showApp(model)
            assertBelowStatusBar("top_back")
        }

    /** Kleines Display, 200 % Schrift, Systemleisten: Ziffer + Labels beider Flächen ohne Überlappung, Ziffer lesbar. */
    private fun ComposeUiTest.assertScoresVisibleOnSmallScreen(
        model: AdvantlyAppModel,
        interact: ComposeUiTest.() -> Unit = {},
    ) {
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 2f),
                LocalSafeInsets provides WindowInsets(0.dp, STATUS_BAR.dp, 0.dp, NAV_BAR.dp),
            ) {
                Box(Modifier.size(360.dp, 640.dp)) { AdvantlyApp(model = model, darkTheme = true) }
            }
        }
        waitForIdle()
        interact()
        for ((panel, score) in listOf("panel_us" to "score_us", "panel_them" to "score_them")) {
            val panelBounds = onNodeWithTag(panel).fetchSemanticsNode().boundsInRoot
            val digit = onNodeWithTag(score, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val label = onNodeWithTag("${panel}_label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(digit.height >= MIN_DIGIT, "$score nur ${digit.height} hoch")
            assertTrue(digit.top >= panelBounds.top && digit.bottom <= panelBounds.bottom, "$score ragt aus $panel: $digit / $panelBounds")
            val overlap = minOf(digit.bottom, label.bottom) - maxOf(digit.top, label.top)
            assertTrue(overlap <= 0f, "$score überlappt Label: $digit / $label")
        }
    }

    /** Review W1-I Runde 2 (Reproducer combined-insets-200): Anzeige-Modus, Verbindung weg, 360 × 640, 200 %. */
    @Test
    fun displayScoreStaysVisibleOnSmallScreenWithLargeFontAndBars() =
        runComposeUiTest {
            val rig = SessionRig()
            rig.startWatchScoredTennis()
            rig.watchPlays("AAB")
            rig.model.navigator.push(Overlay.Live)
            assertScoresVisibleOnSmallScreen(rig.model) {
                runOnIdle {
                    rig.offline()
                    rig.settle(42_000)
                }
                mainClock.advanceTimeBy(1_100)
                onNodeWithTag("stale_hint").assertExists()
            }
            onNodeWithTag("score_us", useUnmergedTree = true).assertTextEquals("30")
        }

    @Test
    fun scorerScoreStaysVisibleOnSmallScreenWithLargeFontAndBars() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format(), "AAB") }
            model.navigator.push(Overlay.Live)
            assertScoresVisibleOnSmallScreen(model)
        }

    companion object {
        /** Mindesthöhe der Ziffer (dp) – lesbar aus Armlänge. */
        const val MIN_DIGIT = 30f

        /** Pixel-ähnliche Werte in dp. */
        const val STATUS_BAR = 52
        const val NAV_BAR = 24
        const val KEYBOARD = 330
    }
}
