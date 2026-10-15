package eu.advantly.ui.phone.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import eu.advantly.core.model.RegularSet
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.Presets
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.app.AppTab
import eu.advantly.ui.phone.play
import eu.advantly.ui.phone.setupFor
import eu.advantly.ui.phone.testModel
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DE/EN bei 100 % und 200 %; PNGs der tatsächlich bedienbaren Zustände, kein Pixelvergleich. */
@OptIn(ExperimentalTestApi::class)
class HistoryStatisticsUiTest {
    private fun ComposeUiTest.capture(name: String) {
        val file = File("build/reports/screenshots/stats/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    private fun ComposeUiTest.click(tag: String) = onNodeWithTag(tag).performScrollTo().performClick()

    private fun matrix(block: ComposeUiTest.(Locale, String) -> Unit) {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.US)) {
                Locale.setDefault(locale)
                for (scale in listOf(1f, 2f)) {
                    runDesktopComposeUiTest(width = 720, height = 1280) {
                        currentScale = scale
                        block(locale, "${locale.language}-${scale.toInt()}")
                    }
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    private var currentScale = 1f

    private fun ComposeUiTest.show(model: AdvantlyAppModel) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, currentScale)) {
                AdvantlyApp(model = model, darkTheme = true)
            }
        }
        waitForIdle()
    }

    @Test
    fun matchDetailMomentumPointsFormatAndH2hInBothLanguagesAndSizes() =
        matrix { locale, suffix ->
            val model = testModel()
            val setup =
                model.setupFor(
                    Presets.TENNIS_ONE_SET.format().copy(presetId = null, set = RegularSet(4, 4)),
                    firstServerSide = Side.B,
                )
            val id = model.live.start(setup)
            model.live.play("AAABA" + "AAAA" + "B")
            model.live.pause()
            model.live.resume()
            model.live.endMatch(EndReason.STOPPED)
            model.openResult(id)
            show(model)
            capture("detail-top-$suffix")
            onNodeWithTag("result_format_title").performScrollTo().assertTextEquals(
                if (locale ==
                    Locale.GERMANY
                ) {
                    "Benutzerdefiniert"
                } else {
                    "Custom"
                },
            )
            onNodeWithTag("result_format_description").assertTextContains("4", substring = true)
            capture("detail-format-$suffix")
            onNodeWithTag("show_game_history").performScrollTo()
            onNodeWithTag("momentum_chart").assertIsDisplayed().assertContentDescriptionContains("Break", substring = true)
            capture("detail-momentum-$suffix")
            click("show_game_history")
            click("show_points_0")
            onNodeWithTag("point_0_1").performScrollTo().assertIsDisplayed()
            capture("detail-points-$suffix")
            val opponent = setup.lineup.b.single()
            click("h2h_person_${opponent.value}")
            onNodeWithTag("h2h").assertExists()
            onNodeWithTag("h2h_record_OPPONENT").assertTextContains("1", substring = true)
            capture("h2h-$suffix")
            click("h2h_match_${id.value}")
            onNodeWithTag("result").assertExists()
        }

    @Test
    fun historySearchPersonRoleGroupingAndGuestEntryInBothLanguagesAndSizes() =
        matrix { _, suffix ->
            val model = testModel()
            val setup = model.setupFor(Presets.PADEL_GOLDEN_BO3.format())
            val first = model.live.start(setup)
            model.live.play("AAAA")
            model.live.endMatch(EndReason.STOPPED)
            model.live.dismiss()
            model.live.start(setup)
            model.live.play("B")
            model.live.endMatch(EndReason.ABANDONED)
            model.live.dismiss()
            model.navigator.tab = AppTab.History
            show(model)
            onNodeWithTag("history_search").performTextInput("jonAS")
            onNodeWithTag("history").performScrollToNode(hasTestTag("history_record"))
            onNodeWithTag("history_record").assertTextContains("1", substring = true)
            capture("history-$suffix")
            onNodeWithTag("history").performScrollToNode(hasTestTag("group_WEEK"))
            onNodeWithTag("group_WEEK").performClick()
            onNodeWithTag("history").performScrollToNode(hasTestTag("role_PARTNER"))
            onNodeWithTag("role_PARTNER").performClick()
            onNodeWithTag("history").performScrollToNode(hasTestTag("person_${setup.lineup.a.last().value}"))
            onNodeWithTag("person_${setup.lineup.a.last().value}").performClick()
            onNodeWithTag("history").performScrollToNode(hasTestTag("history_search"))
            onNodeWithTag("history_search").performTextClearance()
            onNodeWithTag("history_search").performTextInput("No such player")
            onNodeWithTag("history").performScrollToNode(hasTestTag("history_empty"))
            onNodeWithTag("history_empty").assertExists()
            runOnIdle { model.navigator.tab = AppTab.Profile }
            click("guest_h2h_${setup.lineup.b.first().value}")
            onNodeWithTag("h2h").assertExists()
            assertEquals(2, model.history.matches.value.size)
            val description = onNodeWithTag("h2h_record_OPPONENT").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
            assertTrue(description.startsWith("1"), description)
            onNodeWithTag("h2h_match_${first.value}").performScrollTo().assertExists()
        }

    @Test
    fun abandonedDetailHasExplicitUnratedLabelAndNoH2hContribution() =
        matrix { locale, suffix ->
            val model = testModel()
            val setup = model.setupFor(Presets.BADMINTON_1GAME_15.format())
            val id = model.live.start(setup)
            model.live.play("ABBA")
            model.live.endMatch(EndReason.ABANDONED)
            model.openResult(id)
            show(model)
            val expectedUnrated = if (locale == Locale.GERMANY) "ohne Wertung" else "not rated"
            onNodeWithTag("result_not_rated").performScrollTo().assertTextContains(expectedUnrated, substring = true)
            capture("abandoned-$suffix")
            click("h2h_person_${setup.lineup.b.first().value}")
            onNodeWithTag("h2h_record_OPPONENT").assertTextContains("0", substring = true)
        }
}
