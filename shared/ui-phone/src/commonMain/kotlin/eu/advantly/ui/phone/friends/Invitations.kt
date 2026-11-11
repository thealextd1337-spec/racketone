package eu.advantly.ui.phone.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.advantly.cloud.sync.InviteKind
import eu.advantly.cloud.sync.InviteStatus
import eu.advantly.presentation.friends.FriendsAccess
import eu.advantly.presentation.friends.InvitationsState
import eu.advantly.presentation.friends.InvitationsViewModel
import eu.advantly.presentation.friends.InviteProblem
import eu.advantly.presentation.match.WallClock
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.action_cancel
import eu.advantly.ui.phone.resources.friends_dismiss
import eu.advantly.ui.phone.resources.friends_unknown
import eu.advantly.ui.phone.resources.invite_accept
import eu.advantly.ui.phone.resources.invite_already_friends
import eu.advantly.ui.phone.resources.invite_claim_accept
import eu.advantly.ui.phone.resources.invite_claim_decline
import eu.advantly.ui.phone.resources.invite_claim_text
import eu.advantly.ui.phone.resources.invite_claim_title
import eu.advantly.ui.phone.resources.invite_decline
import eu.advantly.ui.phone.resources.invite_done_claim
import eu.advantly.ui.phone.resources.invite_done_friend
import eu.advantly.ui.phone.resources.invite_error_claimed
import eu.advantly.ui.phone.resources.invite_error_claimed_neutral
import eu.advantly.ui.phone.resources.invite_error_expired
import eu.advantly.ui.phone.resources.invite_error_invalid
import eu.advantly.ui.phone.resources.invite_error_own
import eu.advantly.ui.phone.resources.invite_error_revoked
import eu.advantly.ui.phone.resources.invite_error_too_many
import eu.advantly.ui.phone.resources.invite_error_used
import eu.advantly.ui.phone.resources.invite_friend_text
import eu.advantly.ui.phone.resources.invite_friend_title
import eu.advantly.ui.phone.resources.invite_needs_account
import eu.advantly.ui.phone.resources.invite_open_links
import eu.advantly.ui.phone.resources.invite_pending
import eu.advantly.ui.phone.resources.invite_qr_description
import eu.advantly.ui.phone.resources.invite_qr_expired
import eu.advantly.ui.phone.resources.invite_qr_hint
import eu.advantly.ui.phone.resources.invite_qr_new
import eu.advantly.ui.phone.resources.invite_qr_remaining
import eu.advantly.ui.phone.resources.invite_retry
import eu.advantly.ui.phone.resources.invite_revoke_all
import eu.advantly.ui.phone.resources.invite_revoke_all_text
import eu.advantly.ui.phone.resources.invite_revoke_all_title
import eu.advantly.ui.phone.resources.invite_share_link
import eu.advantly.ui.phone.resources.invite_share_text
import eu.advantly.ui.phone.resources.invite_show_qr
import eu.advantly.ui.phone.resources.invite_title
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Block „Freunde einladen“ (plan-einladungen §5.1) unter „Dein Freundescode“: „Link teilen“ (nur mit Teilen-Menü der
 * Plattform) und „QR-Code zeigen“. Ein In-App-Scanner fehlt bewusst: die Systemkamera öffnet den App Link.
 */
@Composable
internal fun InviteBlock(
    model: InvitationsViewModel,
    onShareText: ((String) -> Unit)?,
    clock: WallClock,
) {
    val state by model.state.collectAsState()
    Title(Res.string.invite_title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onShareText != null) {
            Button(model::shareLink, enabled = !state.inviteBusy, modifier = Modifier.testTag("invite_share_link")) {
                Text(stringResource(Res.string.invite_share_link))
            }
        }
        OutlinedButton(model::openQr, modifier = Modifier.testTag("invite_show_qr")) { Text(stringResource(Res.string.invite_show_qr)) }
    }
    LaunchedEffect(model, state.access) { if (state.access == FriendsAccess.READY) model.loadFriendLinks() }
    var confirmRevoke by remember { mutableStateOf(false) }
    val open = state.friendLinks ?: 0
    if (open > 0) {
        FlowRow(verticalArrangement = Arrangement.Center) {
            Text(
                stringResource(Res.string.invite_open_links, open),
                color = AdvantlyTheme.colors.text2,
                modifier = Modifier.align(Alignment.CenterVertically).testTag("invite_open_links"),
            )
            TextButton({ confirmRevoke = true }, enabled = !state.inviteBusy, modifier = Modifier.testTag("invite_revoke_all")) {
                Text(stringResource(Res.string.invite_revoke_all))
            }
        }
    }
    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text(stringResource(Res.string.invite_revoke_all_title)) },
            text = { Text(stringResource(Res.string.invite_revoke_all_text)) },
            confirmButton = {
                TextButton(
                    {
                        confirmRevoke = false
                        model.revokeFriendLinks()
                    },
                    modifier = Modifier.testTag("invite_revoke_all_confirm"),
                ) { Text(stringResource(Res.string.invite_revoke_all)) }
            },
            dismissButton = { TextButton({ confirmRevoke = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
    if (!state.qrOpen) InviteError(state.inviteError, model::dismissInviteError)
    val share = state.share
    val text = share?.let { stringResource(Res.string.invite_share_text, it.link) }
    LaunchedEffect(share) {
        if (share != null && text != null) {
            onShareText?.invoke(text)
            model.shareHandled()
        }
    }
    if (state.qrOpen) QrSheet(model, state, clock)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrSheet(
    model: InvitationsViewModel,
    state: InvitationsState,
    clock: WallClock,
) {
    val colors = AdvantlyTheme.colors
    ModalBottomSheet(
        onDismissRequest = model::closeQr,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.m)
                .testTag("invite_qr_sheet"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(
                stringResource(Res.string.invite_show_qr),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                modifier = Modifier.semantics { heading() },
            )
            val qr = state.qr
            // Nur die Anzeige der Restzeit tickt; ein neuer Code entsteht nie im Takt.
            var now by remember { mutableLongStateOf(clock.nowMs()) }
            LaunchedEffect(qr) {
                while (qr != null) {
                    now = clock.nowMs()
                    if (now >= qr.expiresAtMillis) break
                    delay(1_000)
                }
            }
            if (qr == null) {
                if (state.inviteBusy) CircularProgressIndicator(Modifier.testTag("invite_qr_loading"))
            } else {
                val left = qr.expiresAtMillis - now
                QrCode(
                    qr.link,
                    stringResource(Res.string.invite_qr_description),
                    Modifier
                        .widthIn(max = 320.dp)
                        .fillMaxWidth()
                        .alpha(if (left > 0) 1f else 0.2f)
                        .testTag("invite_qr_code"),
                )
                Text(
                    if (left >
                        0
                    ) {
                        stringResource(Res.string.invite_qr_remaining, remaining(left))
                    } else {
                        stringResource(Res.string.invite_qr_expired)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (left > 0) colors.text else colors.text2,
                    modifier = Modifier.testTag("invite_qr_remaining"),
                )
            }
            Text(stringResource(Res.string.invite_qr_hint), color = colors.text2)
            InviteError(state.inviteError, model::dismissInviteError)
            Button(model::newQr, enabled = !state.inviteBusy, modifier = Modifier.testTag("invite_qr_new")) {
                Text(stringResource(Res.string.invite_qr_new))
            }
        }
    }
}

/** `m:ss`, aufgerundet auf volle Sekunden („Noch 9:41“). */
internal fun remaining(millis: Long): String {
    val seconds = (millis + 999) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun InviteError(
    error: String?,
    dismiss: () -> Unit,
) {
    error ?: return
    Text(stringResource(inviteErrorLabel(error)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("invite_error"))
    TextButton(dismiss) { Text(stringResource(Res.string.friends_dismiss)) }
}

/** Fehler beim Erzeugen eigener Links; Rest wie im Freunde-Tab. */
internal fun inviteErrorLabel(error: String): StringResource =
    if (error == "too_many_open_invites") Res.string.invite_error_too_many else errorLabel(error)

/** Hinweisleiste „Einladung wartet“, solange der Freunde-Zugang nicht bereit ist (Konto-Fluss, Freunde-Tab). */
@Composable
internal fun InvitePendingBanner(
    model: InvitationsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    val needsAccount = state.problem?.reason == InvitationsViewModel.NEEDS_ACCOUNT
    if (!state.pending && !needsAccount) return
    Column(modifier.fillMaxWidth().testTag("invite_pending")) {
        Text(
            stringResource(if (needsAccount) Res.string.invite_needs_account else Res.string.invite_pending),
            color = AdvantlyTheme.colors.text,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (needsAccount) {
            TextButton(model::dismissProblem, modifier = Modifier.testTag("invite_pending_dismiss")) {
                Text(stringResource(Res.string.friends_dismiss))
            }
        }
    }
}

/**
 * Vorschau-Dialoge Freund/Claim, Ergebnis-Snackbar und Hinweise zum empfangenen Link (Freunde-Tab mit nutzbarem Konto).
 */
@Composable
internal fun InviteRedeem(model: InvitationsViewModel) {
    val state by model.state.collectAsState()
    state.result?.let { result ->
        val name = result.alias?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.friends_unknown)
        val text =
            when {
                result.alreadyFriends -> stringResource(Res.string.invite_already_friends)
                result.kind == InviteKind.CLAIM_PLAYER -> stringResource(Res.string.invite_done_claim, result.matchCount, name)
                else -> stringResource(Res.string.invite_done_friend, name)
            }
        Snackbar(
            action = {
                TextButton(model::dismissResult, modifier = Modifier.testTag("invite_result_ok")) {
                    Text(stringResource(Res.string.friends_dismiss))
                }
            },
            modifier = Modifier.testTag("invite_result"),
        ) { Text(text) }
    }
    state.problem?.takeUnless { it.reason == InvitationsViewModel.NEEDS_ACCOUNT }?.let { problem ->
        Text(
            problemText(problem),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("invite_problem"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.pending) {
                TextButton(model::retry, enabled = !state.busy, modifier = Modifier.testTag("invite_retry")) {
                    Text(stringResource(Res.string.invite_retry))
                }
                TextButton(model::decline, modifier = Modifier.testTag("invite_discard")) {
                    Text(stringResource(Res.string.invite_decline))
                }
            } else {
                TextButton(model::dismissProblem, modifier = Modifier.testTag("invite_problem_ok")) {
                    Text(stringResource(Res.string.friends_dismiss))
                }
            }
        }
    }
    val preview = state.preview ?: return
    val name = preview.inviterAlias?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.friends_unknown)
    val claim = preview.kind == InviteKind.CLAIM_PLAYER && preview.status == InviteStatus.OK
    val already = preview.status == InviteStatus.ALREADY_FRIENDS
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                when {
                    already -> stringResource(Res.string.invite_already_friends)
                    claim -> stringResource(Res.string.invite_claim_title, preview.guestName.orEmpty())
                    else -> stringResource(Res.string.invite_friend_title, name)
                },
            )
        },
        text =
            if (already) {
                null
            } else {
                {
                    Text(
                        if (claim) {
                            stringResource(Res.string.invite_claim_text, name, preview.matchCount ?: 0, preview.guestName.orEmpty())
                        } else {
                            stringResource(Res.string.invite_friend_text, name)
                        },
                        modifier = Modifier.testTag("invite_dialog_text"),
                    )
                }
            },
        confirmButton = {
            if (already) {
                TextButton(model::decline, modifier = Modifier.testTag("invite_ok")) { Text(stringResource(Res.string.friends_dismiss)) }
            } else {
                TextButton(model::accept, enabled = !state.busy, modifier = Modifier.testTag("invite_accept")) {
                    Text(stringResource(if (claim) Res.string.invite_claim_accept else Res.string.invite_accept))
                }
            }
        },
        dismissButton =
            if (already) {
                null
            } else {
                {
                    TextButton(model::decline, modifier = Modifier.testTag("invite_decline")) {
                        Text(stringResource(if (claim) Res.string.invite_claim_decline else Res.string.invite_decline))
                    }
                }
            },
        modifier = Modifier.testTag(if (claim) "invite_claim_dialog" else "invite_friend_dialog"),
    )
}

@Composable
private fun problemText(problem: InviteProblem): String =
    when (problem.reason) {
        "invalid" -> {
            stringResource(Res.string.invite_error_invalid)
        }

        "expired" -> {
            stringResource(Res.string.invite_error_expired)
        }

        "used" -> {
            stringResource(Res.string.invite_error_used)
        }

        "revoked" -> {
            stringResource(Res.string.invite_error_revoked)
        }

        "own" -> {
            stringResource(Res.string.invite_error_own)
        }

        "already_claimed" -> {
            problem.guestName?.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.invite_error_claimed, it) }
                ?: stringResource(Res.string.invite_error_claimed_neutral)
        }

        else -> {
            stringResource(errorLabel(problem.reason))
        }
    }
