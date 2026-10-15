package eu.advantly.ui.phone.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.ResultReason
import eu.advantly.core.scoring.SetKind
import eu.advantly.presentation.match.MatchHistoryPort
import eu.advantly.presentation.match.MatchSummary
import eu.advantly.ui.phone.common.ChoiceChip
import eu.advantly.ui.phone.common.SportIcon
import eu.advantly.ui.phone.common.durationText
import eu.advantly.ui.phone.common.scorePair
import eu.advantly.ui.phone.common.sportName
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.platform.formatDateTime
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.history_all
import eu.advantly.ui.phone.resources.history_empty
import eu.advantly.ui.phone.resources.history_empty_filtered
import eu.advantly.ui.phone.resources.history_record
import eu.advantly.ui.phone.resources.history_title
import eu.advantly.ui.phone.resources.home_versus
import eu.advantly.ui.phone.resources.result_abandoned
import eu.advantly.ui.phone.resources.result_draw
import eu.advantly.ui.phone.resources.result_loss
import eu.advantly.ui.phone.resources.result_win
import org.jetbrains.compose.resources.stringResource

/** H9 Verlauf (04 §4.1): Filter nach Sportart, Siegquote je Filter, Liste der beendeten Matches → H8. */
@Composable
fun HistoryScreen(
    history: MatchHistoryPort,
    onOpen: (MatchId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val matches by history.matches.collectAsState()
    var filterName by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = filterName?.let(Sport::valueOf)
    val shown = matches.filter { filter == null || it.setup.sport == filter }
    Column(modifier.fillMaxSize().testTag("history")) {
        Text(
            stringResource(Res.string.history_title),
            style = AdvantlyTheme.typography.headline,
            color = AdvantlyTheme.colors.text,
            modifier = Modifier.padding(start = Spacing.screenEdgeCompact, top = Spacing.xl, bottom = Spacing.m).semantics { heading() },
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.screenEdgeCompact),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            ChoiceChip(stringResource(Res.string.history_all), filter == null, { filterName = null }, "filter_ALL")
            Sport.entries.forEach { sport ->
                ChoiceChip(
                    text = stringResource(sportName(sport)),
                    selected = filter == sport,
                    onClick = { filterName = sport.name },
                    tag = "filter_${sport.name}",
                    leading = { SportIcon(sport, size = 16.dp) },
                )
            }
        }
        if (shown.isNotEmpty()) {
            Text(
                stringResource(Res.string.history_record, shown.count { it.result.winner == Side.A }, shown.size),
                style = AdvantlyTheme.typography.label,
                color = AdvantlyTheme.colors.text2,
                modifier = Modifier.padding(horizontal = Spacing.screenEdgeCompact, vertical = Spacing.m).testTag("history_record"),
            )
        }
        if (shown.isEmpty()) {
            Text(
                stringResource(if (matches.isEmpty()) Res.string.history_empty else Res.string.history_empty_filtered),
                style = AdvantlyTheme.typography.body,
                color = AdvantlyTheme.colors.text2,
                modifier = Modifier.padding(Spacing.screenEdgeCompact).testTag("history_empty"),
            )
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            itemsIndexed(shown, key = { _, m -> m.matchId.value }) { index, match ->
                HistoryItem(match, index, onClick = { onOpen(match.matchId) })
            }
        }
    }
}

@Composable
private fun HistoryItem(
    match: MatchSummary,
    index: Int,
    onClick: () -> Unit,
) {
    val colors = AdvantlyTheme.colors
    val sport = match.setup.sport
    Row(
        Modifier
            .testTag("history_item_$index")
            .padding(horizontal = Spacing.screenEdgeCompact)
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(colors.surface, Radii.cardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        SportIcon(sport, size = 28.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(sportName(sport)) + " · " + stringResource(Res.string.home_versus, teamName(match.setup, Side.B)),
                style = AdvantlyTheme.typography.label,
                color = colors.text,
            )
            Text(setsLine(match), style = AdvantlyTheme.typography.scoreS, color = colors.text)
            Text(
                formatDateTime(match.finishedAtMs) + " · " + durationText(match.durationMs),
                style = AdvantlyTheme.typography.caption,
                color = colors.text2,
            )
        }
        ResultBadge(match)
    }
}

/** Sieg/Niederlage/Unentschieden/Abgebrochen – Text + Farbe (nie nur Farbe). */
@Composable
fun ResultBadge(
    match: MatchSummary,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    val (text, color) =
        when {
            match.result.reason == ResultReason.DRAW -> stringResource(Res.string.result_draw) to colors.text2
            match.result.winner == null -> stringResource(Res.string.result_abandoned) to colors.text2
            match.result.winner == Side.A -> stringResource(Res.string.result_win) to colors.success
            else -> stringResource(Res.string.result_loss) to colors.danger
        }
    Text(
        text.uppercase(),
        style = AdvantlyTheme.typography.overline,
        color = color,
        modifier =
            modifier
                .background(color.copy(alpha = 0.14f), Radii.pillShape)
                .padding(horizontal = Spacing.s, vertical = Spacing.xs)
                .testTag("result_badge"),
    )
}

/** „6:4 3:6 10:7“ aus der Sicht von Wir. */
@Composable
fun setsLine(match: MatchSummary): String =
    match.sets
        .map { set ->
            val pair =
                when (set.kind) {
                    SetKind.REGULAR -> set.games
                    SetKind.MATCH_TIEBREAK -> set.tiebreak ?: set.points
                    else -> set.points
                }
            pair?.let { scorePair(it) }.orEmpty()
        }.joinToString(" ")
        .ifEmpty { scorePair(match.setsWon) }
