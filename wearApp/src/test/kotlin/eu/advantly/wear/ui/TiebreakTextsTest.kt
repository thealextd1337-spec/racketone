package eu.advantly.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.wear.compose.material3.Text
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.presentation.live.ScoreHint
import eu.advantly.wear.match.FormatChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Feldtest 2026-10-16: Deutsch heißt der Match-Tiebreak im Tennis „Champions-Tiebreak“ (Hinweis kurz „Champions-TB“),
 * im Padel „Super-Tiebreak“ („Super-TB“); Englisch bleibt „Match tiebreak“ bzw. Padel „Super tiebreak“.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TiebreakTextsTest {
    @get:Rule val rule = createComposeRule()

    private fun render() {
        rule.setContent {
            WearTestFrame {
                Column {
                    val hint = ScoreHint(ScoreHint.Kind.MatchTiebreak)
                    Text(hintText(hint, Sport.TENNIS).orEmpty(), Modifier.testTag("hint_tennis"))
                    Text(hintText(hint, Sport.PADEL).orEmpty(), Modifier.testTag("hint_padel"))
                    Text(formatLabel(FormatChoice.TENNIS_BO3_MTB), Modifier.testTag("format_tennis"))
                    Text(formatLabel(FormatChoice.PADEL_GOLDEN_STB), Modifier.testTag("format_padel"))
                    Text(nextSetText(NextSetKind.MATCH_TIEBREAK_10, Sport.TENNIS), Modifier.testTag("next_tennis"))
                    Text(nextSetText(NextSetKind.MATCH_TIEBREAK_10, Sport.PADEL), Modifier.testTag("next_padel"))
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "de")
    fun germanNamesFollowTheSport() {
        render()
        rule.onNodeWithTag("hint_tennis").assertTextEquals("Champions-TB")
        rule.onNodeWithTag("hint_padel").assertTextEquals("Super-TB")
        rule.onNodeWithTag("format_tennis").assertTextEquals("Best of 3 · Champions-Tiebreak")
        rule.onNodeWithTag("format_padel").assertTextEquals("Golden Point · Super-Tiebreak")
        rule.onNodeWithTag("next_tennis").assertTextEquals("Champions-Tiebreak bis 10")
        rule.onNodeWithTag("next_padel").assertTextEquals("Super-Tiebreak bis 10")
    }

    @Test
    @Config(qualifiers = "en")
    fun englishKeepsMatchTiebreakAndPadelSuperTiebreak() {
        render()
        rule.onNodeWithTag("hint_tennis").assertTextEquals("Match tiebreak")
        rule.onNodeWithTag("hint_padel").assertTextEquals("Super tiebreak")
        rule.onNodeWithTag("format_tennis").assertTextEquals("Best of 3 · match tiebreak")
        rule.onNodeWithTag("format_padel").assertTextEquals("Golden point · super tiebreak")
    }
}
