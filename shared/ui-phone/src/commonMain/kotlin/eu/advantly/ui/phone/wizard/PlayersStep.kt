package eu.advantly.ui.phone.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Sport
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.presentation.wizard.WizardUiState
import eu.advantly.ui.phone.common.ChoiceChip
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.common.ToggleRow
import eu.advantly.ui.phone.common.playerName
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.help_discipline_title
import eu.advantly.ui.phone.resources.wizard_coin_toss
import eu.advantly.ui.phone.resources.wizard_doubles
import eu.advantly.ui.phone.resources.wizard_first_server
import eu.advantly.ui.phone.resources.wizard_mixed
import eu.advantly.ui.phone.resources.wizard_mixed_hint
import eu.advantly.ui.phone.resources.wizard_singles
import eu.advantly.ui.phone.resources.wizard_team_order_hint_badminton
import eu.advantly.ui.phone.resources.wizard_team_order_hint_pickleball
import eu.advantly.ui.phone.resources.wizard_team_order_hint_tabletennis
import eu.advantly.ui.phone.resources.wizard_team_order_hint_tennis
import eu.advantly.ui.phone.resources.wizard_title_players
import org.jetbrains.compose.resources.stringResource

// --- H4 Spieler ---

@Composable
internal fun PlayersStep(
    wizard: MatchWizard,
    state: WizardUiState,
) {
    val input = state.input
    val sport = input.sport ?: return
    StepTitle(stringResource(Res.string.wizard_title_players))
    if (state.options.disciplineSelectable) {
        HelpSection(stringResource(Res.string.help_discipline_title), WizardHelp.DISCIPLINE)
        WizardChoices(
            options = listOf(Discipline.SINGLES, Discipline.DOUBLES),
            selected = input.discipline,
            label = { stringResource(if (it == Discipline.SINGLES) Res.string.wizard_singles else Res.string.wizard_doubles) },
            onSelect = wizard::setDiscipline,
            tagPrefix = "discipline",
        )
    }
    val doubles = input.discipline == Discipline.DOUBLES
    PlayerPlaces(wizard, state, doubles)
    if (doubles) {
        Text(
            stringResource(teamOrderHint(sport)),
            style = AdvantlyTheme.typography.caption,
            color = AdvantlyTheme.colors.text2,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        if (state.options.mixedSelectable) {
            ToggleRow(
                title = stringResource(Res.string.wizard_mixed),
                subtitle = stringResource(Res.string.wizard_mixed_hint),
                checked = input.mixed,
                onChange = wizard::setMixed,
                tag = "toggle_mixed",
            )
        }
    }
    if (state.playersComplete && !(doubles && sport.isTennisLike)) FirstServerPicker(wizard, state)
}

/** Erster Aufschlag: Chips je Spieler + Münzwurf (02 §5.1: Default „Ich“). */
@Composable
private fun FirstServerPicker(
    wizard: MatchWizard,
    state: WizardUiState,
) {
    SectionTitle(stringResource(Res.string.wizard_first_server))
    val current = state.setup?.lineup?.firstServer ?: state.selfId
    val all = state.input.players
    all.chunked(2).forEach { row ->
        Row(Modifier.padding(bottom = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            row.forEach { player ->
                ChoiceChip(
                    text = playerName(player),
                    selected = player.id == current,
                    onClick = { wizard.setFirstServer(player.id) },
                    tag = "server_${player.id.value}",
                )
            }
        }
    }
    ChoiceChip(
        text = stringResource(Res.string.wizard_coin_toss),
        selected = false,
        onClick = { wizard.coinToss() },
        tag = "coin_toss",
    )
}

/** Bedeutung der Reihenfolge im Doppel je Sportart (02 §3.3). */
private fun teamOrderHint(sport: Sport) =
    when (sport) {
        Sport.TENNIS, Sport.PADEL -> Res.string.wizard_team_order_hint_tennis
        Sport.BADMINTON -> Res.string.wizard_team_order_hint_badminton
        Sport.PICKLEBALL -> Res.string.wizard_team_order_hint_pickleball
        Sport.TABLE_TENNIS -> Res.string.wizard_team_order_hint_tabletennis
    }
