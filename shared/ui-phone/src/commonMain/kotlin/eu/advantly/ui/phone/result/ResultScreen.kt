package eu.advantly.ui.phone.result

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.Environment
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.match.MatchRecord
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.stats.MatchStatistics
import eu.advantly.presentation.stats.MatchStats
import eu.advantly.presentation.stats.Ratio
import eu.advantly.ui.phone.common.AdvantlyButton
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.common.SportBadge
import eu.advantly.ui.phone.common.StatTile
import eu.advantly.ui.phone.common.TopBar
import eu.advantly.ui.phone.common.durationText
import eu.advantly.ui.phone.common.safeContentPadding
import eu.advantly.ui.phone.common.sportName
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyIcons
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.history.ResultBadge
import eu.advantly.ui.phone.history.setsLine
import eu.advantly.ui.phone.platform.formatDateTime
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.action_back
import eu.advantly.ui.phone.resources.action_cancel
import eu.advantly.ui.phone.resources.action_delete
import eu.advantly.ui.phone.resources.result_compare_a11y
import eu.advantly.ui.phone.resources.result_delete
import eu.advantly.ui.phone.resources.result_delete_text
import eu.advantly.ui.phone.resources.result_delete_title
import eu.advantly.ui.phone.resources.result_duration
import eu.advantly.ui.phone.resources.result_followup_of
import eu.advantly.ui.phone.resources.result_not_found
import eu.advantly.ui.phone.resources.result_play_again
import eu.advantly.ui.phone.resources.result_points_total
import eu.advantly.ui.phone.resources.result_ratio
import eu.advantly.ui.phone.resources.result_section_compare
import eu.advantly.ui.phone.resources.result_stat_break_points
import eu.advantly.ui.phone.resources.result_stat_deciding_points
import eu.advantly.ui.phone.resources.result_stat_games
import eu.advantly.ui.phone.resources.result_stat_points
import eu.advantly.ui.phone.resources.result_stat_return_points
import eu.advantly.ui.phone.resources.result_stat_serve_points
import eu.advantly.ui.phone.resources.result_stat_set_points
import eu.advantly.ui.phone.resources.result_stat_set_points_pickleball
import eu.advantly.ui.phone.resources.result_stat_streak
import eu.advantly.ui.phone.resources.result_title
import eu.advantly.ui.phone.resources.stats_not_recorded
import eu.advantly.ui.phone.resources.wizard_indoor
import eu.advantly.ui.phone.resources.wizard_outdoor
import eu.advantly.ui.phone.wizard.surfaceName
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * H8 Ergebnis (04 §4.1, §6 – nur Ergebnis-Hero, Stat-Kacheln und Vergleichsbalken): Endstand, Sätze, Dauer und
 * Kennzahlen aus `pointHistory` (02 §11.1, ohne Zusatzeingaben).
 */
@Composable
fun ResultScreen(
    record: MatchRecord?,
    onBack: () -> Unit,
    onPlayAgain: (MatchSetup) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    playerAssignments: @Composable (MatchRecord) -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .background(AdvantlyTheme.colors.bg)
            .safeContentPadding()
            .testTag("result"),
    ) {
        TopBar(stringResource(Res.string.result_title), onBack, stringResource(Res.string.action_back)) {
            if (record != null) {
                val label = stringResource(Res.string.result_delete)
                Box(
                    Modifier
                        .testTag("result_delete")
                        .size(Spacing.minTouchTarget)
                        .clickable(role = Role.Button) { confirmDelete = true }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AdvantlyIcons.Delete, contentDescription = null, tint = AdvantlyTheme.colors.text2)
                }
            }
        }
        // Löschen nur nach Bestätigung (04 Prinzip 4: destruktive Aktionen brauchen Bestätigung)
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(Res.string.result_delete_title)) },
                text = { Text(stringResource(Res.string.result_delete_text)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            onDelete()
                        },
                        modifier = Modifier.testTag("delete_confirm"),
                    ) { Text(stringResource(Res.string.action_delete), color = AdvantlyTheme.colors.danger) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }, modifier = Modifier.testTag("delete_cancel")) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
                modifier = Modifier.testTag("delete_dialog"),
            )
        }
        if (record == null) {
            Text(
                stringResource(Res.string.result_not_found),
                style = AdvantlyTheme.typography.body,
                color = AdvantlyTheme.colors.text2,
                modifier = Modifier.padding(Spacing.screenEdgeCompact),
            )
            return@Column
        }
        val stats = remember(record) { MatchStatistics.compute(record) }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenEdgeCompact, vertical = Spacing.s),
        ) {
            Hero(record)
            playerAssignments(record)
            Row(Modifier.padding(top = Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                StatTile(durationText(stats.netDurationMs), stringResource(Res.string.result_duration), Modifier.weight(1f))
                StatTile(stats.totalPoints.toString(), stringResource(Res.string.result_points_total), Modifier.weight(1f))
            }
            SectionTitle(stringResource(Res.string.result_section_compare))
            Comparison(record, stats)
            ServeStatistics(record.summary.setup, stats)
            AdvantlyButton(
                text = stringResource(Res.string.result_play_again),
                primary = true,
                onClick = { onPlayAgain(record.summary.setup.copy(previousMatchId = null)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl).testTag("result_play_again"),
            )
        }
    }
}

/** Ergebnis-Hero (04 §6): Sport, Datum, beide Seiten, Sieger hervorgehoben, Satzergebnisse groß. */
@Composable
private fun Hero(record: MatchRecord) {
    val summary = record.summary
    val setup = summary.setup
    val colors = AdvantlyTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, Radii.cardShape)
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            val meta =
                listOfNotNull(
                    stringResource(sportName(setup.sport)),
                    setup.meta.surface?.let { stringResource(surfaceName(it)) },
                    setup.meta.environment?.let {
                        stringResource(if (it == Environment.INDOOR) Res.string.wizard_indoor else Res.string.wizard_outdoor)
                    },
                )
            SportBadge(setup.sport, meta.joinToString(" · "))
            Box(Modifier.weight(1f))
            ResultBadge(summary)
        }
        Text(formatDateTime(summary.startedAtMs), style = AdvantlyTheme.typography.caption, color = colors.text2)
        for (side in Side.entries) {
            val winner = summary.result.winner == side
            Text(
                teamName(setup, side),
                style = if (winner) AdvantlyTheme.typography.title else AdvantlyTheme.typography.body,
                color = if (winner) colors.text else colors.text2,
                modifier = Modifier.testTag(if (side == Side.A) "result_team_us" else "result_team_them"),
            )
        }
        Text(
            setsLine(summary),
            style = AdvantlyTheme.typography.scoreM.copy(fontSize = AdvantlyTheme.typography.scoreM.fontSize * 1.3f),
            color = colors.text,
            modifier = Modifier.testTag("result_sets"),
        )
        setup.previousMatchId?.let {
            Text(stringResource(Res.string.result_followup_of), style = AdvantlyTheme.typography.caption, color = colors.text2)
        }
    }
}

/** Vergleichsbalken (Butterfly, 04 §5.6): Wir links/Orange, Gegner rechts/Blau, Werte außen. */
@Composable
private fun Comparison(
    record: MatchRecord,
    stats: MatchStats,
) {
    val sport = record.summary.setup.sport
    val rows = mutableListOf<CompareRow>()
    rows +=
        CompareRow(
            Res.string.result_stat_points,
            stats.pointsWon.map {
                _,
                v,
                ->
                v.toDouble()
            },
            stats.pointsWon.map { _, v -> v.toString() },
        )
    rows += ratioRow(Res.string.result_stat_serve_points, stats.servicePoints)
    rows += ratioRow(Res.string.result_stat_return_points, stats.returnPoints)
    if (sport.isTennisLike) rows += ratioRow(Res.string.result_stat_break_points, stats.breakPoints)
    rows +=
        ratioRow(
            if (sport == Sport.PICKLEBALL) Res.string.result_stat_set_points_pickleball else Res.string.result_stat_set_points,
            stats.setPoints,
        )
    if (stats.decidingPoints.a.total > 0) rows += ratioRow(Res.string.result_stat_deciding_points, stats.decidingPoints)
    stats.games?.let { g ->
        rows += CompareRow(Res.string.result_stat_games, g.map { _, v -> v.toDouble() }, g.map { _, v -> v.toString() })
    }
    rows +=
        CompareRow(
            Res.string.result_stat_streak,
            stats.longestStreak.map { _, v -> v.toDouble() },
            stats.longestStreak.map { _, v -> v.toString() },
        )
    Column(Modifier.testTag("result_compare"), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        rows.forEach { CompareBar(it) }
    }
}

private class CompareRow(
    val label: StringResource,
    val values: SidePair<Double>,
    val texts: SidePair<String>,
)

@Composable
private fun ratioRow(
    label: StringResource,
    ratio: SidePair<Ratio>,
): CompareRow = CompareRow(label, ratio.map { _, r -> r.won.toDouble() }, SidePair(ratioText(ratio.a), ratioText(ratio.b)))

@Composable
internal fun ratioText(r: Ratio): String =
    r.percent?.let { p -> stringResource(Res.string.result_ratio, r.won, r.total, p) } ?: stringResource(Res.string.stats_not_recorded)

@Composable
private fun CompareBar(row: CompareRow) {
    val colors = AdvantlyTheme.colors
    val label = stringResource(row.label)
    val total = (row.values.a + row.values.b).takeIf { it > 0 } ?: 1.0
    val a11y = stringResource(Res.string.result_compare_a11y, label, row.texts.a, row.texts.b)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = a11y }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.texts.a, style = AdvantlyTheme.typography.label, color = colors.text, modifier = Modifier.weight(1f))
            Text(
                label,
                style = AdvantlyTheme.typography.caption,
                color = colors.text2,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(2f),
            )
            Text(
                row.texts.b,
                style = AdvantlyTheme.typography.label,
                color = colors.text,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth().height(8.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.End) {
                val share = (row.values.a / total).toFloat()
                if (share > 0f) Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(colors.teamUsChart, Radii.pillShape))
            }
            Row(Modifier.weight(1f).fillMaxHeight()) {
                val share = (row.values.b / total).toFloat()
                if (share > 0f) Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(colors.teamThemChart, Radii.pillShape))
            }
        }
    }
}
