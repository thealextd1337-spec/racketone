package eu.advantly.ui.phone.live

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.advantly.presentation.live.FakeTennis
import eu.advantly.presentation.live.FakeTennisState
import eu.advantly.presentation.live.PointLabel
import eu.advantly.presentation.live.ScoreHint
import eu.advantly.presentation.live.Team
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.hint_breakpoint
import eu.advantly.ui.phone.resources.hint_changeends
import eu.advantly.ui.phone.resources.hint_deuce
import eu.advantly.ui.phone.resources.hint_match_lost
import eu.advantly.ui.phone.resources.hint_match_won
import eu.advantly.ui.phone.resources.hint_matchpoint
import eu.advantly.ui.phone.resources.hint_setpoint
import eu.advantly.ui.phone.resources.hint_tiebreak
import eu.advantly.ui.phone.resources.live_games_and_points
import eu.advantly.ui.phone.resources.live_score_advantage
import eu.advantly.ui.phone.resources.live_score_pair
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun pointText(label: PointLabel): String =
    when (label) {
        is PointLabel.Number -> label.value.toString()
        PointLabel.Advantage -> stringResource(Res.string.live_score_advantage)
    }

/** „30:15“ (DE) bzw. „30–15“ (EN); mit [withGames] zusätzlich der Game-Stand davor. */
@Composable
internal fun scoreSummary(
    state: FakeTennisState,
    withGames: Boolean,
): String {
    val points =
        stringResource(
            Res.string.live_score_pair,
            pointText(FakeTennis.pointLabel(state, Team.Us)),
            pointText(FakeTennis.pointLabel(state, Team.Them)),
        )
    if (!withGames) return points
    val games = stringResource(Res.string.live_score_pair, state.gamesUs.toString(), state.gamesThem.toString())
    return stringResource(Res.string.live_games_and_points, games, points)
}

/**
 * Hinweis-Pill (04 §4.3.1/§5.6): Amber, Versalien, Pfeil zum betroffenen Team – nie nur Farbe.
 * Stufen: Kontur · gefüllt-hell · gefüllt + Puls (Matchball, nicht bei reduzierter Bewegung / Outdoor).
 */
@Composable
internal fun HintPill(
    hint: ScoreHint,
    outdoor: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    val text =
        stringResource(
            when (hint.kind) {
                ScoreHint.Kind.MatchPoint -> Res.string.hint_matchpoint
                ScoreHint.Kind.SetPoint -> Res.string.hint_setpoint
                ScoreHint.Kind.BreakPoint -> Res.string.hint_breakpoint
                ScoreHint.Kind.Deuce -> Res.string.hint_deuce
                ScoreHint.Kind.Tiebreak -> Res.string.hint_tiebreak
                ScoreHint.Kind.ChangeEnds -> Res.string.hint_changeends
                ScoreHint.Kind.MatchWon -> Res.string.hint_match_won
                ScoreHint.Kind.MatchLost -> Res.string.hint_match_lost
            },
        ).uppercase()
    val filled = outdoor || hint.kind.level == ScoreHint.Level.Filled
    val background =
        when {
            filled -> colors.hint
            hint.kind.level == ScoreHint.Level.Tinted -> colors.hint.copy(alpha = 0.16f)
            else -> Color.Transparent
        }
    val content = if (filled) colors.onHint else colors.hint
    val pulse = hint.kind == ScoreHint.Kind.MatchPoint && !reduceMotion && !outdoor
    val pulseAlpha =
        if (pulse) {
            val transition = rememberInfiniteTransition(label = "hint-pulse")
            val value by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.72f,
                animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
                label = "hint-pulse-alpha",
            )
            value
        } else {
            1f
        }
    Row(
        modifier =
            modifier
                .testTag("hint_pill")
                .alpha(pulseAlpha)
                .height(30.dp)
                .background(background, Radii.pillShape)
                .border(1.5.dp, colors.hint, Radii.pillShape)
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        hint.team?.let { TeamArrow(team = it, color = content) }
        Text(
            text = text,
            style = AdvantlyTheme.typography.overline.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            color = content,
            maxLines = 1,
        )
    }
}

/** ▲ = Wir (obere Fläche), ▼ = Gegner (untere Fläche). */
@Composable
private fun TeamArrow(
    team: Team,
    color: Color,
) {
    Canvas(Modifier.size(9.dp)) {
        val path =
            Path().apply {
                if (team == Team.Us) {
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                } else {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2, size.height)
                }
                close()
            }
        drawPath(path, color)
    }
}

/** Satztabelle (04 §5.6): Zeilen Wir/Gegner, Spalten Sätze + aktueller Satz; gewonnene Sätze hell. */
@Composable
internal fun SetTable(
    state: FakeTennisState,
    usName: String,
    themName: String,
    header: String,
    elapsed: @Composable () -> Unit,
    outdoor: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    val shape = Radii.cardShape
    Column(
        modifier =
            modifier
                .testTag("set_table")
                .fillMaxWidth()
                .background(if (outdoor) Color.Black else colors.surface, shape)
                .then(if (outdoor) Modifier.border(1.5.dp, Color.White, shape) else Modifier)
                .padding(start = Spacing.m, end = Spacing.m, top = Spacing.s, bottom = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = header.uppercase(),
                style = AdvantlyTheme.typography.overline.copy(fontSize = 11.sp),
                color = if (outdoor) Color.White else colors.text2,
                modifier = Modifier.weight(1f),
            )
            elapsed()
        }
        SetRow(Team.Us, usName, state, outdoor)
        SetRow(Team.Them, themName, state, outdoor)
    }
}

@Composable
private fun SetRow(
    team: Team,
    name: String,
    state: FakeTennisState,
    outdoor: Boolean,
) {
    val colors = AdvantlyTheme.colors
    val teamColor = if (team == Team.Us) colors.teamUs else colors.teamThem
    Row(modifier = Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(16.dp).background(teamColor, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(7.dp))
        Box(
            Modifier
                .size(8.dp)
                .alpha(if (state.server == team && !state.isFinished) 1f else 0f)
                .background(colors.sportTennis, CircleShape),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = name,
            style = AdvantlyTheme.typography.label.copy(fontSize = 14.sp),
            color = if (outdoor) Color.White else colors.text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        state.completedSets.forEach { set ->
            val mine = if (team == Team.Us) set.gamesUs else set.gamesThem
            val won = set.winner == team
            val tiebreak = set.tiebreakLoserPoints?.takeIf { !won }
            Row(Modifier.widthIn(min = 30.dp), horizontalArrangement = Arrangement.Center) {
                Text(
                    text = mine.toString(),
                    style = AdvantlyTheme.typography.scoreS,
                    color = if (won || outdoor) colors.text else colors.text3,
                    textAlign = TextAlign.Center,
                )
                if (tiebreak != null) {
                    Text(
                        text = tiebreak.toString(),
                        style = AdvantlyTheme.typography.caption.copy(fontSize = 10.sp, baselineShift = BaselineShift.Superscript),
                        color = if (outdoor) Color.White else colors.text3,
                    )
                }
            }
        }
        if (!state.isFinished) {
            Box(
                modifier =
                    Modifier
                        .widthIn(min = 36.dp)
                        .fillMaxHeight()
                        .background(if (outdoor) Color.Black else colors.surface2, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.games(team).toString(),
                    style = AdvantlyTheme.typography.scoreS.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
                    color = if (outdoor) Color.White else colors.text,
                    modifier = Modifier.testTag(if (team == Team.Us) "games_us" else "games_them"),
                )
            }
        }
    }
}

/** Knopf der Fußleiste (Rückgängig, Outdoor, Mehr): Icon + Label, ≥ 48 dp hoch. */
@Composable
internal fun RowScope.FooterButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    outdoor: Boolean,
    modifier: Modifier = Modifier,
    weight: Float = 1f,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    selected: Boolean? = null,
) {
    val colors = AdvantlyTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val background =
        when {
            outdoor -> Color.Black
            emphasized -> colors.surface2
            else -> colors.surface
        }
    val borderColor =
        when {
            selected == true -> colors.hint
            outdoor -> Color.White
            else -> Color.Transparent
        }
    Column(
        modifier =
            modifier
                .weight(weight)
                .fillMaxHeight()
                .alpha(if (enabled) 1f else 0.4f)
                .background(background, shape)
                .border(1.5.dp, borderColor, shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .then(
                    if (selected != null) {
                        Modifier.semantics { toggleableState = ToggleableState(selected) }
                    } else {
                        Modifier
                    },
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val content = if (outdoor || emphasized || selected == true) colors.text else colors.text2
        Icon(icon, contentDescription = null, tint = if (selected == true) colors.hint else content, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(text = label, style = AdvantlyTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold), color = content)
    }
}

/** Kleiner Status-Chip im Kopf (Sportart). */
@Composable
internal fun SportChip(
    text: String,
    outdoor: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    Row(
        modifier =
            modifier
                .height(28.dp)
                .background(if (outdoor) Color.Black else colors.surface, Radii.pillShape)
                .then(if (outdoor) Modifier.border(1.5.dp, Color.White, Radii.pillShape) else Modifier)
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(8.dp)) {
            drawCircle(
                colors.sportTennis,
                radius = size.minDimension / 2,
                center =
                    Offset(
                        size.width / 2,
                        size.height / 2,
                    ),
            )
        }
        Text(text = text, style = AdvantlyTheme.typography.label.copy(fontSize = 13.sp), color = if (outdoor) Color.White else colors.text)
    }
}
