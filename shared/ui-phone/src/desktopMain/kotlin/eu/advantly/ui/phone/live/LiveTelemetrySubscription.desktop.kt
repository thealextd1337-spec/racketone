package eu.advantly.ui.phone.live

import androidx.compose.runtime.Composable
import eu.advantly.core.model.MatchId

@Composable
internal actual fun LiveTelemetrySubscription(
    matchId: MatchId,
    enabled: Boolean,
): Boolean = false
