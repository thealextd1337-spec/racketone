package eu.advantly.ui.phone.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.DeuceMode
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.PickleballScoring
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Surface
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.SetKind
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.play
import eu.advantly.ui.phone.preview.DemoData
import eu.advantly.ui.phone.setupFor
import eu.advantly.ui.phone.startWith
import eu.advantly.ui.phone.testModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ende-zu-Ende-Flows der Phone-App (Desktop): Assistent je Sportart, Satz-/Matchende, Folge-Match, Schnellstart, Verlauf. */
@OptIn(ExperimentalTestApi::class)
class AppFlowTest {
    private fun ComposeUiTest.showApp(model: AdvantlyAppModel = testModel()): AdvantlyAppModel {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.size(412.dp, 892.dp)) { AdvantlyApp(model = model, darkTheme = true) }
            }
        }
        waitForIdle()
        return model
    }

    private fun ComposeUiTest.click(tag: String) {
        if (onAllNodesWithTag("history").fetchSemanticsNodes().isNotEmpty()) {
            onNodeWithTag("history").performScrollToNode(hasTestTag(tag))
        }
        onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun ComposeUiTest.click(
        tag: String,
        scroll: Boolean,
    ) = if (scroll) click(tag) else onNodeWithTag(tag).performClick()

    private fun ComposeUiTest.addGuest(
        name: String,
        place: String,
    ) {
        click(place)
        click("player_create")
        onNodeWithTag("picker_name").performTextInput(name)
        click("picker_add")
    }

    /** Assistent über die UI: Sportart → (Belag/Umgebung) → Spieler (Gäste per Name) → Format → Wer zählt? → Start. */
    private fun ComposeUiTest.runWizard(
        sport: Sport,
        doubles: Boolean,
        beforeStart: ComposeUiTest.() -> Unit = {},
    ) {
        click("tab_play", scroll = false)
        onNodeWithTag("wizard").assertExists()
        click("sport_${sport.name}")
        when (sport) {
            Sport.TENNIS -> click("surface_CLAY")
            Sport.BADMINTON -> click("wizard_next", scroll = false)
            else -> Unit
        }
        if (doubles && sport != Sport.PADEL) click("discipline_1")
        onNodeWithTag("wizard_next").assertIsNotEnabled()
        val names = if (doubles) listOf("Jonas", "Mia", "Tom") else listOf("Jonas")
        names.zip(listOf("slot_B_0", "slot_A_1", "slot_B_1")).forEach { (name, place) -> addGuest(name, place) }
        click("wizard_next", scroll = false)
        beforeStart()
        click("wizard_next", scroll = false)
        // ohne Uhr zählt dieses Handy (Director), „Meine Uhr“ ist gesperrt
        onNodeWithTag("scorer_THIS_PHONE").assertIsSelected()
        onNodeWithTag("scorer_MY_WATCH").assertIsNotEnabled()
        onNodeWithTag("wizard_start").assertIsEnabled().performClick()
        onNodeWithTag("live_sport_chip").assertExists()
        onNodeWithTag("panel_us").performClick()
    }

    @Test
    fun wizardTennisSinglesOnClay() =
        runComposeUiTest {
            val model = showApp()
            runWizard(Sport.TENNIS, doubles = false)
            val active = assertNotNull(model.live.active.value)
            assertEquals("TENNIS_SINGLES_BO3", active.setup.format.presetId)
            assertEquals(Surface.CLAY, active.setup.meta.surface)
            onNodeWithTag("score_us", useUnmergedTree = true).assertTextEquals("15")
        }

    @Test
    fun wizardPadelDoublesWithGoldenPointDefault() =
        runComposeUiTest {
            val model = showApp()
            runWizard(Sport.PADEL, doubles = true)
            val format =
                model.live.active.value!!
                    .setup.format
            assertEquals(Discipline.DOUBLES, format.discipline)
            assertEquals(DeuceMode.DECIDING_POINT, format.game!!.deuceMode)
            assertEquals(
                4,
                model.live.active.value!!
                    .setup.players.size,
            )
        }

    @Test
    fun wizardPickleballDoublesRallyToFifteen() =
        runComposeUiTest {
            val model = showApp()
            runWizard(Sport.PICKLEBALL, doubles = true) {
                click("points_1")
                click("pb_scoring_1")
            }
            val pb =
                model.live.active.value!!
                    .setup.format.pickleball!!
            assertEquals(15, pb.pointsToWin)
            assertEquals(PickleballScoring.RALLY, pb.scoring)
            onNodeWithTag("score_us", useUnmergedTree = true).assertTextEquals("1")
        }

    @Test
    fun wizardBadmintonSinglesUsesRallyFormat() =
        runComposeUiTest {
            val model = showApp()
            runWizard(Sport.BADMINTON, doubles = false) {
                // Erweitert: nur lesende Regeln + 11 Punkte (Best of 5)
                click("advanced_toggle")
                onNodeWithTag("advanced_content").assertExists()
            }
            val format =
                model.live.active.value!!
                    .setup.format
            assertEquals(Sport.BADMINTON, format.sport)
            assertTrue(format.rally!!.pointsToWin in listOf(15, 21))
            onNodeWithTag("score_us", useUnmergedTree = true).assertTextEquals("1")
        }

    @Test
    fun wizardTableTennisRequiresSetChoice() =
        runComposeUiTest {
            val model = showApp()
            runWizard(Sport.TABLE_TENNIS, doubles = true) {
                onNodeWithTag("sets_required").assertExists()
                onNodeWithTag("wizard_next").assertIsNotEnabled()
                click("sets_1")
            }
            val format =
                model.live.active.value!!
                    .setup.format
            assertEquals(5, format.match.bestOf)
            assertEquals("TABLE_TENNIS_DOUBLES_BO5", format.presetId)
        }

    @Test
    fun wizardBackAndCloseNavigation() =
        runComposeUiTest {
            val model = showApp()
            click("tab_play", scroll = false)
            click("sport_TENNIS")
            onNodeWithTag("surface_CLAY").assertExists()
            click("top_back", scroll = false)
            onNodeWithTag("sport_TENNIS").assertExists()
            click("top_back", scroll = false)
            onNodeWithTag("wizard").assertDoesNotExist()
            assertNull(model.live.active.value)
        }

    @Test
    fun openModeSetEndDialogThenMatchEndAndResult() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_OPEN.format(), "AAAA".repeat(6)) }
            model.navigator.push(Overlay.Live)
            showApp(model)
            onNodeWithTag("set_end_sheet").assertExists()
            onNodeWithTag("sheet_score").assertExists()
            onNodeWithTag("decision_match_tiebreak").assertExists()
            click("decision_yes")
            onNodeWithTag("set_end_sheet").assertDoesNotExist()
            assertEquals(
                MatchStatus.IN_PROGRESS,
                model.live.active.value!!
                    .state.status,
            )

            runOnIdle { model.live.play("BBBB".repeat(6)) }
            onNodeWithTag("set_end_sheet").assertExists()
            click("decision_no")
            // 1:1 im Modus Offen → Unentschieden, kein Folge-Match-Angebot
            onNodeWithTag("match_end_sheet").assertExists()
            onNodeWithTag("follow_up_yes").assertDoesNotExist()
            click("show_result")
            onNodeWithTag("result").assertExists()
            onNodeWithTag("result_compare").assertExists()
            assertNull(model.live.active.value, "Ergebnis ansehen schließt das Match")
            assertEquals(1, model.history.matches.value.size)
        }

    @Test
    fun matchTiebreakChoiceStartsMatchTiebreak() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.PADEL_OPEN.format(), "AAAA".repeat(6)) }
            model.navigator.push(Overlay.Live)
            showApp(model)
            click("decision_match_tiebreak")
            assertEquals(
                SetKind.MATCH_TIEBREAK,
                model.live.active.value!!
                    .state.current!!
                    .setKind,
            )
            onNodeWithTag("hint_pill").assertExists()
        }

    @Test
    fun bestOfMatchEndOffersLinkedFollowUpAndMiniScorebar() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.BADMINTON_1GAME_15.format(), "A".repeat(14)) }
            model.navigator.push(Overlay.Live)
            showApp(model)
            onNodeWithTag("panel_us").performClick()
            onNodeWithTag("match_end_sheet").assertExists()
            val first =
                model.live.active.value!!
                    .matchId
            click("follow_up_yes")
            onNodeWithTag("match_end_sheet").assertDoesNotExist()
            val follow = model.live.active.value!!
            assertEquals(first, follow.setup.previousMatchId)
            assertEquals(MatchMode.OPEN, follow.setup.format.match.mode)
            assertEquals(1, model.history.matches.value.size)

            onNodeWithTag("panel_them").performClick()
            click("live_minimize", scroll = false)
            onNodeWithTag("mini_scorebar").assertExists()
            onNodeWithTag("resume_card").assertExists()
            click("mini_scorebar", scroll = false)
            onNodeWithTag("live_sport_chip").assertExists()
        }

    @Test
    fun earlyEndNeedsConfirmation() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format(), "AB") }
            model.navigator.push(Overlay.Live)
            showApp(model)
            click("more_button", scroll = false)
            click("menu_end_match", scroll = false)
            onNodeWithTag("end_match_sheet").assertExists()
            click("end_cancel")
            assertEquals(
                MatchStatus.IN_PROGRESS,
                model.live.active.value!!
                    .state.status,
            )
            click("more_button", scroll = false)
            click("menu_end_match", scroll = false)
            click("end_retire_them")
            val result =
                model.live.active.value!!
                    .state.result!!
            assertEquals(Side.A, result.winner)
            onNodeWithTag("match_end_sheet").assertExists()
        }

    @Test
    fun serverCorrectionFromTheMenu() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format(), "A") }
            model.navigator.push(Overlay.Live)
            showApp(model)
            val opponent =
                model.live.active.value!!
                    .setup.lineup.b
                    .single()
            click("more_button", scroll = false)
            click("menu_correct_server", scroll = false)
            click("server_${opponent.value}")
            assertEquals(
                opponent,
                model.live.active.value!!
                    .state.serve!!
                    .player,
            )
            onNodeWithTag("panel_them_serve", useUnmergedTree = true).assertExists()
        }

    @Test
    fun quickStartLikeLastTimeAndFavorite() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.PICKLEBALL_SINGLES_11_SIDEOUT.format(), "A".repeat(11)) }
            val last =
                model.live.active.value!!
                    .setup
            model.live.dismiss()
            model.players.saveFavorite(model.setupFor(Presets.PADEL_GOLDEN_BO3.format()))
            showApp(model)
            click("quick_last")
            onNodeWithTag("live_sport_chip").assertExists()
            val started = model.live.active.value!!
            assertEquals(last.format, started.setup.format)
            assertEquals(last.lineup, started.setup.lineup)
            assertNull(started.setup.previousMatchId)

            // Laufendes Match (F22): Schnellstart bleibt sichtbar und startet ein weiteres Match parallel
            click("live_minimize", scroll = false)
            onNodeWithTag("quick_last").assertExists()
            click("quick_fav_0")
            assertEquals(
                Sport.PADEL,
                model.live.active.value!!
                    .setup.sport,
            )
            assertEquals(
                listOf(
                    started.matchId,
                    model.live.active.value!!
                        .matchId,
                ),
                model.live.openMatches.value
                    .map { it.matchId },
            )
            assertEquals(
                MatchStatus.IN_PROGRESS,
                model.live.openMatches.value
                    .first()
                    .state.status,
            )
        }

    @Test
    fun historyFiltersBySportAndOpensResult() =
        runComposeUiTest {
            val model = testModel().apply { DemoData.seed(this) }
            showApp(model)
            click("tab_history", scroll = false)
            repeat(3) { index ->
                onNodeWithTag("history").performScrollToNode(hasTestTag("history_item_$index"))
                onNodeWithTag("history_item_$index").assertExists()
            }
            click("filter_BADMINTON")
            onNodeWithTag("history_item_0").assertExists()
            onNodeWithTag("history_item_1").assertDoesNotExist()
            click("filter_TABLE_TENNIS")
            onNodeWithTag("history_empty").assertExists()
            click("filter_ALL")
            click("history_item_0")
            onNodeWithTag("result").assertExists()
            onNodeWithTag("result_sets").assertExists()
            click("top_back", scroll = false)
            onNodeWithTag("history").assertExists()
        }

    @Test
    fun deleteMatchFromResultNeedsConfirmation() =
        runComposeUiTest {
            val model = testModel().apply { DemoData.seed(this) }
            showApp(model)
            click("tab_history", scroll = false)
            val deleted =
                model.history.matches.value
                    .first()
                    .matchId
            click("history_item_0")
            click("result_delete", scroll = false)
            onNodeWithTag("delete_dialog").assertExists()
            click("delete_cancel", scroll = false)
            onNodeWithTag("delete_dialog").assertDoesNotExist()
            assertEquals(3, model.history.matches.value.size)

            click("result_delete", scroll = false)
            click("delete_confirm", scroll = false)
            onNodeWithTag("history").assertExists()
            assertEquals(2, model.history.matches.value.size)
            assertNull(model.history.record(deleted))
            repeat(2) { index ->
                onNodeWithTag("history").performScrollToNode(hasTestTag("history_item_$index"))
                onNodeWithTag("history_item_$index").assertExists()
            }
        }

    @Test
    fun friendsAndProfileArePlaceholders() =
        runComposeUiTest {
            showApp()
            click("tab_friends", scroll = false)
            onNodeWithTag("coming_soon_friends").assertExists()
            click("tab_profile", scroll = false)
            onNodeWithTag("coming_soon_profile").assertExists()
            click("tab_home", scroll = false)
            onNodeWithTag("home_new_match").assertExists()
        }
}
