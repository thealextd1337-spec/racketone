package eu.advantly.ui.phone

import androidx.compose.ui.window.ComposeUIViewController
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.match.MatchStore
import eu.advantly.ui.phone.app.rememberAdvantlyAppModel
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController
import platform.posix.fflush

/** Startargument für den Auto-Benchmark (CI: `xcrun simctl launch … eu.advantly -advantlyAutoBench`). */
private const val AUTO_BENCH_ARGUMENT = "-advantlyAutoBench"

/**
 * Host-ViewController für die iOS-App; wird über `umbrella-ios` (AdvantlyKit) an Swift gereicht.
 * Mit `-advantlyAutoBench` startet die App direkt ein Tennis-Match und den Auto-Benchmark (Spike S4, CI).
 *
 * @param store Quelle der Ports (AdvantlyKit: Session-Adapter über `AppleMatchSessions`); einmal je ViewModel aufgerufen
 */
@OptIn(ExperimentalForeignApi::class)
fun advantlyViewController(store: () -> MatchStore = { InMemoryMatchStore() }): UIViewController {
    val autoBench = NSProcessInfo.processInfo.arguments.any { it == AUTO_BENCH_ARGUMENT }
    return ComposeUIViewController {
        AdvantlyApp(
            model = rememberAdvantlyAppModel(store),
            debugTools = autoBench,
            autoBenchmark = autoBench,
            // Landet auf stdout (CI: `simctl launch --stdout=…`)
            onBenchmarkFinished = {
                println(it)
                fflush(null)
            },
        )
    }
}
