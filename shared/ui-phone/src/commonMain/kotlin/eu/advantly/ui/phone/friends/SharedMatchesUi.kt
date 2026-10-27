package eu.advantly.ui.phone.friends

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import eu.advantly.core.model.Side
import eu.advantly.presentation.friends.FeedMatch
import eu.advantly.presentation.friends.FriendsAccess
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.common.ToggleRow
import eu.advantly.ui.phone.common.sportName
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.account_cancel
import eu.advantly.ui.phone.resources.friends_dismiss
import eu.advantly.ui.phone.resources.friends_feed_back
import eu.advantly.ui.phone.resources.friends_feed_detail_hint
import eu.advantly.ui.phone.resources.friends_feed_detail_title
import eu.advantly.ui.phone.resources.friends_feed_empty
import eu.advantly.ui.phone.resources.friends_feed_no_score
import eu.advantly.ui.phone.resources.friends_feed_set
import eu.advantly.ui.phone.resources.friends_feed_side_a
import eu.advantly.ui.phone.resources.friends_feed_side_b
import eu.advantly.ui.phone.resources.friends_feed_status_finished
import eu.advantly.ui.phone.resources.friends_feed_status_running
import eu.advantly.ui.phone.resources.friends_feed_status_scheduled
import eu.advantly.ui.phone.resources.friends_feed_vs
import eu.advantly.ui.phone.resources.friends_feed_winner
import eu.advantly.ui.phone.resources.friends_unknown
import eu.advantly.ui.phone.resources.sharing_default_hint
import eu.advantly.ui.phone.resources.sharing_default_loading
import eu.advantly.ui.phone.resources.sharing_default_retry
import eu.advantly.ui.phone.resources.sharing_default_title
import eu.advantly.ui.phone.resources.sharing_hide
import eu.advantly.ui.phone.resources.sharing_hide_text
import eu.advantly.ui.phone.resources.sharing_linked
import eu.advantly.ui.phone.resources.sharing_note_duplicate
import eu.advantly.ui.phone.resources.sharing_note_not_friend
import eu.advantly.ui.phone.resources.sharing_note_rejected
import eu.advantly.ui.phone.resources.sharing_reject
import eu.advantly.ui.phone.resources.sharing_reject_text
import eu.advantly.ui.phone.resources.sharing_share
import eu.advantly.ui.phone.resources.sharing_share_hint
import eu.advantly.ui.phone.resources.sharing_title
import eu.advantly.ui.phone.resources.sharing_unavailable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Feed-Liste (P2-E): je Match Sportart, Status und Paarung; Tippen öffnet die Nur-Lese-Ansicht. */
@Composable
internal fun FeedList(
    feed: List<FeedMatch>,
    onOpen: (String) -> Unit,
) {
    if (feed.isEmpty()) {
        Text(
            stringResource(Res.string.friends_feed_empty),
            color = AdvantlyTheme.colors.text2,
            modifier = Modifier.testTag("friends_feed_empty"),
        )
        return
    }
    feed.forEach { match ->
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onOpen(match.id) }
                .padding(vertical = Spacing.s)
                .testTag("friends_feed_${match.id}"),
        ) {
            Text(headline(match), style = MaterialTheme.typography.bodyLarge, color = AdvantlyTheme.colors.text)
            Text(
                stringResource(Res.string.friends_feed_vs, names(match.teamA), names(match.teamB)),
                color = AdvantlyTheme.colors.text2,
            )
        }
        HorizontalDivider()
    }
}

/** Nur-Lese-Detail eines Feed-Matches; es gibt bewusst keine Aktionen (kein Ersteller, keine Events, keine Telemetrie). */
@Composable
internal fun FeedDetail(
    match: FeedMatch,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("friends_feed_detail"), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        TextButton(onBack, modifier = Modifier.testTag("friends_feed_back")) { Text(stringResource(Res.string.friends_feed_back)) }
        Text(
            stringResource(Res.string.friends_feed_detail_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(headline(match), color = AdvantlyTheme.colors.text)
        Text(stringResource(Res.string.friends_feed_detail_hint), color = AdvantlyTheme.colors.text2)
        Text("${stringResource(Res.string.friends_feed_side_a)}: ${names(match.teamA)}", modifier = Modifier.testTag("friends_feed_team_a"))
        Text("${stringResource(Res.string.friends_feed_side_b)}: ${names(match.teamB)}", modifier = Modifier.testTag("friends_feed_team_b"))
        match.winner?.let { side ->
            Text(
                stringResource(Res.string.friends_feed_winner, names(if (side == Side.A) match.teamA else match.teamB)),
                modifier = Modifier.testTag("friends_feed_winner"),
            )
        }
        if (match.sets.isEmpty()) Text(stringResource(Res.string.friends_feed_no_score), color = AdvantlyTheme.colors.text2)
        match.sets.forEachIndexed { index, (a, b) ->
            Text(stringResource(Res.string.friends_feed_set, index + 1, a, b), modifier = Modifier.testTag("friends_feed_set_$index"))
        }
    }
}

@Composable
private fun headline(match: FeedMatch): String {
    val status =
        when (match.status) {
            "finished" -> Res.string.friends_feed_status_finished
            "scheduled" -> Res.string.friends_feed_status_scheduled
            else -> Res.string.friends_feed_status_running
        }
    return listOfNotNull(match.sport?.let { stringResource(sportName(it)) }, stringResource(status), match.startedAt?.take(10))
        .joinToString(" · ")
}

@Composable
private fun names(team: List<String>): String {
    val unknown = stringResource(Res.string.friends_unknown)
    return team.joinToString(" / ") { it.ifBlank { unknown } }
}

private enum class SharingConfirm(
    val action: StringResource,
    val text: StringResource,
) {
    REJECT(Res.string.sharing_reject, Res.string.sharing_reject_text),
    HIDE(Res.string.sharing_hide, Res.string.sharing_hide_text),
}

/**
 * Freunde-Abschnitt im Match-Detail: „Mit Freunden teilen“, „Aus meinem Verlauf ausblenden“, „War ich nicht“ und
 * Hinweise zur Verknüpfung eingetragener Freunde. Nur mit Konto; [slotName] löst Slot-IDs in lokale Namen auf.
 * [onGone]: Match gehört nicht mehr zu meiner Sicht (Detail schließen, lokal entfernen).
 */
@Composable
fun MatchSharingSection(
    model: FriendsViewModel,
    matchId: String,
    slotName: (String) -> String?,
    onGone: () -> Unit,
) {
    val state by model.state.collectAsState()
    DisposableEffect(model, matchId) {
        model.openMatch(matchId)
        onDispose { model.openMatch(null) }
    }
    val sharing = state.match?.takeIf { it.matchId == matchId }
    LaunchedEffect(sharing?.gone) { if (sharing?.gone == true) onGone() }
    if (state.access != FriendsAccess.READY || sharing == null || sharing.gone) return
    var confirm by remember { mutableStateOf<SharingConfirm?>(null) }
    SectionTitle(stringResource(Res.string.sharing_title))
    Column(Modifier.fillMaxWidth().testTag("sharing_section")) {
        sharing.linkNotes.forEach { (slot, note) ->
            val name = slotName(slot)?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.friends_unknown)
            val text =
                when (note) {
                    "not_friend" -> Res.string.sharing_note_not_friend
                    "link_rejected" -> Res.string.sharing_note_rejected
                    else -> Res.string.sharing_note_duplicate
                }
            Text(
                stringResource(text, name),
                style = AdvantlyTheme.typography.body,
                color = AdvantlyTheme.colors.text2,
                modifier = Modifier.testTag("sharing_note_$slot"),
            )
        }
        val shared = sharing.shared
        if (shared == null) {
            Text(
                stringResource(Res.string.sharing_unavailable),
                style = AdvantlyTheme.typography.body,
                color = AdvantlyTheme.colors.text2,
                modifier = Modifier.testTag("sharing_unavailable"),
            )
        } else {
            if (sharing.canReject) {
                Text(
                    stringResource(Res.string.sharing_linked),
                    style = AdvantlyTheme.typography.body,
                    modifier = Modifier.testTag("sharing_linked"),
                )
            }
            ToggleRow(
                stringResource(Res.string.sharing_share),
                stringResource(Res.string.sharing_share_hint),
                checked = shared,
                onChange = model::setShared,
                tag = "sharing_share",
            )
            if (sharing.canHide) {
                TextButton({ confirm = SharingConfirm.HIDE }, enabled = !state.busy, modifier = Modifier.testTag("sharing_hide")) {
                    Text(stringResource(Res.string.sharing_hide))
                }
            }
            if (sharing.canReject) {
                TextButton({ confirm = SharingConfirm.REJECT }, enabled = !state.busy, modifier = Modifier.testTag("sharing_reject")) {
                    Text(stringResource(Res.string.sharing_reject), color = AdvantlyTheme.colors.danger)
                }
            }
        }
        state.error?.let { error ->
            Text(stringResource(errorLabel(error)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sharing_error"))
            TextButton(model::dismissError) { Text(stringResource(Res.string.friends_dismiss)) }
        }
    }
    confirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(pending.action)) },
            text = { Text(stringResource(pending.text)) },
            confirmButton = {
                TextButton({
                    confirm = null
                    if (pending == SharingConfirm.REJECT) model.rejectParticipation() else model.hideMatch()
                }, modifier = Modifier.testTag("sharing_confirm")) { Text(stringResource(pending.action)) }
            },
            dismissButton = {
                TextButton({ confirm = null }, modifier = Modifier.testTag("sharing_confirm_cancel")) {
                    Text(stringResource(Res.string.account_cancel))
                }
            },
            modifier = Modifier.testTag("sharing_confirm_dialog"),
        )
    }
}

/** Profileinstellung `share_matches_default` (Standard aus); nur mit nutzbarem Konto. */
@Composable
fun ShareDefaultSection(model: FriendsViewModel) {
    val state by model.state.collectAsState()
    if (state.access != FriendsAccess.READY) return
    // Erst wenn nichts anderes läuft: ein belegtes ViewModel würde den Aufruf sonst still verwerfen.
    LaunchedEffect(model, state.busy) { if (!state.busy && state.shareDefault == null && state.error == null) model.loadShareDefault() }
    val share = state.shareDefault
    if (share == null) {
        if (!state.busy && state.error != null) {
            TextButton(model::loadShareDefault, modifier = Modifier.testTag("sharing_default_retry")) {
                Text(stringResource(Res.string.sharing_default_retry))
            }
        } else {
            Text(stringResource(Res.string.sharing_default_loading), color = AdvantlyTheme.colors.text2)
        }
        return
    }
    ToggleRow(
        stringResource(Res.string.sharing_default_title),
        stringResource(Res.string.sharing_default_hint),
        checked = share,
        onChange = model::setShareDefault,
        tag = "sharing_default",
    )
}
