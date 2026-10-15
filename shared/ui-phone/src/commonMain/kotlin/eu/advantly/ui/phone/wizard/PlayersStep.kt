package eu.advantly.ui.phone.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Sport
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.presentation.wizard.WizardUiState
import eu.advantly.ui.phone.common.AdvantlyButton
import eu.advantly.ui.phone.common.ChoiceChip
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.common.SegmentedChoice
import eu.advantly.ui.phone.common.ToggleRow
import eu.advantly.ui.phone.common.playerName
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.wizard_add_guest
import eu.advantly.ui.phone.resources.wizard_add_guest_hint
import eu.advantly.ui.phone.resources.wizard_coin_toss
import eu.advantly.ui.phone.resources.wizard_doubles
import eu.advantly.ui.phone.resources.wizard_first_server
import eu.advantly.ui.phone.resources.wizard_guests
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
        SegmentedChoice(
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
    GuestPicker(wizard, state)
    if (state.playersComplete) FirstServerPicker(wizard, state)
}

/** Gäste: zuletzt verwendete als Chips, neue per Name (nur Name, später verknüpfbar). */
@Composable
private fun GuestPicker(
    wizard: MatchWizard,
    state: WizardUiState,
) {
    if (state.playersComplete) return
    var name by rememberSaveable { mutableStateOf("") }
    val players = wizard.playerSuggestions()
    SectionTitle(stringResource(Res.string.wizard_guests))
    val inLineup =
        state.input.players
            .map { it.id }
            .toSet()
    players.filter { it.id !in inLineup }.take(MAX_GUEST_CHIPS).chunked(2).forEach { row ->
        Row(Modifier.padding(bottom = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            row.forEach { guest ->
                ChoiceChip(
                    text = playerName(guest),
                    selected = false,
                    onClick = { wizard.pickPlayer(guest) },
                    tag = "guest_${guest.displayName}",
                )
            }
        }
    }
    val add = {
        wizard.addGuest(name)
        name = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(Res.string.wizard_add_guest_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f).testTag("guest_name_input"),
        )
        AdvantlyButton(
            text = stringResource(Res.string.wizard_add_guest),
            primary = false,
            enabled = name.isNotBlank(),
            onClick = add,
            modifier = Modifier.testTag("guest_add"),
        )
    }
}

private const val MAX_GUEST_CHIPS = 6

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
