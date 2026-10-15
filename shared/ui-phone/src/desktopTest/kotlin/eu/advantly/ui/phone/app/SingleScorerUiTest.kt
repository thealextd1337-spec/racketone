package eu.advantly.ui.phone.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Presets
import eu.advantly.device.link.CompanionState
import eu.advantly.device.link.testing.FakeCompanionDetector
import eu.advantly.presentation.match.LiveRole
import eu.advantly.presentation.match.ScorerOption
import eu.advantly.presentation.match.StartOptions
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.SessionRig
import eu.advantly.ui.phone.setupFor
import eu.advantly.ui.phone.testModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Single-Scorer in der Phone-UI mit echter Session (Handy + Uhr, W1-I): Assistent „Wer zählt?“, Anzeige-Modus,
 * „Stand vor X s“, Übernahme mit Bestätigung, „N Eingaben nicht übernommen“, Schnellstart-Bestätigung, Umbenennen.
 */
@OptIn(ExperimentalTestApi::class)
class SingleScorerUiTest {
    private fun ComposeUiTest.showApp(model: AdvantlyAppModel) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.size(412.dp, 892.dp)) { AdvantlyApp(model = model, darkTheme = true) }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.click(tag: String) = onNodeWithTag(tag).performScrollTo().performClick()

    private fun ComposeUiTest.addGuest(
        name: String,
        place: String,
    ) {
        click(place)
        click("player_create")
        onNodeWithTag("picker_name").performTextInput(name)
        click("picker_add")
    }

    private fun ComposeUiTest.description(tag: String) =
        onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    @Test
    fun wizardDefaultsToMyWatchAndPhoneBecomesDisplay() =
        runComposeUiTest {
            val rig = SessionRig()
            showApp(rig.model)
            onNodeWithTag("tab_play").performClick()
            click("sport_TENNIS")
            click("surface_CLAY")
            addGuest("Jonas", "slot_B_0")
            onNodeWithTag("wizard_next").performClick()
            onNodeWithTag("wizard_next").performClick()
            // Schritt „Wer zählt?“: Uhr verbunden → Standard „Meine Uhr“, Workout an
            onNodeWithTag("scorer_MY_WATCH").assertIsSelected()
            assertTrue(rig.model.wizard.state.value.input.watchWorkout)
            assertTrue(
                onNodeWithTag("wizard_summary_scorer").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString().let {
                    it.contains("Meine Uhr") || it.contains("My watch")
                },
            )
            onNodeWithTag("wizard_start").performClick()

            val active = assertNotNull(rig.store.active.value)
            assertEquals(LiveRole.DISPLAY, active.role)
            assertEquals(true, rig.store.watchWorkout(active.matchId))
            // Anzeige: deutlich markiert, keine Tap-Flächen, kein Undo, „Zählen übernehmen“
            onNodeWithTag("display_banner").assertExists()
            val banner = description("display_banner")
            assertTrue(banner.contains("Uhr von Alex") || banner.contains("Alex’s watch"), banner)
            onNodeWithTag("panel_us").assertHasNoClickAction()
            onNodeWithTag("undo_button").assertDoesNotExist()
            onNodeWithTag("more_button").assertDoesNotExist()
            onNodeWithTag("takeover_button").assertExists()
            val pill = description("sync_pill")
            assertTrue(pill == "Zähler verbunden" || pill == "Scorer connected", pill)

            // Die Uhr zählt, das Handy zeigt an
            runOnIdle { rig.watchPlays("AA") }
            onNodeWithTag("score_us", useUnmergedTree = true).assertTextEquals("30")
        }

    @Test
    fun withoutWatchTheWizardOnlyOffersThisPhone() =
        runComposeUiTest {
            val rig = SessionRig(watchOnline = false)
            showApp(rig.model)
            onNodeWithTag("tab_play").performClick()
            click("sport_PICKLEBALL")
            listOf("Jonas", "Mia", "Tom").zip(listOf("slot_B_0", "slot_A_1", "slot_B_1")).forEach { (name, place) ->
                addGuest(name, place)
            }
            onNodeWithTag("wizard_next").performClick()
            onNodeWithTag("wizard_next").performClick()
            onNodeWithTag("scorer_MY_WATCH").assertIsNotEnabled()
            onNodeWithTag("scorer_THIS_PHONE").assertIsSelected()
            click("toggle_watch_workout")
            assertFalse(rig.model.wizard.state.value.input.watchWorkout)
            onNodeWithTag("wizard_start").performClick()
            val active = assertNotNull(rig.store.active.value)
            assertEquals(LiveRole.SCORER, active.role)
            assertEquals(false, rig.store.watchWorkout(active.matchId))
            onNodeWithTag("panel_us").assertHasClickAction()
            onNodeWithTag("display_banner").assertDoesNotExist()
        }

    @Test
    fun stalenessThenConfirmedTakeOver() =
        runComposeUiTest {
            val rig = SessionRig()
            rig.startWatchScoredTennis()
            rig.watchPlays("A")
            rig.model.navigator.push(Overlay.Live)
            showApp(rig.model)
            onNodeWithTag("stale_hint").assertDoesNotExist()

            runOnIdle {
                rig.offline()
                rig.settle(10_000)
            }
            mainClock.advanceTimeBy(1_100)
            val pill = description("sync_pill")
            assertTrue(pill.startsWith("Stand vor") || pill.startsWith("Updated"), pill)
            onNodeWithTag("stale_hint").assertDoesNotExist()

            runOnIdle { rig.settle(25_000) }
            mainClock.advanceTimeBy(1_100)
            onNodeWithTag("stale_hint").assertExists()

            // Abbrechen ändert nichts
            onNodeWithTag("stale_takeover").performClick()
            onNodeWithTag("takeover_dialog").assertExists()
            onNodeWithTag("takeover_cancel").performClick()
            onNodeWithTag("takeover_dialog").assertDoesNotExist()
            assertEquals(
                LiveRole.DISPLAY,
                rig.store.active.value!!
                    .role,
            )

            // Übernehmen über die Fußleiste
            onNodeWithTag("takeover_button").performClick()
            onNodeWithTag("takeover_confirm").performClick()
            onNodeWithTag("takeover_dialog").assertDoesNotExist()
            assertEquals(
                LiveRole.SCORER,
                rig.store.active.value!!
                    .role,
            )
            onNodeWithTag("display_banner").assertDoesNotExist()
            onNodeWithTag("stale_hint").assertDoesNotExist()
            onNodeWithTag("panel_them").assertHasClickAction().performClick()
            onNodeWithTag("score_them", useUnmergedTree = true).assertTextEquals("15")
            onNodeWithTag("undo_button").assertExists()
        }

    @Test
    fun lateInputsOfTheOldScorerAreReported() =
        runComposeUiTest {
            val rig = SessionRig()
            rig.startWatchScoredTennis()
            rig.model.navigator.push(Overlay.Live)
            showApp(rig.model)
            runOnIdle {
                rig.offline()
                rig.settle(31_000)
                assertTrue(rig.model.controller.takeOverScoring())
                // die Uhr zählt offline weiter …
                rig.watchPlays("AA")
                // … und liefert nach dem Wiederverbinden nach
                rig.online()
                rig.settle()
            }
            onNodeWithTag("rejected_notice").assertExists()
            val text = onNodeWithTag("rejected_notice").fetchSemanticsNode()
            assertNotNull(text)
            onNodeWithTag("score_us", useUnmergedTree = true).assertTextEquals("0")
            onNodeWithTag("rejected_ok").performClick()
            onNodeWithTag("rejected_notice").assertDoesNotExist()
        }

    /** Feldtest W1-I: „Meine Uhr“ wird im Schritt „Wer zählt?“ wählbar, ohne den Assistenten zu verlassen. */
    @Test
    fun watchBecomesSelectableInsideTheWizard() =
        runComposeUiTest {
            val rig = SessionRig(watchOnline = false, companion = FakeCompanionDetector(CompanionState.APP_NOT_INSTALLED))
            showApp(rig.model)
            onNodeWithTag("tab_play").performClick()
            click("sport_PADEL")
            listOf("Jonas", "Mia", "Tom").zip(listOf("slot_B_0", "slot_A_1", "slot_B_1")).forEach { (name, place) ->
                addGuest(name, place)
            }
            onNodeWithTag("wizard_next").performClick()
            val refreshesBefore = rig.companion.refreshes
            onNodeWithTag("wizard_next").performClick()
            assertTrue(rig.companion.refreshes > refreshesBefore, "Betreten von „Wer zählt?“ prüft neu")
            onNodeWithTag("scorer_MY_WATCH").assertIsNotEnabled()
            val hint = onNodeWithTag("scorer_MY_WATCH").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
            assertTrue(hint.contains("nicht installiert") || hint.contains("not installed"), hint)
            // „Erneut prüfen“ fragt den Stand neu ab
            val beforeRecheck = rig.companion.refreshes
            onNodeWithTag("scorer_recheck").performClick()
            assertEquals(beforeRecheck + 1, rig.companion.refreshes)
            // Nutzer installiert/öffnet die Uhr-App: der Capability-Listener meldet es, der Schritt zieht sofort nach
            runOnIdle { rig.companion.state = CompanionState.APP_INSTALLED }
            onNodeWithTag("scorer_MY_WATCH").assertIsEnabled().assertIsSelected()
            onNodeWithTag("scorer_recheck").assertDoesNotExist()
        }

    /** Start mit „Meine Uhr“ ohne Verbindung: „Warte auf Uhr …“, die Uhr meldet sich später und zählt. */
    @Test
    fun waitingForTheWatchUntilItConnectsAndScores() =
        runComposeUiTest {
            val rig = SessionRig(watchOnline = false, companion = FakeCompanionDetector(CompanionState.APP_INSTALLED))
            rig.store.start(rig.model.setupFor(Presets.TENNIS_SINGLES_BO3.format()), StartOptions(ScorerOption.MY_WATCH))
            rig.model.controller.sync(rig.store.active.value)
            rig.model.navigator.push(Overlay.Live)
            showApp(rig.model)
            onNodeWithTag("waiting_for_scorer").assertExists()
            onNodeWithTag("panel_us").assertHasNoClickAction()
            runOnIdle {
                rig.online()
                rig.watchPlays("A")
            }
            onNodeWithTag("waiting_for_scorer").assertDoesNotExist()
            onNodeWithTag("display_banner").assertExists()
            onNodeWithTag("score_us", useUnmergedTree = true).assertTextEquals("15")
        }

    /** „Doch am Handy zählen“ beendet das Warten sofort. */
    @Test
    fun countOnThePhoneInsteadOfWaiting() =
        runComposeUiTest {
            val rig = SessionRig(watchOnline = false, companion = FakeCompanionDetector(CompanionState.APP_INSTALLED))
            rig.store.start(rig.model.setupFor(Presets.TENNIS_SINGLES_BO3.format()), StartOptions(ScorerOption.MY_WATCH))
            rig.model.controller.sync(rig.store.active.value)
            rig.model.navigator.push(Overlay.Live)
            showApp(rig.model)
            onNodeWithTag("count_on_phone").performClick()
            onNodeWithTag("waiting_for_scorer").assertDoesNotExist()
            onNodeWithTag("panel_us").assertHasClickAction().performClick()
            onNodeWithTag("score_us", useUnmergedTree = true).assertTextEquals("15")
        }

    /** Review W1-I #8: „Eingaben ansehen“ zeigt die verworfenen Eingaben (Zeit, Gerät, Art, Seite). */
    @Test
    fun rejectedInputsCanBeViewed() =
        runComposeUiTest {
            val rig = SessionRig()
            rig.startWatchScoredTennis()
            rig.model.navigator.push(Overlay.Live)
            showApp(rig.model)
            runOnIdle {
                rig.offline()
                rig.settle(31_000)
                assertTrue(rig.model.controller.takeOverScoring())
                rig.watchPlays("AB")
                rig.online()
                rig.settle()
            }
            onNodeWithTag("rejected_view").performClick()
            onNodeWithTag("rejected_sheet").assertExists()
            val first = onNodeWithTag("rejected_item_0").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
            assertTrue((first.contains("Uhr") || first.contains("Watch")) && first.contains("Alex"), first)
            onNodeWithTag("rejected_item_1").assertExists()
            onNodeWithTag("rejected_close").performScrollTo().performClick()
            onNodeWithTag("rejected_sheet").assertDoesNotExist()
            onNodeWithTag("rejected_notice").assertDoesNotExist()
        }

    /** Review W1-I #2: Der Übernahme-Hinweis hat eigenen Platz und verdeckt die Flächen nicht. */
    @Test
    fun staleHintNeverCoversTheScore() =
        runComposeUiTest {
            val rig = SessionRig()
            rig.startWatchScoredTennis()
            rig.model.navigator.push(Overlay.Live)
            showApp(rig.model)
            runOnIdle {
                rig.offline()
                rig.settle(40_000)
            }
            mainClock.advanceTimeBy(1_100)
            val hint = onNodeWithTag("stale_hint").fetchSemanticsNode().boundsInRoot
            val panel = onNodeWithTag("panel_us").fetchSemanticsNode().boundsInRoot
            assertTrue(hint.bottom <= panel.top, "Hinweis $hint überlappt Fläche $panel")
            onNodeWithTag("score_us", useUnmergedTree = true).assertIsDisplayed()
        }

    /** Review W1-I #5: Nach Rollenverlust bleibt kein „Wiederholen“ aus der alten Rolle stehen. */
    @Test
    fun undoSnackbarDisappearsWhenTheRoleIsLost() =
        runComposeUiTest {
            val rig = SessionRig()
            rig.startWatchScoredTennis()
            rig.offline()
            rig.settle(31_000)
            rig.store.takeOverScoring()
            rig.model.controller.sync(rig.store.active.value)
            rig.model.navigator.push(Overlay.Live)
            showApp(rig.model)
            onNodeWithTag("panel_us").performClick()
            onNodeWithTag("undo_button").performClick()
            onNodeWithTag("undo_snackbar").assertExists()
            // die Uhr übernimmt wieder
            runOnIdle {
                rig.online()
                rig.settle()
                assertTrue(rig.watch.takeOverScoring())
                rig.settle()
            }
            onNodeWithTag("display_banner").assertExists()
            onNodeWithTag("undo_snackbar").assertDoesNotExist()
            onNodeWithTag("redo_button").assertDoesNotExist()
        }

    /** F22: Neues Match, während die Uhr zählt – beide laufen, die Übersicht kennzeichnet das Uhr-Match. */
    @Test
    fun parallelMatchKeepsTheWatchMatchRunning() =
        runComposeUiTest {
            val rig = SessionRig()
            val onWatch = rig.startWatchScoredTennis()
            rig.watchPlays("AA")
            showApp(rig.model)
            runOnIdle { rig.model.startMatch(rig.model.setupFor(Presets.PADEL_GOLDEN_BO3.format()), StartOptions(ScorerOption.THIS_PHONE)) }
            rig.settle()
            onNodeWithTag("live_sport_chip").assertExists()
            onNodeWithTag("switch_match_watch_0", useUnmergedTree = true).assertExists()
            onNodeWithTag("live_minimize").performClick()
            onNodeWithTag("mini_scorebar_more", useUnmergedTree = true).assertExists()
            val role = onNodeWithTag("resume_card").fetchSemanticsNode().config.toString()
            assertTrue(role.contains("Deine Uhr zählt") || role.contains("Your watch scores"), role)
            click("resume_card")
            assertEquals(
                onWatch,
                rig.store.active.value!!
                    .matchId,
            )
            onNodeWithTag("display_banner").assertExists()
            assertEquals(
                onWatch,
                rig.watch.state.value.match
                    ?.id,
                "Uhr bleibt bei ihrem Match",
            )
        }

    /** F21: Am Handy (Anzeige) beenden – Übernahme erklärt, bestätigt, die Uhr zeigt das Ende. */
    @Test
    fun displayEndsTheWatchMatchAfterConfirmation() =
        runComposeUiTest {
            val rig = SessionRig()
            rig.startWatchScoredTennis()
            rig.watchPlays("AAB")
            rig.model.navigator.push(eu.advantly.ui.phone.app.Overlay.Live)
            showApp(rig.model)
            onNodeWithTag("display_end_button").performClick()
            onNodeWithTag("end_takeover_note").assertExists()
            click("end_stop")
            val text = onNodeWithTag("end_confirm_text").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
            assertTrue(text.contains("30") && (text.contains("gewonnen") || text.contains("won")), text)
            onNodeWithTag("end_confirm_back").performClick()
            assertEquals(
                LiveRole.DISPLAY,
                rig.store.active.value!!
                    .role,
                "Zurück ändert nichts",
            )
            click("end_stop")
            onNodeWithTag("end_confirm").performClick()
            rig.settle()
            onNodeWithTag("match_end_sheet").assertExists()
            val onTheWatch = rig.watch.state.value.match!!
            assertEquals(eu.advantly.core.scoring.ResultReason.STOPPED, onTheWatch.state.result!!.reason)
        }

    /** F21: Abbrechen am Zähler – bleibt im Verlauf „Abgebrochen“, zählt nicht in die Bilanz, lässt sich löschen. */
    @Test
    fun abandonedMatchStaysInHistoryWithoutCounting() =
        runComposeUiTest {
            val rig = SessionRig(watchOnline = false)
            val id = rig.store.start(rig.model.setupFor(Presets.TENNIS_SINGLES_BO3.format()), StartOptions(ScorerOption.THIS_PHONE))
            rig.model.controller.sync(rig.store.active.value)
            rig.model.navigator.push(eu.advantly.ui.phone.app.Overlay.Live)
            showApp(rig.model)
            onNodeWithTag("panel_us").performClick()
            onNodeWithTag("more_button").performClick()
            onNodeWithTag("menu_end_match").performClick()
            click("end_abandon")
            onNodeWithTag("end_confirm").performClick()
            click("show_result")
            onNodeWithTag("result_not_rated").assertExists()
            onNodeWithTag("result_delete").performClick()
            onNodeWithTag("delete_dialog").assertExists()
            onNodeWithTag("delete_cancel").performClick()
            onNodeWithTag("top_back").performClick()
            onNodeWithTag("tab_history").performClick()
            val record = onNodeWithTag("history_record").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
            assertTrue(record.startsWith("0"), record)
            onNodeWithTag("history_abandoned_note").assertExists()
            assertEquals(
                eu.advantly.core.scoring.ResultReason.ABANDONED,
                rig.store
                    .record(id)!!
                    .finalState.result!!
                    .reason,
            )
        }

    /** Review W1-I #10: Der Debug-Auto-Benchmark bricht kein echtes laufendes Match ab. */
    @Test
    fun benchmarkNeverAbandonsARealMatch() {
        val rig = SessionRig()
        val id = rig.startWatchScoredTennis()
        assertFalse(rig.model.startBenchmarkMatch())
        assertEquals(
            id,
            rig.store.active.value!!
                .matchId,
        )
        assertEquals(
            eu.advantly.core.scoring.MatchStatus.IN_PROGRESS,
            rig.store.active.value!!
                .state.status,
        )
    }

    @Test
    fun quickStartConfirmsWhoScoresWhenAWatchIsConnected() =
        runComposeUiTest {
            val rig = SessionRig()
            rig.store.saveFavorite(rig.model.setupFor(Presets.PADEL_GOLDEN_BO3.format()))
            showApp(rig.model)
            click("quick_fav_0")
            onNodeWithTag("scorer_dialog").assertExists()
            onNodeWithTag("scorer_MY_WATCH").assertIsSelected()
            onNodeWithTag("scorer_THIS_PHONE").performClick()
            onNodeWithTag("scorer_dialog_start").performClick()
            onNodeWithTag("scorer_dialog").assertDoesNotExist()
            assertEquals(
                LiveRole.SCORER,
                rig.store.active.value!!
                    .role,
            )
            assertEquals(
                Sport.PADEL,
                rig.store.active.value!!
                    .setup.sport,
            )
        }

    @Test
    fun quickStartWithoutWatchStartsDirectly() =
        runComposeUiTest {
            val model = testModel()
            model.players.saveFavorite(model.setupFor(Presets.PADEL_GOLDEN_BO3.format()))
            showApp(model)
            click("quick_fav_0")
            onNodeWithTag("scorer_dialog").assertDoesNotExist()
            onNodeWithTag("live_sport_chip").assertExists()
        }

    @Test
    fun guestsCanBeRenamedInTheProfile() =
        runComposeUiTest {
            val model = testModel()
            val jonas = model.players.addGuest("Jonas")
            showApp(model)
            onNodeWithTag("tab_profile").performClick()
            click("rename_${jonas.id.value}")
            onNodeWithTag("rename_input").performTextReplacement("Jonas K.")
            onNodeWithTag("rename_save").performClick()
            onNodeWithTag("rename_dialog").assertDoesNotExist()
            assertEquals(
                "Jonas K.",
                model.players.guests.value
                    .single()
                    .displayName,
            )
            assertFalse(model.players.canRename(model.players.self.value.id))
        }

    @Test
    fun scorerOptionIsRememberedInTheWizardState() {
        val rig = SessionRig()
        rig.model.onPlay()
        assertTrue(rig.model.wizard.state.value.input.watchAvailable)
        assertEquals(ScorerOption.MY_WATCH, rig.model.wizard.state.value.startOptions.scorer)
    }
}
