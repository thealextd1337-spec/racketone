package eu.advantly.ui.phone.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.SetKind
import eu.advantly.presentation.match.LiveMatchPort
import eu.advantly.presentation.match.LiveRole
import eu.advantly.presentation.match.UpdateRequired
import eu.advantly.presentation.wizard.SetsChoice
import eu.advantly.presentation.wizard.TennisMatchChoice
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.app.Overlay
import eu.advantly.ui.phone.play
import eu.advantly.ui.phone.startWith
import eu.advantly.ui.phone.testModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LiveOperationsUiTest {
    private fun ComposeUiTest.show(model: AdvantlyAppModel) {
        model.navigator.push(Overlay.Live)
        setContent { Box(Modifier.size(412.dp, 892.dp)) { AdvantlyApp(model = model, darkTheme = true) } }
        waitForIdle()
    }

    @Test
    fun longPressRemovesOnlyThatSideAndSnackbarRestoresIt() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format(), "ABB") }
            show(model)
            onNodeWithTag("panel_us").performTouchInput { longClick() }
            runOnIdle {
                assertEquals(
                    0,
                    model.live.active.value!!
                        .state.current!!
                        .points.a,
                )
                assertEquals(
                    2,
                    model.live.active.value!!
                        .state.current!!
                        .points.b,
                )
                assertTrue(model.controller.undoNotice!!.pointRemoved)
            }
            onNodeWithTag("redo_button").performClick()
            runOnIdle {
                assertEquals(
                    1,
                    model.live.active.value!!
                        .state.current!!
                        .points.a,
                )
            }
        }

    @Test
    fun serveBarOnlyOffersValidOutcomesAndAllowsChangingAndClearing() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format()) }
            show(model)
            onNodeWithTag("panel_us").performClick()
            onNodeWithTag("serve_DOUBLE_FAULT").assertDoesNotExist()
            onNodeWithTag("serve_FIRST_SERVE_IN").performScrollTo().performClick().assertIsSelected()
            runOnIdle {
                assertEquals(
                    ServeOutcome.FIRST_SERVE_IN,
                    model.live.active.value!!
                        .lastPoint!!
                        .serve,
                )
            }
            onNodeWithTag("serve_SECOND_SERVE_IN").performScrollTo().performClick().assertIsSelected()
            onNodeWithTag("serve_SECOND_SERVE_IN").performClick()
            runOnIdle {
                assertNull(
                    model.live.active.value!!
                        .lastPoint!!
                        .serve,
                )
            }
            runOnIdle { model.controller.debounceEnabled = false }
            onNodeWithTag("panel_them").performClick()
            onNodeWithTag("serve_DOUBLE_FAULT").performScrollTo().performClick().assertIsSelected()
            runOnIdle {
                assertEquals(
                    ServeOutcome.DOUBLE_FAULT,
                    model.live.active.value!!
                        .lastPoint!!
                        .serve,
                )
            }
        }

    @Test
    fun expiredAndSupersededServePromptsCannotEditTheNextPoint() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format()) }
            show(model)
            onNodeWithTag("panel_us").performClick()
            val first = runOnIdle { assertNotNull(model.controller.servePromptId) }
            runOnIdle { model.controller.debounceEnabled = false }
            onNodeWithTag("panel_them").performClick()
            runOnIdle {
                assertFalse(model.controller.setServeInfo(first, ServeOutcome.FIRST_SERVE_IN))
                model.controller.dismissServePrompt(first)
                assertNotNull(model.controller.servePromptId)
            }
            mainClock.advanceTimeBy(6_100)
            waitForIdle()
            onNodeWithTag("serve_info").assertDoesNotExist()
        }

    @Test
    fun disabledServeTrackingShowsNoBar() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.PADEL_GOLDEN_BO3.format(), meta = MatchMeta(serveStats = false)) }
            show(model)
            onNodeWithTag("panel_us").performClick()
            onNodeWithTag("serve_info").assertDoesNotExist()
            runOnIdle { assertNull(model.controller.servePromptId) }
        }

    @Test
    fun doublesChoiceIncludesOtherTeamAndCountingRemainsPossible() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.PADEL_GOLDEN_BO3.format()) }
            show(model)
            val opponent =
                model.live.active.value!!
                    .setup.lineup.b
                    .last()
            onNodeWithTag("choose_server_${opponent.value}").performScrollTo().performClick().assertIsSelected()
            runOnIdle {
                assertEquals(
                    opponent,
                    model.live.active.value!!
                        .state.serve!!
                        .player,
                )
            }
            onNodeWithTag("panel_us").performClick()
            runOnIdle {
                assertEquals(
                    1,
                    model.live.active.value!!
                        .pointsPlayed,
                )
            }
            runOnIdle {
                model.live.play("AAA")
                model.controller.sync(model.live.active.value)
            }
            val choice =
                model.live.active.value!!
                    .state.serverChoice!!
            assertEquals(2, choice.game)
            assertEquals(2, choice.players.size)
            onNodeWithTag("choose_server_${choice.players.last().value}").performScrollTo().performClick().assertIsSelected()
            runOnIdle {
                model.live.play("A".repeat(20))
                model.controller.sync(model.live.active.value)
            }
            val nextSet =
                model.live.active.value!!
                    .state.serverChoice!!
            assertEquals(1, nextSet.game)
            assertEquals(4, nextSet.players.size)
            val nextOpponent =
                model.live.active.value!!
                    .setup.lineup.b
                    .first()
            onNodeWithTag("choose_server_${nextOpponent.value}").performScrollTo().performClick().assertIsSelected()
        }

    @Test
    fun setEndUsesPortOptionsAndAcceptsTiebreakSeven() =
        runComposeUiTest {
            val model = testModel()
            val wizard = model.wizard
            wizard.selectSport(Sport.TENNIS)
            wizard.addGuest("Jonas")
            wizard.setGamesPerSet(1)
            wizard.setSets(SetsChoice.Open)
            model.live.start(wizard.finish()!!)
            model.live.play("AAAAAAA")
            model.controller.sync(model.live.active.value)
            show(model)
            onNodeWithTag("panel_us").performClick()
            onNodeWithTag("serve_info").assertExists()
            onNodeWithTag("serve_FIRST_SERVE_IN").performClick()
            onNodeWithTag("decision_yes").assertExists()
            onNodeWithTag("decision_match_tiebreak").assertExists()
            onNodeWithTag("decision_tiebreak_7").performClick()
            runOnIdle {
                assertEquals(
                    SetKind.MATCH_TIEBREAK,
                    model.live.active.value!!
                        .state.current!!
                        .setKind,
                )
                assertFalse(model.controller.startNextSet(NextSetKind.REGULAR))
            }
        }

    @Test
    fun displayRoleHidesAllNewScoringControls() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.PADEL_GOLDEN_BO3.format(), "A") }
            val display =
                model.live.active.value!!
                    .copy(role = LiveRole.DISPLAY, revision = 999)
            // Der Controller erhält denselben Rollenwechsel wie über den Session-Flow.
            model.controller.sync(display)
            setContent {
                eu.advantly.ui.phone.designsystem.AdvantlyTheme(darkTheme = true) {
                    ServerChoiceBar(model.controller, display)
                    ServeInfoBar(model.controller, display)
                }
            }
            onNodeWithTag("server_choice").assertDoesNotExist()
            onNodeWithTag("serve_info").assertDoesNotExist()
            runOnIdle {
                assertFalse(model.controller.removeLastPointOf(Side.A))
                assertFalse(
                    model.controller.chooseServer(
                        display.setup.lineup.a
                            .first(),
                    ),
                )
                assertFalse(model.controller.setServeInfo(display.lastPoint!!.id, ServeOutcome.FIRST_SERVE_IN))
            }
        }

    @Test
    fun updateBannerAppearsWithoutAMatchAndInsideWizard() =
        runComposeUiTest {
            val update = MutableStateFlow<UpdateRequired?>(UpdateRequired.OTHER_DEVICE)
            val model =
                testModel { base ->
                    object : LiveMatchPort by base {
                        override val updateRequired = update
                    }
                }
            setContent { AdvantlyApp(model = model, darkTheme = true) }
            onNodeWithTag("update_required").assertExists()
            runOnIdle { model.onPlay() }
            onNodeWithTag("update_required").assertExists()
            runOnIdle { update.value = UpdateRequired.THIS_DEVICE }
            onNodeWithTag("update_required").assertExists()
            runOnIdle { update.value = null }
            onNodeWithTag("update_required").assertDoesNotExist()
        }

    @Test
    fun wizardHidesRegularFieldsForTiebreakAndPreservesServeOption() =
        runComposeUiTest {
            val model = testModel()
            model.onPlay()
            model.wizard.selectSport(Sport.TENNIS)
            model.wizard.selectSurface(null)
            model.wizard.addGuest("Jonas")
            model.wizard.next()
            setContent { Box(Modifier.size(412.dp, 892.dp)) { AdvantlyApp(model = model, darkTheme = true) } }
            onNodeWithTag("games_0").assertExists()
            onNodeWithTag("advanced_toggle").performScrollTo().performClick()
            onNodeWithTag("match_kind_TIEBREAK_7").performScrollTo().performClick()
            onNodeWithTag("games_0").assertDoesNotExist()
            onNodeWithTag("sets_0").assertDoesNotExist()
            onNodeWithTag("toggle_serve_stats").performScrollTo().performClick()
            runOnIdle {
                assertEquals(TennisMatchChoice.TIEBREAK_7, model.wizard.state.value.input.tennisMatch)
                assertFalse(
                    model.wizard
                        .finish()!!
                        .meta.serveStats,
                )
            }
        }
}
