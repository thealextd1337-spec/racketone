package eu.advantly.wear.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import eu.advantly.core.model.MatchId
import eu.advantly.wear.R
import eu.advantly.wear.live.ValueThrottle
import eu.advantly.wear.live.telemetryActive
import eu.advantly.wear.workout.WorkoutUiState

/** Eine schmale, mittig platzierte Zeile für runde Displays; beide Werte im Ambient-Modus höchstens 1×/min. */
@Composable
internal fun WorkoutTelemetry(
    matchId: MatchId,
    workout: WorkoutUiState,
    isAmbient: Boolean,
    modifier: Modifier,
) {
    if (!workout.telemetryActive) return
    val throttle = remember(matchId) { ValueThrottle<Pair<Int?, Long?>>() }
    var shown by remember(matchId) { mutableStateOf(workout.heartRateBpm to workout.steps) }
    LaunchedEffect(workout.heartRateBpm, workout.steps, isAmbient) {
        shown = throttle.display(workout.heartRateBpm to workout.steps, SystemClock.elapsedRealtime(), isAmbient) ?: (null to null)
    }
    val pulse = shown.first?.toString() ?: stringResource(R.string.telemetry_unknown)
    val steps = shown.second?.toString() ?: stringResource(R.string.telemetry_unknown)
    val description = stringResource(R.string.live_workout_telemetry_description, pulse, steps)
    Text(
        text = stringResource(R.string.live_workout_telemetry, pulse, steps),
        fontSize = 10.sp,
        maxLines = 1,
        color = if (isAmbient) AmbientDigits else Color.White,
        modifier = modifier.semantics { contentDescription = description },
    )
}
