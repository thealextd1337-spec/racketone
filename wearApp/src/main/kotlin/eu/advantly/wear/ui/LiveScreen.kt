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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.LocalTextStyle
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.material3.timeTextSeparator
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.match.session.OperationTimings
import eu.advantly.presentation.live.ScoreHint
import eu.advantly.wear.R
import eu.advantly.wear.live.LiveModel
import eu.advantly.wear.live.LiveOverlay
import eu.advantly.wear.live.LiveRole
import eu.advantly.wear.live.LiveUi
import eu.advantly.wear.live.OperationRules
import eu.advantly.wear.live.SyncUi
import eu.advantly.wear.live.TouchDecision
import eu.advantly.wear.live.TouchRouting
import eu.advantly.wear.live.WatchToast
import eu.advantly.wear.live.telemetryActive
import eu.advantly.wear.scoring.ActionStrip
import eu.advantly.wear.scoring.GestureClassifier
import eu.advantly.wear.scoring.ScoreLayout
import eu.advantly.wear.scoring.TapGuard
import eu.advantly.wear.scoring.TapZones
import eu.advantly.wear.workout.WorkoutPhase
import eu.advantly.wear.workout.WorkoutUiState
import kotlinx.coroutines.delay
import kotlin.math.min

/** Aktionen des Live-Screens (Zähler und Anzeige). */
interface LiveActions : OperationActions {
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
    glanceLaunch: Int = 0,
) {
    var controls by remember(live.matchId, glanceLaunch) { mutableStateOf(false) }
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

        live.overlay == LiveOverlay.SERVER_CHOICE && live.serverChoice != null -> {
            ServerChoiceScreen(live, live.serverChoice, actions)
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
    // Halten auf einer Hälfte: Fortschritt von rechts nach links, löst nach der Haltezeit aus (solange der Finger liegt)
    var hold by remember { mutableStateOf<HoldUi?>(null) }
    val holdProgress = rememberHoldProgress(hold)
    val currentLive by rememberUpdatedState(live)
    val currentActions by rememberUpdatedState(actions)
    val currentOpenControls by rememberUpdatedState(onOpenControls)
    // Aktionsleiste im Mittelband (Aufschlag-Leiste, „Rückgängig“ nach Punkt abziehen, „Aufschlag wählen“)
    val strip = if (isAmbient) null else OperationRules.stripAction(live, toast)
    val currentStrip by rememberUpdatedState(strip)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val swipeThreshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    val scorer = live.role == LiveRole.SCORER

    // Status („3 ausstehend“, „ANZEIGE · Handy zählt“, Sperre) gebogen neben der Uhrzeit oben am Rand: kostet keine
    // eigene Zeile über „Wir“ (Feldtest 2026-10-16: Statuszeile, Uhrzeit und Namenszeile lagen übereinander)
    ScreenScaffold(timeText = { StatusTimeText(live, isAmbient) }) { _ ->
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(isAmbient, scorer) {
                        if (isAmbient) return@pointerInput // Handgelenk unten = Eingaben aus (04 §4.3.3 Punkt 4)
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // Ziel beim Aufsetzen festlegen: wer auf der Aktionsleiste beginnt, zählt und zieht nie ab
                            val stripGeometry = ActionStrip.geometry(size.width.toFloat(), size.height.toFloat(), density)
                            val target = TouchRouting.target(currentStrip, ActionStrip.hit(down.position.x, down.position.y, stripGeometry))
                            var last = down.position
                            var endTime = down.uptimeMillis
                            var multiTouch = false
                            val downHalf = TapZones.classify(down.position.y, size.height.toFloat())
                            val holdSide =
                                TouchRouting.holdSide(
                                    target = target,
                                    half = downHalf,
                                    locked = currentLive.locked,
                                    scorer = currentLive.role == LiveRole.SCORER,
                                    canRemove = downHalf?.let { currentLive.canRemovePoint[it] } == true,
                                )
                            var holding = holdSide != null
                            var held = false
                            if (holdSide != null) hold = HoldUi(holdSide, down.uptimeMillis)
                            try {
                                while (true) {
                                    val event =
                                        if (holding) {
                                            val heldFor = endTime - down.uptimeMillis
                                            withTimeoutOrNull((GestureClassifier.LONG_PRESS_MILLIS - heldFor).coerceAtLeast(1)) {
                                                awaitPointerEvent()
                                            }
                                        } else {
                                            awaitPointerEvent()
                                        }
                                    if (event == null) {
                                        // Haltezeit erreicht, Finger liegt still: jetzt auslösen (Anzeige ist genau jetzt voll)
                                        holding = false
                                        held = true
                                        hold = hold?.copy(fired = true)
                                        if (currentLive.locked) {
                                            currentActions.unlock()
                                        } else {
                                            holdSide?.let {
                                                currentActions.removePoint(
                                                    it,
                                                )
                                            }
                                        }
                                        continue
                                    }
                                    if (event.changes.count { it.pressed } > 1) multiTouch = true
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    last = change.position
                                    endTime = change.uptimeMillis
                                    if (holding && (multiTouch || (last - down.position).getDistance() > touchSlop)) {
                                        holding = false // bewegt bzw. zweiter Finger: Halten abgebrochen
                                        hold = null
                                    }
                                    if (!change.pressed) break
                                }
                            } finally {
                                hold = null // Loslassen/Abbruch: Anzeige läuft kurz zurück
                            }
                            if (multiTouch || held) return@awaitEachGesture // Handballen/Mehrfinger ignorieren; Halten erledigt
                            val gesture =
                                GestureClassifier.classify(
                                    dx = last.x - down.position.x,
                                    dy = last.y - down.position.y,
                                    durationMillis = endTime - down.uptimeMillis,
                                    touchSlopPx = touchSlop,
                                    swipeThresholdPx = swipeThreshold,
                                )
                            val decision =
                                TouchRouting.decide(
                                    target = target,
                                    gesture = gesture,
                                    stripNow = currentStrip,
                                    locked = currentLive.locked,
                                    scorer = currentLive.role == LiveRole.SCORER,
                                )
                            val half = TapZones.classify(down.position.y, size.height.toFloat())
                            when (decision) {
                                TouchDecision.Point -> {
                                    if (half != null && currentActions.point(half)) {
                                        flashSide = half
                                        flashKey++
                                    }
                                }

                                // Halten ohne Abzugsmöglichkeit (sonst löst der Fortschritt schon beim Halten aus):
                                // Controller meldet „nichts abzuziehen“
                                TouchDecision.RemovePoint -> {
                                    half?.let { currentActions.removePoint(it) }
                                }

                                TouchDecision.Unlock -> {
                                    currentActions.unlock()
                                }

                                TouchDecision.Undo -> {
                                    currentActions.undo()
                                }

                                TouchDecision.OpenControls -> {
                                    currentOpenControls()
                                }

                                is TouchDecision.Strip -> {
                                    onStripTap(decision.action, down.position.x, stripGeometry, currentActions)
                                }

                                TouchDecision.None -> {
                                    Unit
                                }
                            }
                        }
                    },
        ) {
            val halfHeight = maxHeight * HALF_FRACTION
            val statusTop = maxHeight * TapZones.TOP_DEAD_ZONE
            val bottomGap = maxHeight * BOTTOM_FRACTION
            val screen = maxWidth
            // „Gegner“-Namen stehen direkt unter dem Mittelband: neben ihnen ragen Puls/Schritte ins Feld
            val telemetryInset = if (workout.telemetryActive) TELEMETRY_EDGE + TELEMETRY_WIDTH + 2.dp else 0.dp
            val bandHeight = maxHeight * TapZones.MIDDLE_BAND
            // Beide Hälften gleich groß: höchstens so groß, wie „Wir“ unter Uhrzeit, Statuszeile und Namenszeilen Platz hat
            // (die Ziffern-Box ist etwa DIGIT_BOX_RATIO × Schriftgröße hoch); notfalls schrumpft autoSize weiter
            val digitSize =
                with(LocalDensity.current) {
                    val line = LABEL_LINE_HEIGHT.toDp()
                    val roomA = halfHeight - statusTop - line * (if (live.doubles) 2 else 1)
                    minOf(halfHeight * DIGIT_FRACTION, roomA / DIGIT_BOX_RATIO).toSp()
                }
            val scoreA = pointText(live.points.a)
            val scoreB = pointText(live.points.b)
            val scoreDescription = stringResource(R.string.a11y_score, scoreA, scoreB)
            val serveDescription =
                live.serve
                    ?.let {
                        stringResource(
                            if (it.side ==
                                Side.A
                            ) {
                                R.string.a11y_serving_us
                            } else {
                                R.string.a11y_serving_them
                            },
                        )
                    }.orEmpty()
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
                    val removeLabel = stringResource(if (side == Side.A) R.string.live_remove_point_us else R.string.live_remove_point_them)
                    TeamHalf(
                        live = live,
                        side = side,
                        value = if (side == Side.A) scoreA else scoreB,
                        top = if (side == Side.A) 0.dp else halfHeight + bandHeight,
                        height = halfHeight,
                        screen = screen,
                        sideInset = if (side == Side.B) telemetryInset else 0.dp,
                        digitSize = digitSize.value,
                        isAmbient = isAmbient,
                        flashing = flashSide == side,
                        holdProgress = { if (holdProgress.side == side) holdProgress.fraction else 0f },
                        // „Gegner“-Namen liegen am Mittelband unter der Aktionsleiste: solange sie steht, ausblenden
                        // (statt halb verdeckt durchzuscheinen); Layout bleibt gleich
                        labelHidden = side == Side.B && strip != null,
                        // Statuszeile oben und Hinweise unten bekommen eigenen Platz in ihrer Hälfte (keine Überlagerung)
                        header = {
                            if (side == Side.A) {
                                Spacer(Modifier.height(statusTop)) // Uhrzeit mit Status
                            }
                        },
                        footer = {
                            if (side == Side.B && BottomLine.shown(live, isAmbient)) {
                                // runder Safe-Inset: so breit, wie der Kreis an der Unterkante erlaubt (Galaxy Watch8: „unten
                                // abgeschnitten“)
                                val bottomWidth = ScoreLayout.chordWidth(screen.value, (screen - bottomGap).value).dp
                                BottomLine(live, actions, Modifier.padding(top = 2.dp, bottom = bottomGap).widthIn(max = bottomWidth))
                            }
                        },
                        modifier =
                            if (scorer && !isAmbient) {
                                Modifier.semantics {
                                    role = Role.Button
                                    contentDescription = "$pointLabel. $scoreDescription. $serveDescription"
                                    onClick { actions.point(side) }
                                    customActions =
                                        listOfNotNull(
                                            CustomAccessibilityAction(undoLabel) { actions.undo() },
                                            CustomAccessibilityAction(removeLabel) { actions.removePoint(side) }
                                                .takeIf { live.canRemovePoint[side] },
                                            CustomAccessibilityAction(controlsLabel) { onOpenControls().let { true } },
                                        )
                                }
                            } else {
                                Modifier
                            },
                    )
                    if (i == 0) {
                        if (strip == null) {
                            MiddleBand(live, workout, toast.takeUnless { isAmbient }, isAmbient, Modifier.height(bandHeight))
                        } else {
                            // die Aktionsleiste liegt darüber (Puls/Schritte am Rand pausieren, solange sie steht)
                            Spacer(Modifier.height(bandHeight))
                        }
                    }
                }
            }
            if (strip != null) {
                val density = LocalDensity.current
                val geometry = with(density) { ActionStrip.geometry(maxWidth.toPx(), maxHeight.toPx(), density.density) }
                val stripTop = with(density) { geometry.visualTop.toDp() }
                // nicht zwingend mittig: auf kleinen Displays rückt die Leiste nach unten (beide Zählflächen ≥ 48 dp)
                ActionStripContent(strip, live, geometry, actions, Modifier.align(Alignment.TopCenter).padding(top = stripTop))
            }
        }
    }
}

/**
 * Eine Hälfte des Scores. Alles steht im Layout untereinander statt übereinander gelegt: [header] (Hälfte „Wir“:
 * Uhrzeit-Abstand und Statuszeile), Team-/Namenszeile mit Aufschlag-Indikator, Ziffern, [footer] (Hälfte „Gegner“:
 * Hinweise der Anzeige). Die Ziffern füllen den Rest und werden bei Bedarf kleiner ([DIGIT_MIN_FRACTION]) – so
 * überlappt auf keiner Displaygröße und bei keiner Systemschrift etwas.
 */
@Composable
private fun TeamHalf(
    live: LiveUi,
    side: Side,
    value: String,
    top: Dp,
    height: Dp,
    screen: Dp,
    sideInset: Dp,
    digitSize: Float,
    isAmbient: Boolean,
    flashing: Boolean,
    holdProgress: () -> Float,
    labelHidden: Boolean,
    header: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
    modifier: Modifier,
) {
    val color = teamColor(side)
    val base = modifier.fillMaxWidth().height(height)
    val styled =
        if (isAmbient) {
            base.border(1.dp, color.copy(alpha = 0.5f)) // nur Kontur (04 §4.3.6)
        } else {
            base
                .background(color.copy(alpha = if (flashing) 0.35f else 0.12f))
                .semantics { holdProgressValue = holdProgress() }
                .drawBehind {
                    // Halte-Fortschritt: Füllung läuft von rechts nach links (wie der Restzeit-Balken der Aufschlag-Leiste)
                    val p = holdProgress()
                    if (p > 0f) {
                        drawRect(
                            color.copy(alpha = HOLD_FILL_ALPHA),
                            topLeft = Offset(size.width * (1 - p), 0f),
                            size = Size(size.width * p, size.height),
                        )
                    }
                }
        }
    val serving = live.serve?.takeIf { it.side == side && live.status != MatchStatus.FINISHED }
    val arrowServe = serving?.takeIf { it.server != null && it.receiver != null }
    Box(modifier = styled) {
        val label: @Composable () -> Unit = {
            val names = live.teams[side]
            val team = stringResource(if (side == Side.A) R.string.label_us else R.string.label_them)
            val text = if (live.doubles) team else "$team · ${playerName(names.first(), doubles = false)}"
            Row(
                modifier = Modifier.testTag(LiveTags.label(side)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = text,
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 11.sp,
                    lineHeight = LABEL_LINE_HEIGHT,
                    style = SMALL_LINE,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isAmbient) AmbientDigits else color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (serving != null) {
                    val marker = listOfNotNull("●", serving.court?.name, serving.servesRemaining?.let { "•".repeat(it) }).joinToString(" ")
                    Text(
                        " $marker",
                        modifier = Modifier.testTag(LiveTags.SERVE_MARKER),
                        fontSize = 11.sp,
                        lineHeight = LABEL_LINE_HEIGHT,
                        style = SMALL_LINE,
                        color = if (isAmbient) AmbientDigits else Color.White,
                        maxLines = 1,
                    )
                }
            }
            if (arrowServe?.server != null && arrowServe.receiver != null) {
                Text(
                    stringResource(R.string.serve_arrow, playerName(arrowServe.server, true), playerName(arrowServe.receiver, true)),
                    modifier = Modifier.testTag(LiveTags.SERVE_ARROW),
                    fontSize = 10.sp,
                    lineHeight = LABEL_LINE_HEIGHT,
                    style = SMALL_LINE,
                    color = if (isAmbient) AmbientDigits else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val digits: @Composable () -> Unit = {
            val maxSize = digitSize.sp
            BasicText(
                text = value,
                modifier = Modifier.testTag(LiveTags.points(side)),
                style =
                    LocalTextStyle.current.merge(
                        TextStyle(
                            fontSize = maxSize,
                            lineHeight = 1.em,
                            fontWeight = FontWeight.Bold,
                            color = if (isAmbient) AmbientDigits else Color.White,
                            textAlign = TextAlign.Center,
                            platformStyle = NO_FONT_PADDING,
                            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                        ),
                    ),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = maxSize * DIGIT_MIN_FRACTION, maxFontSize = maxSize, stepSize = 1.sp),
            )
        }
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            header()
            NamesAndDigits(
                side = side,
                halfTop = top,
                halfHeight = height,
                screen = screen,
                sideInset = sideInset,
                label = {
                    Column(
                        modifier = if (labelHidden) Modifier.alpha(0f) else Modifier,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { label() }
                },
                digits = digits,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            footer()
        }
    }
}

/**
 * Namenszeile(n) und Ziffern einer Hälfte, in beiden Hälften die Namen über den Ziffern: „Wir“ unten bündig (Ziffern
 * am Mittelband), „Gegner“ oben bündig (Namen am Mittelband – dort liegt sie unter der Aufschlag-Leiste, während
 * diese kurz steht; die Ziffern bleiben immer frei). Die Ziffern bekommen die Höhe ohne die Namenszeilen (und schrumpfen
 * bei Bedarf); die Namenszeile wird erst gemessen, wenn ihre Lage feststeht – so breit, wie der runde Rand an ihrer
 * randnächsten Kante erlaubt ([ScoreLayout.chordWidth]), der Rest endet mit „…“. [halfTop]/[halfHeight] = Lage der
 * Hälfte im Screen, [screen] = Displaydurchmesser, [sideInset] = links und rechts freizuhaltender Rand (Telemetrie-
 * Spalten neben der „Gegner“-Zeile am Mittelband).
 */
@Composable
private fun NamesAndDigits(
    side: Side,
    halfTop: Dp,
    halfHeight: Dp,
    screen: Dp,
    sideInset: Dp,
    label: @Composable () -> Unit,
    digits: @Composable () -> Unit,
    modifier: Modifier,
) {
    Layout(contents = listOf(label, digits), modifier = modifier) { (labels, numbers), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val labelMeasurable = labels.single()
        val labelHeight = labelMeasurable.minIntrinsicHeight(width)
        val digitsPlaceable =
            numbers.single().measure(Constraints(maxWidth = width, maxHeight = (height - labelHeight).coerceAtLeast(0)))
        // „Wir“: Kopf (Uhrzeit) liegt darüber, dieser Bereich endet am Mittelband; „Gegner“: beginnt am Mittelband
        val top = halfTop.toPx() + if (side == Side.A) halfHeight.toPx() - height else 0f
        val labelY = if (side == Side.A) height - digitsPlaceable.height - labelHeight else 0
        val digitsY = labelY + labelHeight
        val margin = ScoreLayout.EDGE_MARGIN_DP.dp.toPx()
        val chord =
            min(
                ScoreLayout.chordWidth(screen.toPx(), top + labelY, margin),
                ScoreLayout.chordWidth(screen.toPx(), top + labelY + labelHeight, margin),
            )
        val labelWidth = min(min(chord, width * LABEL_WIDTH_FRACTION), width - 2 * sideInset.toPx()).toInt().coerceIn(0, width)
        val labelPlaceable = labelMeasurable.measure(Constraints(maxWidth = labelWidth, maxHeight = labelHeight.coerceAtLeast(0)))
        layout(width, height) {
            labelPlaceable.place((width - labelPlaceable.width) / 2, labelY)
            digitsPlaceable.place((width - digitsPlaceable.width) / 2, digitsY)
        }
    }
}

/**
 * Mittelband: Toast (3 s) oder Satzstände + Hinweis-Pill (bzw. Pickleball-Ansage); bei laufendem Workout links der
 * Puls, rechts die Schritte als kompakte Spalten am runden Rand. Alles in einer Zeile mit festen Randspalten – die
 * Mitte bekommt nur den Platz dazwischen, Telemetrie und Satzanzeige können sich nicht überlagern. Nicht klickbar:
 * Taps gehen wie bisher an die Gesten des Scores (das Mittelband zählt nie).
 */
@Composable
private fun MiddleBand(
    live: LiveUi,
    workout: WorkoutUiState,
    toast: WatchToast?,
    isAmbient: Boolean,
    modifier: Modifier,
) {
    val telemetry = workout.telemetryActive
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (telemetry) TelemetrySide(TelemetryKind.HEART_RATE, workout, isAmbient, Modifier.padding(start = TELEMETRY_EDGE))
        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (toast != null) {
                Text(
                    toastText(toast),
                    modifier = Modifier.testTag(LiveTags.TOAST),
                    fontSize = 11.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                return@Box
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val left = live.scoreCall ?: LiveModel.setsText(live.sets)
                if (left.isNotEmpty()) {
                    BasicText(
                        left,
                        modifier = Modifier.weight(1f, fill = false).testTag(LiveTags.SETS),
                        style =
                            LocalTextStyle.current.merge(
                                TextStyle(color = if (isAmbient) AmbientDigits else Color.White, platformStyle = NO_FONT_PADDING),
                            ),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
                    )
                }
                val hint = live.hint?.let { hintText(it, live.sport) }
                if (hint != null) {
                    HintPill(hint, live.hint.kind.level, isAmbient, Modifier.padding(start = 6.dp).testTag(LiveTags.HINT))
                }
            }
        }
        if (telemetry) TelemetrySide(TelemetryKind.STEPS, workout, isAmbient, Modifier.padding(end = TELEMETRY_EDGE))
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
    BasicText(
        text = text.uppercase(),
        modifier = pill.padding(horizontal = 6.dp, vertical = 1.dp),
        style =
            LocalTextStyle.current.merge(
                TextStyle(
                    fontWeight = FontWeight.Bold,
                    color =
                        when {
                            isAmbient -> AmbientDigits
                            level == ScoreHint.Level.Filled -> Color.Black
                            else -> HintAmber
                        },
                    platformStyle = NO_FONT_PADDING,
                ),
            ),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 7.5.sp, maxFontSize = 10.sp, stepSize = 0.5.sp),
    )
}

/** Status der Score-Seite: Zähler „Synchron / N ausstehend“ bzw. „ANZEIGE · Handy zählt / vor X s“, Sperre, Update. */
@Composable
internal fun statusText(live: LiveUi): String =
    when {
        live.locked -> stringResource(R.string.locked_label)

        live.updateRequired != null -> updateText(live.updateRequired, short = true)

        live.role == LiveRole.SCORER -> syncText(live.sync)

        // kurz, weil gebogen neben der Uhrzeit: „ANZEIGE · Handy zählt“ / „ANZEIGE · vor 45 s“
        live.sync == SyncUi.Live -> stringResource(R.string.status_view_live, deviceName(live.scorerKind))

        else -> stringResource(R.string.status_view_stale, syncText(live.sync))
    }

/**
 * Uhrzeit oben am Rand mit dem Status davor („3 ausstehend · 12:15“), gebogen wie die System-Uhrzeit (Wear-Muster
 * `TimeText`). Hinweise (ausstehend, Anzeige ohne Live-Verbindung, Update) in Amber.
 */
@Composable
private fun StatusTimeText(
    live: LiveUi,
    isAmbient: Boolean,
) {
    val text = statusText(live)
    val color =
        when {
            isAmbient -> AmbientDigits
            live.updateRequired != null -> HintAmber
            live.role == LiveRole.DISPLAY && live.sync != SyncUi.Live -> HintAmber
            live.sync is SyncUi.Pending -> HintAmber
            else -> TextSecondary
        }
    TimeText(
        modifier = Modifier.semantics { contentDescription = text }.testTag(LiveTags.TOP_STATUS),
        maxSweepAngle = STATUS_SWEEP_DEGREES,
    ) { time ->
        timeTextCurvedText(text, CurvedTextStyle(color = color))
        timeTextSeparator()
        timeTextCurvedText(time)
    }
}

/**
 * Unten in der Hälfte „Gegner“ (Anzeige): nach > 30 s ohne Zähler „Zählen übernehmen“ (tippbar, keine Score-Fläche)
 * bzw. „N Eingaben nicht übernommen“. Puls und Schritte stehen seit dem Feldtest 2026-10-16 seitlich im Mittelband.
 */
@Composable
private fun BottomLine(
    live: LiveUi,
    actions: LiveActions,
    modifier: Modifier,
) {
    if (live.takeOverOffered) {
        androidx.wear.compose.material3.CompactButton(
            onClick = actions::requestTakeOver,
            modifier = modifier,
            label = {
                BasicText(
                    stringResource(R.string.take_over_offer),
                    style = LocalTextStyle.current.merge(TextStyle(color = LocalContentColor.current)),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = 0.5.sp),
                )
            },
        )
        return
    }
    Text(
        pluralStringResource(R.plurals.rejected_inputs, live.rejectedInputs, live.rejectedInputs),
        modifier = modifier,
        fontSize = 10.sp,
        lineHeight = LABEL_LINE_HEIGHT,
        color = HintAmber,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private object BottomLine {
    /** Steht unten etwas? Nur interaktiv und nur für die Anzeige-Rolle. */
    fun shown(
        live: LiveUi,
        isAmbient: Boolean,
    ): Boolean = !isAmbient && (live.takeOverOffered || (live.rejectedInputs > 0 && live.role == LiveRole.DISPLAY))
}

/** Laufendes Halten auf [side] seit [startUptime]; [fired] = Haltezeit erreicht, Aktion ausgelöst (Anzeige voll). */
private data class HoldUi(
    val side: Side,
    val startUptime: Long,
    val fired: Boolean = false,
)

/** Halte-Fortschritt einer Hälfte (0 → 1). */
private class HoldProgress {
    var side by mutableStateOf<Side?>(null)
    var fraction by mutableFloatStateOf(0f)
}

/**
 * Fortschritt 0 → 1 über die Haltezeit ([GestureClassifier.LONG_PRESS_MILLIS], Frame-Zeit statt Animationsskala, damit
 * er genau beim Auslösen voll ist); ausgelöst bleibt er voll, bis der Finger hochgeht; Loslassen bzw. Abbruch lässt ihn
 * kurz zurücklaufen ([HOLD_RESET_MILLIS]).
 */
@Composable
private fun rememberHoldProgress(hold: HoldUi?): HoldProgress {
    val progress = remember { HoldProgress() }
    LaunchedEffect(hold) {
        when {
            hold == null -> {
                val from = progress.fraction
                if (from <= 0f) return@LaunchedEffect
                val first = withFrameMillis { it }
                while (true) {
                    val t = (withFrameMillis { it } - first).toFloat() / HOLD_RESET_MILLIS
                    progress.fraction = (from * (1 - t)).coerceAtLeast(0f)
                    if (t >= 1f) break
                }
            }

            hold.fired -> {
                progress.side = hold.side
                progress.fraction = 1f
            }

            else -> {
                progress.side = hold.side
                progress.fraction = 0f
                val first = withFrameMillis { it }
                while (true) {
                    progress.fraction = OperationTimings.holdFraction(withFrameMillis { it } - first)
                    if (progress.fraction >= 1f) break
                }
            }
        }
    }
    return progress
}

/** Halte-Fortschritt einer Hälfte (für Tests und Barrierefreiheits-Werkzeuge). */
val HoldProgressKey = SemanticsPropertyKey<Float>("HoldProgress")
private var SemanticsPropertyReceiver.holdProgressValue by HoldProgressKey

private const val HOLD_FILL_ALPHA = 0.35f
private const val HOLD_RESET_MILLIS = 180L

/** Test-Tags der Score-Seite (Layout-Tests: nichts darf sich überlagern). */
internal object LiveTags {
    const val TOP_STATUS = "live_top_status"
    const val SERVE_MARKER = "live_serve_marker"
    const val SERVE_ARROW = "live_serve_arrow"
    const val SETS = "live_sets"
    const val HINT = "live_hint"
    const val TOAST = "live_toast"
    const val HEART_RATE = "live_heart_rate"
    const val STEPS = "live_steps"

    fun label(side: Side): String = "live_label_${side.name}"

    fun points(side: Side): String = "live_points_${side.name}"
}

private const val HALF_FRACTION = (1f - TapZones.MIDDLE_BAND) / 2f
private const val DIGIT_FRACTION = 0.55f

/** Höhe der Ziffern-Box im Verhältnis zur Schriftgröße (Ober-/Unterlänge der Schrift). */
private const val DIGIT_BOX_RATIO = 1.2f

/** Bogen der Uhrzeit-Zeile mit Status (länger als die reine Uhrzeit, damit „ANZEIGE · Stand vor 45 s“ passt). */
private const val STATUS_SWEEP_DEGREES = 100f

/** Abstand der Anzeige-Hinweise zum unteren Rand (wie die obere Totzone; darunter ist der Kreis zu schmal). */
private const val BOTTOM_FRACTION = 0.12f

/** Ziffern dürfen bis auf diesen Anteil schrumpfen, damit Status, Namen und Hinweise Platz behalten. */
private const val DIGIT_MIN_FRACTION = 0.45f

/** Namenszeile: höchstens dieser Anteil der Breite (dazu begrenzt der runde Rand, [ScoreLayout]). */
private const val LABEL_WIDTH_FRACTION = 0.86f

/** Feste Zeilenhöhe der kleinen Zeilen (Status, Namen) – planbar statt Theme-Zeilenhöhe. */
private val LABEL_LINE_HEIGHT = 13.sp

/** Ohne Font-Padding: die Textbox ist genau die Zeilenhöhe (Layout planbar, keine unsichtbaren Überlappungen). */
private val NO_FONT_PADDING = PlatformTextStyle(includeFontPadding = false)

/** Stil der kleinen Zeilen (Status, Namen, Aufschlag-Pfeil). */
private val SMALL_LINE = TextStyle(platformStyle = NO_FONT_PADDING, lineHeightStyle = LineHeightStyle.Default)

/** Abstand der Telemetrie-Spalten zum runden Rand (auf Höhe der Mitte ist der Kreis am breitesten). */
private val TELEMETRY_EDGE = 3.dp
private val SWIPE_THRESHOLD = 40.dp

/** Back-Geste auf Unterseiten: zurück zum Score. */
@Composable
internal fun BackToScore(onBack: () -> Unit) = BackHandler(onBack = onBack)
