package eu.advantly.ui.phone

import androidx.compose.ui.window.ComposeUIViewController
import eu.advantly.ui.phone.live.LiveScoreSpikeApp
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController
import platform.posix.fflush

/** Startargument für den Auto-Benchmark (CI: `xcrun simctl launch … eu.advantly -advantlyAutoBench`). */
private const val AUTO_BENCH_ARGUMENT = "-advantlyAutoBench"

/**
 * Host-ViewController für die iOS-App; wird über `umbrella-ios` (AdvantlyKit) an Swift gereicht.
 * Spike S4: zeigt direkt den Live-Score-Screen.
 */
@OptIn(ExperimentalForeignApi::class)
fun advantlyViewController(): UIViewController {
    val autoBench = NSProcessInfo.processInfo.arguments.any { it == AUTO_BENCH_ARGUMENT }
    return ComposeUIViewController {
        LiveScoreSpikeApp(
            autoBenchmark = autoBench,
            // Landet auf stdout (CI: `simctl launch --stdout=…`)
            onBenchmarkFinished = {
                println(it)
                fflush(null)
            },
        )
    }
}
