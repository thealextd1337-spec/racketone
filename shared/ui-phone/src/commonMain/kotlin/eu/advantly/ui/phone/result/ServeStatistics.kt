package eu.advantly.ui.phone.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Side
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.stats.MatchStats
import eu.advantly.presentation.stats.ServeStats
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.common.playerName
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.result_stat_serve_points
import eu.advantly.ui.phone.resources.stats_double_faults
import eu.advantly.ui.phone.resources.stats_first_serve_pct
import eu.advantly.ui.phone.resources.stats_first_serve_won
import eu.advantly.ui.phone.resources.stats_not_recorded
import eu.advantly.ui.phone.resources.stats_second_serve_won
import eu.advantly.ui.phone.resources.stats_serve_title
import eu.advantly.ui.phone.resources.stats_service_games
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ServeStatistics(
    setup: MatchSetup,
    stats: MatchStats,
) {
    if (!setup.sport.isTennisLike) return
    SectionTitle(stringResource(Res.string.stats_serve_title))
    Column(Modifier.testTag("serve_statistics"), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        Side.entries.forEach { side ->
            ServeStatsGroup(
                teamName(setup, side),
                stats.serve[side],
                "serve_team_${side.name}",
                showServicePoints = setup.format.discipline == Discipline.DOUBLES,
            )
        }
        if (setup.format.discipline == Discipline.DOUBLES) {
            stats.servers.forEach { server ->
                ServeStatsGroup(playerName(setup, server.player), server.stats, "serve_player_${server.player.value}")
            }
        }
    }
}

@Composable
private fun ServeStatsGroup(
    name: String,
    stats: ServeStats,
    tag: String,
    showServicePoints: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(name, style = AdvantlyTheme.typography.label, color = AdvantlyTheme.colors.text)
        if (showServicePoints) ServeStatRow(Res.string.result_stat_serve_points, ratioText(stats.servicePoints))
        ServeStatRow(Res.string.stats_service_games, ratioText(stats.serviceGames))
        ServeStatRow(Res.string.stats_first_serve_pct, ratioText(stats.firstServeIn))
        ServeStatRow(Res.string.stats_first_serve_won, ratioText(stats.firstServeWon))
        ServeStatRow(Res.string.stats_second_serve_won, ratioText(stats.secondServeWon))
        val faults = if (stats.recorded == 0) stringResource(Res.string.stats_not_recorded) else stats.doubleFaults.toString()
        ServeStatRow(Res.string.stats_double_faults, faults)
    }
}

@Composable
private fun ServeStatRow(
    label: StringResource,
    value: String,
) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text(
            stringResource(label),
            style = AdvantlyTheme.typography.caption,
            color = AdvantlyTheme.colors.text2,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = AdvantlyTheme.typography.label,
            color = AdvantlyTheme.colors.text,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
