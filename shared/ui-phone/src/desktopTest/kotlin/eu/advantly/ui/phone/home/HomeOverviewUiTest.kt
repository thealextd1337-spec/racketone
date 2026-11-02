package eu.advantly.ui.phone.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.core.model.MatchId
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.match.HealthRecords
import eu.advantly.presentation.match.InMemoryHealthRecords
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.app.Overlay
import eu.advantly.ui.phone.platform.formatCount
import eu.advantly.ui.phone.play
import eu.advantly.ui.phone.setupFor
import eu.advantly.ui.phone.testModel
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Start: Gesamtstatistik (gewertete Matches) und „Letzte Matches“ mit Navigation ins Match-Detail. */
@OptIn(ExperimentalTestApi::class)
class HomeOverviewUiTest {
    private fun AdvantlyAppModel.finished(
        points: String,
        reason: EndReason = EndReason.STOPPED,
    ): MatchId {
        val id = live.start(setupFor(Presets.TENNIS_ONE_SET.format()))
        live.play(points)
        live.endMatch(reason)
        live.dismiss()
        return id
    }

    @Test
    fun totalsCountRatedMatchesAndRecentListOpensDetail() =
        runDesktopComposeUiTest(width = 720, height = 1280) {
            val model = testModel()
            // 6 gewertete Matches mit je einem gewonnenen Spiel (Badminton zählt den gewonnenen Satz), 1 abgebrochenes
            repeat(5) { model.finished("AAAA") }
            model.live.start(model.setupFor(Presets.BADMINTON_1GAME_15.format()))
            model.live.play("A".repeat(15))
            model.live.dismiss()
            val newest =
                model.history.matches.value
                    .first()
                    .matchId
            model.finished("BBBB", EndReason.ABANDONED)
            setContent { AdvantlyApp(model = model, darkTheme = true) }
            waitForIdle()
            onNodeWithTag("total_matches").performScrollTo().assert(hasAnyDescendant(hasText("6")))
            onNodeWithTag("total_games").assert(hasAnyDescendant(hasText("6")))
            onNodeWithTag("total_max_pulse").assert(hasAnyDescendant(hasText("—")))
            onNodeWithTag("total_max_steps").assert(hasAnyDescendant(hasText("—")))
            onNodeWithTag("week_summary").assertExists()
            onNodeWithTag("recent_item_4").assertExists()
            onNodeWithTag("recent_item_5").assertDoesNotExist()

            (model.health as InMemoryHealthRecords).setRecords(HealthRecords(maxBpm = 171, maxStepsInSet = 987))
            waitForIdle()
            onNodeWithTag("total_max_pulse").assert(hasAnyDescendant(hasText("171", substring = true)))
            onNodeWithTag("total_max_steps").assert(hasAnyDescendant(hasText("987")))

            onNodeWithTag("recent_item_0").performScrollTo().performClick()
            waitForIdle()
            assertEquals(Overlay.Result(newest), model.navigator.top)
            onNodeWithTag("result").assertExists()
        }

    @Test
    fun recentMatchesFollowTheTotalsAndStepsAreGroupedPerLocale() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                model.finished("AAAA")
                (model.health as InMemoryHealthRecords).setRecords(HealthRecords(maxStepsInSet = 12_345))
                setContent { AdvantlyApp(model = model, darkTheme = true) }
                waitForIdle()
                onNodeWithTag("total_max_steps").performScrollTo().assert(hasAnyDescendant(hasText("12.345")))

                fun y(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
                assertTrue(y("total_summary") < y("recent_matches") && y("recent_matches") < y("week_summary"))
            }
            Locale.setDefault(Locale.US)
            assertEquals("12,345", formatCount(12_345))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun withoutMatchesTotalsAndRecentAreHidden() =
        runDesktopComposeUiTest(width = 720, height = 1280) {
            val model = testModel()
            setContent { AdvantlyApp(model = model, darkTheme = true) }
            waitForIdle()
            onNodeWithTag("total_summary").assertDoesNotExist()
            onNodeWithTag("recent_matches").assertDoesNotExist()
        }
}
