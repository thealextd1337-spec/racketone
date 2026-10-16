package eu.advantly.ui.phone.wizard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Surface
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.presentation.wizard.PlayerSelection
import eu.advantly.presentation.wizard.WizardStep
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.help_discipline_title
import org.jetbrains.compose.resources.getString
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WizardHelpUiTest {
    @Test
    fun padelPlayersStepHasNeitherDisciplineHeadingNorHelpIcon() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val wizard = MatchWizard(InMemoryMatchStore(selfName = "Alex")).apply { selectSport(Sport.PADEL) }
            if (wizard.state.value.step == WizardStep.SURFACE) wizard.selectSurface(null)
            assertEquals(WizardStep.PLAYERS, wizard.state.value.step)
            val title = getString(Res.string.help_discipline_title)
            setContent {
                AdvantlyTheme(darkTheme = true) {
                    Column { PlayersStep(wizard, wizard.state.value) }
                }
            }
            onNodeWithText(title).assertDoesNotExist()
            onNodeWithTag("help_DISCIPLINE").assertDoesNotExist()
            onNodeWithTag("discipline_0").assertDoesNotExist()
        }

    @Test
    fun everyHelpTopicHasTranslatedScrollableTextAndACloseActionAtDoubleFontSize() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.ENGLISH, Locale.GERMAN)) {
                Locale.setDefault(locale)
                runDesktopComposeUiTest(width = 412, height = 892) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                            AdvantlyTheme(darkTheme = true) {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    WizardHelp.entries.forEach { topic -> HelpSection(topic.name, topic) }
                                }
                            }
                        }
                    }
                    for (topic in WizardHelp.entries) {
                        val expected = getString(topic.body)
                        assertTrue(expected.length > 40, "Jedes Hilfesymbol braucht erklärenden Text: $topic")
                        onNodeWithTag("help_${topic.name}").performScrollTo().assertHasClickAction().performClick()
                        onNodeWithTag("help_text").assertTextEquals(expected).assertIsDisplayed()
                        val layouts = mutableListOf<TextLayoutResult>()
                        onNodeWithTag("help_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        assertTrue(layouts.isNotEmpty())
                        assertFalse(layouts.single().hasVisualOverflow, "Hilfetext darf bei 200 % nicht abgeschnitten werden: $topic")
                        val screenshot = onNodeWithTag("help_dialog").captureToImage().toAwtImage()
                        val directory = File(System.getenv("ADVANTLY_SCREENSHOT_DIR") ?: "build/reports/screenshots").apply { mkdirs() }
                        ImageIO.write(screenshot, "png", File(directory, "help-${topic.name.lowercase()}-${locale.language}-200.png"))
                        onNodeWithTag("help_close").assertIsDisplayed().performClick()
                        onNodeWithTag("help_dialog").assertDoesNotExist()
                    }
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun formatHelpIsReachableForEverySportAtDoubleFontSizeWithoutChangingTheChosenRules() {
        for (sport in Sport.entries) {
            runDesktopComposeUiTest(width = 412, height = 892) {
                val store = InMemoryMatchStore(selfName = "Alex")
                val wizard =
                    MatchWizard(store).apply {
                        selectSport(sport)
                        if (state.value.step == WizardStep.SURFACE) selectSurface(if (sport == Sport.TENNIS) Surface.CLAY else null)
                        PlayerSelection.places(state.value.input).forEach { place ->
                            if (state.value.input.team(place.side)[place.index] ==
                                null
                            ) {
                                addGuestAt("Sam ${place.side} ${place.index}", place)
                            }
                        }
                        assertTrue(next())
                        toggleAdvanced()
                    }
                assertEquals(WizardStep.FORMAT, wizard.state.value.step)
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        AdvantlyTheme(darkTheme = true) { WizardScreen(wizard, {}, {}) }
                    }
                }
                val expected =
                    when (sport) {
                        Sport.TENNIS, Sport.PADEL -> {
                            listOf(
                                WizardHelp.FORMATS,
                                WizardHelp.SETS,
                                WizardHelp.GAMES,
                                WizardHelp.DEUCE,
                                if (sport == Sport.PADEL) WizardHelp.DECIDING_PADEL else WizardHelp.DECIDING,
                                WizardHelp.SERVE_STATS,
                            )
                        }

                        Sport.PICKLEBALL -> {
                            listOf(WizardHelp.SETS, WizardHelp.PICKLEBALL, WizardHelp.END_CHANGE)
                        }

                        Sport.BADMINTON -> {
                            listOf(WizardHelp.SETS, WizardHelp.BADMINTON)
                        }

                        Sport.TABLE_TENNIS -> {
                            listOf(WizardHelp.SETS, WizardHelp.TABLE_TENNIS)
                        }
                    }
                val before = wizard.state.value.input
                for (topic in expected) {
                    val icons = onAllNodesWithTag("help_${topic.name}")
                    repeat(icons.fetchSemanticsNodes().size) { index ->
                        icons[index].performScrollTo().assertIsDisplayed().performClick()
                        onNodeWithTag("help_dialog").assertIsDisplayed()
                        val screenshot = onNodeWithTag("help_dialog").captureToImage().toAwtImage()
                        val directory = File(System.getenv("ADVANTLY_SCREENSHOT_DIR") ?: "build/reports/screenshots").apply { mkdirs() }
                        ImageIO.write(
                            screenshot,
                            "png",
                            File(directory, "wizard-help-${sport.name.lowercase()}-${topic.name.lowercase()}-200.png"),
                        )
                        onNodeWithTag("help_close").performClick()
                    }
                }
                assertEquals(before, wizard.state.value.input)
                val screenshot = onNodeWithTag("wizard").captureToImage().toAwtImage()
                val directory = File(System.getenv("ADVANTLY_SCREENSHOT_DIR") ?: "build/reports/screenshots").apply { mkdirs() }
                ImageIO.write(screenshot, "png", File(directory, "wizard-${sport.name.lowercase()}-200.png"))
            }
        }
    }
}
