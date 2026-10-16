package eu.advantly.ui.phone.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.live.ScoreHint
import eu.advantly.presentation.wizard.DecidingSetChoice
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.live.hintText
import eu.advantly.ui.phone.wizard.WizardHelp
import eu.advantly.ui.phone.wizard.decidingHelp
import eu.advantly.ui.phone.wizard.decidingLabel
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Feldtest 2026-10-16 (Österreich): Der Match-Tiebreak heißt auf Deutsch im Tennis „Champions-Tiebreak“, im Padel
 * „Super-Tiebreak“ – nie „Match-Tiebreak“. Englisch bleibt „Match tiebreak“, Padel „Super tiebreak“.
 */
@OptIn(ExperimentalTestApi::class)
class TiebreakNamingTest {
    private data class Expected(
        val hintTennis: String,
        val hintPadel: String,
        val deciderTennis: String,
        val deciderPadel: String,
        val wizardTennis: String,
        val wizardPadel: String,
    )

    private val expected =
        mapOf(
            Locale.GERMANY to
                Expected(
                    hintTennis = "Champions-Tiebreak",
                    hintPadel = "Super-Tiebreak",
                    deciderTennis = "Champions-Tiebreak bis 10",
                    deciderPadel = "Super-Tiebreak bis 10",
                    wizardTennis = "Champions-TB bis 10",
                    wizardPadel = "Super-Tiebreak",
                ),
            Locale.US to
                Expected(
                    hintTennis = "Match tiebreak",
                    hintPadel = "Super tiebreak",
                    deciderTennis = "Match tiebreak to 10",
                    deciderPadel = "Super tiebreak to 10",
                    wizardTennis = "Match TB to 10",
                    wizardPadel = "Super tiebreak",
                ),
        )

    @Test
    fun matchTiebreakIsNamedPerSport() {
        val previous = Locale.getDefault()
        try {
            for ((locale, e) in expected) {
                Locale.setDefault(locale)
                runComposeUiTest {
                    setContent {
                        AdvantlyTheme(darkTheme = true) {
                            Column {
                                val hint = ScoreHint(ScoreHint.Kind.MatchTiebreak)
                                Text(hintText(hint, Sport.TENNIS), Modifier.testTag("hint_tennis"))
                                Text(hintText(hint, Sport.PADEL), Modifier.testTag("hint_padel"))
                                Text(formatDescription(Presets.TENNIS_BO3_MTB10.format()), Modifier.testTag("format_tennis"))
                                Text(formatDescription(Presets.PADEL_GOLDEN_STB.format()), Modifier.testTag("format_padel"))
                                Text(
                                    formatDescription(Presets.PADEL_TIEBREAK_ONLY_10.format().copy(presetId = null)),
                                    Modifier.testTag("only_padel"),
                                )
                                Text(decidingLabel(DecidingSetChoice.MATCH_TIEBREAK, Sport.TENNIS), Modifier.testTag("wizard_tennis"))
                                Text(decidingLabel(DecidingSetChoice.MATCH_TIEBREAK, Sport.PADEL), Modifier.testTag("wizard_padel"))
                                Text(stringResource(decidingHelp(Sport.TENNIS).body), Modifier.testTag("help_tennis"))
                                Text(stringResource(decidingHelp(Sport.PADEL).body), Modifier.testTag("help_padel"))
                            }
                        }
                    }
                    onNodeWithTag("hint_tennis").assertTextEquals(e.hintTennis)
                    onNodeWithTag("hint_padel").assertTextEquals(e.hintPadel)
                    assertTrue(text("format_tennis").endsWith(e.deciderTennis), text("format_tennis"))
                    assertTrue(text("format_padel").endsWith(e.deciderPadel), text("format_padel"))
                    assertTrue(e.deciderPadel in text("only_padel"), text("only_padel"))
                    onNodeWithTag("wizard_tennis").assertTextEquals(e.wizardTennis)
                    onNodeWithTag("wizard_padel").assertTextEquals(e.wizardPadel)
                    if (locale == Locale.GERMANY) {
                        assertTrue(text("help_tennis").contains("Champions-Tiebreak (auch Super-Tiebreak; im Padel Super-Tiebreak)"))
                        assertTrue(text("help_padel").contains("Super-Tiebreak"))
                        for (tag in listOf(
                            "hint_tennis",
                            "hint_padel",
                            "format_tennis",
                            "format_padel",
                            "only_padel",
                            "help_tennis",
                            "help_padel",
                        )) {
                            assertFalse("Match-T" in text(tag), "$tag: ${text(tag)}")
                        }
                    }
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun decidingSetHelpFollowsTheSport() {
        assertEquals(WizardHelp.DECIDING, decidingHelp(Sport.TENNIS))
        assertEquals(WizardHelp.DECIDING_PADEL, decidingHelp(Sport.PADEL))
        assertEquals(WizardHelp.DECIDING, decidingHelp(null))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.text(tag: String): String =
        onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString { it.text }
}
