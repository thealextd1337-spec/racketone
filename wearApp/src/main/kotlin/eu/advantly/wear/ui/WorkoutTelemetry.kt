package eu.advantly.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import eu.advantly.core.model.MatchId
import eu.advantly.wear.R
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
    val pulse = workout.heartRateBpm?.toString() ?: stringResource(R.string.telemetry_unknown)
    val steps = workout.steps?.toString() ?: stringResource(R.string.telemetry_unknown)
    val description = stringResource(R.string.live_workout_telemetry_description, pulse, steps)
    val color = if (isAmbient) AmbientDigits else Color.White
    Row(
        modifier =
            modifier
                .padding(bottom = 8.dp)
                .width((LocalConfiguration.current.screenWidthDp * 0.68f).dp)
                .heightIn(min = 22.dp)
                .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.live_workout_telemetry, pulse),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = color,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(painterResource(R.drawable.ic_steps), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(
            text = steps,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = color,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
