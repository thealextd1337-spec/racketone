package eu.advantly.ui.phone.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.MatchId
import eu.advantly.presentation.live.LiveTelemetryStore
import eu.advantly.presentation.match.MonotonicClock
import eu.advantly.sync.protocol.LiveTelemetry
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class LiveWorkoutTelemetryTest {
    private val match = MatchId("match")

    @Test
    fun liveValuesAndMissingMetricsInEnglish() =
        runDesktopComposeUiTest(width = 412, height = 120) {
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.US)
            try {
                var now = 0L
                val source = LiveTelemetryStore(MonotonicClock { now })
                setContent {
                    AdvantlyTheme(darkTheme = true) { LiveWorkoutTelemetry(match, true, false, source) }
                }
                onNodeWithTag("live_telemetry_heart_rate").assertTextEquals("Heart rate: — bpm")
                onNodeWithTag("live_telemetry_steps").assertTextEquals("Steps: —")
                runOnIdle { source.receive(LiveTelemetry(1, match, 142, 1234)) }
                onNodeWithTag("live_telemetry_heart_rate").assertTextEquals("Heart rate: 142 bpm")
                onNodeWithTag("live_telemetry_steps").assertTextEquals("Steps: 1234")
                runOnIdle { source.receive(LiveTelemetry(1, match, steps = 0)) }
                onNodeWithTag("live_telemetry_heart_rate").assertTextEquals("Heart rate: — bpm")
                onNodeWithTag("live_telemetry_steps").assertTextEquals("Steps: 0")
                runOnIdle {
                    now = 30_000
                    source.expire()
                }
                onNodeWithTag("live_telemetry_steps").assertTextEquals("Steps: —")
                runOnIdle { source.receive(LiveTelemetry(1, MatchId("other"), 180, 999)) }
                onNodeWithTag("live_telemetry_heart_rate").assertTextEquals("Heart rate: — bpm")
                onNodeWithTag("live_telemetry_steps").assertTextEquals("Steps: —")
            } finally {
                Locale.setDefault(previous)
            }
        }

    @Test
    fun disabledWorkoutHidesTelemetry() =
        runDesktopComposeUiTest(width = 412, height = 120) {
            setContent { AdvantlyTheme(darkTheme = true) { LiveWorkoutTelemetry(match, false, false) } }
            onNodeWithTag("live_workout_telemetry").assertDoesNotExist()
        }

    @Test
    fun germanLargeFontScreenshot() =
        runDesktopComposeUiTest(width = 320, height = 180) {
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.GERMANY)
            try {
                val source = LiveTelemetryStore().apply { receive(LiveTelemetry(1, match, 155, 12345)) }
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        AdvantlyTheme(darkTheme = true) {
                            Box(Modifier.fillMaxSize().background(AdvantlyTheme.colors.liveBackground).padding(12.dp)) {
                                LiveWorkoutTelemetry(match, true, true, source)
                            }
                        }
                    }
                }
                onNodeWithTag("live_telemetry_heart_rate").assertTextEquals("Puls: 155 bpm").assertIsDisplayed()
                onNodeWithTag("live_telemetry_steps").assertTextEquals("Schritte: 12345").assertIsDisplayed()
                val output = File("build/reports/screenshots/live-telemetry-de-large.png")
                output.parentFile.mkdirs()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", output)
            } finally {
                Locale.setDefault(previous)
            }
        }
}
