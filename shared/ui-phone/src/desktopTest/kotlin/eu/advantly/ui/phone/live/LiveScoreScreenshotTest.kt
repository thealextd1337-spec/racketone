package eu.advantly.ui.phone.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Environment
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Surface
import eu.advantly.core.scoring.Presets
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.app.AppTab
import eu.advantly.ui.phone.app.Overlay
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.preview.DemoData
import eu.advantly.ui.phone.startWith
import eu.advantly.ui.phone.testModel
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Erzeugt Screenshots der Phone-App (kein Pixelvergleich) unter `build/reports/screenshots/` – Sichtprüfung von
 * Layout, Schriften, DE/EN, Outdoor-Kontrast und 200 % Schrift für alle fünf Sportarten.
 */
@OptIn(ExperimentalTestApi::class)
class LiveScoreScreenshotTest {
    private val outDir = File("build/reports/screenshots").apply { mkdirs() }

    private fun capture(
        name: String,
        locale: Locale,
        model: AdvantlyAppModel = testModel(),
        widthDp: Int = 412,
        heightDp: Int = 892,
        fontScale: Float = 1f,
        interact: ComposeUiTest.(AdvantlyAppModel) -> Unit = {},
        content: @Composable (AdvantlyAppModel) -> Unit = { AdvantlyApp(model = it, darkTheme = true) },
    ) {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            // Standard 412 × 892 dp (Pixel-Klasse) bei Dichte 2
            runDesktopComposeUiTest(width = widthDp * 2, height = heightDp * 2) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) { content(model) }
                }
                waitForIdle()
                interact(model)
                waitForIdle()
                val image = onRoot().captureToImage().toAwtImage()
                ImageIO.write(image, "png", File(outDir, "$name.png"))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun liveModel(
        format: eu.advantly.core.model.MatchFormat,
        points: String,
        firstServerSide: Side = Side.A,
        meta: MatchMeta = MatchMeta(),
    ) = testModel().apply {
        startWith(format, points, firstServerSide, meta)
        navigator.push(Overlay.Live)
    }

    // --- Tabs ---

    @Test
    fun homeWithQuickStart() = capture("app-home-de", Locale.GERMANY, testModel().apply { DemoData.seed(this) })

    @Test
    fun homeWithRunningMatch() =
        capture(
            "app-home-running-en",
            Locale.US,
            testModel().apply {
                DemoData.seed(this)
                startWith(Presets.TENNIS_SINGLES_BO3.format(), "AAAABBA")
            },
        )

    @Test
    fun history() =
        capture(
            "app-history-en",
            Locale.US,
            testModel().apply {
                DemoData.seed(this)
                navigator.tab = AppTab.History
            },
        )

    @Test
    fun result() =
        capture(
            "app-result-de",
            Locale.GERMANY,
            testModel().apply {
                DemoData.seed(this)
                navigator.push(
                    Overlay.Result(
                        history.matches.value
                            .last()
                            .matchId,
                    ),
                )
            },
        )

    @Test
    fun friendsPlaceholder() = capture("app-friends-de", Locale.GERMANY, testModel().apply { navigator.tab = AppTab.Friends })

    // --- Assistent ---

    @Test
    fun wizardSport() = capture("wizard-1-sport-de", Locale.GERMANY, testModel().apply { onPlay() })

    @Test
    fun wizardSurface() =
        capture(
            "wizard-2-surface-en",
            Locale.US,
            testModel().apply {
                onPlay()
                wizard.selectSport(Sport.TENNIS)
            },
        )

    @Test
    fun wizardPlayersDoubles() =
        capture(
            "wizard-3-players-de",
            Locale.GERMANY,
            testModel().apply {
                onPlay()
                wizard.selectSport(Sport.PADEL)
                wizard.addGuest("Jonas")
                wizard.addGuest("Mia")
                wizard.addGuest("Tom")
            },
        )

    @Test
    fun wizardFormatTennisAdvanced() =
        capture(
            "wizard-4-format-tennis-de",
            Locale.GERMANY,
            testModel().apply {
                onPlay()
                wizard.selectSport(Sport.TENNIS)
                wizard.selectSurface(Surface.CLAY)
                wizard.addGuest("Jonas")
                wizard.next()
                wizard.toggleAdvanced()
            },
        )

    @Test
    fun wizardFormatTableTennisRequired() =
        capture(
            "wizard-4-format-tabletennis-en",
            Locale.US,
            testModel().apply {
                onPlay()
                wizard.selectSport(Sport.TABLE_TENNIS)
                wizard.addGuest("Jonas")
                wizard.next()
                wizard.toggleAdvanced()
            },
        )

    @Test
    fun wizardFormatBadminton() =
        capture(
            "wizard-4-format-badminton-de",
            Locale.GERMANY,
            testModel().apply {
                onPlay()
                wizard.selectSport(Sport.BADMINTON)
                wizard.next()
                wizard.addGuest("Jonas")
                wizard.next()
                wizard.toggleAdvanced()
            },
            interact = { onNodeWithTag("advanced_content").performScrollTo() },
        )

    // --- Live, alle Sportarten ---

    @Test
    fun liveTennisBreakPointGerman() =
        capture(
            "live-tennis-breakpoint-de",
            Locale.GERMANY,
            liveModel(Presets.TENNIS_SINGLES_BO3.format(), "AAAA" + "BBBB" + "AAA", Side.B, MatchMeta(Surface.CLAY)),
        )

    @Test
    fun liveTennisSetPointEnglish() =
        capture("live-tennis-setpoint-en", Locale.US, liveModel(Presets.TENNIS_SINGLES_BO3.format(), "AAAA".repeat(5) + "AAA"))

    @Test
    fun livePadelGoldenPoint() = capture("live-padel-golden-en", Locale.US, liveModel(Presets.PADEL_GOLDEN_BO3.format(), "AAAA" + "AAABBB"))

    @Test
    fun livePickleballDoubles() =
        capture("live-pickleball-de", Locale.GERMANY, liveModel(Presets.PICKLEBALL_DOUBLES_11_SIDEOUT.format(), "AAAABAB"))

    @Test
    fun liveBadmintonDoubles() =
        capture(
            "live-badminton-doubles-de",
            Locale.GERMANY,
            liveModel(
                Presets.BADMINTON_DOUBLES_BO3_21.format(),
                "AB".repeat(9) + "AAB",
                meta = MatchMeta(environment = Environment.INDOOR),
            ),
        )

    @Test
    fun liveTableTennisDeuce() =
        capture("live-tabletennis-en", Locale.US, liveModel(Presets.TABLE_TENNIS_SINGLES_BO5.format(Discipline.SINGLES), "AB".repeat(10)))

    @Test
    fun liveOutdoorMatchPoint() =
        capture(
            "live-tennis-outdoor-matchpoint-de",
            Locale.GERMANY,
            liveModel(Presets.TENNIS_ONE_SET.format(), "AAAA".repeat(5) + "AAA").apply { controller.outdoorContrast = true },
        )

    @Test
    fun liveUndoSnackbar() =
        capture("live-undo-de", Locale.GERMANY, liveModel(Presets.TENNIS_SINGLES_BO3.format(), "AAAB"), interact = { m ->
            runOnIdle { m.controller.undo() }
        })

    @Test
    fun liveFontScale200Percent() =
        capture(
            "live-fontscale-2-de",
            Locale.GERMANY,
            liveModel(Presets.TENNIS_SINGLES_BO3.format(), "AAAA".repeat(6) + "A"),
            fontScale = 2f,
        )

    @Test
    fun liveLandscapeFontScale200Percent() =
        capture(
            "live-landscape-fontscale-2-de",
            Locale.GERMANY,
            liveModel(Presets.BADMINTON_SINGLES_BO3_21.format(), "A".repeat(20)),
            widthDp = 892,
            heightDp = 412,
            fontScale = 2f,
        )

    @Test
    fun liveDebugOverlay() =
        capture(
            "live-debug-overlay-de",
            Locale.GERMANY,
            liveModel(Presets.TENNIS_SINGLES_BO3.format(), "AB"),
            content = { AdvantlyApp(model = it, darkTheme = true, debugTools = true) },
            interact = { onNodeWithTag("panel_us").performClick() },
        )

    // --- Dialoge ---

    @Test
    fun setEndSheet() =
        capture(
            "sheet-set-end-de",
            Locale.GERMANY,
            liveModel(
                Presets.TENNIS_OPEN.format(),
                "AAAABBBB".repeat(2) + "AAAA".repeat(4),
            ),
        )

    @Test
    fun matchEndSheet() =
        capture(
            "sheet-match-end-en",
            Locale.US,
            liveModel(
                Presets.BADMINTON_1GAME_21.format(),
                "AB".repeat(9) + "A".repeat(12),
            ),
        )

    @Test
    fun endMatchConfirmation() =
        capture("sheet-end-match-de", Locale.GERMANY, liveModel(Presets.TENNIS_SINGLES_BO3.format(), "A"), interact = {
            onNodeWithTag("more_button").performClick()
            onNodeWithTag("menu_end_match").performClick()
        })

    @Test
    fun barlowCondensedDigitsAreTabular() =
        runDesktopComposeUiTestNoWindow {
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

    private fun runDesktopComposeUiTestNoWindow(block: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest { block() }

    private fun measure(
        measurer: TextMeasurer,
        text: String,
        style: androidx.compose.ui.text.TextStyle,
    ): Int = measurer.measure(text, style).size.width
}
