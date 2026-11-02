package eu.advantly.ui.phone.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import eu.advantly.presentation.friends.AcceptIssue
import eu.advantly.presentation.friends.FriendEntry
import eu.advantly.presentation.friends.FriendsAccess
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.presentation.friends.HandoverPhase
import eu.advantly.presentation.friends.LiveEntry
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.presentation.match.PlayersPort
import eu.advantly.ui.phone.common.AdvantlyCard
import eu.advantly.ui.phone.common.ChoiceChip
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.account_cancel
import eu.advantly.ui.phone.resources.friends_dismiss
import eu.advantly.ui.phone.resources.friends_unknown
import eu.advantly.ui.phone.resources.handover_accepted
import eu.advantly.ui.phone.resources.handover_awaiting_watch
import eu.advantly.ui.phone.resources.handover_button
import eu.advantly.ui.phone.resources.handover_cancel
import eu.advantly.ui.phone.resources.handover_failed
import eu.advantly.ui.phone.resources.handover_hint
import eu.advantly.ui.phone.resources.handover_invited
import eu.advantly.ui.phone.resources.handover_queued
import eu.advantly.ui.phone.resources.handover_title
import eu.advantly.ui.phone.resources.handover_to
import eu.advantly.ui.phone.resources.scorer_accept_phone
import eu.advantly.ui.phone.resources.scorer_accept_watch
import eu.advantly.ui.phone.resources.scorer_decline
import eu.advantly.ui.phone.resources.scorer_fell_back
import eu.advantly.ui.phone.resources.scorer_handover_ended
import eu.advantly.ui.phone.resources.scorer_handover_pending
import eu.advantly.ui.phone.resources.scorer_invitation
import eu.advantly.ui.phone.resources.scorer_open
import eu.advantly.ui.phone.resources.scorer_retry
import eu.advantly.ui.phone.resources.scorer_takeover
import eu.advantly.ui.phone.resources.scorer_takeover_confirm
import eu.advantly.ui.phone.resources.scorer_takeover_pending
import eu.advantly.ui.phone.resources.scorer_takeover_warning
import eu.advantly.ui.phone.resources.scorer_waiting
import eu.advantly.ui.phone.resources.visitors_close
import eu.advantly.ui.phone.resources.wizard_handover_hint
import eu.advantly.ui.phone.resources.wizard_handover_title
import org.jetbrains.compose.resources.stringResource

/**
 * Ziele von „Zählen weitergeben“: als Freund gesetzte Spieler des Lineups und laut Abgleich verknüpfte Mitspieler mit
 * Konto (z. B. nachträglich zugewiesen), ohne mich.
 */
internal fun handoverCandidates(
    model: FriendsViewModel,
    snapshot: LiveMatchSnapshot,
    players: PlayersPort,
): List<FriendEntry> {
    val lineup = snapshot.setup.lineup
    val seated =
        (lineup.a + lineup.b).mapNotNull { id ->
            players.friendProfile(id)?.let { FriendEntry(it, snapshot.setup.player(id)?.displayName) }
        }
    return (seated + model.linkedPlayers(snapshot.matchId.value)).distinctBy { it.profileId }
}

/**
 * Zählendes Handy (plan-live-mitspieler §3.4): „Zählen weitergeben an …“ unter den verknüpften Mitspielern mit Konto
 * ([candidates]). Dieses Handy zählt weiter, bis die Einladung angenommen ist, und gibt dann selbst ab.
 */
@Composable
fun LiveHandoverButton(
    model: FriendsViewModel,
    matchId: String,
    candidates: List<FriendEntry>,
) {
    val state by model.state.collectAsState()
    if (state.access != FriendsAccess.READY || !model.canHandOver(matchId)) return
    val status = state.handovers[matchId]
    if (candidates.isEmpty() && status == null) return
    val unknown = stringResource(Res.string.friends_unknown)

    fun name(profileId: String) =
        candidates.firstOrNull { it.profileId == profileId }?.alias?.takeIf { it.isNotBlank() }
            ?: state.friends
                .firstOrNull { it.profileId == profileId }
                ?.alias
                ?.takeIf { it.isNotBlank() }
            ?: unknown
    if (status?.phase == HandoverPhase.FAILED) {
        // Server lehnte die Abgabe ab: dieses Handy bzw. die eigene Uhr zählt wieder; sichtbar ohne Dialog.
        Text(
            stringResource(Res.string.handover_failed, name(status.scorerId)),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("live_handover_failed"),
        )
        TextButton({ model.cancelHandover(matchId) }, modifier = Modifier.testTag("live_handover_failed_ok")) {
            Text(stringResource(Res.string.friends_dismiss))
        }
        return
    }
    var open by remember(matchId) { mutableStateOf(false) }
    TextButton({ open = true }, modifier = Modifier.testTag("live_handover")) { Text(stringResource(Res.string.handover_button)) }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(Res.string.handover_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.testTag("live_handover_dialog")) {
                if (status == null) {
                    Text(stringResource(Res.string.handover_hint), color = AdvantlyTheme.colors.text2)
                    candidates.forEach { friend ->
                        TextButton(
                            { model.handOver(matchId, friend.profileId) },
                            modifier = Modifier.testTag("live_handover_to_${friend.profileId}"),
                        ) { Text(stringResource(Res.string.handover_to, name(friend.profileId))) }
                    }
                } else {
                    val text =
                        when (status.phase) {
                            HandoverPhase.QUEUED -> Res.string.handover_queued
                            HandoverPhase.INVITED -> Res.string.handover_invited
                            HandoverPhase.ACCEPTED -> Res.string.handover_accepted
                            HandoverPhase.AWAITING_WATCH -> Res.string.handover_awaiting_watch
                            HandoverPhase.FAILED -> Res.string.handover_failed
                        }
                    Text(stringResource(text, name(status.scorerId)), modifier = Modifier.testTag("live_handover_status"))
                    if (status.phase != HandoverPhase.ACCEPTED) {
                        TextButton({ model.cancelHandover(matchId) }, modifier = Modifier.testTag("live_handover_cancel")) {
                            Text(stringResource(Res.string.handover_cancel))
                        }
                    }
                }
                HandoverError(model, state.handoverError)
            }
        },
        confirmButton = {
            TextButton(
                { open = false },
                modifier = Modifier.testTag("live_handover_close"),
            ) { Text(stringResource(Res.string.visitors_close)) }
        },
    )
}

/** Empfänger: Die angenommene Übergabe kam nicht (rechtzeitig); nach Ablauf der Frist kann erneut gewartet werden. */
@Composable
private fun AcceptIssueHint(
    model: FriendsViewModel,
    matchId: String,
    issue: AcceptIssue,
) {
    val text =
        when (issue) {
            AcceptIssue.FELL_BACK -> Res.string.scorer_fell_back
            AcceptIssue.ENDED -> Res.string.scorer_handover_ended
            AcceptIssue.TIMED_OUT -> Res.string.scorer_handover_pending
        }
    Text(stringResource(text), color = AdvantlyTheme.colors.hint, modifier = Modifier.testTag("scorer_issue_${issue.name.lowercase()}"))
    if (issue == AcceptIssue.TIMED_OUT) {
        TextButton({ model.retryScoring(matchId) }, modifier = Modifier.testTag("scorer_retry")) {
            Text(stringResource(Res.string.scorer_retry))
        }
    }
    TextButton({ model.dismissScoringIssue(matchId) }, modifier = Modifier.testTag("scorer_issue_dismiss")) {
        Text(stringResource(Res.string.friends_dismiss))
    }
}

@Composable
private fun HandoverError(
    model: FriendsViewModel,
    error: String?,
) {
    error ?: return
    Text(
        stringResource(errorLabel(error)),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("handover_error"),
    )
    TextButton(model::dismissHandoverError) { Text(stringResource(Res.string.friends_dismiss)) }
}

/** Assistent, Schritt „Wer zählt?“: eine gesetzte Person mit Konto soll übernehmen (Einladung beim Start, minimal). */
@Composable
internal fun WizardHandover(
    friends: List<FriendEntry>,
    selected: String?,
    onChange: (String?) -> Unit,
) {
    if (friends.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = Spacing.m).testTag("wizard_handover")) {
        SectionTitle(stringResource(Res.string.wizard_handover_title))
        Text(
            stringResource(Res.string.wizard_handover_hint),
            style = AdvantlyTheme.typography.caption,
            color = AdvantlyTheme.colors.text2,
            modifier = Modifier.padding(bottom = Spacing.s),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            friends.forEach { friend ->
                ChoiceChip(
                    friend.alias.orEmpty(),
                    selected = friend.profileId == selected,
                    onClick = { onChange(friend.profileId.takeIf { it != selected }) },
                    tag = "wizard_handover_${friend.profileId}",
                    role = Role.RadioButton,
                )
            }
        }
    }
}

/**
 * Empfänger in der Live-Ansicht: Einladung annehmen (Handy oder eigene Uhr) bzw. ablehnen, Warten auf die Übergabe,
 * „Zählen öffnen“ nach dem Wechsel und die Notfall-Übernahme (nur angenommene Rolle, vollständiger Stand, mit Warnung).
 */
@Composable
internal fun ScorerActions(
    model: FriendsViewModel,
    entry: LiveEntry,
    onScore: (String) -> Unit,
) {
    if (!model.hasHandover) return
    val state by model.state.collectAsState()
    // Rollen kommen mit dem Abgleich: beim Öffnen (z. B. per Push) einmal anstoßen
    LaunchedEffect(entry.matchId) { model.syncNow() }
    val id = entry.matchId
    var confirm by remember(id) { mutableStateOf(false) }
    val colors = AdvantlyTheme.colors
    entry.acceptIssue?.let { AcceptIssueHint(model, id, it) }
    when {
        entry.scoring -> {
            TextButton({ onScore(id) }, modifier = Modifier.testTag("scorer_open")) { Text(stringResource(Res.string.scorer_open)) }
        }

        entry.awaiting -> {
            Text(stringResource(Res.string.scorer_waiting), color = colors.text2, modifier = Modifier.testTag("scorer_waiting"))
        }

        entry.scorerRole == "invited" -> {
            AdvantlyCard(Modifier.testTag("scorer_invitation"), border = colors.brand) {
                Text(stringResource(Res.string.scorer_invitation), style = MaterialTheme.typography.bodyLarge, color = colors.text)
                TextButton({ model.acceptScoring(id, onWatch = false) }, modifier = Modifier.testTag("scorer_accept_phone")) {
                    Text(stringResource(Res.string.scorer_accept_phone))
                }
                if (model.hasWatch()) {
                    TextButton({ model.acceptScoring(id, onWatch = true) }, modifier = Modifier.testTag("scorer_accept_watch")) {
                        Text(stringResource(Res.string.scorer_accept_watch))
                    }
                }
                TextButton({ model.declineScoring(id) }, modifier = Modifier.testTag("scorer_decline")) {
                    Text(stringResource(Res.string.scorer_decline))
                }
            }
        }

        entry.scorerRole == "accepted" -> {
            TextButton({ confirm = true }, enabled = entry.canTakeOver, modifier = Modifier.testTag("scorer_takeover")) {
                Text(stringResource(Res.string.scorer_takeover))
            }
            if (!entry.canTakeOver) {
                Text(
                    stringResource(Res.string.scorer_takeover_pending),
                    style = AdvantlyTheme.typography.caption,
                    color = colors.text2,
                )
            }
        }
    }
    HandoverError(model, state.handoverError)
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(Res.string.scorer_takeover)) },
            text = { Text(stringResource(Res.string.scorer_takeover_warning)) },
            confirmButton = {
                TextButton({
                    confirm = false
                    model.takeOverScoring(id)
                }, modifier = Modifier.testTag("scorer_takeover_confirm")) { Text(stringResource(Res.string.scorer_takeover_confirm)) }
            },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(Res.string.account_cancel)) } },
            modifier = Modifier.testTag("scorer_takeover_dialog"),
        )
    }
}
