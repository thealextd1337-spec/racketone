package eu.advantly.ui.phone.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.SetKind
import eu.advantly.presentation.live.HapticCue
import eu.advantly.presentation.match.LiveMatchPort
import eu.advantly.presentation.match.LiveRole
import eu.advantly.presentation.match.UpdateRequired
import eu.advantly.presentation.wizard.SetsChoice
import eu.advantly.presentation.wizard.TennisMatchChoice
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.app.Overlay
import eu.advantly.ui.phone.latency.InputClockSource
import eu.advantly.ui.phone.platform.Haptics
import eu.advantly.ui.phone.play
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.dialog_no_finish
import eu.advantly.ui.phone.resources.dialog_yes
import eu.advantly.ui.phone.resources.live_restore_point
import eu.advantly.ui.phone.startWith
import eu.advantly.ui.phone.testModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LiveOperationsUiTest {
    private fun ComposeUiTest.show(
        model: AdvantlyAppModel,
        skipChoice: Boolean = true,
    ) {
        model.navigator.push(Overlay.Live)
        setContent { Box(Modifier.size(412.dp, 892.dp)) { AdvantlyApp(model = model, darkTheme = true) } }
        waitForIdle()
        if (skipChoice) runOnIdle { model.controller.dismissServerChoice() }
        waitForIdle()
    }

    @Test
    fun undoInALaterSetReviewsTheMarkedChoiceAndRoleLossClosesIt() {
        val model = testModel().apply { startWith(Presets.PADEL_OPEN.format(), "A".repeat(24)) }
        val c = model.controller
        c.setChoiceInteractive(true)
        assertTrue(c.startNextSet(NextSetKind.REGULAR))
        assertNotNull(c.serverChoicePresentation)
        val first = c.serverChoicePresentation!!
        assertTrue(c.chooseServer(first.players.last(), first))
        c.debounceEnabled = false
        c.onTap(Side.A, null)
        c.undo()
        assertEquals(
            1,
            c.snapshot!!
                .state.current
                ?.setIndex,
        )
        assertNotNull(c.serverChoicePresentation?.chosen)
        c.sync(c.snapshot!!.copy(role = LiveRole.DISPLAY, revision = c.snapshot!!.revision + 1))
        assertNull(c.serverChoicePresentation)
    }

    @Test
    fun correctionAfterOpeningChoiceUsesForwardPath() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.PADEL_GOLDEN_BO3.format()) }
            val controller = model.controller
            val first = controller.snapshot!!.serverChoiceContext!!
            assertTrue(controller.chooseServer(first.players.last(), first))
            val snap = controller.snapshot!!
            assertFalse(snap.canCorrectServerRetroactively)
            var closed = false
            setContent {
                eu.advantly.ui.phone.designsystem.AdvantlyTheme(darkTheme = true) {
                    Box { CorrectServerSheet(controller, snap, onClose = { closed = true }) }
                }
            }
            onNodeWithTag("server_${first.players.first().value}").performScrollTo().performClick()
            assertTrue(closed)
            assertEquals(
                first.players.first(),
                controller.snapshot!!
                    .state.serve
                    ?.player,
            )
        }

    @Test
    fun automaticChoiceBlocksInputAndLaterStaysLocalUntilTheNextWindow() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.PADEL_GOLDEN_BO3.format()) }
            show(model, skipChoice = false)
            onNodeWithTag("server_choice_sheet").assertExists()
            onNodeWithTag("panel_us").assertDoesNotExist()
            val first = model.controller.serverChoicePresentation!!
            runOnIdle {
                model.controller.onTap(Side.A, null)
                model.controller.undo()
                assertEquals(
                    0,
                    model.live.active.value!!
                        .pointsPlayed,
                )
            }
            onNodeWithTag("server_choice_later").performScrollTo().performClick()
            runOnIdle {
                model.controller.sync(model.live.active.value)
                model.controller.setChoiceInteractive(false)
                model.controller.setChoiceInteractive(true)
                assertNull(model.controller.serverChoicePresentation)
                model.live.play("AAAA")
                model.controller.sync(model.live.active.value)
                assertNotNull(model.controller.serverChoicePresentation)
                val second = model.controller.serverChoicePresentation!!
                val revision =
                    model.live.active.value!!
                        .revision
                assertFalse(model.controller.chooseServer(second.players.first(), first))
                assertEquals(
                    revision,
                    model.live.active.value!!
                        .revision,
                )
                model.controller.openServerChoice()
            }
            val second = model.controller.serverChoicePresentation!!
            onNodeWithTag("sheet_choose_server_${second.players.last().value}").performScrollTo().performClick()
            onNodeWithTag("server_choice_sheet").assertDoesNotExist()
            runOnIdle {
                assertEquals(
                    4,
                    model.live.active.value!!
                        .pointsPlayed,
                )
                assertEquals(
                    second.players.last(),
                    model.live.active.value!!
                        .state.serve
                        ?.player,
                )
            }
        }

    @Test
    fun rejectedCorrectionStaysOpenAndShowsTheFailure() =
        runComposeUiTest {
            val model =
                testModel { store ->
                    object : LiveMatchPort by store {
                        override fun correctServer(
                            player: eu.advantly.core.model.PlayerId,
                            retroactive: Boolean,
                        ) = false
                    }
                }.apply { startWith(Presets.TENNIS_SINGLES_BO3.format()) }
            var closed = false
            val snap = model.controller.snapshot!!
            setContent {
                eu.advantly.ui.phone.designsystem.AdvantlyTheme(darkTheme = true) {
                    Box { CorrectServerSheet(model.controller, snap, onClose = { closed = true }) }
                }
            }
            onNodeWithTag("server_${snap.setup.lineup.b.first().value}").performScrollTo().performClick()
            onNodeWithTag("correct_server_rejected").assertExists()
            assertFalse(closed)
        }

    @Test
    fun longPressRemovesOnlyThatSideAndSnackbarRestoresIt() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format(), "ABB") }
            show(model)
            onNodeWithTag("panel_us").performTouchInput { longClick(durationMillis = 1_100) }
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
            onNodeWithTag("redo_button").assertTextEquals(getString(Res.string.live_restore_point)).performClick()
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
            assertEquals(Role.RadioButton, onNodeWithTag("serve_FIRST_SERVE_IN").fetchSemanticsNode().config[SemanticsProperties.Role])
            assertEquals(
                LiveRegionMode.Polite,
                onNodeWithTag("serve_info_title", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
            )
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
            mainClock.advanceTimeBy(10_100)
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
                model.controller.dismissServerChoice()
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
                model.controller.dismissServerChoice()
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
            mainClock.advanceTimeBy(11_000)
            waitForIdle()
            onNodeWithTag("serve_info").assertExists()
            onNodeWithTag("serve_FIRST_SERVE_IN").performClick()
            onNodeWithTag("decision_yes").assertExists()
            onNodeWithTag("decision_match_tiebreak").assertExists()
            runOnIdle { assertNotNull(model.controller.servePromptId) }
            onNodeWithTag("decision_tiebreak_7").performClick()
            onNodeWithTag("serve_info").assertDoesNotExist()
            runOnIdle {
                assertEquals(
                    SetKind.MATCH_TIEBREAK,
                    model.live.active.value!!
                        .state.current!!
                        .setKind,
                )
                assertNull(model.controller.servePromptId)
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
            onNodeWithTag("advanced_content").assertDoesNotExist()
            onNodeWithTag("match_kind_TIEBREAK_7").performScrollTo().performClick()
            onNodeWithTag("games_0").assertDoesNotExist()
            onNodeWithTag("sets_0").assertDoesNotExist()
            onNodeWithTag("advanced_toggle").performScrollTo().performClick()
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

    /** Halte-Fortschritt (Feldtest 2026-10-16): läuft von rechts nach links, voll genau beim Auslösen, sonst zurück. */
    @Test
    fun holdProgressIsFullWhenTheRemovalTriggersAndResetsOnEarlyRelease() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format(), "ABB") }
            show(model)

            fun progress() = onNodeWithTag("panel_them").fetchSemanticsNode().config[HoldProgressKey]
            mainClock.autoAdvance = false
            onNodeWithTag("panel_them").performTouchInput { down(center) }
            mainClock.advanceTimeBy(500)
            assertEquals(0.5f, progress(), 0.08f)
            onNodeWithTag("panel_them").performTouchInput { up() }
            mainClock.advanceTimeBy(400)
            assertEquals(0f, progress(), 0.001f, "vorzeitig losgelassen: zurückgesetzt")
            // Handy wie bisher: kürzer als 1 s gehalten zählt beim Loslassen als Tap – abgezogen wird nichts
            assertEquals(
                3,
                model.live.active.value!!
                    .state.current!!
                    .points.b,
                "nichts abgezogen",
            )
            onNodeWithTag("panel_them").performTouchInput { down(center) }
            mainClock.advanceTimeBy(1_050)
            assertEquals(1f, progress(), 0.001f, "ausgelöst und voll")
            assertEquals(
                2,
                model.live.active.value!!
                    .state.current!!
                    .points.b,
            )
            onNodeWithTag("panel_them").performTouchInput { up() }
            mainClock.autoAdvance = true
            waitForIdle()
        }

    @Test
    fun holdingWithoutRemovablePointCountsOnRelease() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format()) }
            show(model)
            onNodeWithTag("panel_us").performTouchInput { longClick(durationMillis = 1_200) }
            runOnIdle {
                assertEquals(
                    1,
                    model.live.active.value!!
                        .pointsPlayed,
                )
            }
        }

    @Test
    fun holdingForLessThanOneSecondCountsInsteadOfRemoving() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format(), "A") }
            show(model)
            onNodeWithTag("panel_us").performTouchInput { longClick(durationMillis = 900) }
            runOnIdle {
                assertEquals(
                    2,
                    model.live.active.value!!
                        .pointsPlayed,
                )
            }
        }

    @Test
    fun talkBackLongClickRemovesPoint() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format(), "AB") }
            show(model)
            onNodeWithTag("panel_us").performSemanticsAction(SemanticsActions.OnLongClick)
            runOnIdle {
                assertEquals(
                    0,
                    model.live.active.value!!
                        .state.current!!
                        .points.a,
                )
                assertEquals(
                    1,
                    model.live.active.value!!
                        .state.current!!
                        .points.b,
                )
            }
        }

    @Test
    fun servePromptAndItsTimeoutKeepBothPanelBoundsStable() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.PADEL_GOLDEN_BO3.format()) }
            show(model)
            val us = onNodeWithTag("panel_us").getBoundsInRoot()
            val them = onNodeWithTag("panel_them").getBoundsInRoot()
            onNodeWithTag("panel_us").performClick()
            onNodeWithTag("serve_info").assertExists()
            assertEquals(us, onNodeWithTag("panel_us").getBoundsInRoot())
            assertEquals(them, onNodeWithTag("panel_them").getBoundsInRoot())
            mainClock.advanceTimeBy(9_000)
            waitForIdle()
            onNodeWithTag("serve_info").assertExists()
            mainClock.advanceTimeBy(1_100)
            waitForIdle()
            onNodeWithTag("serve_info").assertDoesNotExist()
            assertEquals(us, onNodeWithTag("panel_us").getBoundsInRoot())
            assertEquals(them, onNodeWithTag("panel_them").getBoundsInRoot())
        }

    @Test
    fun nonTennisSetEndUsesYesAndNoFinish() {
        for ((sport, points) in listOf(Sport.BADMINTON to 15, Sport.TABLE_TENNIS to 11, Sport.PICKLEBALL to 11)) {
            runComposeUiTest {
                val model = testModel()
                model.wizard.selectSport(sport)
                model.wizard.setSets(SetsChoice.Open)
                model.wizard.setPoints(points)
                repeat(model.wizard.state.value.input.discipline.playersPerSide * 2 - 1) { model.wizard.addGuest("Guest $it") }
                model.live.start(model.wizard.finish()!!)
                model.live.play("A".repeat(points))
                model.controller.sync(model.live.active.value)
                show(model)
                onNodeWithTag("set_end_sheet").assertExists()
                onNodeWithTag("decision_yes").assertTextEquals(getString(Res.string.dialog_yes)).performScrollTo().performClick()
                onNodeWithTag("set_end_sheet").assertDoesNotExist()
                runOnIdle {
                    var rallies = 0
                    while (model.live.active.value!!
                            .nextSetOptions
                            .isEmpty()
                    ) {
                        check(++rallies <= 40)
                        assertTrue(model.live.recordPoint(Side.A))
                    }
                    model.controller.sync(model.live.active.value)
                }
                onNodeWithTag("decision_no").assertTextEquals(getString(Res.string.dialog_no_finish)).performScrollTo().performClick()
                onNodeWithTag("match_end_sheet").assertExists()
            }
        }
    }

    @Test
    fun serveMetadataKeepsRestoreAndAddsNoLatencyInput() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.PADEL_GOLDEN_BO3.format(), "AAAAB") }
            val controller = model.controller
            assertTrue(controller.removeLastPointOf(Side.B))
            val notice = controller.undoNotice
            controller.resetMeasurements()
            val current = controller.snapshot!!
            assertTrue(
                controller.chooseServer(
                    current.state.serverChoice!!
                        .players
                        .last(),
                ),
            )
            assertEquals(notice, controller.undoNotice)
            assertTrue(controller.setServeInfo(current.lastPoint!!.id, ServeOutcome.FIRST_SERVE_IN))
            assertEquals(notice, controller.undoNotice)
            controller.tracker.onDrawn(controller.generation, Long.MAX_VALUE / 2)
            controller.tracker.onNextFrame(Long.MAX_VALUE / 2 + 1)
            assertEquals(0, controller.tracker.summary(InputClockSource.Handler).samples)
            controller.redo()
            assertEquals(5, controller.snapshot!!.pointsPlayed)
        }

    @Test
    fun metadataChangesDoNotReplayTheLastPointFlash() {
        val model = testModel().apply { startWith(Presets.PADEL_GOLDEN_BO3.format(), "AAA") }
        val controller = model.controller
        controller.debounceEnabled = false
        controller.onTap(Side.A, null)
        assertEquals(Side.A, controller.flashSide)
        val choice = assertNotNull(controller.snapshot!!.state.serverChoice)
        assertTrue(controller.chooseServer(choice.players.last()))
        assertNull(controller.flashSide)
        assertEquals(4, controller.snapshot!!.pointsPlayed)
        controller.onTap(Side.B, null)
        assertEquals(Side.B, controller.flashSide)
        assertTrue(controller.setServeInfo(controller.snapshot!!.lastPoint!!.id, ServeOutcome.SECOND_SERVE_IN))
        assertNull(controller.flashSide)
        assertEquals(5, controller.snapshot!!.pointsPlayed)
    }

    @Test
    fun doublesWithoutServeStatsKeepOperationsSpaceAcrossChoiceWindows() {
        for (format in listOf(Presets.TENNIS_BO3_MTB10.format(Discipline.DOUBLES), Presets.PADEL_GOLDEN_BO3.format())) {
            runComposeUiTest {
                val model = testModel().apply { startWith(format, meta = MatchMeta(serveStats = false)) }
                show(model)
                val slot = onNodeWithTag("live_operations_slot").getBoundsInRoot()
                val us = onNodeWithTag("panel_us").getBoundsInRoot()
                val them = onNodeWithTag("panel_them").getBoundsInRoot()
                onNodeWithTag("panel_us").performClick()
                onNodeWithTag("server_choice").assertDoesNotExist()
                assertEquals(slot, onNodeWithTag("live_operations_slot").getBoundsInRoot())
                assertEquals(us.top, onNodeWithTag("panel_us").getBoundsInRoot().top)
                assertEquals(them.bottom, onNodeWithTag("panel_them").getBoundsInRoot().bottom)
                runOnIdle {
                    model.live.play("AAA")
                    model.controller.sync(model.live.active.value)
                    model.controller.dismissServerChoice()
                }
                onNodeWithTag("server_choice").assertExists()
                assertEquals(slot, onNodeWithTag("live_operations_slot").getBoundsInRoot())
                assertEquals(us.top, onNodeWithTag("panel_us").getBoundsInRoot().top)
                assertEquals(them.bottom, onNodeWithTag("panel_them").getBoundsInRoot().bottom)
                runOnIdle {
                    model.live.play("A".repeat(20))
                    model.controller.sync(model.live.active.value)
                    model.controller.dismissServerChoice()
                }
                onNodeWithTag("server_choice").assertExists()
                assertEquals(slot, onNodeWithTag("live_operations_slot").getBoundsInRoot())
                assertEquals(us.top, onNodeWithTag("panel_us").getBoundsInRoot().top)
                assertEquals(them.bottom, onNodeWithTag("panel_them").getBoundsInRoot().bottom)
            }
        }
    }

    @Test
    fun failedRemovalHasRejectedHaptic() {
        val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format()) }
        val cues = mutableListOf<HapticCue>()
        model.controller.haptics = Haptics { cues += it }
        assertFalse(model.controller.removeLastPointOf(Side.A))
        assertEquals(listOf(HapticCue.Rejected), cues)
    }

    @Test
    fun autoBenchmarkDoesNotOpenServePrompts() =
        runBlocking {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format()) }
            model.controller.debounceEnabled = false
            model.controller.runAutoBenchmark({ true }, points = 4, intervalMillis = 1)
            assertEquals(4, model.controller.countedPoints)
            assertNull(model.controller.servePromptId)
        }
}
