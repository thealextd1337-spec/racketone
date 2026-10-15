package eu.advantly.ui.phone.live

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.advantly.ui.phone.designsystem.AdvantlyTheme

/**
 * Einstieg für Spike S4: der Live-Score-Screen im (immer dunklen) Advantly-Theme.
 * Android-Debug-Build, iOS-Host und Desktop-Preview starten direkt hier.
 */
@Composable
fun LiveScoreSpikeApp(
    modifier: Modifier = Modifier,
    autoBenchmark: Boolean = false,
    onBenchmarkFinished: (String) -> Unit = {},
) {
    AdvantlyTheme(darkTheme = true) {
        LiveScoreScreen(modifier = modifier, autoBenchmark = autoBenchmark, onBenchmarkFinished = onBenchmarkFinished)
    }
}
