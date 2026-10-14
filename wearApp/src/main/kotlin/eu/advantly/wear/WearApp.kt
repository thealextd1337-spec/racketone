package eu.advantly.wear

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import eu.advantly.wear.scoring.GestureClassifier
import eu.advantly.wear.scoring.ScoreRepository
import eu.advantly.wear.scoring.ScoringGesture
import eu.advantly.wear.scoring.Side
import eu.advantly.wear.scoring.SpikeScore
import eu.advantly.wear.scoring.TapGuard
import eu.advantly.wear.scoring.TapZones
import eu.advantly.wear.workout.CapabilitiesRepository
import eu.advantly.wear.workout.ExerciseTypeSelector
import eu.advantly.wear.workout.SpikeSport
import eu.advantly.wear.workout.WatchExerciseType
import eu.advantly.wear.workout.WorkoutPhase
import eu.advantly.wear.workout.WorkoutStateHolder
import eu.advantly.wear.workout.WorkoutUiState
import kotlinx.coroutines.delay

// Farb-Tokens aus 04 §5.2 (Dark): Team Wir = Orange, Team Gegner = Blau, Live-Screen schwarz.
private val TeamUs = Color(0xFFFF6A1A)
private val TeamThem = Color(0xFF5B9BFF)
private val AmbientDigits = Color.White.copy(alpha = 0.65f)

/** Wurzel-Composable der Wear-App (Spike S5: Workout-Laufzeit). Texte aus res/values[-de]/strings.xml. */
@Composable
fun WearApp(
    isAmbient: Boolean,
    permissionsGranted: Boolean,
    initialSport: SpikeSport,
    logPath: String?,
    onStart: (SpikeSport) -> Unit,
    onPoint: (Side) -> Boolean,
    onUndo: () -> Side?,
    onEndMatch: () -> Unit,
) {
    MaterialTheme {
        val workout by WorkoutStateHolder.state.collectAsState()
        val score by ScoreRepository.score.collectAsState()
        var showControls by remember { mutableStateOf(false) }
        when (workout.phase) {
            WorkoutPhase.IDLE -> {
                LaunchedEffect(Unit) { showControls = false }
                SetupScreen(initialSport = initialSport, permissionsGranted = permissionsGranted, onStart = onStart)
            }

            WorkoutPhase.FAILED -> {
                ErrorScreen(message = workout.errorMessage, onBack = onEndMatch)
            }

            else -> {
                if (showControls && !isAmbient) {
                    ControlsScreen(workout = workout, logPath = logPath, onResume = { showControls = false }, onEndMatch = onEndMatch)
                } else {
                    ScoringScreen(
                        score = score,
                        workout = workout,
                        isAmbient = isAmbient,
                        onPoint = onPoint,
                        onUndo = onUndo,
                        onOpenControls = { showControls = true },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Setup

@Composable
private fun SetupScreen(
    initialSport: SpikeSport,
    permissionsGranted: Boolean,
    onStart: (SpikeSport) -> Unit,
) {
    var sport by remember { mutableStateOf(initialSport) }
    val capabilities by CapabilitiesRepository.capabilities.collectAsState()
    ScalingLazyColumn(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        item {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        SpikeSport.entries.forEach { option ->
            item {
                Button(
                    onClick = { sport = option },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (option == sport) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Text(sportName(option))
                }
            }
        }
        item {
            val caps = capabilities
            val text =
                when {
                    caps == null -> {
                        stringResource(R.string.exercise_type_loading)
                    }

                    else -> {
                        val selection = ExerciseTypeSelector.select(sport, caps)
                        when {
                            selection == null -> stringResource(R.string.exercise_type_unsupported)
                            selection.isFallback -> stringResource(R.string.exercise_type_fallback, typeName(selection.type))
                            else -> stringResource(R.string.exercise_type_native, typeName(selection.type))
                        }
                    }
                }
            Text(text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        item {
            Button(onClick = { onStart(sport) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_start))
            }
        }
        item {
            Text(
                stringResource(if (permissionsGranted) R.string.permission_rationale else R.string.permission_missing),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(stringResource(R.string.gesture_hint), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        item {
            Text(
                stringResource(R.string.wear_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun sportName(sport: SpikeSport): String =
    when (sport) {
        SpikeSport.TENNIS -> stringResource(R.string.sport_tennis)
        SpikeSport.BADMINTON -> stringResource(R.string.sport_badminton)
        SpikeSport.TABLE_TENNIS -> stringResource(R.string.sport_table_tennis)
    }

@Composable
private fun typeName(type: WatchExerciseType): String =
    when (type) {
        WatchExerciseType.TENNIS -> stringResource(R.string.sport_tennis)
        WatchExerciseType.BADMINTON -> stringResource(R.string.sport_badminton)
        WatchExerciseType.TABLE_TENNIS -> stringResource(R.string.sport_table_tennis)
        WatchExerciseType.WORKOUT -> stringResource(R.string.exercise_type_workout)
    }

// ------------------------------------------------------------------------------------------------ Scoring

/**
 * Scoring-Screen nach 04 §4.3: obere Hälfte = Wir, untere = Gegner, große Ziffern, Totzonen oben (12 %) und
 * im Mittelband (10 %), Tap zählt erst beim Loslassen, 600 ms Sperre nach jedem Punkt (in [TapGuard]),
 * Wischen nach unten = Undo, nach links = Steuerseite. Im Ambient-Modus: schwarz, gedimmte Ziffern, nur Konturen,
 * keine Eingaben.
 */
@Composable
private fun ScoringScreen(
    score: SpikeScore,
    workout: WorkoutUiState,
    isAmbient: Boolean,
    onPoint: (Side) -> Boolean,
    onUndo: () -> Side?,
    onOpenControls: () -> Unit,
) {
    val resources = LocalResources.current
    var flashSide by remember { mutableStateOf<Side?>(null) }
    var flashKey by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(flashKey) {
        if (flashSide != null) {
            delay(TapGuard.DEFAULT_LOCKOUT_MILLIS)
            flashSide = null
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(UNDO_TOAST_MILLIS)
            toast = null
        }
    }
    // Sekundentakt für die Dauer nur im interaktiven Modus (Ambient: kein Sekundentakt, 04 §4.3.6).
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(isAmbient) {
        nowElapsed = SystemClock.elapsedRealtime()
        while (!isAmbient) {
            delay(1_000)
            nowElapsed = SystemClock.elapsedRealtime()
        }
    }

    val currentScore by rememberUpdatedState(score)
    val currentOnPoint by rememberUpdatedState(onPoint)
    val currentOnUndo by rememberUpdatedState(onUndo)
    val currentOnOpenControls by rememberUpdatedState(onOpenControls)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val swipeThreshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(isAmbient) {
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
                        when (gesture) {
                            ScoringGesture.TAP -> {
                                TapZones.classify(down.position.y, size.height.toFloat())?.let { side ->
                                    if (currentOnPoint(side)) {
                                        flashSide = side
                                        flashKey++
                                    }
                                }
                            }

                            ScoringGesture.SWIPE_DOWN -> {
                                val before = currentScore
                                toast =
                                    when (currentOnUndo()) {
                                        Side.US -> {
                                            resources.getString(
                                                R.string.undo_toast,
                                                before.format(),
                                                before.copy(us = before.us - 1).format(),
                                            )
                                        }

                                        Side.THEM -> {
                                            resources.getString(
                                                R.string.undo_toast,
                                                before.format(),
                                                before
                                                    .copy(
                                                        them =
                                                            before.them - 1,
                                                    ).format(),
                                            )
                                        }

                                        null -> {
                                            resources.getString(R.string.nothing_to_undo)
                                        }
                                    }
                            }

                            ScoringGesture.SWIPE_LEFT -> {
                                currentOnOpenControls()
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
        val heartRatePadding = maxHeight * 0.04f
        val digitSize = with(LocalDensity.current) { (halfHeight * DIGIT_FRACTION).toSp() }
        Column(modifier = Modifier.fillMaxSize()) {
            TeamHalf(
                side = Side.US,
                value = score.us,
                label = stringResource(R.string.label_us),
                height = halfHeight,
                digitSize = digitSize.value,
                isAmbient = isAmbient,
                flashing = flashSide == Side.US,
                verticalArrangement = Arrangement.Bottom,
            )
            Box(modifier = Modifier.fillMaxWidth().height(bandHeight), contentAlignment = Alignment.Center) {
                Text(
                    text = toast?.takeUnless { isAmbient } ?: middleBandText(workout, nowElapsed),
                    fontSize = 11.sp,
                    color = if (isAmbient) AmbientDigits else Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            TeamHalf(
                side = Side.THEM,
                value = score.them,
                label = stringResource(R.string.label_them),
                height = halfHeight,
                digitSize = digitSize.value,
                isAmbient = isAmbient,
                flashing = flashSide == Side.THEM,
                verticalArrangement = Arrangement.Top,
            )
        }
        Text(
            text =
                workout.heartRateBpm?.let { stringResource(R.string.heart_rate_value, it) }
                    ?: stringResource(R.string.heart_rate_unknown),
            fontSize = 12.sp,
            color = if (isAmbient) AmbientDigits else Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = heartRatePadding),
        )
    }
}

@Composable
private fun middleBandText(
    workout: WorkoutUiState,
    nowElapsed: Long,
): String {
    if (workout.phase == WorkoutPhase.STARTING) return stringResource(R.string.workout_starting)
    val anchor = workout.durationAnchorElapsedMillis
    val activeMillis =
        if (anchor != null && workout.phase == WorkoutPhase.ACTIVE) {
            workout.activeDurationMillis + (nowElapsed - anchor).coerceAtLeast(0)
        } else {
            workout.activeDurationMillis
        }
    val totalSec = activeMillis / 1000
    val duration = "${totalSec / 3600}:${twoDigits((totalSec / 60) % 60)}:${twoDigits(totalSec % 60)}"
    val type = workout.selection?.let { typeName(it.type) }
    return if (type != null) "$type · $duration" else duration
}

private fun twoDigits(value: Long): String = value.toString().padStart(2, '0')

@Composable
private fun TeamHalf(
    side: Side,
    value: Int,
    label: String,
    height: Dp,
    digitSize: Float,
    isAmbient: Boolean,
    flashing: Boolean,
    verticalArrangement: Arrangement.Vertical,
) {
    val teamColor = if (side == Side.US) TeamUs else TeamThem
    val base =
        Modifier
            .fillMaxWidth()
            .height(height)
    val styled =
        if (isAmbient) {
            base.border(1.dp, teamColor.copy(alpha = 0.5f)) // nur Kontur (04 §4.3.6)
        } else {
            base.background(teamColor.copy(alpha = if (flashing) 0.35f else 0.12f))
        }
    Column(modifier = styled, verticalArrangement = verticalArrangement, horizontalAlignment = Alignment.CenterHorizontally) {
        val labelText: @Composable ColumnScope.() -> Unit = {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isAmbient) AmbientDigits else teamColor,
            )
        }
        val digitText: @Composable ColumnScope.() -> Unit = {
            Text(
                text = value.toString(),
                fontSize = digitSize.sp,
                lineHeight = digitSize.sp,
                fontWeight = FontWeight.Bold,
                color = if (isAmbient) AmbientDigits else Color.White,
            )
        }
        if (side == Side.US) {
            labelText()
            digitText()
        } else {
            digitText()
            labelText()
        }
    }
}

// ------------------------------------------------------------------------------------------------ Steuerseite / Fehler

@Composable
private fun ControlsScreen(
    workout: WorkoutUiState,
    logPath: String?,
    onResume: () -> Unit,
    onEndMatch: () -> Unit,
) {
    ScalingLazyColumn(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        item { Text(stringResource(R.string.controls_title), style = MaterialTheme.typography.titleMedium) }
        item {
            Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_resume)) }
        }
        item {
            Button(
                onClick = onEndMatch,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) { Text(stringResource(R.string.action_end_match)) }
        }
        item {
            Text(
                stringResource(
                    R.string.stats_line,
                    workout.caloriesKcal?.toInt()?.toString() ?: "--",
                    workout.steps?.toString() ?: "--",
                ),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        if (logPath != null) {
            item {
                Text(stringResource(R.string.log_path, logPath), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ErrorScreen(
    message: String?,
    onBack: () -> Unit,
) {
    ScalingLazyColumn(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        item { Text(stringResource(R.string.error_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
        if (message != null) {
            item { Text(message, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
        }
        item {
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_back)) }
        }
    }
}

private const val HALF_FRACTION = (1f - TapZones.MIDDLE_BAND) / 2f
private const val DIGIT_FRACTION = 0.55f
private const val UNDO_TOAST_MILLIS = 3_000L
private val SWIPE_THRESHOLD = 40.dp
