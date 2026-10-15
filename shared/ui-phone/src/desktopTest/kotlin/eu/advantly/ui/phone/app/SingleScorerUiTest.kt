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
import eu.advantly.presentation.match.LiveRole
import eu.advantly.presentation.match.ScorerOption
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
            onNodeWithTag("guest_name_input").performScrollTo().performTextInput("Jonas")
            click("guest_add")
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
            val rig = SessionRig()
            rig.offline()
            rig.settle()
            showApp(rig.model)
            onNodeWithTag("tab_play").performClick()
            click("sport_PICKLEBALL")
            listOf("Jonas", "Mia", "Tom").forEach {
                onNodeWithTag("guest_name_input").performScrollTo().performTextInput(it)
                click("guest_add")
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
