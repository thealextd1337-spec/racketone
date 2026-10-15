package eu.advantly.ui.phone.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.GameKind
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.stats.GameProgress
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.common.scorePair
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.detail_break
import eu.advantly.ui.phone.resources.detail_game
import eu.advantly.ui.phone.resources.detail_hide_games
import eu.advantly.ui.phone.resources.detail_hide_points
import eu.advantly.ui.phone.resources.detail_momentum
import eu.advantly.ui.phone.resources.detail_momentum_hint
import eu.advantly.ui.phone.resources.detail_momentum_rally
import eu.advantly.ui.phone.resources.detail_momentum_rally_hint
import eu.advantly.ui.phone.resources.detail_momentum_sets
import eu.advantly.ui.phone.resources.detail_momentum_sets_hint
import eu.advantly.ui.phone.resources.detail_no_point
import eu.advantly.ui.phone.resources.detail_point
import eu.advantly.ui.phone.resources.detail_progress_empty
import eu.advantly.ui.phone.resources.detail_rallies
import eu.advantly.ui.phone.resources.detail_rally_game
import eu.advantly.ui.phone.resources.detail_rally_set
import eu.advantly.ui.phone.resources.detail_show_games
import eu.advantly.ui.phone.resources.detail_show_points
import eu.advantly.ui.phone.resources.detail_tiebreak
import eu.advantly.ui.phone.resources.detail_unfinished
import eu.advantly.ui.phone.resources.live_score_advantage
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

@Composable
private fun gameLabel(
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
private fun gameDescription(
    game: GameProgress,
    setup: MatchSetup,
): String =
    listOfNotNull(
        gameLabel(game, setup),
        stringResource(Res.string.detail_rallies, scorePair(game.pointsWon)),
        game.winner?.let { teamName(setup, it) } ?: stringResource(Res.string.detail_unfinished),
        stringResource(Res.string.detail_break).takeIf { game.isBreak },
    ).joinToString(" · ")

/** Divergierende Balken aus der Ballwechseldifferenz je Game; Breaks nur im Standardspiel. */
@Composable
internal fun MatchProgression(
    setup: MatchSetup,
    games: List<GameProgress>,
) {
    val colors = AdvantlyTheme.colors
    val descriptions = games.map { gameDescription(it, setup) }
    val alternative = descriptions.joinToString(". ")
    var showGames by rememberSaveable { mutableStateOf(false) }
    SectionTitle(
        stringResource(
            when {
                setup.sport.isTennisLike -> Res.string.detail_momentum
                setup.sport == Sport.PICKLEBALL -> Res.string.detail_momentum_rally
                else -> Res.string.detail_momentum_sets
            },
        ),
    )
    if (games.isEmpty()) {
        Text(stringResource(Res.string.detail_progress_empty), color = colors.text2)
        return
    }
    Text(
        stringResource(
            when {
                setup.sport.isTennisLike -> Res.string.detail_momentum_hint
                setup.sport == Sport.PICKLEBALL -> Res.string.detail_momentum_rally_hint
                else -> Res.string.detail_momentum_sets_hint
            },
        ),
        style = AdvantlyTheme.typography.caption,
        color = colors.text2,
    )
    val chartWidth = maxOf(280f, games.size * 40f * LocalDensity.current.fontScale).dp
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Canvas(
            Modifier
                .width(chartWidth)
                .height(160.dp)
                .testTag("momentum_chart")
                .clearAndSetSemantics { contentDescription = alternative },
        ) {
            val middle = size.height / 2f
            val slot = size.width / games.size
            val max = games.maxOf { abs(it.margin) }.coerceAtLeast(1)
            drawLine(colors.text2, Offset(0f, middle), Offset(size.width, middle), strokeWidth = 1.dp.toPx())
            games.forEachIndexed { index, game ->
                val x = (index + 0.5f) * slot
                val height = abs(game.margin).toFloat() / max * (middle - 14.dp.toPx())
                val top = if (game.margin >= 0) middle - height else middle
                val color =
                    when {
                        game.margin > 0 -> colors.teamUsChart
                        game.margin < 0 -> colors.teamThemChart
                        else -> colors.text2
                    }
                drawRect(color, Offset(x - slot * 0.32f, top), Size(slot * 0.64f, maxOf(height, 2.dp.toPx())))
                if (game.isBreak) {
                    drawCircle(
                        colors.text,
                        4.dp.toPx(),
                        Offset(
                            x,
                            if (game.margin >=
                                0
                            ) {
                                top - 6.dp.toPx()
                            } else {
                                top + height + 6.dp.toPx()
                            },
                        ),
                    )
                }
                if (index > 0 && games[index - 1].set != game.set) {
                    drawLine(colors.text2, Offset(index * slot, 0f), Offset(index * slot, size.height), 1.dp.toPx())
                }
            }
        }
        Row(Modifier.width(chartWidth)) {
            games.forEach { game ->
                Text(
                    if (setup.sport.isTennisLike) "${game.set}.${game.game}" else game.set.toString(),
                    style = AdvantlyTheme.typography.caption,
                    color = colors.text2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    TextButton(onClick = { showGames = !showGames }, modifier = Modifier.testTag("show_game_history")) {
        Text(stringResource(if (showGames) Res.string.detail_hide_games else Res.string.detail_show_games))
    }
    if (showGames) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m), modifier = Modifier.testTag("game_history")) {
            games.forEachIndexed { index, game -> GameHistory(game, descriptions[index], setup, index) }
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
