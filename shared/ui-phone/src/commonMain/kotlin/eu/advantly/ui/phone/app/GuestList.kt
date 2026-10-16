package eu.advantly.ui.phone.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.presentation.match.PlayersPort
import eu.advantly.ui.phone.designsystem.AdvantlyIcons
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.action_cancel
import eu.advantly.ui.phone.resources.action_save
import eu.advantly.ui.phone.resources.player_actions
import eu.advantly.ui.phone.resources.player_delete
import eu.advantly.ui.phone.resources.player_delete_hint
import eu.advantly.ui.phone.resources.player_delete_title
import eu.advantly.ui.phone.resources.player_name_label
import eu.advantly.ui.phone.resources.player_rename
import eu.advantly.ui.phone.resources.player_rename_title
import eu.advantly.ui.phone.resources.profile_players
import eu.advantly.ui.phone.resources.profile_players_empty
import org.jetbrains.compose.resources.stringResource

/**
 * Spielerliste im Profil: Dummy-/Gastspieler mit „Umbenennen“ und „Löschen“ – nur, wenn dieses Gerät sie angelegt hat
 * (`PlayersPort.canRename`, 00 „Aliase“: keine Spitznamen für Nutzer mit App).
 */
@Composable
internal fun GuestList(
    players: PlayersPort,
    onHistory: (PlayerId) -> Unit = {},
) {
    val colors = AdvantlyTheme.colors
    val guests by players.guests.collectAsState()
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Text(
        stringResource(Res.string.profile_players).uppercase(),
        style = AdvantlyTheme.typography.overline,
        color = colors.text2,
        modifier = Modifier.padding(top = Spacing.l).semantics { heading() },
    )
    if (guests.isEmpty()) {
        Text(stringResource(Res.string.profile_players_empty), style = AdvantlyTheme.typography.body, color = colors.text2)
    }
    Column(Modifier.testTag("guest_list"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        guests.forEach { guest ->
            GuestRow(
                guest,
                canRename = players.canRename(guest.id),
                onRename = { renaming = guest.id.value },
                onDelete = { deleting = guest.id.value },
                onHistory = { onHistory(guest.id) },
            )
        }
    }
    guests.firstOrNull { it.id.value == deleting }?.let { guest ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(Res.string.player_delete_title, guest.displayName)) },
            text = { Text(stringResource(Res.string.player_delete_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        players.deleteGuest(guest.id)
                        deleting = null
                    },
                    modifier = Modifier.testTag("delete_confirm"),
                ) {
                    Text(stringResource(Res.string.player_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, modifier = Modifier.testTag("delete_cancel")) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
            modifier = Modifier.testTag("delete_dialog"),
        )
    }
    guests.firstOrNull { it.id.value == renaming }?.let { guest ->
        RenameDialog(
            guest = guest,
            onSave = { name -> if (players.renameGuest(guest.id, name)) renaming = null },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun GuestRow(
    guest: Player,
    canRename: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit,
) {
    val colors = AdvantlyTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .testTag("guest_row_${guest.id.value}")
                .clickable(role = Role.Button, onClick = onHistory)
                .fillMaxWidth()
                .heightIn(min = Spacing.minTouchTarget)
                .background(colors.surface, Radii.cardShape)
                .padding(start = Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(guest.displayName, style = AdvantlyTheme.typography.label, color = colors.text, modifier = Modifier.weight(1f))
            if (canRename) {
                var menu by rememberSaveable { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { menu = true },
                        modifier = Modifier.size(Spacing.minTouchTarget).testTag("guest_actions_${guest.id.value}"),
                    ) {
                        Icon(AdvantlyIcons.More, stringResource(Res.string.player_actions, guest.displayName), tint = colors.text2)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.player_rename)) },
                            onClick = {
                                menu = false
                                onRename()
                            },
                            modifier = Modifier.testTag("rename_${guest.id.value}"),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.player_delete), color = colors.danger) },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                            modifier = Modifier.testTag("delete_${guest.id.value}"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    guest: Player,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(guest.id.value) { mutableStateOf(guest.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.player_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.player_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onSave(name) }),
                modifier = Modifier.testTag("rename_input"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank(), modifier = Modifier.testTag("rename_save")) {
                Text(stringResource(Res.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("rename_cancel")) { Text(stringResource(Res.string.action_cancel)) }
        },
        modifier = Modifier.testTag("rename_dialog"),
    )
}
