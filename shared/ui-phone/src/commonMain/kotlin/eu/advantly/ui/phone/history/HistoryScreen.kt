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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.presentation.match.MatchHistoryPort
import eu.advantly.presentation.match.MatchSummary
import eu.advantly.presentation.match.isDraw
import eu.advantly.presentation.match.isRated
import eu.advantly.presentation.match.localDayAt
import eu.advantly.presentation.stats.HistoryFilter
import eu.advantly.presentation.stats.HistoryGrouping
import eu.advantly.presentation.stats.HistoryOutcome
import eu.advantly.presentation.stats.HistoryRole
import eu.advantly.presentation.stats.filterHistory
import eu.advantly.presentation.stats.groupHistory
import eu.advantly.presentation.stats.historyPeople
import eu.advantly.presentation.stats.outcome
import eu.advantly.ui.phone.common.ChoiceChip
import eu.advantly.ui.phone.common.LocalSelfAliases
import eu.advantly.ui.phone.common.LocalSelfId
import eu.advantly.ui.phone.common.SportIcon
import eu.advantly.ui.phone.common.durationText
import eu.advantly.ui.phone.common.resultLine
import eu.advantly.ui.phone.common.sportName
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.platform.formatDateTime
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.history_abandoned_note
import eu.advantly.ui.phone.resources.history_all
import eu.advantly.ui.phone.resources.history_by_month
import eu.advantly.ui.phone.resources.history_by_week
import eu.advantly.ui.phone.resources.history_chip_draw
import eu.advantly.ui.phone.resources.history_chip_loss
import eu.advantly.ui.phone.resources.history_chip_win
import eu.advantly.ui.phone.resources.history_empty
import eu.advantly.ui.phone.resources.history_empty_filtered
import eu.advantly.ui.phone.resources.history_month_group
import eu.advantly.ui.phone.resources.history_opponents
import eu.advantly.ui.phone.resources.history_partners
import eu.advantly.ui.phone.resources.history_people_all
import eu.advantly.ui.phone.resources.history_record
import eu.advantly.ui.phone.resources.history_role_all
import eu.advantly.ui.phone.resources.history_search
import eu.advantly.ui.phone.resources.history_title
import eu.advantly.ui.phone.resources.history_week_group
import eu.advantly.ui.phone.resources.home_versus
import eu.advantly.ui.phone.resources.result_abandoned
import eu.advantly.ui.phone.resources.result_draw
import eu.advantly.ui.phone.resources.result_loss
import eu.advantly.ui.phone.resources.result_win
import org.jetbrains.compose.resources.stringResource

/** H9: Namenssuche, Sport-/Personenfilter und lokale Kalendergruppen ohne Event-Replay. */
@Composable
fun HistoryScreen(
    history: MatchHistoryPort,
    onOpen: (MatchId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val matches by history.matches.collectAsState()
    var filterName by rememberSaveable { mutableStateOf<String?>(null) }
    var personId by rememberSaveable { mutableStateOf<String?>(null) }
    var roleName by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var groupingName by rememberSaveable { mutableStateOf(HistoryGrouping.MONTH.name) }
    val filter = filterName?.let(Sport::valueOf)
    val role = roleName?.let(HistoryRole::valueOf)
    val selfIds = LocalSelfAliases.current + listOfNotNull(LocalSelfId.current)
    val people =
        remember(matches, selfIds) {
            matches
                .flatMap {
                    historyPeople(
                        it.setup,
                        selfIds,
                    )
                }.distinctBy { it.player.id to it.role }
                .sortedBy { it.player.displayName.lowercase() }
        }
    val shown =
        remember(matches, filter, personId, role, query, selfIds) {
            filterHistory(matches, HistoryFilter(filter, personId?.let(::PlayerId), role, query), selfIds)
        }
    val grouping = HistoryGrouping.valueOf(groupingName)
    val groups = remember(shown, grouping) { groupHistory(shown, grouping, ::localDayAt) }
    LazyColumn(
        modifier.fillMaxSize().testTag("history"),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item {
            Text(
                stringResource(Res.string.history_title),
                style = AdvantlyTheme.typography.headline,
                color = AdvantlyTheme.colors.text,
                modifier =
                    Modifier
                        .padding(
                            start = Spacing.screenEdgeCompact,
                            top = Spacing.xl,
                            bottom = Spacing.m,
                        ).semantics { heading() },
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(Res.string.history_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenEdgeCompact).testTag("history_search"),
            )
        }
        item {
            FilterRow {
                ChoiceChip(stringResource(Res.string.history_all), filter == null, { filterName = null }, "filter_ALL")
                Sport.entries.forEach { sport ->
                    ChoiceChip(stringResource(sportName(sport)), filter == sport, { filterName = sport.name }, "filter_${sport.name}")
                }
            }
        }
        item {
            FilterRow {
                ChoiceChip(stringResource(Res.string.history_role_all), role == null, { roleName = null }, "role_ALL")
                HistoryRole.entries.forEach { choice ->
                    ChoiceChip(
                        stringResource(if (choice == HistoryRole.OPPONENT) Res.string.history_opponents else Res.string.history_partners),
                        role == choice,
                        { roleName = choice.name },
                        "role_${choice.name}",
                    )
                }
            }
        }
        item {
            FilterRow {
                ChoiceChip(stringResource(Res.string.history_people_all), personId == null, { personId = null }, "person_ALL")
                people.filter { role == null || it.role == role }.distinctBy { it.player.id }.forEach { person ->
                    ChoiceChip(person.player.displayName, personId == person.player.id.value, {
                        personId = person.player.id.value
                    }, "person_${person.player.id.value}")
                }
            }
        }
        item {
            FilterRow {
                HistoryGrouping.entries.forEach { choice ->
                    ChoiceChip(
                        stringResource(if (choice == HistoryGrouping.MONTH) Res.string.history_by_month else Res.string.history_by_week),
                        grouping == choice,
                        { groupingName = choice.name },
                        "group_${choice.name}",
                    )
                }
            }
        }
        item {
            val rated = shown.filter { it.isRated }
            Text(
                stringResource(Res.string.history_record, rated.count { it.result.winner == Side.A }, rated.size),
                style = AdvantlyTheme.typography.label,
                color = AdvantlyTheme.colors.text2,
                modifier = Modifier.padding(horizontal = Spacing.screenEdgeCompact, vertical = Spacing.m).testTag("history_record"),
            )
            if (rated.size < shown.size) {
                Text(
                    stringResource(Res.string.history_abandoned_note),
                    color = AdvantlyTheme.colors.text2,
                    modifier = Modifier.padding(horizontal = Spacing.screenEdgeCompact).testTag("history_abandoned_note"),
                )
            }
            if (shown.isEmpty()) {
                Text(
                    stringResource(if (matches.isEmpty()) Res.string.history_empty else Res.string.history_empty_filtered),
                    color = AdvantlyTheme.colors.text2,
                    modifier = Modifier.padding(Spacing.screenEdgeCompact).testTag("history_empty"),
                )
            }
        }
        var offset = 0
        groups.forEach { group ->
            item(key = "group_${group.start}") {
                val date = group.start
                val y = date.year.toString()
                val m = date.month.toString().padStart(2, '0')
                val d = date.day.toString().padStart(2, '0')
                Text(
                    if (grouping == HistoryGrouping.MONTH) {
                        stringResource(Res.string.history_month_group, y, m)
                    } else {
                        stringResource(Res.string.history_week_group, y, m, d)
                    },
                    style = AdvantlyTheme.typography.title,
                    color = AdvantlyTheme.colors.text,
                    modifier = Modifier.padding(horizontal = Spacing.screenEdgeCompact, vertical = Spacing.m).semantics { heading() },
                )
            }
            val startIndex = offset
            itemsIndexed(group.matches, key = { _, m -> m.matchId.value }) { index, match ->
                HistoryItem(match, startIndex + index, onClick = { onOpen(match.matchId) })
            }
            offset += group.matches.size
        }
    }
}

@Composable
private fun FilterRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.screenEdgeCompact),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        content = content,
    )
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
            ResultBadge(match)
            Text(
                formatDateTime(match.finishedAtMs) + " · " + durationText(match.durationMs),
                style = AdvantlyTheme.typography.caption,
                color = colors.text2,
            )
        }
    }
}

/** Sieg/Niederlage/Unentschieden/Abgebrochen – Text + Farbe (nie nur Farbe). */
@Composable
fun ResultBadge(
    match: MatchSummary,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    val (fullText, text, color) =
        when (match.outcome()) {
            HistoryOutcome.WIN -> {
                Triple(stringResource(Res.string.result_win), stringResource(Res.string.history_chip_win), colors.success)
            }

            HistoryOutcome.LOSS -> {
                Triple(stringResource(Res.string.result_loss), stringResource(Res.string.history_chip_loss), colors.danger)
            }

            HistoryOutcome.DRAW -> {
                Triple(stringResource(Res.string.result_draw), stringResource(Res.string.history_chip_draw), colors.text2)
            }

            HistoryOutcome.ABANDONED -> {
                Triple(
                    stringResource(Res.string.result_abandoned),
                    stringResource(Res.string.result_abandoned),
                    colors.text2,
                )
            }
        }
    Text(
        text.uppercase(),
        style = AdvantlyTheme.typography.overline,
        color = color,
        modifier =
            modifier
                .background(color.copy(alpha = 0.14f), Radii.pillShape)
                .padding(horizontal = Spacing.s, vertical = Spacing.xs)
                .testTag("result_badge")
                .clearAndSetSemantics { contentDescription = fullText },
    )
}

/** „6:4 3:6 10:7“ aus der Sicht von Wir; bei Aufgabe mit eingefrorenem Satz („6:4 2:1 (30:0) Aufg.“). */
@Composable
fun setsLine(match: MatchSummary): String = resultLine(match.sets, match.interruptedSet, match.result, match.setsWon)
