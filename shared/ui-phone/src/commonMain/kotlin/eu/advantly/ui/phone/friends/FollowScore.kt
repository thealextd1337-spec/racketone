package eu.advantly.ui.phone.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.friends.LiveEntry
import eu.advantly.presentation.live.SetColumn
import eu.advantly.ui.phone.common.sportColor
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.live.ScoreDirection
import eu.advantly.ui.phone.live.ScorePanel
import eu.advantly.ui.phone.live.ScorePanelContent
import eu.advantly.ui.phone.live.SetTable
import eu.advantly.ui.phone.platform.rememberReduceMotion
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.live_badge
import eu.advantly.ui.phone.resources.live_serve
import eu.advantly.ui.phone.resources.live_team_a
import eu.advantly.ui.phone.resources.live_team_b
import eu.advantly.ui.phone.resources.watching_tiebreak
import org.jetbrains.compose.resources.stringResource

/** Dieselben Score-Flächen und dieselbe Satztabelle wie beim Zähler, mit reinen Cloud-Anzeigedaten. */
@Composable
internal fun FollowScore(
    entry: LiveEntry,
    landscape: Boolean,
) {
    val watched = entry.score
    val match = watched.match
    val teamNames = SidePair(names(match, Side.A), names(match, Side.B))
    // Tennis/Padel melden im Match-Tiebreak Games 0:0; dann zeigt die laufende Spalte wie beim Zähler die Punkte.
    val tiebreakPoints = watched.tiebreak && (watched.games == null || watched.games == 0 to 0)
    val pointPair = watched.points?.let { (a, b) -> a.toIntOrNull()?.let { x -> b.toIntOrNull()?.let { y -> x to y } } }
    val columns =
        match.sets.mapIndexed { index, (a, b) ->
            SetColumn(
                SidePair(a, b),
                null,
                when {
                    a > b -> Side.A
                    b > a -> Side.B
                    else -> null
                },
                isCurrent = false,
                isTiebreakSet = index in match.matchTiebreakSets,
            )
        } +
            listOfNotNull(
                (if (tiebreakPoints) pointPair else watched.games ?: pointPair)?.let { (a, b) ->
                    SetColumn(SidePair(a, b), null, null, isCurrent = true, isTiebreakSet = tiebreakPoints)
                },
            )
    val serveLabel = stringResource(Res.string.live_serve)
    val reduceMotion = rememberReduceMotion()
    val serveColor = match.sport?.let { sportColor(it) } ?: AdvantlyTheme.colors.brand
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SetTable(
            columns = columns,
            teamNames = teamNames,
            serving = entry.serving,
            sport = match.sport,
            header = stringResource(if (watched.tiebreak) Res.string.watching_tiebreak else Res.string.live_badge),
            elapsed = {},
        )

        @Composable
        fun Panel(
            side: Side,
            modifier: Modifier,
        ) {
            val point = watched.points?.let { if (side == Side.A) it.first else it.second } ?: "—"
            val serving = entry.serving == side
            ScorePanel(
                content =
                    ScorePanelContent(
                        side = side,
                        teamLabel = stringResource(if (side == Side.A) Res.string.live_team_a else Res.string.live_team_b),
                        playerName = teamNames[side],
                        pointText = point,
                        serving = serving,
                        serveLabel = serveLabel,
                        serveColor = serveColor,
                        a11yDescription = listOfNotNull(teamNames[side], point, serveLabel.takeIf { serving }).joinToString(". "),
                        undoLabel = "",
                    ),
                direction = ScoreDirection.Forward,
                flashKey = 0,
                outdoor = false,
                reduceMotion = reduceMotion,
                enabled = false,
                onTap = {},
                onSwipeDown = {},
                modifier = modifier,
                interactive = false,
            )
        }

        if (landscape) {
            Row(Modifier.fillMaxWidth().testTag("live_follow_panels"), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Panel(Side.A, Modifier.weight(1f).height(240.dp))
                Panel(Side.B, Modifier.weight(1f).height(240.dp))
            }
        } else {
            Column(Modifier.fillMaxWidth().testTag("live_follow_panels"), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Panel(Side.A, Modifier.fillMaxWidth().height(240.dp))
                Panel(Side.B, Modifier.fillMaxWidth().height(240.dp))
            }
        }
    }
}
