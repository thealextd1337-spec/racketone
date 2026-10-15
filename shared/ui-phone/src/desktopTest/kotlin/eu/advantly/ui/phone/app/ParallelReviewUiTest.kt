package eu.advantly.ui.phone.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchResult
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ResultReason
import eu.advantly.device.link.CompanionState
import eu.advantly.device.link.testing.FakeCompanionDetector
import eu.advantly.presentation.match.LiveRole
import eu.advantly.presentation.match.ScorerOption
import eu.advantly.presentation.match.StartOptions
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.SessionRig
import eu.advantly.ui.phone.setupFor
import eu.advantly.ui.phone.startWith
import eu.advantly.ui.phone.testModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review-Runde 1 zu W3-M (PR #35): Abbruch im Satzende-Sheet, Entprellung je Match, bestätigte Übernahme. */
@OptIn(ExperimentalTestApi::class)
class ParallelReviewUiTest {
    private fun ComposeUiTest.showApp(model: AdvantlyAppModel) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) { AdvantlyApp(model = model, darkTheme = true) }
        }
        waitForIdle()
    }

    /** #2: „Abbrechen (ohne Wertung)“ im Satzende-Sheet mit Bestätigung – kein gewertetes Ergebnis. */
    @Test
    fun abandonFromTheSetEndSheetIsNotRated() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_OPEN.format(), "AAAA".repeat(6)) }
            assertEquals(
                MatchStatus.AWAITING_DECISION,
                model.live.active.value!!
                    .state.status,
            )
            model.navigator.push(Overlay.Live)
            showApp(model)
            onNodeWithTag("set_end_abandon").performScrollTo().performClick()
            onNodeWithTag("end_confirm_dialog").assertExists()
            onNodeWithTag("end_confirm").performClick()
            assertEquals(
                MatchResult(null, ResultReason.ABANDONED),
                model.live.active.value!!
                    .state.result,
            )
            onNodeWithTag("match_end_sheet").assertExists()
        }

    /** #6: Die Entprellung gilt je Match – ein schneller Punkt nach dem Wechsel auf den Nachbarplatz zählt. */
    @Test
    fun debounceDoesNotSwallowAPointAfterSwitchingMatches() {
        val model = testModel()
        val controller = model.controller
        controller.debounceEnabled = true
        val first = model.live.start(model.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        controller.sync(model.live.active.value)
        controller.onTap(Side.A, null)
        controller.onTap(Side.A, null) // gleiches Match, < 600 ms: entprellt
        assertEquals(
            1,
            model.live.active.value!!
                .pointsPlayed,
        )
        model.live.start(model.setupFor(Presets.PADEL_GOLDEN_BO3.format()))
        controller.sync(model.live.active.value)
        controller.onTap(Side.B, null) // anderes Match direkt danach: zählt
        assertEquals(
            1,
            model.live.active.value!!
                .pointsPlayed,
        )
        assertTrue(model.live.select(first))
        assertEquals(
            1,
            model.live.active.value!!
                .pointsPlayed,
        )
    }

    /** #5: Uhr zählt ein anderes Match und ist nicht erreichbar – „Meine Uhr“ startet erst nach Bestätigung. */
    @Test
    fun offlineWatchSwitchAsksBeforeTakingOver() =
        runComposeUiTest {
            val rig = SessionRig(companion = FakeCompanionDetector(CompanionState.APP_INSTALLED))
            val onWatch = rig.startWatchScoredTennis()
            rig.watchPlays("A")
            rig.offline()
            rig.settle()
            showApp(rig.model)
            runOnIdle { rig.model.startMatch(rig.model.setupFor(Presets.PADEL_GOLDEN_BO3.format()), StartOptions(ScorerOption.MY_WATCH)) }
            val text = onNodeWithTag("watch_takeover_text").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
            assertTrue(text.contains("Jonas"), text)
            onNodeWithTag("watch_takeover_cancel").performClick()
            assertEquals(
                onWatch,
                rig.store.active.value!!
                    .matchId,
                "abgebrochen: nichts gestartet",
            )
            assertEquals(
                LiveRole.DISPLAY,
                rig.store.active.value!!
                    .role,
            )
            runOnIdle { rig.model.startMatch(rig.model.setupFor(Presets.PADEL_GOLDEN_BO3.format()), StartOptions(ScorerOption.MY_WATCH)) }
            onNodeWithTag("watch_takeover_confirm").performClick()
            val old =
                rig.store.openMatches.value
                    .single { it.matchId == onWatch }
            assertEquals(LiveRole.SCORER, old.role, "Handy hat das alte Uhr-Match übernommen")
            assertTrue(
                rig.store.active.value!!
                    .onWatch,
            )
            assertNull(rig.model.pendingWatchTakeOver)
        }
}
