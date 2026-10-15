package eu.advantly.ui.phone.live

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import eu.advantly.presentation.live.ScoreChange
import eu.advantly.presentation.live.Team
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import kotlin.math.abs

/** Dauer der Ziffern-Roll-Animation (04 §5.7). */
private const val ROLL_MILLIS = 150

/** Aufblitzen der Fläche (04 §5.6 Score-Panel). */
private const val FLASH_MILLIS = 120
private const val FLASH_ALPHA = 0.24f

/** Inhalt eines Score-Panels. */
internal data class ScorePanelContent(
    val team: Team,
    val teamLabel: String,
    val playerName: String,
    val pointText: String,
    val serving: Boolean,
    val serveLabel: String,
    val a11yDescription: String,
    val undoLabel: String,
)

/**
 * Tap-Fläche mit Team-Label, Namen, Score XL, Aufschlag-Ball und Druck-Feedback (04 §5.6).
 *
 * Gesten: Tap (zählt beim Loslassen, solange der Finger nicht über die Touch-Slop wandert) = Punkt;
 * Wischen nach unten = Rückgängig; Mehrfinger-Kontakt wird ignoriert (Handballen-Filter, 04 §4.3.3).
 */
@Composable
internal fun ScorePanel(
    content: ScorePanelContent,
    direction: ScoreChange.Direction,
    flashKey: Int,
    outdoor: Boolean,
    reduceMotion: Boolean,
    enabled: Boolean,
    onTap: (eventUptimeMillis: Long?) -> Unit,
    onSwipeDown: (eventUptimeMillis: Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    val teamColor = if (content.team == Team.Us) colors.teamUs else colors.teamThem
    val tint = if (content.team == Team.Us) colors.teamUsTint else colors.teamThemTint
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnSwipeDown by rememberUpdatedState(onSwipeDown)
    var pressed by remember { mutableStateOf(false) }

    // Neues Animatable pro Punkt, mit Startwert > 0: Der Blitz ist damit schon im ERSTEN Frame nach dem Tap
    // sichtbar (wichtig für die Tap→Frame-Messung), danach klingt er in 120 ms ab.
    val flash = remember(flashKey) { Animatable(if (flashKey > 0) FLASH_ALPHA else 0f) }
    LaunchedEffect(flash) { if (flash.value > 0f) flash.animateTo(0f, tween(FLASH_MILLIS, easing = EaseOut)) }

    val shape = Radii.scorePanelShape
    val tag = if (content.team == Team.Us) "panel_us" else "panel_them"
    BoxWithConstraints(
        modifier =
            modifier
                .testTag(tag)
                .graphicsLayer {
                    val scale = if (pressed) 0.985f else 1f
                    scaleX = scale
                    scaleY = scale
                }.clip(shape)
                .background(if (outdoor) Color.Black else tint)
                .border(if (outdoor) 6.dp else 1.5.dp, if (outdoor) teamColor else teamColor.copy(alpha = 0.4f), shape)
                .drawWithContent {
                    drawContent()
                    val alpha = flash.value
                    if (alpha > 0f) drawRect(Color.White.copy(alpha = alpha))
                }.semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = content.a11yDescription
                    liveRegion = LiveRegionMode.Polite
                    onClick {
                        currentOnTap(null)
                        true
                    }
                    customActions =
                        listOf(
                            CustomAccessibilityAction(content.undoLabel) {
                                currentOnSwipeDown(null)
                                true
                            },
                        )
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val swipeMin = 72.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        var multiTouch = false
                        var moved = false
                        var up: PointerInputChange? = null
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } > 1) multiTouch = true
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                                if (!change.pressed) {
                                    change.consume()
                                    up = change
                                    break
                                }
                            }
                        } finally {
                            pressed = false
                        }
                        val release = up ?: return@awaitEachGesture
                        val delta = release.position - down.position
                        when {
                            multiTouch -> Unit
                            !moved -> currentOnTap(release.uptimeMillis)
                            delta.y > swipeMin && delta.y > abs(delta.x) * 1.5f -> currentOnSwipeDown(release.uptimeMillis)
                        }
                    }
                },
    ) {
        val textColor = if (outdoor) Color.White else teamColor
        val secondary = if (outdoor) Color.White else colors.text2
        // Team-Label + Name links, Aufschlag rechts in EINER Zeile: bei großer Schrift kürzt der Name (Ellipse),
        // statt mit dem Aufschlag-Hinweis zu überlappen.
        Row(
            modifier =
                Modifier
                    .align(if (content.team == Team.Us) Alignment.TopStart else Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.l, vertical = Spacing.m),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = content.teamLabel, style = AdvantlyTheme.typography.overline, color = textColor, maxLines = 1)
            Text(
                text = content.playerName,
                style = AdvantlyTheme.typography.caption,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (content.serving) {
                Row(
                    modifier = Modifier.testTag("${tag}_serve"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).background(colors.sportTennis, CircleShape))
                    Text(
                        text = content.serveLabel,
                        style = AdvantlyTheme.typography.caption,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Score XL: 96–120 pt laut 04 §5.4, an die Fläche angepasst und bewusst NICHT mit der Schriftskalierung
        // vergrößert („Live-Screen skaliert Labels, nicht die ohnehin maximalen Ziffern“).
        val density = LocalDensity.current
        val digitHeight = min(min(maxHeight * 0.62f, maxWidth * 0.42f), 132.dp)
        val fontSize = (digitHeight.value / density.fontScale).sp
        val animate = !outdoor
        AnimatedContent(
            targetState = content.pointText,
            modifier = Modifier.align(Alignment.Center),
            transitionSpec = { scoreTransition(animate, reduceMotion, direction) },
            contentAlignment = Alignment.Center,
            label = "score-roll",
        ) { text ->
            Text(
                text = text,
                modifier = Modifier.testTag(if (content.team == Team.Us) "score_us" else "score_them"),
                style = AdvantlyTheme.typography.scoreXl.copy(fontSize = fontSize, lineHeight = fontSize),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** Ziffer rollt vertikal (150 ms, ease-out), Undo rollt rückwärts; „Bewegung reduzieren“ → nur Überblendung. */
private fun scoreTransition(
    animate: Boolean,
    reduceMotion: Boolean,
    direction: ScoreChange.Direction,
): ContentTransform {
    if (!animate) return EnterTransition.None togetherWith ExitTransition.None
    val spec = tween<Float>(ROLL_MILLIS, easing = EaseOut)
    if (reduceMotion) return fadeIn(spec) togetherWith fadeOut(spec)
    val sign = if (direction == ScoreChange.Direction.Forward) 1 else -1
    val slide = tween<IntOffset>(ROLL_MILLIS, easing = EaseOut)
    return ContentTransform(
        targetContentEnter = slideInVertically(slide) { sign * it } + fadeIn(spec),
        initialContentExit = slideOutVertically(slide) { -sign * it } + fadeOut(spec),
        sizeTransform = SizeTransform(clip = true),
    )
}
