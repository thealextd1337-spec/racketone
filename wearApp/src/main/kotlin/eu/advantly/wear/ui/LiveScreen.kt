package eu.advantly.wear.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.presentation.live.ScoreHint
import eu.advantly.wear.R
import eu.advantly.wear.live.LiveModel
import eu.advantly.wear.live.LiveOverlay
import eu.advantly.wear.live.LiveRole
import eu.advantly.wear.live.LiveUi
import eu.advantly.wear.live.SyncUi
import eu.advantly.wear.live.WatchToast
import eu.advantly.wear.scoring.GestureClassifier
import eu.advantly.wear.scoring.ScoringGesture
import eu.advantly.wear.scoring.TapGuard
import eu.advantly.wear.scoring.TapZones
import eu.advantly.wear.workout.WorkoutPhase
import eu.advantly.wear.workout.WorkoutUiState
import kotlinx.coroutines.delay

/** Aktionen des Live-Screens (Zähler und Anzeige). */
interface LiveActions {
    fun point(side: Side): Boolean

    fun undo(): Boolean

    fun redo(): Boolean

    fun pause(): Boolean

    fun resumePlay(): Boolean

    fun lock()

    fun unlock()

    fun requestEnd()

    fun requestCorrection()

    fun correctServer(player: PlayerId): Boolean

    fun swapPositions(side: Side): Boolean

    fun confirmEnd(): Boolean

    fun requestTakeOver()

    fun confirmTakeOver(): Boolean

    fun dismissOverlay()

    fun continueDecision(
        anotherSet: Boolean,
        matchTiebreak: Boolean,
    ): Boolean

    fun followUp(): Boolean

    fun close()

    fun retryWorkout()

    fun replaceOtherWorkout()

    fun skipWorkout()
}

/**
 * Live-Match (04 W3–W7): Score-Seite (Zähler mit Tap-Flächen bzw. Anzeige ohne), Steuerseite (Wischen nach links),
 * Satzende-Dialog, Matchende, Bestätigungen. Im Ambient-Modus nur die reduzierte Score-Seite.
 */
@Composable
fun LiveScreen(
    live: LiveUi,
    workout: WorkoutUiState,
    heartRate: Int?,
    isAmbient: Boolean,
    toast: WatchToast?,
    actions: LiveActions,
) {
    var controls by remember(live.matchId) { mutableStateOf(false) }
    when {
        isAmbient -> {
            ScorePage(live, workout, isAmbient = true, toast = null, actions = actions, onOpenControls = {})
        }

        live.overlay == LiveOverlay.CONFIRM_TAKE_OVER -> {
            ConfirmTakeOverScreen(live, actions)
        }

        live.overlay == LiveOverlay.CONFIRM_END -> {
            ConfirmEndScreen(live, actions)
        }

        live.overlay == LiveOverlay.CORRECT_SERVER && live.corrections != null -> {
            CorrectServerScreen(live, live.corrections, actions)
        }

        workout.phase == WorkoutPhase.CONFLICT -> {
            WorkoutConflictScreen(actions)
        }

        live.finished != null -> {
            FinishedScreen(live, live.finished, actions)
        }

        live.decision != null -> {
            DecisionScreen(live, live.decision, toast, actions)
        }

        controls -> {
            ControlsPage(live, workout, heartRate, actions, onClose = { controls = false })
        }

        else -> {
            ScorePage(live, workout, isAmbient = false, toast = toast, actions = actions, onOpenControls = { controls = true })
        }
    }
}

/**
 * Score-Seite nach 04 §4.3: obere Hälfte = Wir, untere = Gegner, große Ziffern, Totzonen oben (12 %) und im Mittelband
 * (10 %), Tap zählt erst beim Loslassen, 600 ms Sperre (Controller), Wischen nach unten = Undo, nach links =
 * Steuerseite, langes Halten entsperrt. Anzeige-Rolle: gleiche Darstellung ohne Tap-Flächen, „ANZEIGE“ + Zähler.
 * Ambient: schwarz, gedimmte Ziffern, nur Konturen, keine Eingaben.
 */
@Composable
private fun ScorePage(
    live: LiveUi,
    workout: WorkoutUiState,
    isAmbient: Boolean,
    toast: WatchToast?,
    actions: LiveActions,
    onOpenControls: () -> Unit,
) {
    var flashSide by remember { mutableStateOf<Side?>(null) }
    var flashKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(flashKey) {
        if (flashSide != null) {
            delay(TapGuard.DEFAULT_LOCKOUT_MILLIS)
            flashSide = null
        }
    }
    val currentLive by rememberUpdatedState(live)
    val currentActions by rememberUpdatedState(actions)
    val currentOpenControls by rememberUpdatedState(onOpenControls)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val swipeThreshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    val scorer = live.role == LiveRole.SCORER

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(isAmbient, scorer) {
                    if (isAmbient) return@pointerInput // Handgelenk unten = Eingaben aus (04 §4.3.3 Punkt 4)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var last = down.position
                        var endTime = down.uptimeMillis
                        var multiTouch = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) multiTouch = true
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            last = change.position
                            endTime = change.uptimeMillis
                            if (!change.pressed) break
                        }
                        if (multiTouch) return@awaitEachGesture // Handballen/Mehrfinger ignorieren
                        val gesture =
                            GestureClassifier.classify(
                                dx = last.x - down.position.x,
                                dy = last.y - down.position.y,
                                durationMillis = endTime - down.uptimeMillis,
                                touchSlopPx = touchSlop,
                                swipeThresholdPx = swipeThreshold,
                            )
                        val isScorer = currentLive.role == LiveRole.SCORER
                        when (gesture) {
                            ScoringGesture.TAP -> {
                                if (isScorer) {
                                    TapZones.classify(down.position.y, size.height.toFloat())?.let { side ->
                                        if (currentActions.point(side)) {
                                            flashSide = side
                                            flashKey++
                                        }
                                    }
                                }
                            }

                            ScoringGesture.LONG_PRESS -> {
                                if (currentLive.locked) currentActions.unlock()
                            }

                            ScoringGesture.SWIPE_DOWN -> {
                                if (isScorer) currentActions.undo()
                            }

                            ScoringGesture.SWIPE_LEFT -> {
                                if (!currentLive.locked) currentOpenControls()
                            }

                            ScoringGesture.NONE -> {
                                Unit
                            }
                        }
                    }
                },
    ) {
        val halfHeight = maxHeight * HALF_FRACTION
        val bandHeight = maxHeight * TapZones.MIDDLE_BAND
        val digitSize = with(LocalDensity.current) { (halfHeight * DIGIT_FRACTION).toSp() }
        val scoreA = pointText(live.points.a)
        val scoreB = pointText(live.points.b)
        val scoreDescription = stringResource(R.string.a11y_score, scoreA, scoreB)
        val serveDescription =
            live.serve?.let { stringResource(if (it.side == Side.A) R.string.a11y_serving_us else R.string.a11y_serving_them) }.orEmpty()
        val undoLabel = stringResource(R.string.action_undo)
        val controlsLabel = stringResource(R.string.controls_title)
        val roleDescription = stringResource(R.string.a11y_view_only, stringResource(R.string.scoring_by, deviceName(live.scorerKind)))
        Column(
            modifier =
                Modifier.fillMaxSize().then(
                    if (scorer) {
                        Modifier
                    } else {
                        // Anzeige: Stand als Live-Region (höflich), keine Zähl-Aktionen (04 §8)
                        Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "$roleDescription. $scoreDescription. $serveDescription"
                            liveRegion = LiveRegionMode.Polite
                            customActions = listOf(CustomAccessibilityAction(controlsLabel) { onOpenControls().let { true } })
                        }
                    },
                ),
        ) {
            listOf(Side.A, Side.B).forEachIndexed { i, side ->
                val pointLabel = stringResource(if (side == Side.A) R.string.a11y_point_us else R.string.a11y_point_them)
                TeamHalf(
                    live = live,
                    side = side,
                    value = if (side == Side.A) scoreA else scoreB,
                    height = halfHeight,
                    digitSize = digitSize.value,
                    isAmbient = isAmbient,
                    flashing = flashSide == side,
                    modifier =
                        if (scorer && !isAmbient) {
                            Modifier.semantics {
                                role = Role.Button
                                contentDescription = "$pointLabel. $scoreDescription. $serveDescription"
                                onClick { actions.point(side) }
                                customActions =
                                    listOf(
                                        CustomAccessibilityAction(undoLabel) { actions.undo() },
                                        CustomAccessibilityAction(controlsLabel) { onOpenControls().let { true } },
                                    )
                            }
                        } else {
                            Modifier
                        },
                )
                if (i == 0) {
                    MiddleBand(live, toast.takeUnless { isAmbient }, isAmbient, Modifier.height(bandHeight))
                }
            }
        }
        TopStatus(live, isAmbient, Modifier.align(Alignment.TopCenter).padding(top = maxHeight * TOP_STATUS_FRACTION))
        BottomLine(live, workout, isAmbient, actions, Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * 0.04f))
    }
}

@Composable
private fun TeamHalf(
    live: LiveUi,
    side: Side,
    value: String,
    height: Dp,
    digitSize: Float,
    isAmbient: Boolean,
    flashing: Boolean,
    modifier: Modifier,
) {
    val color = teamColor(side)
    val base = modifier.fillMaxWidth().height(height)
    val styled =
        if (isAmbient) {
            base.border(1.dp, color.copy(alpha = 0.5f)) // nur Kontur (04 §4.3.6)
        } else {
            base.background(color.copy(alpha = if (flashing) 0.35f else 0.12f))
        }
    val serving = live.serve?.takeIf { it.side == side && live.status != MatchStatus.FINISHED }
    Column(
        modifier = styled,
        verticalArrangement = if (side == Side.A) Arrangement.Bottom else Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val label: @Composable () -> Unit = {
            val names = live.teams[side]
            val team = stringResource(if (side == Side.A) R.string.label_us else R.string.label_them)
            val text = if (live.doubles) team else "$team · ${playerName(names.first(), doubles = false)}"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isAmbient) AmbientDigits else color,
                    maxLines = 1,
                )
                if (serving != null) {
                    val marker = listOfNotNull("●", serving.court?.name, serving.servesRemaining?.let { "•".repeat(it) }).joinToString(" ")
                    Text(" $marker", fontSize = 11.sp, color = if (isAmbient) AmbientDigits else Color.White)
                }
            }
            if (serving?.server != null && serving.receiver != null) {
                Text(
                    stringResource(R.string.serve_arrow, playerName(serving.server, true), playerName(serving.receiver, true)),
                    fontSize = 10.sp,
                    color = if (isAmbient) AmbientDigits else TextSecondary,
                    maxLines = 1,
                )
            }
        }
        val digits: @Composable () -> Unit = {
            Text(
                text = value,
                fontSize = digitSize.sp,
                lineHeight = digitSize.sp,
                fontWeight = FontWeight.Bold,
                color = if (isAmbient) AmbientDigits else Color.White,
            )
        }
        if (side == Side.A) {
            label()
            digits()
        } else {
            digits()
            label()
        }
    }
}

/** Mittelband: Toast (3 s) oder Satzstände + Hinweis-Pill (bzw. Pickleball-Ansage). */
@Composable
private fun MiddleBand(
    live: LiveUi,
    toast: WatchToast?,
    isAmbient: Boolean,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (toast != null) {
            Text(toastText(toast), fontSize = 11.sp, color = Color.White, textAlign = TextAlign.Center, maxLines = 1)
            return@Box
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val left = live.scoreCall ?: LiveModel.setsText(live.sets)
            if (left.isNotEmpty()) {
                Text(left, fontSize = 12.sp, color = if (isAmbient) AmbientDigits else Color.White, maxLines = 1)
            }
            val hint = live.hint?.let { hintText(it, live.sport) }
            if (hint != null) {
                HintPill(hint, live.hint.kind.level, isAmbient, Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun HintPill(
    text: String,
    level: ScoreHint.Level,
    isAmbient: Boolean,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(50)
    val filled = !isAmbient && level != ScoreHint.Level.Outline
    val pill =
        if (filled) {
            modifier.background(if (level == ScoreHint.Level.Filled) HintAmber else HintAmber.copy(alpha = 0.3f), shape)
        } else {
            modifier.border(1.dp, if (isAmbient) AmbientDigits else HintAmber, shape)
        }
    Text(
        text = text.uppercase(),
        modifier = pill.padding(horizontal = 6.dp, vertical = 1.dp),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color =
            when {
                isAmbient -> AmbientDigits
                level == ScoreHint.Level.Filled -> Color.Black
                else -> HintAmber
            },
        maxLines = 1,
    )
}

/** Oben (unter der Uhrzeit): Zähler „Synchron / N ausstehend“ bzw. „ANZEIGE · Handy zählt / vor X s“, Sperre. */
@Composable
private fun TopStatus(
    live: LiveUi,
    isAmbient: Boolean,
    modifier: Modifier,
) {
    val text =
        when {
            live.locked -> stringResource(R.string.locked_label)

            live.role == LiveRole.SCORER -> syncText(live.sync)

            live.sync == SyncUi.Live -> "${stringResource(
                R.string.role_view,
            )} · ${stringResource(R.string.scoring_by, deviceName(live.scorerKind))}"

            else -> "${stringResource(R.string.role_view)} · ${stringResource(R.string.sync_last_update, syncText(live.sync))}"
        }
    val color =
        when {
            isAmbient -> AmbientDigits
            live.role == LiveRole.DISPLAY && live.sync != SyncUi.Live -> HintAmber
            live.sync is SyncUi.Pending -> HintAmber
            else -> TextSecondary
        }
    Text(text, modifier = modifier, fontSize = 10.sp, color = color, maxLines = 1)
}

/** Unten: Puls und Schritte des laufenden Workouts bzw. bei der Anzeige nach > 30 s ohne Zähler „Zählen übernehmen“ (tippbar, keine Score-Fläche). */
@Composable
private fun BottomLine(
    live: LiveUi,
    workout: WorkoutUiState,
    isAmbient: Boolean,
    actions: LiveActions,
    modifier: Modifier,
) {
    if (live.takeOverOffered && !isAmbient) {
        androidx.wear.compose.material3.CompactButton(
            onClick = actions::requestTakeOver,
            modifier = modifier,
            label = { Text(stringResource(R.string.take_over_offer), fontSize = 11.sp) },
        )
        return
    }
    if (live.rejectedInputs > 0 && live.role == LiveRole.DISPLAY && !isAmbient) {
        Text(
            pluralStringResource(R.plurals.rejected_inputs, live.rejectedInputs, live.rejectedInputs),
            modifier = modifier,
            fontSize = 10.sp,
            color = HintAmber,
        )
        return
    }
    WorkoutTelemetry(live.matchId, workout, isAmbient, modifier)
}

private const val HALF_FRACTION = (1f - TapZones.MIDDLE_BAND) / 2f
private const val DIGIT_FRACTION = 0.55f
private const val TOP_STATUS_FRACTION = 0.13f
private val SWIPE_THRESHOLD = 40.dp

/** Back-Geste auf Unterseiten: zurück zum Score. */
@Composable
internal fun BackToScore(onBack: () -> Unit) = BackHandler(onBack = onBack)
