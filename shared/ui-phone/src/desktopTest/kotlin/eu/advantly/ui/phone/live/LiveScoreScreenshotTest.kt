package eu.advantly.ui.phone.live

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import eu.advantly.presentation.live.DemoScenario
import eu.advantly.presentation.live.Team
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.latency.InputClockSource
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Erzeugt Screenshots des Live-Scores (kein Pixelvergleich) unter `build/reports/screenshots/` –
 * für den Spike-Bericht und als schnelle Sichtprüfung von Layout, Schriften und Outdoor-Kontrast.
 */
@OptIn(ExperimentalTestApi::class)
class LiveScoreScreenshotTest {
    private val outDir = File("build/reports/screenshots").apply { mkdirs() }

    private fun capture(
        name: String,
        locale: Locale,
        widthDp: Int = 412,
        heightDp: Int = 892,
        fontScale: Float = 1f,
        configure: LiveScoreController.() -> Unit,
    ) {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            // Standard 412 × 892 dp (Pixel-Klasse) bei Dichte 2
            runDesktopComposeUiTest(width = widthDp * 2, height = heightDp * 2) {
                val controller = LiveScoreController()
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                        AdvantlyTheme(darkTheme = true) {
                            LiveScoreScreen(modifier = Modifier.fillMaxSize(), controller = controller)
                        }
                    }
                }
                waitForIdle()
                runOnIdle { controller.configure() }
                waitForIdle()
                val image = onRoot().captureToImage().toAwtImage()
                ImageIO.write(image, "png", File(outDir, "$name.png"))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun liveScoreGermanBreakPoint() =
        capture("live-score-de", Locale.GERMANY) {
            onTap(Team.Us, null)
            onTap(Team.Them, null)
            demo(DemoScenario.BreakPoint)
        }

    @Test
    fun liveScoreEnglishSetPoint() =
        capture("live-score-en", Locale.US) {
            demo(DemoScenario.SetPoint)
        }

    @Test
    fun liveScoreOutdoorMatchPoint() =
        capture("live-score-de-outdoor", Locale.GERMANY) {
            demo(DemoScenario.MatchPoint)
            outdoorContrast = true
        }

    @Test
    fun liveScoreUndoSnackbar() =
        capture("live-score-de-undo", Locale.GERMANY) {
            repeat(3) { onTap(Team.Us, null) }
            onTap(Team.Them, null)
            undo()
        }

    @Test
    fun liveScoreFontScale200Percent() =
        capture("live-score-de-fontscale-2", Locale.GERMANY, fontScale = 2f) {
            demo(DemoScenario.Tiebreak)
        }

    @Test
    fun liveScoreFontScale200PercentWithFullOverlay() =
        capture("live-score-de-fontscale-2-overlay", Locale.GERMANY, fontScale = 2f) {
            InputClockSource.entries.forEachIndexed { i, source ->
                tracker.onInput(100 + i, 0, source)
                tracker.onDrawn(100 + i, 5_000_000)
                tracker.onNextFrame(16_000_000)
            }
            demo(DemoScenario.BreakPoint)
        }

    @Test
    fun liveScoreCompactLandscapeFontScale200Percent() =
        capture("live-score-de-landscape-fontscale-2", Locale.GERMANY, widthDp = 892, heightDp = 412, fontScale = 2f) {
            demo(DemoScenario.MatchPoint)
        }

    @Test
    fun barlowCondensedDigitsAreTabular() =
        runDesktopComposeUiTest {
            var widths = emptyList<Int>()
            setContent {
                AdvantlyTheme(darkTheme = true) {
                    val measurer =
                        androidx.compose.ui.text
                            .rememberTextMeasurer()
                    val style = AdvantlyTheme.typography.scoreXl
                    widths = listOf("00", "11", "15", "40").map { measure(measurer, it, style) }
                    // Gegenprobe: ohne Barlow (Systemschrift) unterscheiden sich die Breiten typischerweise
                    measure(measurer, "11", style.copy(fontFamily = FontFamily.Default))
                }
            }
            waitForIdle()
            assertTrue(widths.isNotEmpty())
            assertEquals(1, widths.toSet().size, "tabellarische Ziffern erwartet, gemessen: $widths")
        }

    private fun measure(
        measurer: TextMeasurer,
        text: String,
        style: androidx.compose.ui.text.TextStyle,
    ): Int = measurer.measure(text, style).size.width
}
