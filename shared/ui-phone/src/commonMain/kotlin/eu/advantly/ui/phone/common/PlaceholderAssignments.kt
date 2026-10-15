package eu.advantly.ui.phone.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.presentation.match.MatchHistoryPort
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.match.PlayersPort
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.assign_failed
import eu.advantly.ui.phone.resources.assign_new_player
import eu.advantly.ui.phone.resources.assign_player
import eu.advantly.ui.phone.resources.assign_player_name
import org.jetbrains.compose.resources.stringResource

/** Dieselbe Personenwahl in Live-Ansicht und Ergebnis; keine Zählrechte erforderlich. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderAssignments(
    matchId: MatchId,
    setup: MatchSetup,
    history: MatchHistoryPort,
    players: PlayersPort,
) {
    if (!history.canAssignPlayer(matchId)) return
    val slots = (setup.lineup.a + setup.lineup.b).filter { setup.player(it)?.isPlaceholder == true }
    var choosing by remember(matchId) { mutableStateOf<PlayerId?>(null) }
    Column {
        slots.forEach { slot ->
            TextButton(onClick = { choosing = slot }, modifier = Modifier.fillMaxWidth().testTag("assign_${slot.value}")) {
                Text(stringResource(Res.string.assign_player, playerName(setup, slot)))
            }
        }
    }
    val slot = choosing ?: return
    val guests by players.guests.collectAsState()
    var name by remember(slot) { mutableStateOf("") }
    var failed by remember(slot) { mutableStateOf(false) }
    val occupied = (setup.lineup.a + setup.lineup.b).map { setup.assignedPlayers[it]?.id ?: it }.toSet()
    val choices =
        (players.recentPlayers(setup.sport) + guests + players.self.value)
            .distinctBy { it.id }
            .filterNot { it.id in occupied || it.isPlaceholder }
            .filter { it.displayName.isNotBlank() }

    fun assign(id: PlayerId) {
        if (history.assignPlayer(matchId, slot, id)) choosing = null else failed = true
    }
    ModalBottomSheet(onDismissRequest = { choosing = null }) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenEdgeCompact, vertical = Spacing.s)
                .testTag("assign_player_sheet"),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            SectionTitle(stringResource(Res.string.assign_player, playerName(setup, slot)))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(Res.string.assign_player_name)) },
                modifier = Modifier.fillMaxWidth().testTag("assign_name"),
            )
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { assign(players.addGuest(name).id) },
                modifier = Modifier.fillMaxWidth().testTag("assign_create"),
            ) { Text(stringResource(Res.string.assign_new_player)) }
            if (failed) Text(stringResource(Res.string.assign_failed))
            choices.filter { it.displayName.contains(name, ignoreCase = true) }.forEach { person ->
                TextButton(
                    onClick = { assign(person.id) },
                    modifier = Modifier.fillMaxWidth().testTag("assign_person_${person.id.value}"),
                ) {
                    Text(person.displayName)
                }
            }
        }
    }
}
