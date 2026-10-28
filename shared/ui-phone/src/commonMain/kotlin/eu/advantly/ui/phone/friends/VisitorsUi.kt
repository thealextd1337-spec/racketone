package eu.advantly.ui.phone.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.advantly.presentation.friends.FriendEntry
import eu.advantly.presentation.friends.FriendsAccess
import eu.advantly.presentation.friends.FriendsState
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.presentation.friends.WatchedMatch
import eu.advantly.ui.phone.common.ChoiceChip
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.account_cancel
import eu.advantly.ui.phone.resources.friends_dismiss
import eu.advantly.ui.phone.resources.friends_feed_vs
import eu.advantly.ui.phone.resources.friends_unknown
import eu.advantly.ui.phone.resources.visitors_add
import eu.advantly.ui.phone.resources.visitors_add_confirm
import eu.advantly.ui.phone.resources.visitors_close
import eu.advantly.ui.phone.resources.visitors_empty
import eu.advantly.ui.phone.resources.visitors_hint
import eu.advantly.ui.phone.resources.visitors_limit
import eu.advantly.ui.phone.resources.visitors_loading
import eu.advantly.ui.phone.resources.visitors_no_friends
import eu.advantly.ui.phone.resources.visitors_pending
import eu.advantly.ui.phone.resources.visitors_remove
import eu.advantly.ui.phone.resources.visitors_title
import eu.advantly.ui.phone.resources.watching_empty
import eu.advantly.ui.phone.resources.watching_games
import eu.advantly.ui.phone.resources.watching_live
import eu.advantly.ui.phone.resources.watching_loading
import eu.advantly.ui.phone.resources.watching_stop
import eu.advantly.ui.phone.resources.watching_tiebreak
import eu.advantly.ui.phone.resources.wizard_visitors_hint
import eu.advantly.ui.phone.resources.wizard_visitors_title
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Abfrageintervall von „Ich schaue zu“: Realtime ist serverseitig aus (`can_receive_realtime`), daher Polling. */
internal const val WATCHING_POLL_MS = 15_000L

/** Höchstens so viele aktive Zuschauer pro Match; der Server setzt dieselbe Grenze durch (`visitor_limit_reached`). */
internal const val MAX_VISITORS = 10

/** Mehrfachauswahl von Freunden als Zuschauer (Assistent und „Hinzufügen“). */
@Composable
private fun VisitorPicker(
    friends: List<FriendEntry>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    tagPrefix: String,
    limit: Int,
) {
    if (selected.size >= limit) {
        Text(
            stringResource(Res.string.visitors_limit),
            style = AdvantlyTheme.typography.caption,
            color = AdvantlyTheme.colors.text2,
            modifier = Modifier.testTag("${tagPrefix}_limit"),
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        friends.forEach { friend ->
            ChoiceChip(
                friend.alias.orEmpty(),
                selected = friend.profileId in selected,
                // Abwählen geht immer, Hinzufügen nur unterhalb der Grenze.
                onClick = { if (friend.profileId in selected || selected.size < limit) onToggle(friend.profileId) },
                tag = "${tagPrefix}_${friend.profileId}",
            )
        }
    }
}

/** Assistent, Schritt „Wer zählt?“: optionale Zuschauer aus den Freunden; ohne Konto oder Freunde unsichtbar. */
@Composable
internal fun WizardVisitors(
    friends: List<FriendEntry>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    if (friends.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("wizard_visitors")) {
        SectionTitle(stringResource(Res.string.wizard_visitors_title))
        Text(
            stringResource(Res.string.wizard_visitors_hint),
            style = AdvantlyTheme.typography.caption,
            color = AdvantlyTheme.colors.text2,
            modifier = Modifier.padding(bottom = Spacing.s),
        )
        VisitorPicker(
            friends,
            selected,
            { id -> onChange(if (id in selected) selected - id else selected + id) },
            "wizard_visitor",
            MAX_VISITORS,
        )
    }
}

/**
 * Zuschauer eines Matches (Detail und Live, F16/F25): aktuelle Zuschauer mit „Entfernen“, wo erlaubt (selbst benannt
 * oder Ersteller), und „Hinzufügen“ aus den eigenen Freunden. Nur mit nutzbarem Konto.
 */
@Composable
fun MatchVisitorsSection(
    model: FriendsViewModel,
    matchId: String,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    DisposableEffect(model, matchId) {
        model.openVisitors(matchId)
        onDispose { model.openVisitors(null) }
    }
    val section = state.visitors?.takeIf { it.matchId == matchId }
    if (state.access != FriendsAccess.READY || section == null) return
    var adding by remember(matchId) { mutableStateOf(false) }
    var chosen by remember(matchId) { mutableStateOf(emptySet<String>()) }
    val colors = AdvantlyTheme.colors
    Column(modifier.fillMaxWidth().testTag("visitors_section")) {
        SectionTitle(stringResource(Res.string.visitors_title))
        Text(stringResource(Res.string.visitors_hint), style = AdvantlyTheme.typography.caption, color = colors.text2)
        when {
            section.visitors.isEmpty() && section.loading -> {
                Text(stringResource(Res.string.visitors_loading), color = colors.text2, modifier = Modifier.padding(top = Spacing.s))
            }

            section.visitors.isEmpty() -> {
                Text(
                    stringResource(Res.string.visitors_empty),
                    color = colors.text2,
                    modifier = Modifier.padding(top = Spacing.s).testTag("visitors_empty"),
                )
            }
        }
        val unknown = stringResource(Res.string.friends_unknown)
        section.visitors.forEach { visitor ->
            Row(
                Modifier.fillMaxWidth().testTag("visitor_${visitor.profileId}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(visitor.alias?.takeIf { it.isNotBlank() } ?: unknown, color = colors.text)
                    if (visitor.pending) {
                        Text(stringResource(Res.string.visitors_pending), style = AdvantlyTheme.typography.caption, color = colors.text2)
                    }
                }
                if (visitor.canRevoke) {
                    TextButton(
                        { model.removeVisitor(visitor.profileId) },
                        modifier = Modifier.testTag("visitor_remove_${visitor.profileId}"),
                    ) {
                        Text(stringResource(Res.string.visitors_remove))
                    }
                }
            }
        }
        val taken = section.visitors.mapTo(mutableSetOf()) { it.profileId } + section.players
        val candidates = state.friends.filter { it.profileId !in taken && !it.alias.isNullOrBlank() }
        val remaining = MAX_VISITORS - section.visitors.size
        if (remaining <= 0) {
            Text(stringResource(Res.string.visitors_limit), color = colors.text2, modifier = Modifier.testTag("visitors_limit"))
        } else if (!adding) {
            TextButton({ adding = true }, modifier = Modifier.testTag("visitors_add")) { Text(stringResource(Res.string.visitors_add)) }
        } else if (candidates.isEmpty()) {
            Text(stringResource(Res.string.visitors_no_friends), color = colors.text2, modifier = Modifier.testTag("visitors_no_friends"))
        } else {
            VisitorPicker(candidates, chosen, { id -> chosen = if (id in chosen) chosen - id else chosen + id }, "visitor_pick", remaining)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                TextButton({
                    model.addVisitors(chosen)
                    chosen = emptySet()
                    adding = false
                }, enabled = chosen.isNotEmpty(), modifier = Modifier.testTag("visitors_add_confirm")) {
                    Text(stringResource(Res.string.visitors_add_confirm))
                }
                TextButton({
                    chosen = emptySet()
                    adding = false
                }, modifier = Modifier.testTag("visitors_add_cancel")) { Text(stringResource(Res.string.account_cancel)) }
            }
        }
        section.error?.let { error ->
            Text(stringResource(errorLabel(error)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("visitors_error"))
            TextButton(model::dismissVisitorError) { Text(stringResource(Res.string.friends_dismiss)) }
        }
    }
}

/** Laufendes Match: kleiner Einstieg „Zuschauer“, öffnet die Verwaltung als Dialog über dem Live-Score. */
@Composable
fun LiveVisitorsButton(
    model: FriendsViewModel,
    matchId: String,
) {
    val state by model.state.collectAsState()
    if (state.access != FriendsAccess.READY) return
    var open by remember(matchId) { mutableStateOf(false) }
    TextButton({ open = true }, modifier = Modifier.testTag("live_visitors")) { Text(stringResource(Res.string.visitors_title)) }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton({ open = false }, modifier = Modifier.testTag("live_visitors_close")) {
                    Text(stringResource(Res.string.visitors_close))
                }
            },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { MatchVisitorsSection(model, matchId) } },
            modifier = Modifier.testTag("live_visitors_dialog"),
        )
    }
}

/** Freunde-Tab, Segment „Ich schaue zu“: Live-Stand bzw. Ergebnis, alle [WATCHING_POLL_MS] neu, solange sichtbar. */
@Composable
internal fun WatchingList(
    model: FriendsViewModel,
    state: FriendsState,
) {
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(model) {
        val job =
            scope.launch {
                while (true) {
                    model.loadWatching()
                    delay(WATCHING_POLL_MS)
                }
            }
        onPauseOrDispose { job.cancel() }
    }
    val colors = AdvantlyTheme.colors
    Column(Modifier.fillMaxWidth().testTag("friends_watching")) {
        state.watchingError?.let { error ->
            Text(stringResource(errorLabel(error)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("watching_error"))
        }
        val watching = state.watching
        when {
            watching == null -> {
                Text(stringResource(Res.string.watching_loading), color = colors.text2)
            }

            watching.isEmpty() -> {
                Text(
                    stringResource(Res.string.watching_empty),
                    color = colors.text2,
                    modifier = Modifier.testTag("watching_empty"),
                )
            }
        }
        watching.orEmpty().forEach { watched ->
            WatchedRow(watched) { model.stopWatching(watched.match.id) }
            HorizontalDivider()
        }
    }
}

@Composable
private fun WatchedRow(
    watched: WatchedMatch,
    onStop: () -> Unit,
) {
    val match = watched.match
    val colors = AdvantlyTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = Spacing.s).testTag("watching_${match.id}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(headline(match), style = MaterialTheme.typography.bodyLarge, color = colors.text)
        Text(stringResource(Res.string.friends_feed_vs, names(match.teamA), names(match.teamB)), color = colors.text2)
        if (match.sets.isNotEmpty()) {
            Text(
                match.sets.joinToString("  ") { (a, b) -> "$a:$b" },
                color = colors.text,
                modifier = Modifier.testTag("watching_sets_${match.id}"),
            )
        }
        val live =
            listOfNotNull(
                watched.games?.let { (a, b) -> stringResource(Res.string.watching_games, a, b) },
                watched.points?.let { (a, b) -> stringResource(Res.string.watching_live, a, b) },
                stringResource(Res.string.watching_tiebreak).takeIf { watched.tiebreak },
            )
        if (live.isNotEmpty()) Text(live.joinToString(" · "), color = colors.text, modifier = Modifier.testTag("watching_live_${match.id}"))
        TextButton(onStop, modifier = Modifier.testTag("watching_stop_${match.id}")) { Text(stringResource(Res.string.watching_stop)) }
    }
}
