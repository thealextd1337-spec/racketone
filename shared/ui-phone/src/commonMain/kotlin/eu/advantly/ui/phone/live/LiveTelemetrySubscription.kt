package eu.advantly.ui.phone.live

import androidx.compose.runtime.Composable
import eu.advantly.core.model.MatchId

/** Hält nur während der sichtbaren Live-Komposition das Wear-Abo frisch; andere Plattformen liefern false. */
@Composable
internal expect fun LiveTelemetrySubscription(
    matchId: MatchId,
    enabled: Boolean,
): Boolean
