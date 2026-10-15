package eu.advantly.ui.phone.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.live.HapticCue
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.latency.InputClockSource
import eu.advantly.ui.phone.platform.Haptics
import eu.advantly.ui.phone.startWith
import eu.advantly.ui.phone.testModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Compose-UI-Tests (Desktop/JVM) für den Live-Score über der echten Engine: Taps, Undo, Gesten, Hinweise, Layout. */
@OptIn(ExperimentalTestApi::class)
class LiveScoreScreenTest {
    private val haptics = mutableListOf<HapticCue>()

    private fun ComposeUiTest.showLive(
        model: AdvantlyAppModel = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format()) },
        size: DpSize = DpSize(412.dp, 892.dp),
        fontScale: Float = 1f,
        debugTools: Boolean = false,
    ): AdvantlyAppModel {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                AdvantlyTheme(darkTheme = true) {
                    Box(Modifier.size(size)) { LiveScoreScreen(controller = model.controller, debugTools = debugTools) }
                }
            }
        }
        // Nach dem ersten Frame überschreibt der Screen die Haptik mit der Plattform-Variante (Desktop: still) –
        // für die Tests eine aufzeichnende einsetzen.
        waitForIdle()
        runOnIdle { model.controller.haptics = Haptics { haptics += it } }
        return model
    }

    private fun ComposeUiTest.assertScore(
        us: String,
        them: String,
    ) {
        onNodeWithTag("score_us", useUnmergedTree = true).assertTextEquals(us)
        onNodeWithTag("score_them", useUnmergedTree = true).assertTextEquals(them)
    }

    @Test
    fun tapOnPanelsScoresPointsThroughTheEngine() =
        runComposeUiTest {
            showLive()
            assertScore("0", "0")
            onNodeWithTag("panel_us").performClick()
            assertScore("15", "0")
            onNodeWithTag("panel_them").performClick()
            onNodeWithTag("panel_them").performClick()
            assertScore("15", "30")
            assertEquals(listOf(HapticCue.PointUs, HapticCue.PointThem, HapticCue.PointThem), haptics)
        }

    @Test
    fun gameWinUpdatesSetTableAndCue() =
        runComposeUiTest {
            showLive()
            repeat(4) { onNodeWithTag("panel_us").performClick() }
            assertScore("0", "0")
            onNodeWithTag("current_us", useUnmergedTree = true).assertTextEquals("1")
            // 1:0 → Seitenwechsel (ungerade Spielsumme) ist stärker als „Spiel gewonnen“
            assertEquals(HapticCue.ChangeEnds, haptics.last())
            onNodeWithTag("hint_pill").assertExists()
        }

    @Test
    fun undoButtonRevertsLastPointAndSnackbarOffersRedo() =
        runComposeUiTest {
            showLive()
            onNodeWithTag("panel_us").performClick()
            onNodeWithTag("panel_them").performClick()
            assertScore("15", "15")

            onNodeWithTag("undo_button").performClick()
            assertScore("15", "0")
            onNodeWithTag("undo_snackbar").assertExists()

            onNodeWithTag("redo_button").performClick()
            assertScore("15", "15")
            onNodeWithTag("undo_snackbar").assertDoesNotExist()
        }

    @Test
    fun swipeDownOnPanelUndoes() =
        runComposeUiTest {
            showLive()
            onNodeWithTag("panel_us").performClick()
            onNodeWithTag("panel_us").performClick()
            assertScore("30", "0")
            onNodeWithTag("panel_them").performTouchInput { swipeDown(startY = top + 10f, endY = bottom - 10f) }
            assertScore("15", "0")
            onNodeWithTag("undo_snackbar").assertExists()
            assertTrue(HapticCue.Undo in haptics)
        }

    @Test
    fun rapidDoubleTapCountsBothTaps() =
        runComposeUiTest {
            val model = showLive()
            onNodeWithTag("panel_us").performTouchInput {
                click()
                advanceEventTime(60)
                click()
            }
            assertScore("30", "0")
            assertEquals(2, model.controller.rawTaps)
            assertEquals(2, model.controller.countedPoints)
            assertEquals(listOf(HapticCue.PointUs, HapticCue.PointUs), haptics)
        }

    @Test
    fun debounceSwallowsSecondTapWithin600Millis() =
        runComposeUiTest {
            val model = showLive()
            runOnIdle { model.controller.debounceEnabled = true }
            onNodeWithTag("panel_them").performTouchInput {
                click()
                advanceEventTime(60)
                click()
            }
            assertScore("0", "15")
            assertEquals(1, model.controller.debouncedTaps)
            assertEquals(listOf(HapticCue.PointThem, HapticCue.Rejected), haptics)
        }

    @Test
    fun breakPointPillNamesCountAndTeamAndServeIndicatorFollowsEngine() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format(), "AAA", firstServerSide = Side.B) }
            showLive(model)
            assertScore("40", "0")
            val node = onNodeWithTag("hint_pill").fetchSemanticsNode()
            val description = node.config[SemanticsProperties.ContentDescription].single()
            assertTrue(description == "3 Breakbälle für Wir" || description == "3 break points for us", description)
            assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion])
            onNodeWithTag("panel_them_serve", useUnmergedTree = true).assertExists()
            onNodeWithTag("panel_us_serve", useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun outdoorToggleSwitchesContrastMode() =
        runComposeUiTest {
            val model = showLive()
            onNodeWithTag("outdoor_toggle").assertIsOff()
            onNodeWithTag("outdoor_toggle").performClick()
            onNodeWithTag("outdoor_toggle").assertIsOn()
            assertTrue(model.controller.outdoorContrast)
        }

    @Test
    fun latencyOverlayOnlyWithDebugTools() =
        runComposeUiTest {
            val model = showLive(debugTools = true)
            repeat(3) { onNodeWithTag("panel_us").performClick() }
            waitForIdle()
            // Die virtuelle Test-Uhr passt nicht zur Ereigniszeit → Handler-Fallback, getrennt ausgewiesen
            val summary = model.controller.tracker.summary(InputClockSource.Handler)
            assertEquals(3, summary.samples)
            assertEquals(
                0,
                model.controller.tracker
                    .summary(InputClockSource.InputEvent)
                    .samples,
            )
            assertTrue(summary.inputToNextFrameP50!! >= summary.inputToDrawP50!!)
            onNodeWithTag("latency_Handler", useUnmergedTree = true).assertExists()
        }

    @Test
    fun latencyOverlayHiddenInRelease() =
        runComposeUiTest {
            showLive(debugTools = false)
            onNodeWithTag("latency_overlay").assertDoesNotExist()
        }

    @Test
    fun redoStaysReachableFromTheMenuForANewController() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format(), "AA") }
            model.live.undo()
            // Neuer Controller über demselben Port (wie nach Rotation): keine Snackbar, Redo im Menü
            val fresh = AdvantlyAppModel(model.live, model.history, model.players, clock = model.clock)
            showLive(fresh)
            assertScore("15", "0")
            onNodeWithTag("undo_snackbar").assertDoesNotExist()
            onNodeWithTag("more_button").performClick()
            onNodeWithTag("menu_redo").assertIsEnabled().performClick()
            assertScore("30", "0")
            assertFalse(
                model.live.active.value!!
                    .canRedo,
            )
        }

    @Test
    fun hintArrowPointsToTheTeamPanelInBothOrientations() {
        for ((size, expected) in listOf(DpSize(412.dp, 892.dp) to "up", DpSize(892.dp, 412.dp) to "left")) {
            runComposeUiTest {
                // Breakball für Wir
                showLive(testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format(), "AAA", firstServerSide = Side.B) }, size)
                val node = onNodeWithTag("hint_pill").fetchSemanticsNode()
                assertEquals(expected, node.config[HintArrowDirection])
            }
        }
    }

    @Test
    fun setTableRowsGrowWithFontScale200Percent() =
        runComposeUiTest {
            showLive(fontScale = 2f)
            onNodeWithTag("set_name_us", useUnmergedTree = true).assertHeightIsAtLeast(32.dp)
            onNodeWithTag("set_name_them", useUnmergedTree = true).assertHeightIsAtLeast(32.dp)
            onNodeWithTag("current_us", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("panel_us").assertHeightIsAtLeast(120.dp)
            onNodeWithTag("panel_them").assertHeightIsAtLeast(120.dp)
            onNodeWithTag("undo_button").assertHeightIsAtLeast(64.dp)
        }

    @Test
    fun fullOverlayAtFontScale200KeepsScorePanelsAndLabelsApart() =
        runComposeUiTest {
            val model = showLive(fontScale = 2f, debugTools = true)
            // Overlay mit allen drei Quellen füllen (Review S4: Flächen schrumpften sonst auf 55 dp)
            runOnIdle {
                InputClockSource.entries.forEachIndexed { i, source ->
                    model.controller.tracker.onInput(100 + i, 0, source)
                    model.controller.tracker.onDrawn(100 + i, 5_000_000)
                    model.controller.tracker.onNextFrame(16_000_000)
                }
            }
            waitForIdle()
            for (team in listOf("us", "them")) {
                onNodeWithTag("panel_$team").assertHeightIsAtLeast(150.dp)
                val score = onNodeWithTag("score_$team", useUnmergedTree = true).getBoundsInRoot()
                val label = onNodeWithTag("panel_${team}_label", useUnmergedTree = true).getBoundsInRoot()
                val apart = if (team == "us") score.top >= label.bottom else score.bottom <= label.top
                assertTrue(apart, "Ziffer überdeckt Label ($team): score=$score label=$label")
            }
        }

    @Test
    fun compactLandscapeKeepsUsableTapPanels() =
        runComposeUiTest {
            showLive(size = DpSize(892.dp, 412.dp), fontScale = 2f)
            onNodeWithTag("panel_us").assertHeightIsAtLeast(200.dp)
            onNodeWithTag("panel_them").assertHeightIsAtLeast(200.dp)
            onNodeWithTag("panel_us").performClick()
            assertScore("15", "0")
            onNodeWithTag("set_name_us", useUnmergedTree = true).assertHeightIsAtLeast(32.dp)
        }

    @Test
    fun badmintonDoublesShowsRallyPointsCourtAndServeLine() =
        runComposeUiTest {
            showLive(testModel().apply { startWith(Presets.BADMINTON_DOUBLES_BO3_21.format(), "AAB") })
            assertScore("2", "1")
            onNodeWithTag("live_sport_chip").assertExists()
            // Doppel: „Aufschläger → Rückschläger“ im Mittelband
            onNodeWithTag("serve_line", useUnmergedTree = true).assertExists()
            onNodeWithTag("current_us", useUnmergedTree = true).assertTextEquals("2")
        }

    @Test
    fun pickleballShowsScoreCall() =
        runComposeUiTest {
            showLive(testModel().apply { startWith(Presets.PICKLEBALL_DOUBLES_11_SIDEOUT.format(), "A") })
            assertScore("1", "0")
            onNodeWithTag("serve_line", useUnmergedTree = true).assertTextEquals("1-0-2")
        }

    @Test
    fun padelGoldenPointAndTableTennisDisplay() {
        runComposeUiTest {
            showLive(testModel().apply { startWith(Presets.PADEL_GOLDEN_BO3.format(), "AAABBB") })
            assertScore("40", "40")
            val description = onNodeWithTag("hint_pill").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
            assertEquals("Golden Point", description.replace("Golden point", "Golden Point"))
        }
        runComposeUiTest {
            showLive(testModel().apply { startWith(Presets.TABLE_TENNIS_SINGLES_BO5.format(Discipline.SINGLES), "AB" + "A".repeat(9)) })
            assertScore("10", "1")
            onNodeWithTag("hint_pill").assertExists()
        }
    }

    @Test
    fun matchEndShowsSheetAndPanelsIgnoreTaps() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.BADMINTON_1GAME_15.format(), "A".repeat(15)) }
            showLive(model)
            onNodeWithTag("match_end_sheet").assertExists()
            assertEquals(
                MatchStatus.FINISHED,
                model.live.active.value!!
                    .state.status,
            )
            onNodeWithTag("sheet_undo").performClick()
            onNodeWithTag("match_end_sheet").assertDoesNotExist()
            assertScore("14", "0")
        }
}
