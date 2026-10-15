package eu.advantly.ui.phone

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eu.advantly.presentation.live.Team
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.latency.InputClockSource
import eu.advantly.ui.phone.live.LiveScoreController
import eu.advantly.ui.phone.live.LiveScoreScreen
import eu.advantly.ui.phone.live.rememberLiveScoreController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.geom.Point2D
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Desktop-Preview auf Windows (kein Produkt): `./gradlew :shared:ui-phone:run`.
 *
 * Mit `ADVANTLY_BENCH=1` (oder Argument `--bench`) misst die Preview Tap → Frame selbst: Sie stellt echte
 * AWT-Mausereignisse in die System-Event-Queue (gleicher Pfad wie ein Mausklick, nur ohne Maus-Hardware),
 * gibt die Messwerte auf stdout aus und beendet sich.
 */
fun main(args: Array<String>) {
    val bench = "--bench" in args || System.getenv("ADVANTLY_BENCH") == "1"
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Advantly – Desktop-Preview",
            state = rememberWindowState(width = 412.dp, height = 892.dp),
        ) {
            // Gleicher Weg wie auf Android/iOS: ViewModel vom ViewModelStoreOwner des Fensters
            val controller = rememberLiveScoreController()
            AdvantlyTheme(darkTheme = true) {
                LiveScoreScreen(controller = controller, onBenchmarkFinished = { println(it) })
            }
            if (bench) {
                LaunchedEffect(Unit) {
                    val report = withContext(Dispatchers.IO) { DesktopBenchmark(window, controller).run() }
                    println(report)
                    exitApplication()
                }
            }
        }
    }
}

/** Klickt per AWT-Event-Queue auf die Tap-Flächen und liest danach den LatencyTracker aus. */
private class DesktopBenchmark(
    private val window: JFrame,
    private val controller: LiveScoreController,
) {
    fun run(): String {
        Thread.sleep(2_500)
        val lines = mutableListOf<String>()

        // Phase 1: einzelne Taps im Abstand von 300 ms (abwechselnd Wir/Gegner → Dauer-Einstand, kein Matchende)
        onEdt {
            controller.newMatch()
            controller.resetMeasurements()
        }
        repeat(SINGLE_TAPS) { i ->
            tap(if (i % 2 == 0) Team.Us else Team.Them)
            Thread.sleep(300)
        }
        Thread.sleep(500)
        lines += "single taps:  " + report()

        // Phase 2: schnelle Doppel-Taps (70 ms Abstand) – geht ein Tap verloren?
        onEdt {
            controller.newMatch()
            controller.resetMeasurements()
        }
        repeat(DOUBLE_TAPS) { i ->
            val team = if (i % 2 == 0) Team.Us else Team.Them
            tap(team)
            Thread.sleep(70)
            tap(team)
            Thread.sleep(400)
        }
        Thread.sleep(500)
        lines += "double taps:  " + report() + " injected=${DOUBLE_TAPS * 2}"
        return lines.joinToString("\n")
    }

    /** Nur Messungen mit Ereignis-Zeitstempel; Handler-Fallbacks werden separat gezählt. */
    private fun report(): String =
        onEdt {
            controller.tracker.summary(InputClockSource.InputEvent).toLogLine() +
                " fallback_n=${controller.tracker.summary(InputClockSource.Handler).samples}"
        } + " " + counters()

    private fun counters(): String =
        onEdt { "taps=${controller.rawTaps} points=${controller.countedPoints} debounced=${controller.debouncedTaps}" }

    private fun tap(team: Team) {
        val (target, point) = onEdt { targetFor(team) }
        post(MouseEvent.MOUSE_PRESSED, target, point, InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(40)
        post(MouseEvent.MOUSE_RELEASED, target, point, 0)
        post(MouseEvent.MOUSE_CLICKED, target, point, 0)
    }

    private fun targetFor(team: Team): Pair<java.awt.Component, Point2D> {
        val bounds = checkNotNull(controller.panelBounds[team]) { "Panel $team noch nicht gelayoutet" }
        val scale = window.graphicsConfiguration.defaultTransform.scaleX
        val content = window.contentPane
        val x = (bounds.center.x / scale).toInt()
        val y = (bounds.center.y / scale).toInt()
        val target = SwingUtilities.getDeepestComponentAt(content, x, y) ?: content
        val local = SwingUtilities.convertPoint(content, x, y, target)
        return target to local
    }

    private fun post(
        id: Int,
        target: java.awt.Component,
        point: Point2D,
        modifiers: Int,
    ) {
        val event =
            MouseEvent(
                target,
                id,
                System.currentTimeMillis(),
                modifiers,
                point.x.toInt(),
                point.y.toInt(),
                1,
                false,
                MouseEvent.BUTTON1,
            )
        Toolkit.getDefaultToolkit().systemEventQueue.postEvent(event)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private companion object {
        const val SINGLE_TAPS = 60
        const val DOUBLE_TAPS = 20
    }
}
