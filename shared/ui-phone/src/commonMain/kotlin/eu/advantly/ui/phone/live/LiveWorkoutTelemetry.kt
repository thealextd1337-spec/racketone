package eu.advantly.ui.phone.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.MatchId
import eu.advantly.presentation.live.LiveTelemetryStore
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.live_telemetry_heart_rate
import eu.advantly.ui.phone.resources.live_telemetry_steps
import eu.advantly.ui.phone.resources.live_telemetry_unknown
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** Kleiner Live-Baustein: nur eigene Workout-Werte des passenden Matches, nie SavedState/RememberSaveable. */
@Composable
internal fun LiveWorkoutTelemetry(
    matchId: MatchId,
    workoutEnabled: Boolean,
    outdoor: Boolean,
    source: LiveTelemetryStore = LiveTelemetryStore.Phone,
) {
    if (!workoutEnabled) return
    val received by source.state.collectAsState()
    LaunchedEffect(source, received) {
        delay(source.remainingLifetimeMs())
        source.expire()
    }
    val values = source.forMatch(matchId, workoutEnabled)
    val unknown = stringResource(Res.string.live_telemetry_unknown)
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("live_workout_telemetry"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(Res.string.live_telemetry_heart_rate, values?.heartRateBpm?.toString() ?: unknown),
            style = AdvantlyTheme.typography.caption,
            color = if (outdoor) androidx.compose.ui.graphics.Color.White else AdvantlyTheme.colors.text2,
            modifier = Modifier.testTag("live_telemetry_heart_rate"),
        )
        Text(
            stringResource(Res.string.live_telemetry_steps, values?.steps?.toString() ?: unknown),
            style = AdvantlyTheme.typography.caption,
            color = if (outdoor) androidx.compose.ui.graphics.Color.White else AdvantlyTheme.colors.text2,
            modifier = Modifier.testTag("live_telemetry_steps"),
        )
    }
}
