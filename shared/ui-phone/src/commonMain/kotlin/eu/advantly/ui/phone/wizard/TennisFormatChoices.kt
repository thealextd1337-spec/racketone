package eu.advantly.ui.phone.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import eu.advantly.core.model.Sport
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.presentation.wizard.TennisMatchChoice
import eu.advantly.presentation.wizard.WizardUiState
import eu.advantly.ui.phone.common.ChoiceChip
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.set_end_regular
import eu.advantly.ui.phone.resources.setup_match_kind
import eu.advantly.ui.phone.resources.setup_padel_tiebreak_only_10
import eu.advantly.ui.phone.resources.setup_tiebreak_only_10
import eu.advantly.ui.phone.resources.setup_tiebreak_only_7
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun tennisMatchLabel(
    choice: TennisMatchChoice,
    sport: Sport,
): String =
    stringResource(
        when (choice) {
            TennisMatchChoice.REGULAR -> {
                Res.string.set_end_regular
            }

            TennisMatchChoice.TIEBREAK_7 -> {
                Res.string.setup_tiebreak_only_7
            }

            TennisMatchChoice.TIEBREAK_10 -> {
                if (sport == Sport.PADEL) Res.string.setup_padel_tiebreak_only_10 else Res.string.setup_tiebreak_only_10
            }
        },
    )

/** Die großen Format-Chips umbrechen auch mit vergrößerter Schrift. */
@Composable
internal fun TennisFormatChoices(
    wizard: MatchWizard,
    state: WizardUiState,
) {
    val sport = state.input.sport ?: return
    HelpSection(stringResource(Res.string.setup_match_kind), WizardHelp.FORMATS)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        TennisMatchChoice.entries.forEach { choice ->
            ChoiceChip(
                text = tennisMatchLabel(choice, sport),
                selected = state.input.tennisMatch == choice,
                onClick = { wizard.setTennisMatch(choice) },
                tag = "match_kind_${choice.name}",
            )
        }
    }
}

@Composable
internal fun GamesPerSetChoices(
    wizard: MatchWizard,
    state: WizardUiState,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        state.options.gamesPerSet.forEachIndexed { index, games ->
            ChoiceChip(
                text = gamesLabel(games),
                selected = games == state.input.gamesPerSet,
                onClick = { wizard.setGamesPerSet(games) },
                tag = "games_$index",
            )
        }
    }
}
