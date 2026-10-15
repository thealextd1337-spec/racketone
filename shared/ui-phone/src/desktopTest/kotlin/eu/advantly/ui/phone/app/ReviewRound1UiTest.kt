package eu.advantly.ui.phone.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Preset
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ResultReason
import eu.advantly.presentation.match.ConflictInfo
import eu.advantly.presentation.match.DeviceKind
import eu.advantly.presentation.match.ForkBranch
import eu.advantly.presentation.match.ForkChoice
import eu.advantly.presentation.match.ForkInfo
import eu.advantly.presentation.match.SyncInfo
import eu.advantly.presentation.match.SyncState
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.fakeSyncModel
import eu.advantly.ui.phone.preview.DemoData
import eu.advantly.ui.phone.startWith
import eu.advantly.ui.phone.testModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** UI-Tests zu Review-Runde 1 (PR #14). */
@OptIn(ExperimentalTestApi::class)
class ReviewRound1UiTest {
    private var dispatcherOwner: NavigationEventDispatcherOwner? = null

    private fun ComposeUiTest.showApp(
        model: AdvantlyAppModel,
        size: DpSize = DpSize(412.dp, 892.dp),
        fontScale: Float = 1f,
        uriHandler: UriHandler? = null,
    ): AdvantlyAppModel {
        setContent {
            dispatcherOwner = LocalNavigationEventDispatcherOwner.current
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                Box(Modifier.size(size)) {
                    if (uriHandler != null) {
                        CompositionLocalProvider(LocalUriHandler provides uriHandler) { AdvantlyApp(model = model, darkTheme = true) }
                    } else {
                        AdvantlyApp(model = model, darkTheme = true)
                    }
                }
            }
        }
        waitForIdle()
        return model
    }

    private fun ComposeUiTest.click(tag: String) = onNodeWithTag(tag).performClick()

    /** Zustand eines anderen Logs (Vorschau eines Zweigs). */
    private fun preview(
        preset: Preset,
        points: String,
    ): MatchState =
        testModel()
            .apply { startWith(preset.format(), points) }
            .live.active.value!!
            .state

    // 1 – Neuer Start bei laufendem Match nur nach Bestätigung
    @Test
    fun playAgainWhileMatchRunsAsksBeforeAbandoning() =
        runComposeUiTest {
            val model = testModel().apply { DemoData.seed(this) }
            model.startWith(Presets.TENNIS_SINGLES_BO3.format(), "AB")
            val running =
                model.live.active.value!!
                    .matchId
            showApp(model)
            click("tab_history")
            onNodeWithTag("history_item_0").performClick()
            onNodeWithTag("result_play_again").performScrollTo().performClick()
            onNodeWithTag("abandon_dialog").assertExists()
            click("abandon_cancel")
            onNodeWithTag("abandon_dialog").assertDoesNotExist()
            assertEquals(
                running,
                model.live.active.value!!
                    .matchId,
            )
            assertEquals(
                MatchStatus.IN_PROGRESS,
                model.live.active.value!!
                    .state.status,
            )

            onNodeWithTag("result_play_again").performScrollTo().performClick()
            click("abandon_confirm")
            assertNotEquals(
                running,
                model.live.active.value!!
                    .matchId,
            )
            onNodeWithTag("live_sport_chip").assertExists()
            assertEquals(
                ResultReason.ABANDONED,
                model.history
                    .record(running)!!
                    .finalState.result!!
                    .reason,
            )
        }

    // 2 – Pickleball Side-Out: Ballwechsel gespielt, Stand 0:0 → Aufgabe statt Walkover
    @Test
    fun sideOutRallyOffersRetirementNotWalkover() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.PICKLEBALL_SINGLES_11_SIDEOUT.format(), "B") }
            model.navigator.push(Overlay.Live)
            showApp(model)
            click("more_button")
            click("menu_end_match")
            click("end_retire_them")
            onNodeWithTag("end_match_sheet").assertDoesNotExist()
            assertEquals(
                ResultReason.RETIRED,
                model.live.active.value!!
                    .state.result!!
                    .reason,
            )
        }

    @Test
    fun rejectedEndKeepsSheetOpenWithError() =
        runComposeUiTest {
            val (model, fake) = fakeSyncModel()
            model.startWith(Presets.TENNIS_SINGLES_BO3.format(), "A")
            fake.rejectEndMatch = true
            model.navigator.push(Overlay.Live)
            showApp(model)
            click("more_button")
            click("menu_end_match")
            onNodeWithTag("end_retire_them").performScrollTo().performClick()
            onNodeWithTag("end_match_sheet").assertExists()
            onNodeWithTag("end_failed").assertExists()
        }

    // 3 – Aufgabe bei 6:4 2:1 (30:0): eingefrorener Stand in Live, Matchende und Verlauf
    @Test
    fun retirementShowsFrozenSetEverywhere() =
        runComposeUiTest {
            val model = testModel()
            model.startWith(Presets.TENNIS_SINGLES_BO3.format(), "AAAABBBB".repeat(4) + "AAAA".repeat(2) + "AAAABBBB" + "AAAA" + "AA")
            model.live.endMatch(EndReason.RETIRED, Side.B)
            model.controller.sync(model.live.active.value)
            model.navigator.push(Overlay.Live)
            showApp(model)
            val score = onNodeWithTag("sheet_score").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
            assertTrue(score.contains("(30") && (score.endsWith("Aufg.") || score.endsWith("ret.")), score)
            onNodeWithTag("current_us", useUnmergedTree = true).assertExists()
            click("show_result")
            val sets = onNodeWithTag("result_sets").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
            assertEquals(score, sets)
        }

    // 4 – Sync-Pill, Konflikt-Banner mit 1-Tap-Undo, absorbierte Taps, Verzweigungs-Dialog
    @Test
    fun syncPillConflictBannerAndAbsorbedNotice() =
        runComposeUiTest {
            val (model, fake) = fakeSyncModel()
            model.startWith(Presets.TENNIS_SINGLES_BO3.format(), "AB")
            model.navigator.push(Overlay.Live)
            showApp(model)
            val local = onNodeWithTag("sync_pill").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
            assertTrue(local == "Nur lokal" || local == "Local only", local)

            runOnIdle {
                fake.update {
                    sync = SyncInfo(SyncState.CONNECTED, 1)
                    conflicts = listOf(ConflictInfo("c1", EventId("a"), EventId("b")))
                }
            }
            val connected = onNodeWithTag("sync_pill").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
            assertTrue(connected == "Uhr verbunden" || connected == "Watch connected", connected)
            onNodeWithTag("conflict_banner").assertExists()
            click("conflict_undo_them")
            onNodeWithTag("conflict_banner").assertDoesNotExist()
            assertEquals(listOf(Side.B), fake.undoneConflictSides)

            runOnIdle { fake.update { absorbedTaps = 1 } }
            onNodeWithTag("absorbed_notice").assertExists()
        }

    @Test
    fun forkDialogResolvesWithChosenBranch() =
        runComposeUiTest {
            val (model, fake) = fakeSyncModel()
            model.startWith(Presets.TENNIS_SINGLES_BO3.format(), "AAAA")
            fake.update {
                sync = SyncInfo(SyncState.CONFLICT, 1)
                forks =
                    listOf(
                        ForkInfo(
                            forkId = "f1",
                            branchA = ForkBranch(DeviceId("watch"), DeviceKind.WATCH, preview(Presets.TENNIS_SINGLES_BO3, "AAAAAA")),
                            branchB = ForkBranch(DeviceId("phone"), DeviceKind.PHONE, preview(Presets.TENNIS_SINGLES_BO3, "AAAABB")),
                            mergedPreview = preview(Presets.TENNIS_SINGLES_BO3, "AAAAAABB"),
                        ),
                    )
            }
            model.controller.sync(model.live.active.value)
            model.navigator.push(Overlay.Live)
            showApp(model)
            onNodeWithTag("fork_sheet").assertExists()
            onNodeWithTag("panel_us").assertDoesNotExist()
            onNodeWithTag("fork_branch_a").performScrollTo().performClick()
            assertEquals(listOf("f1" to ForkChoice.BRANCH_A), fake.resolved)
            onNodeWithTag("fork_sheet").assertDoesNotExist()
            onNodeWithTag("panel_us").assertExists()
        }

    // 5 – Modale Sheets: Hintergrund und Tabs nicht erreichbar
    @Test
    fun openSheetHidesBackgroundFromAccessibility() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_OPEN.format(), "AAAA".repeat(6)) }
            model.navigator.push(Overlay.Live)
            showApp(model)
            onNodeWithTag("set_end_sheet").assertExists()
            onNodeWithTag("panel_us").assertDoesNotExist()
            onNodeWithTag("undo_button").assertDoesNotExist()
            onNodeWithTag("tab_home").assertDoesNotExist()
            click("decision_yes")
            onNodeWithTag("panel_us").assertExists()
            onNodeWithTag("tab_home").assertDoesNotExist()
        }

    // 9 – Querformat + 200 %: Aufschlagfeld bleibt sichtbar
    @Test
    fun courtLabelSurvivesLandscapeAt200Percent() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.BADMINTON_SINGLES_BO3_21.format(), "A".repeat(20)) }
            model.navigator.push(Overlay.Live)
            showApp(model, size = DpSize(892.dp, 412.dp), fontScale = 2f)
            onNodeWithTag("panel_us_court", useUnmergedTree = true).assertIsDisplayed().assertWidthIsAtLeast(8.dp)
        }

    // 10 – Zurück schließt zuerst den Dialog, nicht den Live-Screen
    @Test
    fun backClosesOpenDialogBeforeLiveScreen() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format(), "A") }
            model.navigator.push(Overlay.Live)
            showApp(model)
            click("more_button")
            click("menu_end_match")
            onNodeWithTag("end_match_sheet").assertExists()
            // System-Zurück wie auf Android/iOS: über den NavigationEventDispatcher des Hosts
            runOnIdle {
                val input = DirectNavigationEventInput()
                checkNotNull(dispatcherOwner).navigationEventDispatcher.addInput(input)
                input.backCompleted()
            }
            onNodeWithTag("end_match_sheet").assertDoesNotExist()
            onNodeWithTag("live_sport_chip").assertExists()
            assertEquals(Overlay.Live, model.navigator.top)
        }

    // Freigabekriterium Play: Datenschutz und Impressum im Profil
    @Test
    fun profileOpensPrivacyAndImprint() =
        runComposeUiTest {
            val opened = mutableListOf<String>()
            showApp(
                testModel(),
                uriHandler =
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            opened += uri
                        }
                    },
            )
            click("tab_profile")
            onNodeWithTag("coming_soon_profile").assertExists()
            click("profile_privacy")
            click("profile_imprint")
            assertTrue(
                opened[0] in listOf("https://advantly.eu/app-datenschutz.html", "https://advantly.eu/en/app-privacy.html"),
                opened[0],
            )
            assertEquals("https://advantly.eu/impressum.html", opened[1])
        }
}
