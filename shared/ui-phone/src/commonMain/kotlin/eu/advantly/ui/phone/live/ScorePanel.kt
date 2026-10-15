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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import eu.advantly.core.model.Side
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import kotlin.math.abs

/** Dauer der Ziffern-Roll-Animation (04 §5.7). */
private const val ROLL_MILLIS = 150

/** Höchstanteil der Panelhöhe für die Label-Zeile; den Rest bekommt immer die Ziffer. */
private const val LABEL_MAX_FRACTION = 0.4f

/** Unter dieser Panelhöhe zeigt die Label-Zeile nur das Nötigste (kein Aufschlag-Text, wenig Abstand). */
private val COMPACT_PANEL_HEIGHT = 200.dp

/** Kleinste Ziffernhöhe des Stands. */
private val MIN_DIGIT_HEIGHT = 24.dp

/** Aufblitzen der Fläche (04 §5.6 Score-Panel). */
private const val FLASH_MILLIS = 120
private const val FLASH_ALPHA = 0.24f

/** Inhalt eines Score-Panels. */
internal data class ScorePanelContent(
    val side: Side,
    val teamLabel: String,
    val playerName: String,
    val pointText: String,
    val serving: Boolean,
    /** z. B. „Aufschlag“ oder „Aufschlag · Mia“ (umbricht bei großer Schrift). */
    val serveLabel: String,
    /** Aufschlagfeld „R“/„L“ (Badminton, Pickleball) – eigener Platz, wird nie gekürzt. */
    val serveCourt: String? = null,
    /** Farbe des Aufschlag-Balls (Sportfarbe). */
    val serveColor: Color,
    val a11yDescription: String,
    val undoLabel: String,
    val removeLabel: String = undoLabel,
)

/**
 * Tap-Fläche mit Team-Label, Namen, Score XL, Aufschlag-Ball und Druck-Feedback (04 §5.6).
 *
 * Gesten: Tap (zählt beim Loslassen, solange der Finger nicht über die Touch-Slop wandert) = Punkt;
 * Wischen nach unten = Rückgängig; Mehrfinger-Kontakt wird ignoriert (Handballen-Filter, 04 §4.3.3).
 * Mit [interactive] = `false` (Anzeige-Gerät, 04 H6a) gleiche Darstellung ohne Tap-Fläche, Geste und Button-Rolle.
 */
@Composable
internal fun ScorePanel(
    content: ScorePanelContent,
    direction: ScoreDirection,
    flashKey: Int,
    outdoor: Boolean,
    reduceMotion: Boolean,
    enabled: Boolean,
    onTap: (eventUptimeMillis: Long?) -> Unit,
    onSwipeDown: (eventUptimeMillis: Long?) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    canRemovePoint: Boolean = false,
    onRemovePoint: () -> Unit = {},
) {
    val colors = AdvantlyTheme.colors
    val teamColor = if (content.side == Side.A) colors.teamUs else colors.teamThem
    val tint = if (content.side == Side.A) colors.teamUsTint else colors.teamThemTint
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnSwipeDown by rememberUpdatedState(onSwipeDown)
    val currentOnRemove by rememberUpdatedState(onRemovePoint)
    var pressed by remember { mutableStateOf(false) }

    // Neues Animatable pro Punkt, mit Startwert > 0: Der Blitz ist damit schon im ERSTEN Frame nach dem Tap
    // sichtbar (wichtig für die Tap→Frame-Messung), danach klingt er in 120 ms ab.
    val flash = remember(flashKey) { Animatable(if (flashKey > 0) FLASH_ALPHA else 0f) }
    LaunchedEffect(flash) { if (flash.value > 0f) flash.animateTo(0f, tween(FLASH_MILLIS, easing = EaseOut)) }

    val shape = Radii.scorePanelShape
    val tag = if (content.side == Side.A) "panel_us" else "panel_them"
    Box(
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
                    contentDescription = content.a11yDescription
                    liveRegion = LiveRegionMode.Polite
                    if (interactive) {
                        role = Role.Button
                        if (enabled) {
                            onClick {
                                currentOnTap(null)
                                true
                            }
                        }
                        if (canRemovePoint) {
                            onLongClick(label = content.removeLabel) {
                                currentOnRemove()
                                true
                            }
                        }
                        customActions =
                            listOf(
                                CustomAccessibilityAction(content.undoLabel) {
                                    currentOnSwipeDown(null)
                                    true
                                },
                            )
                    }
                }.pointerInput(enabled, interactive, canRemovePoint) {
                    if ((!enabled && !canRemovePoint) || !interactive) return@pointerInput
                    val swipeMin = 72.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        var multiTouch = false
                        var moved = false
                        var up: PointerInputChange? = null
                        var longPressHandled = false
                        try {
                            while (true) {
                                val event =
                                    if (!moved && !multiTouch && !longPressHandled) {
                                        val remaining =
                                            (
                                                viewConfiguration.longPressTimeoutMillis -
                                                    (
                                                        currentEvent.changes
                                                            .firstOrNull { it.id == down.id }
                                                            ?.uptimeMillis
                                                            ?.minus(down.uptimeMillis) ?: 0L
                                                    )
                                            ).coerceAtLeast(1L)
                                        withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                    } else {
                                        awaitPointerEvent()
                                    }
                                if (event == null) {
                                    longPressHandled = true
                                    pressed = false
                                    if (canRemovePoint) currentOnRemove()
                                    continue
                                }
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
                            multiTouch || longPressHandled -> Unit
                            !moved && enabled -> currentOnTap(release.uptimeMillis)
                            enabled && delta.y > swipeMin && delta.y > abs(delta.x) * 1.5f -> currentOnSwipeDown(release.uptimeMillis)
                        }
                    }
                },
    ) {
        val textColor = if (outdoor) Color.White else teamColor
        val secondary = if (outdoor) Color.White else colors.text2
        val labelRow: @Composable (compact: Boolean, maxHeight: Dp) -> Unit = { compact, labelMaxHeight ->
            // Team-Label + Name links, Aufschlag rechts in EINER Zeile: bei großer Schrift kürzt der Name (Ellipse),
            // statt mit dem Aufschlag-Hinweis zu überlappen. Höhe gedeckelt – die Ziffer hat Vorrang.
            Row(
                modifier =
                    Modifier
                        .testTag("${tag}_label")
                        .fillMaxWidth()
                        .heightIn(max = labelMaxHeight)
                        .clipToBounds()
                        .padding(horizontal = Spacing.l, vertical = if (compact) Spacing.xs else Spacing.m),
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
                        modifier = Modifier.testTag("${tag}_serve").weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(12.dp).background(content.serveColor, CircleShape))
                        // Flache Fläche: nur der Aufschlag-Punkt (Text steckt auch in der Bedienhilfe-Beschreibung)
                        if (!compact) {
                            Text(
                                text = content.serveLabel,
                                style = AdvantlyTheme.typography.caption,
                                color = secondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        content.serveCourt?.let { court ->
                            Text(
                                text = court,
                                style = AdvantlyTheme.typography.label,
                                color = if (outdoor) Color.White else colors.text,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.testTag("${tag}_court"),
                            )
                        }
                    }
                }
            }
        }

        // Label-Zeile oben (Wir) bzw. unten (Gegner). Die Ziffer hat Vorrang: Die Labels bekommen höchstens
        // LABEL_MAX_FRACTION der Panelhöhe (gekürzt/abgeschnitten), die Ziffer immer den Rest – auch bei 200 % Schrift
        // auf kleinen Displays mit Systemleisten (Review W1-I Runde 2). Überdecken können sich beide nicht.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < COMPACT_PANEL_HEIGHT
            val labelMax = maxHeight * LABEL_MAX_FRACTION
            Column(Modifier.fillMaxSize()) {
                if (content.side == Side.A) labelRow(compact, labelMax)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    // Score XL: 96–120 pt laut 04 §5.4, an die Fläche angepasst und bewusst NICHT mit der Schriftskalierung
                    // vergrößert („Live-Screen skaliert Labels, nicht die ohnehin maximalen Ziffern“).
                    val density = LocalDensity.current
                    // Untergrenze: auch eine sehr flache Fläche (kleines Display, 200 % Schrift) zeigt den Stand lesbar
                    val digitHeight = min(min(maxHeight * 0.9f, maxWidth * 0.42f), 132.dp).coerceAtLeast(MIN_DIGIT_HEIGHT)
                    val fontSize = (digitHeight.value / density.fontScale).sp
                    val animate = !outdoor
                    AnimatedContent(
                        targetState = content.pointText,
                        transitionSpec = { scoreTransition(animate, reduceMotion, direction) },
                        contentAlignment = Alignment.Center,
                        label = "score-roll",
                    ) { text ->
                        Text(
                            text = text,
                            modifier = Modifier.testTag(if (content.side == Side.A) "score_us" else "score_them"),
                            style = AdvantlyTheme.typography.scoreXl.copy(fontSize = fontSize, lineHeight = fontSize),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
                if (content.side == Side.B) labelRow(compact, labelMax)
            }
        }
    }
}

/** Ziffer rollt vertikal (150 ms, ease-out), Undo rollt rückwärts; „Bewegung reduzieren“ → nur Überblendung. */
private fun scoreTransition(
    animate: Boolean,
    reduceMotion: Boolean,
    direction: ScoreDirection,
): ContentTransform {
    if (!animate) return EnterTransition.None togetherWith ExitTransition.None
    val spec = tween<Float>(ROLL_MILLIS, easing = EaseOut)
    if (reduceMotion) return fadeIn(spec) togetherWith fadeOut(spec)
    val sign = if (direction == ScoreDirection.Forward) 1 else -1
    val slide = tween<IntOffset>(ROLL_MILLIS, easing = EaseOut)
    return ContentTransform(
        targetContentEnter = slideInVertically(slide) { sign * it } + fadeIn(spec),
        initialContentExit = slideOutVertically(slide) { -sign * it } + fadeOut(spec),
        sizeTransform = SizeTransform(clip = true),
    )
}
