package eu.advantly.ui.phone.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.GameKind
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.stats.GameProgress
import eu.advantly.ui.phone.common.scorePair
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.detail_break
import eu.advantly.ui.phone.resources.detail_game
import eu.advantly.ui.phone.resources.detail_hide_games
import eu.advantly.ui.phone.resources.detail_hide_points
import eu.advantly.ui.phone.resources.detail_no_point
import eu.advantly.ui.phone.resources.detail_point
import eu.advantly.ui.phone.resources.detail_rallies
import eu.advantly.ui.phone.resources.detail_rally_game
import eu.advantly.ui.phone.resources.detail_rally_set
import eu.advantly.ui.phone.resources.detail_show_games
import eu.advantly.ui.phone.resources.detail_show_points
import eu.advantly.ui.phone.resources.detail_tiebreak
import eu.advantly.ui.phone.resources.detail_unfinished
import eu.advantly.ui.phone.resources.live_score_advantage
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun gameLabel(
    game: GameProgress,
    setup: MatchSetup,
): String =
    if (game.kind == GameKind.RALLY_GAME) {
        stringResource(if (setup.sport == Sport.PICKLEBALL) Res.string.detail_rally_game else Res.string.detail_rally_set, game.set)
    } else {
        stringResource(Res.string.detail_game, game.set, game.game) +
            if (game.kind == GameKind.STANDARD) "" else " · " + stringResource(Res.string.detail_tiebreak)
    }

@Composable
internal fun gameDescription(
    game: GameProgress,
    setup: MatchSetup,
): String =
    listOfNotNull(
        gameLabel(game, setup),
        stringResource(Res.string.detail_rallies, scorePair(game.pointsWon)),
        game.winner?.let { teamName(setup, it) } ?: stringResource(Res.string.detail_unfinished),
        stringResource(Res.string.detail_break).takeIf { game.isBreak },
    ).joinToString(" · ")

/** Aufklappbare Game-Liste unter dem Spielverlauf („Games anzeigen“), je Game mit Punktverlauf. */
@Composable
internal fun GameHistoryList(
    setup: MatchSetup,
    games: List<GameProgress>,
) {
    if (games.isEmpty()) return
    var showGames by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { showGames = !showGames }, modifier = Modifier.testTag("show_game_history")) {
        Text(stringResource(if (showGames) Res.string.detail_hide_games else Res.string.detail_show_games))
    }
    if (showGames) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m), modifier = Modifier.testTag("game_history")) {
            games.forEachIndexed { index, game -> GameHistory(game, gameDescription(game, setup), setup, index) }
        }
    }
}

@Composable
private fun GameHistory(
    game: GameProgress,
    description: String,
    setup: MatchSetup,
    index: Int,
) {
    var expanded by rememberSaveable(game.set, game.game, game.kind.name) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(description, style = AdvantlyTheme.typography.label, color = AdvantlyTheme.colors.text)
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("show_points_$index")) {
            Text(stringResource(if (expanded) Res.string.detail_hide_points else Res.string.detail_show_points))
        }
        if (expanded) {
            game.points.forEach { point ->
                val advantage = stringResource(Res.string.live_score_advantage)
                val score =
                    scorePair(
                        point.before.a.let { if (it == "AD") advantage else it },
                        point.before.b.let { if (it == "AD") advantage else it },
                    )
                val winner = teamName(setup, point.winner)
                Text(
                    stringResource(Res.string.detail_point, point.number, score, winner) +
                        if (point.scored) "" else " · " + stringResource(Res.string.detail_no_point),
                    style = AdvantlyTheme.typography.body,
                    color = AdvantlyTheme.colors.text2,
                    modifier = Modifier.testTag("point_${index}_${point.number}"),
                )
            }
        }
    }
}
