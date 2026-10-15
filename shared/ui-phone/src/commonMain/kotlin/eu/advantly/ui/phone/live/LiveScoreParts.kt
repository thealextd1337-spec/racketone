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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.presentation.live.LiveScoreRules
import eu.advantly.presentation.live.ScoreHint
import eu.advantly.presentation.live.SetColumn
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.ui.phone.common.SportIcon
import eu.advantly.ui.phone.common.sportColor
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.a11y_hint_for_them
import eu.advantly.ui.phone.resources.a11y_hint_for_us
import org.jetbrains.compose.resources.stringResource

/**
 * Hinweis-Pill (04 §4.3.1/§5.6): Amber, Versalien, Pfeil zum betroffenen Team – nie nur Farbe.
 * Stufen: Kontur · gefüllt-hell · gefüllt + Puls (Matchball, nicht bei reduzierter Bewegung / Outdoor).
 */
@Composable
internal fun HintPill(
    hint: ScoreHint,
    sport: Sport,
    outdoor: Boolean,
    reduceMotion: Boolean,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    val name = hintText(hint, sport)
    val text = name.uppercase()
    // Screenreader: „Matchball für Wir“ statt „MATCHBALL“ + stummem Pfeil; Änderungen werden angesagt.
    val description =
        when (hint.side) {
            Side.A -> stringResource(Res.string.a11y_hint_for_us, name)
            Side.B -> stringResource(Res.string.a11y_hint_for_them, name)
            null -> name
        }
    val filled = outdoor || hint.kind.level == ScoreHint.Level.Filled
    val background =
        when {
            filled -> colors.hint
            hint.kind.level == ScoreHint.Level.Tinted -> colors.hint.copy(alpha = 0.16f)
            else -> Color.Transparent
        }
    val content = if (filled) colors.onHint else colors.hint
    val pulse = hint.kind == ScoreHint.Kind.MatchPoint && !reduceMotion && !outdoor
    // Puls nur im Draw-Pass auslesen (graphicsLayer), damit er nicht jeden Frame neu komponiert.
    val pulseAlpha: State<Float>? =
        if (pulse) {
            rememberInfiniteTransition(label = "hint-pulse").animateFloat(
                initialValue = 1f,
                targetValue = 0.72f,
                animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
                label = "hint-pulse-alpha",
            )
        } else {
            null
        }
    Row(
        modifier =
            modifier
                .testTag("hint_pill")
                .clearAndSetSemantics {
                    contentDescription = description
                    liveRegion = LiveRegionMode.Polite
                    hint.side?.let { this[HintArrowDirection] = arrowDirection(it, landscape) }
                }.graphicsLayer { alpha = pulseAlpha?.value ?: 1f }
                .heightIn(min = 30.dp)
                .background(background, Radii.pillShape)
                .border(1.5.dp, colors.hint, Radii.pillShape)
                .padding(horizontal = 14.dp, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        hint.side?.let { TeamArrow(side = it, landscape = landscape, color = content) }
        Text(
            text = text,
            style = AdvantlyTheme.typography.overline.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            color = content,
            maxLines = 1,
        )
    }
}

/**
 * Pfeil zur Fläche des Teams: Hochformat ▲ Wir (oben) / ▼ Gegner (unten), Querformat ◀ Wir (links) / ▶ Gegner (rechts).
 * Rein dekorativ – die Pill-Semantik nennt das Team im Text.
 */
@Composable
private fun TeamArrow(
    side: Side,
    landscape: Boolean,
    color: Color,
) {
    Canvas(Modifier.size(9.dp)) {
        val w = size.width
        val h = size.height
        val path =
            Path().apply {
                when {
                    landscape && side == Side.A -> {
                        moveTo(0f, h / 2)
                        lineTo(w, 0f)
                        lineTo(w, h)
                    }

                    landscape -> {
                        moveTo(w, h / 2)
                        lineTo(0f, 0f)
                        lineTo(0f, h)
                    }

                    side == Side.A -> {
                        moveTo(w / 2, 0f)
                        lineTo(w, h)
                        lineTo(0f, h)
                    }

                    else -> {
                        moveTo(0f, 0f)
                        lineTo(w, 0f)
                        lineTo(w / 2, h)
                    }
                }
                close()
            }
        drawPath(path, color)
    }
}

/** Satztabelle (04 §5.6): Zeilen Wir/Gegner, Spalten Sätze + laufender Satz; gewonnene Sätze hell. */
@Composable
internal fun SetTable(
    snapshot: LiveMatchSnapshot,
    header: String,
    elapsed: @Composable () -> Unit,
    outdoor: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    val shape = Radii.cardShape
    val columns = LiveScoreRules.setColumns(snapshot.state)
    val serving = snapshot.state.serve?.side
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
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = AdvantlyTheme.typography.overline.copy(fontSize = 11.sp),
                color = if (outdoor) Color.White else colors.text2,
                modifier = Modifier.weight(1f).testTag("set_table_header"),
            )
            elapsed()
        }
        for (side in Side.entries) {
            SetRow(side, teamName(snapshot.setup, side), columns, serving == side, snapshot.setup.sport, outdoor)
        }
    }
}

@Composable
private fun SetRow(
    side: Side,
    name: String,
    columns: List<SetColumn>,
    serving: Boolean,
    sport: Sport,
    outdoor: Boolean,
) {
    val colors = AdvantlyTheme.colors
    val teamColor = if (side == Side.A) colors.teamUs else colors.teamThem
    val tagSuffix = if (side == Side.A) "us" else "them"
    // Höhe folgt dem Inhalt (Schriftgröße bis 200 %), mindestens 28 dp; IntrinsicSize für die hinterlegte Spalte.
    Row(modifier = Modifier.heightIn(min = 28.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(16.dp).background(teamColor, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(7.dp))
        Box(
            Modifier
                .size(8.dp)
                .alpha(if (serving) 1f else 0f)
                .background(sportColor(sport), CircleShape),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = name,
            style = AdvantlyTheme.typography.label.copy(fontSize = 14.sp),
            color = if (outdoor) Color.White else colors.text,
            modifier = Modifier.weight(1f).testTag("set_name_$tagSuffix"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        columns.forEachIndexed { index, column ->
            val value = column.values[side]
            if (column.isCurrent) {
                Box(
                    modifier =
                        Modifier
                            .widthIn(min = 36.dp)
                            .fillMaxHeight()
                            .background(if (outdoor) Color.Black else colors.surface2, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (column.isTiebreakSet) "[$value]" else value.toString(),
                        style = AdvantlyTheme.typography.scoreS.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
                        color = if (outdoor) Color.White else colors.text,
                        modifier = Modifier.testTag("current_$tagSuffix"),
                    )
                }
            } else {
                val won = column.winner == side
                val tiebreak = column.tiebreakLoserPoints?.takeIf { !won }
                Row(Modifier.widthIn(min = 30.dp).testTag("set_${index}_$tagSuffix"), horizontalArrangement = Arrangement.Center) {
                    Text(
                        text = if (column.isTiebreakSet) "[$value]" else value.toString(),
                        style = AdvantlyTheme.typography.scoreS.copy(fontWeight = if (won) FontWeight.Bold else FontWeight.Medium),
                        color = if (won || outdoor) colors.text else colors.text2,
                        textAlign = TextAlign.Center,
                    )
                    if (tiebreak != null) {
                        Text(
                            text = tiebreak.toString(),
                            style = AdvantlyTheme.typography.caption.copy(fontSize = 10.sp, baselineShift = BaselineShift.Superscript),
                            color = if (outdoor) Color.White else colors.text2,
                        )
                    }
                }
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
    sport: Sport,
    text: String,
    outdoor: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    Row(
        modifier =
            modifier
                .heightIn(min = 28.dp)
                .background(if (outdoor) Color.Black else colors.surface, Radii.pillShape)
                .then(if (outdoor) Modifier.border(1.5.dp, Color.White, Radii.pillShape) else Modifier)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SportIcon(sport, size = 14.dp)
        Text(text = text, style = AdvantlyTheme.typography.label.copy(fontSize = 13.sp), color = if (outdoor) Color.White else colors.text)
    }
}

/** Richtung des Teampfeils als Semantik-Eigenschaft (nur für Tests, wird nicht vorgelesen). */
internal val HintArrowDirection = SemanticsPropertyKey<String>("HintArrowDirection")

/** `up`/`down` (Hochformat) bzw. `left`/`right` (Querformat). */
internal fun arrowDirection(
    side: Side,
    landscape: Boolean,
): String =
    when {
        landscape && side == Side.A -> "left"
        landscape -> "right"
        side == Side.A -> "up"
        else -> "down"
    }
