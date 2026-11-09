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
import androidx.compose.runtime.LaunchedEffect
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
import eu.advantly.presentation.friends.FriendsAccess
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.presentation.friends.InvitationsViewModel
import eu.advantly.presentation.match.PlayersPort
import eu.advantly.ui.phone.designsystem.AdvantlyIcons
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.friends.inviteErrorLabel
import eu.advantly.ui.phone.platform.formatDateTime
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.action_cancel
import eu.advantly.ui.phone.resources.action_save
import eu.advantly.ui.phone.resources.friends_dismiss
import eu.advantly.ui.phone.resources.friends_error_rate_limit
import eu.advantly.ui.phone.resources.friends_unknown
import eu.advantly.ui.phone.resources.guest_invite_confirm_text
import eu.advantly.ui.phone.resources.guest_invite_open
import eu.advantly.ui.phone.resources.guest_invite_revoke
import eu.advantly.ui.phone.resources.guest_invite_send
import eu.advantly.ui.phone.resources.guest_invite_share_text
import eu.advantly.ui.phone.resources.guest_link
import eu.advantly.ui.phone.resources.guest_link_choose
import eu.advantly.ui.phone.resources.guest_link_confirm
import eu.advantly.ui.phone.resources.guest_link_confirm_text
import eu.advantly.ui.phone.resources.guest_link_confirm_title
import eu.advantly.ui.phone.resources.guest_link_done
import eu.advantly.ui.phone.resources.guest_link_error_linked
import eu.advantly.ui.phone.resources.guest_link_error_not_friend
import eu.advantly.ui.phone.resources.guest_link_error_rejected
import eu.advantly.ui.phone.resources.guest_link_error_unavailable
import eu.advantly.ui.phone.resources.guest_link_no_friends
import eu.advantly.ui.phone.resources.guest_linked_to
import eu.advantly.ui.phone.resources.guest_unlink
import eu.advantly.ui.phone.resources.player_actions
import eu.advantly.ui.phone.resources.player_delete
import eu.advantly.ui.phone.resources.player_delete_hint
import eu.advantly.ui.phone.resources.player_delete_title
import eu.advantly.ui.phone.resources.player_deleted
import eu.advantly.ui.phone.resources.player_name_label
import eu.advantly.ui.phone.resources.player_rename
import eu.advantly.ui.phone.resources.player_rename_title
import eu.advantly.ui.phone.resources.profile_players
import eu.advantly.ui.phone.resources.profile_players_empty
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Spielerliste im Profil: Dummy-/Gastspieler mit „Umbenennen“ und „Löschen“ – nur, wenn dieses Gerät sie angelegt hat
 * (`PlayersPort.canRename`, 00 „Aliase“: keine Spitznamen für Nutzer mit App). Mit Konto ([friends], aktuelle
 * Zustimmung) und Server-Stand des Gasts zusätzlich „Mit Freund verknüpfen“ bzw. „Verknüpfung lösen“ (plan-0.6.3 §3.5);
 * redigierte Gäste (Freund gelöscht, §3.6) gelten nicht als verknüpft und bieten nichts davon an.
 */
@Composable
internal fun GuestList(
    players: PlayersPort,
    onHistory: (PlayerId) -> Unit = {},
    friends: FriendsViewModel? = null,
    /** Claim-Links (plan-einladungen §1.3); ohne ViewModel oder Teilen-Menü der Plattform bleibt „Einladungslink senden“ aus. */
    invitations: InvitationsViewModel? = null,
    onShareText: ((String) -> Unit)? = null,
) {
    val colors = AdvantlyTheme.colors
    val guests by players.guests.collectAsState()
    var inviting by rememberSaveable { mutableStateOf<String?>(null) }
    val inviteState = invitations?.state?.collectAsState()?.value
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var linking by rememberSaveable { mutableStateOf<String?>(null) }
    var linkTo by rememberSaveable { mutableStateOf<String?>(null) }
    val friendsState = friends?.state?.collectAsState()?.value
    val links = friendsState?.guestLinks.orEmpty()
    val known = friendsState?.friends.orEmpty().filterNot { it.alias.isNullOrBlank() }
    val ready = friendsState?.access == FriendsAccess.READY
    LaunchedEffect(invitations, ready) { if (ready) invitations?.loadGuestInvites() }

    fun alias(profileId: String?) = known.firstOrNull { it.profileId == profileId }?.alias
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
            val link = links[guest.id.value]?.takeUnless { it.redacted }
            val busy = friendsState?.busy == true
            // Gleiche Bedingung wie „Mit Freund verknüpfen“: Server kennt den Gast, unverknüpft, nicht redigiert.
            val claimable = link != null && link.friendId == null
            val open = inviteState?.guestInvites?.get(guest.id.value)?.takeIf { claimable }
            val inviteIdle = inviteState?.guestBusy == false
            GuestRow(
                guest,
                canRename = players.canRename(guest.id),
                onRename = { renaming = guest.id.value },
                onDelete = { deleting = guest.id.value },
                onHistory = { onHistory(guest.id) },
                redacted = links[guest.id.value]?.redacted == true,
                linkedTo = link?.friendId?.let { alias(it) ?: stringResource(Res.string.friends_unknown) },
                onLink = { linking = guest.id.value }.takeIf { link != null && link.friendId == null && !busy },
                onUnlink = friends?.takeIf { link?.friendId != null && !busy }?.let { model -> { model.unlinkGuest(guest.id.value) } },
                inviteOpenUntil = open?.let { formatDateTime(it.expiresAtMillis) },
                onInvite = { inviting = guest.id.value }.takeIf { claimable && inviteIdle && onShareText != null },
                onRevokeInvite =
                    invitations?.takeIf { open != null && inviteIdle }?.let { model ->
                        { model.revokeGuestInvite(guest.id.value) }
                    },
            )
        }
    }
    friendsState?.guestError?.let { error ->
        Text(stringResource(guestErrorLabel(error)), color = colors.danger, modifier = Modifier.testTag("guest_link_error"))
        TextButton({ friends.dismissError() }) { Text(stringResource(Res.string.friends_dismiss)) }
    }
    inviteState?.guestError?.let { error ->
        Text(stringResource(inviteErrorLabel(error)), color = colors.danger, modifier = Modifier.testTag("guest_invite_error"))
        TextButton({ invitations.dismissGuestError() }) { Text(stringResource(Res.string.friends_dismiss)) }
    }
    // Neuer Claim-Link: sofort ins Teilen-Menü (Name und Ablauf stehen im Text).
    val share = inviteState?.guestShare
    val shareText =
        share?.let { invite ->
            guests.firstOrNull { it.id.value == invite.playerId }?.let { guest ->
                stringResource(Res.string.guest_invite_share_text, guest.displayName, formatDateTime(invite.expiresAtMillis), invite.link)
            }
        }
    LaunchedEffect(share) {
        if (share != null) {
            shareText?.let { onShareText?.invoke(it) }
            invitations?.guestShareHandled()
        }
    }
    guests.firstOrNull { it.id.value == inviting }?.let { guest ->
        AlertDialog(
            onDismissRequest = { inviting = null },
            title = { Text(stringResource(Res.string.guest_invite_send)) },
            text = { Text(stringResource(Res.string.guest_invite_confirm_text, guest.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        invitations?.sendGuestInvite(guest.id.value)
                        inviting = null
                    },
                    modifier = Modifier.testTag("guest_invite_confirm"),
                ) { Text(stringResource(Res.string.guest_invite_send)) }
            },
            dismissButton = {
                TextButton(onClick = { inviting = null }, modifier = Modifier.testTag("guest_invite_cancel")) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
            modifier = Modifier.testTag("guest_invite_dialog"),
        )
    }
    friendsState?.guestLinked?.let { done ->
        val guestName = guests.firstOrNull { it.id.value == done.playerId }?.displayName.orEmpty()
        Text(
            stringResource(Res.string.guest_link_done, guestName, alias(done.friendId).orEmpty(), done.matches),
            style = AdvantlyTheme.typography.body,
            color = colors.text,
            modifier = Modifier.testTag("guest_link_done"),
        )
        TextButton({ friends.dismissGuestLinked() }, modifier = Modifier.testTag("guest_link_done_ok")) {
            Text(stringResource(Res.string.friends_dismiss))
        }
    }
    guests.firstOrNull { it.id.value == linking }?.let { guest ->
        val friendId = linkTo
        if (friendId == null) {
            AlertDialog(
                onDismissRequest = { linking = null },
                title = { Text(stringResource(Res.string.guest_link_choose, guest.displayName)) },
                text = {
                    Column {
                        if (known.isEmpty()) Text(stringResource(Res.string.guest_link_no_friends))
                        known.forEach { friend ->
                            TextButton(
                                onClick = { linkTo = friend.profileId },
                                modifier = Modifier.fillMaxWidth().testTag("guest_link_friend_${friend.profileId}"),
                            ) { Text(friend.alias.orEmpty()) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { linking = null }) { Text(stringResource(Res.string.action_cancel)) }
                },
                modifier = Modifier.testTag("guest_link_dialog"),
            )
        } else {
            val friendName = alias(friendId).orEmpty()
            AlertDialog(
                onDismissRequest = { linkTo = null },
                title = { Text(stringResource(Res.string.guest_link_confirm_title, guest.displayName, friendName)) },
                text = { Text(stringResource(Res.string.guest_link_confirm_text, guest.displayName, friendName)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            friends?.linkGuest(guest.id.value, friendId)
                            linking = null
                            linkTo = null
                        },
                        modifier = Modifier.testTag("guest_link_confirm"),
                    ) { Text(stringResource(Res.string.guest_link_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { linkTo = null }, modifier = Modifier.testTag("guest_link_cancel")) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
                modifier = Modifier.testTag("guest_link_confirm_dialog"),
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

/** Eigene Fehlertexte fürs Verknüpfen; offline, fehlende Bindung und ein noch nicht hochgeladener Gast sind „nicht bereit“. */
private fun guestErrorLabel(error: String): StringResource =
    when (error) {
        "assignee_must_be_friend" -> Res.string.guest_link_error_not_friend
        "already_assigned" -> Res.string.guest_link_error_linked
        "assignment_rejected_before" -> Res.string.guest_link_error_rejected
        "rate_limit_exceeded" -> Res.string.friends_error_rate_limit
        else -> Res.string.guest_link_error_unavailable
    }

@Composable
private fun GuestRow(
    guest: Player,
    canRename: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit,
    redacted: Boolean = false,
    linkedTo: String? = null,
    onLink: (() -> Unit)? = null,
    onUnlink: (() -> Unit)? = null,
    inviteOpenUntil: String? = null,
    onInvite: (() -> Unit)? = null,
    onRevokeInvite: (() -> Unit)? = null,
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
            Column(Modifier.weight(1f)) {
                Text(
                    if (redacted) stringResource(Res.string.player_deleted) else guest.displayName,
                    style = AdvantlyTheme.typography.label,
                    color = colors.text,
                )
                if (linkedTo != null) {
                    Text(
                        stringResource(Res.string.guest_linked_to, linkedTo),
                        style = AdvantlyTheme.typography.caption,
                        color = colors.text2,
                        modifier = Modifier.testTag("guest_linked_${guest.id.value}"),
                    )
                }
                if (inviteOpenUntil != null) {
                    Text(
                        stringResource(Res.string.guest_invite_open, inviteOpenUntil),
                        style = AdvantlyTheme.typography.caption,
                        color = colors.text2,
                        modifier = Modifier.testTag("guest_invite_open_${guest.id.value}"),
                    )
                }
            }
            if (canRename || onLink != null || onUnlink != null || onInvite != null || onRevokeInvite != null) {
                var menu by rememberSaveable { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { menu = true },
                        modifier = Modifier.size(Spacing.minTouchTarget).testTag("guest_actions_${guest.id.value}"),
                    ) {
                        Icon(AdvantlyIcons.More, stringResource(Res.string.player_actions, guest.displayName), tint = colors.text2)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (onLink != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.guest_link)) },
                                onClick = {
                                    menu = false
                                    onLink()
                                },
                                modifier = Modifier.testTag("link_${guest.id.value}"),
                            )
                        }
                        if (onInvite != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.guest_invite_send)) },
                                onClick = {
                                    menu = false
                                    onInvite()
                                },
                                modifier = Modifier.testTag("invite_${guest.id.value}"),
                            )
                        }
                        if (onRevokeInvite != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.guest_invite_revoke)) },
                                onClick = {
                                    menu = false
                                    onRevokeInvite()
                                },
                                modifier = Modifier.testTag("revoke_invite_${guest.id.value}"),
                            )
                        }
                        if (onUnlink != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.guest_unlink)) },
                                onClick = {
                                    menu = false
                                    onUnlink()
                                },
                                modifier = Modifier.testTag("unlink_${guest.id.value}"),
                            )
                        }
                        if (canRename) {
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
