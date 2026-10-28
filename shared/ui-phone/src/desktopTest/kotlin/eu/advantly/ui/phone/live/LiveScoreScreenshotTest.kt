package eu.advantly.ui.phone.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Environment
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Surface
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.Presets
import eu.advantly.device.link.CompanionState
import eu.advantly.device.link.testing.FakeCompanionDetector
import eu.advantly.presentation.match.ScorerOption
import eu.advantly.presentation.match.StartOptions
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.SessionRig
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.app.AppTab
import eu.advantly.ui.phone.app.Overlay
import eu.advantly.ui.phone.common.LocalSafeInsets
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.play
import eu.advantly.ui.phone.preview.DemoData
import eu.advantly.ui.phone.setupFor
import eu.advantly.ui.phone.startWith
import eu.advantly.ui.phone.testModel
import java.awt.image.BufferedImage
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

    @Test
    fun customHeaderAt200PercentStaysOnOneLine() =
        capture(
            "live-custom-header-small-fontscale-2-de",
            Locale.GERMANY,
            liveModel(Presets.TENNIS_SINGLES_BO3.format().copy(presetId = null), "AAB"),
            widthDp = 360,
            heightDp = 640,
            fontScale = 2f,
            interact = {
                val layouts = mutableListOf<TextLayoutResult>()
                onNodeWithTag("set_table_header").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertTrue(
                    layout.layoutInput.text.text
                        .contains("BENUTZERDEFINIERT"),
                )
                assertEquals(1, layout.lineCount)
                assertEquals(2f, layout.layoutInput.density.fontScale)
                assertEquals(false, layout.layoutInput.softWrap)
                assertEquals(TextOverflow.Ellipsis, layout.layoutInput.overflow)
            },
        )

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
                // Dialoge (AlertDialog) liegen in eigenen Wurzeln: alle Ebenen übereinander zeichnen
                val roots = onAllNodes(isRoot())
                val layers = roots.fetchSemanticsNodes().indices.map { roots[it].captureToImage().toAwtImage() }
                val image = BufferedImage(layers.first().width, layers.first().height, BufferedImage.TYPE_INT_ARGB)
                image.createGraphics().apply {
                    layers.forEach { drawImage(it, 0, 0, null) }
                    dispose()
                }
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

    @Test
    fun doublesServerChoice() =
        capture(
            "w2-server-choice-de",
            Locale.GERMANY,
            liveModel(Presets.PADEL_GOLDEN_BO3.format(), ""),
        )

    @Test
    fun serveInfoWithLargeText() {
        for ((language, locale) in listOf("en" to Locale.US, "de" to Locale.GERMANY)) {
            capture(
                "w2-serve-info-$language-200",
                locale,
                liveModel(Presets.TENNIS_SINGLES_BO3.format(), ""),
                fontScale = 2f,
                interact = {
                    onNodeWithTag("panel_them").performClick()
                    waitForIdle()
                    val chip = onNodeWithTag("serve_DOUBLE_FAULT")
                    val full = chip.getUnclippedBoundsInRoot()
                    val visible = chip.getBoundsInRoot()
                    assertEquals(full.bottom - full.top, visible.bottom - visible.top)
                },
            )
        }
    }

    @Test
    fun tiebreakOnlyResult() =
        capture(
            "w2-tiebreak-result-top-de",
            Locale.GERMANY,
            testModel().apply {
                startWith(Presets.TENNIS_TIEBREAK_ONLY_7.format())
                live.play("ABABABABABAA")
                val id =
                    live.active.value!!
                        .lastPoint!!
                        .id
                live.setServeInfo(id, eu.advantly.core.scoring.ServeOutcome.FIRST_SERVE_IN)
                navigator.push(Overlay.Result(live.active.value!!.matchId))
            },
        )

    @Test
    fun tiebreakOnlyResultAndServeStats() =
        capture(
            "w2-tiebreak-result-de",
            Locale.GERMANY,
            testModel().apply {
                startWith(Presets.TENNIS_TIEBREAK_ONLY_7.format())
                live.play("ABABABABABAA")
                val id =
                    live.active.value!!
                        .lastPoint!!
                        .id
                live.setServeInfo(id, eu.advantly.core.scoring.ServeOutcome.FIRST_SERVE_IN)
                navigator.push(Overlay.Result(live.active.value!!.matchId))
            },
            interact = { onNodeWithTag("serve_statistics").performScrollTo() },
        )

    @Test
    fun wizardTiebreakOnlyAdvanced() =
        capture(
            "w2-wizard-tiebreak-de",
            Locale.GERMANY,
            testModel().apply {
                onPlay()
                wizard.selectSport(Sport.TENNIS)
                wizard.selectSurface(null)
                wizard.addGuest("Jonas")
                wizard.next()
                wizard.setTennisMatch(eu.advantly.presentation.wizard.TennisMatchChoice.TIEBREAK_10)
            },
        )

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
            },
            interact = { onNodeWithTag("advanced_content").performScrollTo() },
        )

    // --- Edge-to-Edge: simulierte Status-/Navigationsleiste (halbtransparent eingezeichnet) ---

    private val withSystemBars: @Composable (AdvantlyAppModel) -> Unit = { model ->
        CompositionLocalProvider(LocalSafeInsets provides WindowInsets(0.dp, 52.dp, 0.dp, 24.dp)) {
            Box(Modifier.fillMaxSize()) {
                AdvantlyApp(model = model, darkTheme = true)
                Box(Modifier.fillMaxWidth().height(52.dp).background(Color.Magenta.copy(alpha = 0.35f)))
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(Color.Magenta.copy(alpha = 0.35f)),
                )
            }
        }
    }

    @Test
    fun wizardPlayersDoublesWithSystemBars() =
        capture(
            "insets-wizard-3-players-de",
            Locale.GERMANY,
            testModel().apply {
                onPlay()
                wizard.selectSport(Sport.PADEL)
                wizard.addGuest("Jonas")
            },
            content = withSystemBars,
        )

    @Test
    fun liveWithSystemBars() =
        capture(
            "insets-live-de",
            Locale.GERMANY,
            liveModel(Presets.TENNIS_SINGLES_BO3.format(), "AAB"),
            content = withSystemBars,
        )

    /** Review W1-I Runde 2: 360 × 640 dp, 200 % Schrift, Systemleisten, Anzeige ohne Verbindung. */
    @Test
    fun liveDisplayStaleSmallScreenWithSystemBars() {
        val rig = SessionRig()
        rig.startWatchScoredTennis()
        rig.watchPlays("AAB")
        rig.model.navigator.push(Overlay.Live)
        val goOffline: androidx.compose.ui.test.ComposeUiTest.(AdvantlyAppModel) -> Unit = {
            runOnIdle {
                rig.offline()
                rig.settle(42_000)
            }
            mainClock.advanceTimeBy(1_100)
        }
        capture("insets-live-display-stale-small-fontscale-2-de", Locale.GERMANY, rig.model, 360, 640, 2f, goOffline, withSystemBars)
    }

    @Test
    fun homeWithSystemBars() = capture("insets-home-en", Locale.US, testModel().apply { DemoData.seed(this) }, content = withSystemBars)

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

    // --- Single-Scorer (W1-I): Wer zählt?, Anzeige, Staleness, Übernahme, abgelehnte Eingaben ---

    @Test
    fun wizardWhoScores() {
        val rig = SessionRig()
        rig.model.apply {
            onPlay()
            wizard.selectSport(Sport.TENNIS)
            wizard.selectSurface(Surface.HARD)
            wizard.addGuest("Jonas")
            wizard.next()
            wizard.next()
        }
        capture("wizard-5-scorer-de", Locale.GERMANY, rig.model)
    }

    @Test
    fun wizardWatchNotInstalled() {
        val rig = SessionRig(watchOnline = false, companion = FakeCompanionDetector(CompanionState.APP_NOT_INSTALLED))
        rig.model.apply {
            onPlay()
            wizard.selectSport(Sport.TENNIS)
            wizard.selectSurface(Surface.HARD)
            wizard.addGuest("Jonas")
            wizard.next()
            wizard.next()
        }
        capture("wizard-5-scorer-not-installed-de", Locale.GERMANY, rig.model)
    }

    @Test
    fun liveWaitingForTheWatch() {
        val rig = SessionRig(watchOnline = false, companion = FakeCompanionDetector(CompanionState.APP_INSTALLED))
        rig.store.start(rig.model.setupFor(Presets.TENNIS_SINGLES_BO3.format()), StartOptions(ScorerOption.MY_WATCH))
        rig.model.controller.sync(rig.store.active.value)
        rig.model.navigator.push(Overlay.Live)
        capture("live-waiting-for-watch-en", Locale.US, rig.model)
    }

    @Test
    fun liveDisplayWhileTheWatchScores() {
        val rig = SessionRig()
        rig.startWatchScoredTennis()
        rig.watchPlays("AAAA" + "BBBB" + "AAB")
        rig.model.navigator.push(Overlay.Live)
        capture("live-display-de", Locale.GERMANY, rig.model)
    }

    @Test
    fun liveDisplayStaleOffersTakeOver() {
        val rig = SessionRig()
        rig.startWatchScoredTennis()
        rig.watchPlays("AAB")
        rig.model.navigator.push(Overlay.Live)
        capture("live-display-stale-en", Locale.US, rig.model, interact = {
            runOnIdle {
                rig.offline()
                rig.settle(42_000)
            }
            mainClock.advanceTimeBy(1_100)
        })
    }

    /** Review W1-I #2: kleines Display (360 × 640 dp) mit 200 % Schrift – Hinweis, Stand und Flächen sichtbar. */
    @Test
    fun liveDisplayStaleSmallScreenFontScale200Percent() {
        val rig = SessionRig()
        rig.startWatchScoredTennis()
        rig.watchPlays("AAB")
        rig.model.navigator.push(Overlay.Live)
        val goOffline: androidx.compose.ui.test.ComposeUiTest.(AdvantlyAppModel) -> Unit = {
            runOnIdle {
                rig.offline()
                rig.settle(42_000)
            }
            mainClock.advanceTimeBy(1_100)
        }
        capture("live-display-stale-small-fontscale-2-de", Locale.GERMANY, rig.model, 360, 640, 2f, goOffline)
    }

    @Test
    fun rejectedInputsSheet() {
        val rig = SessionRig()
        rig.startWatchScoredTennis()
        rig.offline()
        rig.settle(31_000)
        rig.store.takeOverScoring()
        rig.watchPlays("AAB")
        rig.online()
        rig.settle()
        rig.model.controller.sync(rig.store.active.value)
        rig.model.navigator.push(Overlay.Live)
        capture("sheet-rejected-inputs-de", Locale.GERMANY, rig.model, interact = { onNodeWithTag("rejected_view").performClick() })
    }

    /** F21: Beenden auf dem Anzeige-Gerät (Uhr zählt) – Sheet mit Übernahme-Hinweis. */
    @Test
    fun endWhileTheWatchScores() {
        val rig = SessionRig()
        rig.startWatchScoredTennis()
        rig.watchPlays("AAAAB")
        rig.model.navigator.push(Overlay.Live)
        capture("sheet-display-end-de", Locale.GERMANY, rig.model, interact = { onNodeWithTag("display_end_button").performClick() })
    }

    /** F22: Übersicht mit zwei parallelen Matches (eines auf der Uhr) und Match-Leiste im Live-Screen. */
    @Test
    fun parallelMatches() {
        val rig = SessionRig()
        rig.startWatchScoredTennis()
        rig.watchPlays("AAAAB")
        rig.store.start(rig.model.setupFor(Presets.PADEL_GOLDEN_BO3.format()), StartOptions(ScorerOption.THIS_PHONE))
        rig.settle()
        capture("home-parallel-de", Locale.GERMANY, rig.model)
        rig.model.controller.sync(rig.store.active.value)
        rig.model.navigator.push(Overlay.Live)
        capture("live-parallel-en", Locale.US, rig.model)
    }

    @Test
    fun takeOverConfirmation() {
        val rig = SessionRig()
        rig.startWatchScoredTennis()
        rig.watchPlays("AB")
        rig.model.navigator.push(Overlay.Live)
        capture("dialog-takeover-de", Locale.GERMANY, rig.model, interact = { onNodeWithTag("takeover_button").performClick() })
    }

    @Test
    fun rejectedInputsAfterTakeOver() {
        val rig = SessionRig()
        rig.startWatchScoredTennis()
        rig.offline()
        rig.settle(31_000)
        rig.store.takeOverScoring()
        rig.store.play("B")
        rig.watchPlays("AAA")
        rig.online()
        rig.settle()
        rig.model.controller.sync(rig.store.active.value)
        rig.model.navigator.push(Overlay.Live)
        capture("live-rejected-inputs-de", Locale.GERMANY, rig.model)
    }

    @Test
    fun quickStartWhoScores() {
        val rig = SessionRig()
        rig.store.saveFavorite(rig.model.setupFor(Presets.PADEL_GOLDEN_BO3.format()))
        capture("dialog-quickstart-scorer-en", Locale.US, rig.model, interact = { onNodeWithTag("quick_fav_0").performClick() })
    }

    @Test
    fun matchEndRetired() {
        val model = liveModel(Presets.TENNIS_SINGLES_BO3.format(), "AAAABBBB".repeat(4) + "AAAA".repeat(2) + "AAAABBBB" + "AAAA" + "AA")
        model.live.endMatch(EndReason.RETIRED, Side.B)
        model.controller.sync(model.live.active.value)
        capture("sheet-match-end-retired-de", Locale.GERMANY, model)
    }

    @Test
    fun profileWithGuestsAndLegalLinks() =
        capture(
            "app-profile-de",
            Locale.GERMANY,
            testModel().apply {
                DemoData.seed(this)
                navigator.tab = AppTab.Profile
            },
        )

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
