package eu.advantly.ui.phone.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.presentation.match.DeviceKind
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.ui.phone.common.SportIcon
import eu.advantly.ui.phone.common.sportName
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyIcons
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.end_abandon
import eu.advantly.ui.phone.resources.end_abandon_hint
import eu.advantly.ui.phone.resources.end_back
import eu.advantly.ui.phone.resources.end_confirm_abandon
import eu.advantly.ui.phone.resources.end_confirm_abandon_title
import eu.advantly.ui.phone.resources.end_confirm_stop
import eu.advantly.ui.phone.resources.end_confirm_stop_text
import eu.advantly.ui.phone.resources.end_confirm_stop_title
import eu.advantly.ui.phone.resources.end_display_title
import eu.advantly.ui.phone.resources.end_failed
import eu.advantly.ui.phone.resources.end_keep_playing
import eu.advantly.ui.phone.resources.end_stop
import eu.advantly.ui.phone.resources.end_stop_hint
import eu.advantly.ui.phone.resources.end_takeover_note
import eu.advantly.ui.phone.resources.hint_match_drawn
import eu.advantly.ui.phone.resources.hint_match_lost
import eu.advantly.ui.phone.resources.hint_match_won
import eu.advantly.ui.phone.resources.home_versus
import eu.advantly.ui.phone.resources.live_on_watch
import eu.advantly.ui.phone.resources.live_switch_a11y
import eu.advantly.ui.phone.resources.open_match_phone_scores
import eu.advantly.ui.phone.resources.open_match_scored_by
import eu.advantly.ui.phone.resources.open_match_watch_scores
import org.jetbrains.compose.resources.stringResource

/** Kurzbezeichnung eines Matches (Übersicht, Wechsel, Hinweise): „Tennis · vs. Jonas · 6:4 (2:1) 30:15“. */
@Composable
internal fun matchLabel(snapshot: LiveMatchSnapshot): String =
    stringResource(sportName(snapshot.setup.sport)) + " · " +
        stringResource(Res.string.home_versus, teamName(snapshot.setup, Side.B)) + " · " + compactScore(snapshot)

/**
 * Wer zählt ein offenes Match, mit Kennzeichnung der eigenen Uhr (F22): „Deine Uhr zählt“, „Dieses Handy zählt · Auf
 * deiner Uhr“ (Director, die Uhr zeigt an), „Dieses Handy zählt“ oder „Es zählt: …“.
 */
@Composable
internal fun openMatchRole(snapshot: LiveMatchSnapshot): String =
    when {
        snapshot.onWatch && !snapshot.isScorer && snapshot.scorerKind == DeviceKind.WATCH -> {
            stringResource(Res.string.open_match_watch_scores)
        }

        snapshot.isScorer -> {
            stringResource(Res.string.open_match_phone_scores) +
                if (snapshot.onWatch) " · " + stringResource(Res.string.live_on_watch) else ""
        }

        else -> {
            stringResource(Res.string.open_match_scored_by, scorerDevice(snapshot))
        }
    }

/**
 * Schneller Wechsel zwischen den offenen Matches im Live-Screen (F22): je Match ein Chip mit Sportart, Gegner und
 * Stand; das Uhr-Match trägt das Uhr-Symbol. Nur sichtbar, wenn mehr als ein Match offen ist.
 */
@Composable
internal fun MatchSwitcher(
    matches: List<LiveMatchSnapshot>,
    activeId: MatchId,
    outdoor: Boolean,
    onSelect: (LiveMatchSnapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (matches.size < 2) return
    val colors = AdvantlyTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .testTag("match_switcher"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        matches.forEachIndexed { index, match ->
            val selected = match.matchId == activeId
            val label = matchLabel(match)
            val a11y = stringResource(Res.string.live_switch_a11y, label)
            val border =
                if (selected) {
                    colors.brand
                } else if (outdoor) {
                    Color.White
                } else {
                    colors.line
                }
            Row(
                Modifier
                    .testTag("switch_match_$index")
                    .heightIn(min = Spacing.minTouchTarget)
                    .background(if (outdoor) Color.Black else colors.surface, Radii.pillShape)
                    .border(1.5.dp, border, Radii.pillShape)
                    .selectable(selected = selected, role = Role.Tab) { if (!selected) onSelect(match) }
                    .semantics { contentDescription = a11y }
                    .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                SportIcon(match.setup.sport, size = 16.dp)
                Text(
                    teamName(match.setup, Side.B) + " · " + compactScore(match),
                    style = AdvantlyTheme.typography.caption,
                    color = if (outdoor || selected) colors.text else colors.text2,
                    maxLines = 1,
                )
                if (match.onWatch) WatchMark(outdoor, Modifier.testTag("switch_match_watch_$index"))
            }
        }
    }
}

/** Uhr-Symbol: dieses Match läuft auf der eigenen Uhr. */
@Composable
internal fun WatchMark(
    outdoor: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(Res.string.live_on_watch)
    Icon(
        AdvantlyIcons.Watch,
        contentDescription = label,
        tint = if (outdoor) Color.White else AdvantlyTheme.colors.brand,
        modifier = modifier.size(16.dp),
    )
}

/** Was „Beenden“ bzw. „Abbrechen“ auslösen soll – erst nach Bestätigung (04 Prinzip 4). */
internal enum class EndChoice { STOP, ABANDON }

/**
 * Matchende am Handy (F21) für Zähler und Anzeige: „Beenden – Ergebnis gilt“ (`STOPPED`, Vorschau des Ergebnisses) und
 * „Abbrechen (ohne Wertung)“ (`ABANDONED`), jeweils mit Bestätigung. Zählt ein anderes Gerät (z. B. die Uhr), erklärt
 * das Sheet, dass dieses Handy vorher übernimmt. [extraOptions] = Aufgabe/nicht angetreten (nur Zähler).
 */
@Composable
internal fun BoxScope.EndChoicesSheet(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
    title: String,
    tag: String,
    onClose: () -> Unit,
    extraOptions: @Composable () -> Unit = {},
) {
    var confirm by remember { mutableStateOf<EndChoice?>(null) }
    var failed by remember { mutableStateOf(false) }
    LiveSheet(title = title, tag = tag) {
        Text(title, style = AdvantlyTheme.typography.title, color = AdvantlyTheme.colors.text)
        if (!snapshot.isScorer) {
            Text(
                stringResource(Res.string.end_takeover_note, scorerDevice(snapshot)),
                style = AdvantlyTheme.typography.body,
                color = AdvantlyTheme.colors.text2,
                modifier = Modifier.testTag("end_takeover_note"),
            )
        }
        SheetButton(
            stringResource(Res.string.end_stop),
            primary = false,
            onClick = { confirm = EndChoice.STOP },
            modifier = Modifier.fillMaxWidth().testTag("end_stop"),
        )
        Text(
            stringResource(Res.string.end_stop_hint, compactScore(snapshot)),
            style = AdvantlyTheme.typography.caption,
            color = AdvantlyTheme.colors.text2,
        )
        extraOptions()
        SheetButton(
            stringResource(Res.string.end_abandon),
            primary = false,
            onClick = { confirm = EndChoice.ABANDON },
            modifier = Modifier.fillMaxWidth().testTag("end_abandon"),
        )
        Text(stringResource(Res.string.end_abandon_hint), style = AdvantlyTheme.typography.caption, color = AdvantlyTheme.colors.text2)
        if (failed) {
            Text(
                stringResource(Res.string.end_failed),
                style = AdvantlyTheme.typography.label,
                color = AdvantlyTheme.colors.danger,
                modifier = Modifier.testTag("end_failed").semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        // „Weiterspielen“ statt „Abbrechen“: im Deutschen wäre das mit „Match abbrechen“ verwechselbar
        SheetButton(
            stringResource(Res.string.end_keep_playing),
            primary = true,
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().testTag("end_cancel"),
        )
    }
    confirm?.let { choice ->
        EndConfirmDialog(
            snapshot = snapshot,
            choice = choice,
            onConfirm = {
                confirm = null
                val ok = if (choice == EndChoice.STOP) controller.stopMatch() else controller.abandonMatch()
                if (ok) onClose() else failed = true
            },
            onDismiss = { confirm = null },
        )
    }
}

/** Sheet-Titel für Anzeige-Geräte („Dieses Match beenden?“). */
@Composable
internal fun displayEndTitle(): String = stringResource(Res.string.end_display_title)

/** Bestätigung „Match beenden?“ (mit Ergebnis-Vorschau) bzw. „Match abbrechen?“ (ohne Wertung). */
@Composable
private fun EndConfirmDialog(
    snapshot: LiveMatchSnapshot,
    choice: EndChoice,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stop = choice == EndChoice.STOP
    val outcome =
        stringResource(
            when (ScoringEngine.stoppedWinner(snapshot.state)) {
                Side.A -> Res.string.hint_match_won
                Side.B -> Res.string.hint_match_lost
                null -> Res.string.hint_match_drawn
            },
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (stop) Res.string.end_confirm_stop_title else Res.string.end_confirm_abandon_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    if (stop) {
                        stringResource(Res.string.end_confirm_stop_text, compactScore(snapshot), outcome)
                    } else {
                        stringResource(Res.string.end_abandon_hint)
                    },
                    modifier = Modifier.testTag("end_confirm_text"),
                )
                if (!snapshot.isScorer) Text(stringResource(Res.string.end_takeover_note, scorerDevice(snapshot)))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("end_confirm")) {
                Text(
                    stringResource(if (stop) Res.string.end_confirm_stop else Res.string.end_confirm_abandon),
                    color = if (stop) AdvantlyTheme.colors.text else AdvantlyTheme.colors.danger,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("end_confirm_back")) {
                Text(stringResource(Res.string.end_back))
            }
        },
        modifier = Modifier.testTag("end_confirm_dialog"),
    )
}

/** Übersichtskarte eines offenen Matches (Start-Tab, F22): Sportart, Teams, Stand, Zähler und Uhr-Kennzeichnung. */
@Composable
internal fun OpenMatchCardContent(snapshot: LiveMatchSnapshot) {
    val colors = AdvantlyTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SportIcon(snapshot.setup.sport, size = 20.dp)
        Text(
            teamName(snapshot.setup, Side.A) + " – " + teamName(snapshot.setup, Side.B),
            style = AdvantlyTheme.typography.label,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        if (snapshot.onWatch) WatchMark(outdoor = false)
    }
    Text(compactScore(snapshot), style = AdvantlyTheme.typography.scoreS, color = colors.text)
    Text(
        openMatchRole(snapshot),
        style = AdvantlyTheme.typography.caption,
        color = colors.text2,
        modifier = Modifier.testTag("open_match_role"),
    )
}
