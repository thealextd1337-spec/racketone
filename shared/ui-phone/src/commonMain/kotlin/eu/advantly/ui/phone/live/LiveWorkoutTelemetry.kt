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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.presentation.live.LiveTelemetryStore
import eu.advantly.ui.phone.app.LocalTelemetryVisibility
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.live_telemetry_heart_rate
import eu.advantly.ui.phone.resources.live_telemetry_steps
import eu.advantly.ui.phone.resources.live_telemetry_unknown
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Kleiner Live-Baustein: nur eigene Workout-Werte des passenden Matches, nie SavedState/RememberSaveable. Bei
 * Telemetrie-Sichtbarkeit „Gar nicht“ (P2-J, [visibility]) kein Abo bei der Uhr und keine Anzeige.
 */
@Composable
internal fun LiveWorkoutTelemetry(
    matchId: MatchId,
    workoutEnabled: Boolean,
    outdoor: Boolean,
    source: LiveTelemetryStore = LiveTelemetryStore.Phone,
    compatibleWatch: Boolean? = null,
    visibility: TelemetryVisibility = LocalTelemetryVisibility.current,
) {
    val enabled = workoutEnabled && visibility.allowsLive
    // Das Abo muss schon vor dem ersten Wert laufen; sonst könnte die Zeile nie erscheinen.
    val watchAvailable = compatibleWatch ?: LiveTelemetrySubscription(matchId, enabled)
    if (!enabled) return
    val received by source.state.collectAsState()
    val recent by source.recent.collectAsState()
    LaunchedEffect(source, received, recent) {
        val remaining = source.remainingLifetimeMs().takeIf { it > 0 } ?: source.remainingVisibilityMs()
        if (remaining == 0L) return@LaunchedEffect
        delay(remaining)
        source.expire()
    }
    if (!watchAvailable || !source.recentlyReceived(matchId)) return
    val values = source.forMatch(matchId, workoutEnabled)
    val unknown = stringResource(Res.string.live_telemetry_unknown)
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("live_workout_telemetry"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(Res.string.live_telemetry_heart_rate, values?.heartRateBpm?.toString() ?: unknown),
            style = AdvantlyTheme.typography.caption,
            color = if (outdoor) Color.White else AdvantlyTheme.colors.text2,
            modifier = Modifier.testTag("live_telemetry_heart_rate"),
        )
        Text(
            stringResource(Res.string.live_telemetry_steps, values?.steps?.toString() ?: unknown),
            style = AdvantlyTheme.typography.caption,
            color = if (outdoor) Color.White else AdvantlyTheme.colors.text2,
            modifier = Modifier.testTag("live_telemetry_steps"),
        )
    }
}
