package eu.advantly.ui.phone.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eu.advantly.cloud.sync.FriendRequestResult
import eu.advantly.presentation.friends.FriendEntry
import eu.advantly.presentation.friends.FriendsAccess
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.account_cancel
import eu.advantly.ui.phone.resources.friends_accept
import eu.advantly.ui.phone.resources.friends_access_consent
import eu.advantly.ui.phone.resources.friends_access_open_account
import eu.advantly.ui.phone.resources.friends_access_paused
import eu.advantly.ui.phone.resources.friends_access_setup
import eu.advantly.ui.phone.resources.friends_access_signed_out
import eu.advantly.ui.phone.resources.friends_add_title
import eu.advantly.ui.phone.resources.friends_block
import eu.advantly.ui.phone.resources.friends_block_explanation
import eu.advantly.ui.phone.resources.friends_blocked_alias_since
import eu.advantly.ui.phone.resources.friends_blocked_empty
import eu.advantly.ui.phone.resources.friends_blocked_show
import eu.advantly.ui.phone.resources.friends_blocked_since
import eu.advantly.ui.phone.resources.friends_blocked_title
import eu.advantly.ui.phone.resources.friends_code_loading
import eu.advantly.ui.phone.resources.friends_code_retry
import eu.advantly.ui.phone.resources.friends_code_title
import eu.advantly.ui.phone.resources.friends_decline
import eu.advantly.ui.phone.resources.friends_dismiss
import eu.advantly.ui.phone.resources.friends_empty
import eu.advantly.ui.phone.resources.friends_error_binding
import eu.advantly.ui.phone.resources.friends_error_connection
import eu.advantly.ui.phone.resources.friends_error_cooldown
import eu.advantly.ui.phone.resources.friends_error_not_found
import eu.advantly.ui.phone.resources.friends_error_not_pending
import eu.advantly.ui.phone.resources.friends_error_rate_limit
import eu.advantly.ui.phone.resources.friends_error_revoked
import eu.advantly.ui.phone.resources.friends_error_self
import eu.advantly.ui.phone.resources.friends_error_session
import eu.advantly.ui.phone.resources.friends_error_unavailable
import eu.advantly.ui.phone.resources.friends_feed_placeholder
import eu.advantly.ui.phone.resources.friends_found_send
import eu.advantly.ui.phone.resources.friends_incoming_title
import eu.advantly.ui.phone.resources.friends_list_title
import eu.advantly.ui.phone.resources.friends_not_found
import eu.advantly.ui.phone.resources.friends_outgoing_title
import eu.advantly.ui.phone.resources.friends_query_hint
import eu.advantly.ui.phone.resources.friends_query_label
import eu.advantly.ui.phone.resources.friends_remove
import eu.advantly.ui.phone.resources.friends_remove_explanation
import eu.advantly.ui.phone.resources.friends_requests_empty
import eu.advantly.ui.phone.resources.friends_result_accepted
import eu.advantly.ui.phone.resources.friends_result_already
import eu.advantly.ui.phone.resources.friends_result_requested
import eu.advantly.ui.phone.resources.friends_search
import eu.advantly.ui.phone.resources.friends_segment_feed
import eu.advantly.ui.phone.resources.friends_segment_friends
import eu.advantly.ui.phone.resources.friends_segment_requests
import eu.advantly.ui.phone.resources.friends_share
import eu.advantly.ui.phone.resources.friends_share_text
import eu.advantly.ui.phone.resources.friends_unblock
import eu.advantly.ui.phone.resources.friends_unblock_explanation
import eu.advantly.ui.phone.resources.friends_unknown
import eu.advantly.ui.phone.resources.friends_withdraw
import eu.advantly.ui.phone.resources.friends_withdraw_explanation
import eu.advantly.ui.phone.resources.tab_friends
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class Segment { FEED, FRIENDS, REQUESTS }

private enum class ConfirmKind(
    val action: StringResource,
    val explanation: StringResource,
) {
    REMOVE(Res.string.friends_remove, Res.string.friends_remove_explanation),
    WITHDRAW(Res.string.friends_withdraw, Res.string.friends_withdraw_explanation),
    BLOCK(Res.string.friends_block, Res.string.friends_block_explanation),
    UNBLOCK(Res.string.friends_unblock, Res.string.friends_unblock_explanation),
}

private data class Confirm(
    val kind: ConfirmKind,
    val profileId: String,
    val name: String?,
)

/**
 * Freunde-Tab (04 §2.1, H11): Segmente Feed (Platzhalter für P2-E) · Freunde · Anfragen. Ohne Konto, Zustimmung oder
 * bei pausiertem Konto nur ein Hinweis; das Zählen ohne Konto bleibt unberührt. [onShareText] öffnet das
 * Teilen-Menü der Plattform (ui-phone bleibt plattformneutral), `null` blendet „Teilen“ aus.
 */
@Composable
fun FriendsScreen(
    model: FriendsViewModel,
    onOpenAccount: () -> Unit,
    onShareText: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    var segment by rememberSaveable { mutableStateOf(Segment.FRIENDS) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    // Auf Bildschirmebene: Segmentwechsel verwirft die Eingabe nicht.
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.access) { if (state.access == FriendsAccess.READY) model.open() }
    val colors = AdvantlyTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl)
            .testTag("friends_screen"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            stringResource(Res.string.tab_friends),
            style = AdvantlyTheme.typography.headline,
            color = colors.text,
            modifier = Modifier.semantics { heading() },
        )
        if (state.access != FriendsAccess.READY) {
            val hint =
                when (state.access) {
                    FriendsAccess.SETUP_REQUIRED -> Res.string.friends_access_setup
                    FriendsAccess.CONSENT_REQUIRED -> Res.string.friends_access_consent
                    FriendsAccess.PAUSED -> Res.string.friends_access_paused
                    else -> Res.string.friends_access_signed_out
                }
            Text(stringResource(hint), color = colors.text2, modifier = Modifier.testTag("friends_hint"))
            Button(onOpenAccount, modifier = Modifier.testTag("friends_open_account")) {
                Text(stringResource(Res.string.friends_access_open_account))
            }
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val requests = state.incoming.size
            listOf(
                Segment.FEED to stringResource(Res.string.friends_segment_feed),
                Segment.FRIENDS to stringResource(Res.string.friends_segment_friends),
                Segment.REQUESTS to stringResource(Res.string.friends_segment_requests) + if (requests > 0) " ($requests)" else "",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = segment == value,
                    onClick = { segment = value },
                    label = { Text(label) },
                    modifier = Modifier.testTag("friends_segment_${value.name.lowercase()}"),
                )
            }
        }
        state.error?.let { error ->
            Text(stringResource(errorLabel(error)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("friends_error"))
            TextButton(model::dismissError) { Text(stringResource(Res.string.friends_dismiss)) }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("friends_busy"))
        when (segment) {
            Segment.FEED -> {
                Text(stringResource(Res.string.friends_feed_placeholder), color = colors.text2, modifier = Modifier.testTag("friends_feed"))
            }

            Segment.FRIENDS -> {
                // Nach einem Fehler nicht ewig „wird geladen“: ausdrücklich erneut versuchen.
                OwnCode(state.friendCode, onShareText, retry = if (!state.busy && state.error != null) model::loadCode else null)
                AddFriend(model, query) { query = it }
                HorizontalDivider()
                Title(Res.string.friends_list_title)
                if (state.friends.isEmpty()) Text(stringResource(Res.string.friends_empty), color = colors.text2)
                state.friends.forEach { friend ->
                    Person(friend) {
                        TextButton(
                            { confirm = Confirm(ConfirmKind.REMOVE, friend.profileId, friend.alias) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("friends_remove_${friend.profileId}"),
                        ) { Text(stringResource(Res.string.friends_remove)) }
                        TextButton(
                            { confirm = Confirm(ConfirmKind.BLOCK, friend.profileId, friend.alias) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("friends_block_${friend.profileId}"),
                        ) { Text(stringResource(Res.string.friends_block)) }
                    }
                }
                HorizontalDivider()
                Title(Res.string.friends_blocked_title)
                val blocked = state.blocked
                if (blocked == null) {
                    TextButton(model::showBlocked, enabled = !state.busy, modifier = Modifier.testTag("friends_blocked_show")) {
                        Text(stringResource(Res.string.friends_blocked_show))
                    }
                } else if (blocked.isEmpty()) {
                    Text(stringResource(Res.string.friends_blocked_empty), color = colors.text2)
                }
                blocked?.forEach { entry ->
                    // Alias nur bei aktivem Profil (cloud_my_blocks_v1), sonst nur der Tag der Blockierung.
                    val since = entry.createdAt.take(10)
                    Text(
                        entry.alias?.let { stringResource(Res.string.friends_blocked_alias_since, it, since) }
                            ?: stringResource(Res.string.friends_blocked_since, since),
                        modifier = Modifier.testTag("friends_blocked_${entry.profileId}"),
                    )
                    TextButton(
                        { confirm = Confirm(ConfirmKind.UNBLOCK, entry.profileId, entry.alias) },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("friends_unblock_${entry.profileId}"),
                    ) { Text(stringResource(Res.string.friends_unblock)) }
                }
            }

            Segment.REQUESTS -> {
                Title(Res.string.friends_incoming_title)
                if (state.incoming.isEmpty()) Text(stringResource(Res.string.friends_requests_empty), color = colors.text2)
                state.incoming.forEach { request ->
                    Person(request) {
                        Button(
                            { model.respond(request.profileId, accept = true) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("friends_accept_${request.profileId}"),
                        ) { Text(stringResource(Res.string.friends_accept)) }
                        TextButton(
                            { model.respond(request.profileId, accept = false) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("friends_decline_${request.profileId}"),
                        ) { Text(stringResource(Res.string.friends_decline)) }
                        TextButton(
                            { confirm = Confirm(ConfirmKind.BLOCK, request.profileId, request.alias) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("friends_block_${request.profileId}"),
                        ) { Text(stringResource(Res.string.friends_block)) }
                    }
                }
                HorizontalDivider()
                Title(Res.string.friends_outgoing_title)
                if (state.outgoing.isEmpty()) Text(stringResource(Res.string.friends_requests_empty), color = colors.text2)
                state.outgoing.forEach { request ->
                    Person(request) {
                        TextButton(
                            { confirm = Confirm(ConfirmKind.WITHDRAW, request.profileId, request.alias) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("friends_withdraw_${request.profileId}"),
                        ) { Text(stringResource(Res.string.friends_withdraw)) }
                    }
                }
            }
        }
    }
    confirm?.let { pending ->
        val name = pending.name?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.friends_unknown)
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(pending.kind.action)) },
            text = { Text(stringResource(pending.kind.explanation, name)) },
            confirmButton = {
                TextButton({
                    confirm = null
                    when (pending.kind) {
                        ConfirmKind.REMOVE, ConfirmKind.WITHDRAW -> model.remove(pending.profileId)
                        ConfirmKind.BLOCK -> model.block(pending.profileId)
                        ConfirmKind.UNBLOCK -> model.unblock(pending.profileId)
                    }
                }, modifier = Modifier.testTag("friends_confirm")) { Text(stringResource(pending.kind.action)) }
            },
            dismissButton = {
                TextButton({ confirm = null }, modifier = Modifier.testTag("friends_confirm_cancel")) {
                    Text(stringResource(Res.string.account_cancel))
                }
            },
            modifier = Modifier.testTag("friends_confirm_dialog"),
        )
    }
}

@Composable
private fun OwnCode(
    code: String?,
    onShareText: ((String) -> Unit)?,
    retry: (() -> Unit)?,
) {
    Title(Res.string.friends_code_title)
    if (code == null) {
        if (retry == null) {
            Text(stringResource(Res.string.friends_code_loading), color = AdvantlyTheme.colors.text2)
        } else {
            TextButton(retry, modifier = Modifier.testTag("friends_code_retry")) { Text(stringResource(Res.string.friends_code_retry)) }
        }
        return
    }
    val shown = formatFriendCode(code)
    SelectionContainer { Text(shown, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("friends_code")) }
    if (onShareText != null) {
        val text = stringResource(Res.string.friends_share_text, shown)
        TextButton({ onShareText(text) }, modifier = Modifier.testTag("friends_share")) { Text(stringResource(Res.string.friends_share)) }
    }
}

@Composable
private fun AddFriend(
    model: FriendsViewModel,
    query: String,
    onQuery: (String) -> Unit,
) {
    val state by model.state.collectAsState()
    Title(Res.string.friends_add_title)
    OutlinedTextField(
        query,
        {
            onQuery(it.take(64))
            model.clearSearch()
        },
        label = { Text(stringResource(Res.string.friends_query_label)) },
        supportingText = { Text(stringResource(Res.string.friends_query_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { model.search(query) }),
        modifier = Modifier.fillMaxWidth().testTag("friends_query"),
    )
    Button(
        { model.search(query) },
        enabled = !state.busy && query.isNotBlank(),
        modifier = Modifier.testTag("friends_search"),
    ) { Text(stringResource(Res.string.friends_search)) }
    state.found?.let { found ->
        val name = found.alias?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.friends_unknown)
        Text(listOfNotNull(name, found.username?.let { "@$it" }).joinToString(" · "), modifier = Modifier.testTag("friends_found"))
        Button({ model.sendRequest(query) }, enabled = !state.busy, modifier = Modifier.testTag("friends_send")) {
            Text(stringResource(Res.string.friends_found_send))
        }
    }
    if (state.notFound) Text(stringResource(Res.string.friends_not_found), modifier = Modifier.testTag("friends_not_found"))
    state.requestResult?.let { result ->
        val label =
            when (result) {
                FriendRequestResult.REQUESTED -> Res.string.friends_result_requested
                FriendRequestResult.ACCEPTED -> Res.string.friends_result_accepted
                FriendRequestResult.ALREADY_FRIENDS -> Res.string.friends_result_already
            }
        Text(stringResource(label), modifier = Modifier.testTag("friends_request_result"))
    }
}

@Composable
private fun Title(text: StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Person(
    entry: FriendEntry,
    actions: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("friends_person_${entry.profileId}")) {
        Text(
            entry.alias?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.friends_unknown),
            style = MaterialTheme.typography.bodyLarge,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

/** 8 Zeichen Crockford-Base32 lesbar als `XXXX-XXXX`; andere Längen unverändert. */
internal fun formatFriendCode(code: String): String = if (code.length == 8) "${code.take(4)}-${code.drop(4)}" else code

private fun errorLabel(error: String): StringResource =
    when (error) {
        "rate_limit_exceeded" -> Res.string.friends_error_rate_limit
        "profile_not_found" -> Res.string.friends_error_not_found
        "request_cooldown" -> Res.string.friends_error_cooldown
        "cannot_befriend_self" -> Res.string.friends_error_self
        "no_pending_request" -> Res.string.friends_error_not_pending
        "device_not_authorized" -> Res.string.friends_error_binding
        "device_revoked" -> Res.string.friends_error_revoked
        "cloud_session_changed" -> Res.string.friends_error_session
        "consent_required", "profile_not_active", "account_suspended", "account_deletion_pending" -> Res.string.friends_error_unavailable
        else -> Res.string.friends_error_connection
    }
