package eu.advantly.ui.phone.result

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.model.Uuid7
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MomentKind
import eu.advantly.core.scoring.Presets
import eu.advantly.health.HealthQuality
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.match.MatchRecord
import eu.advantly.presentation.match.WallClock
import eu.advantly.presentation.stats.MatchStatistics
import eu.advantly.presentation.stats.PulseTrack
import eu.advantly.presentation.stats.PulseTransfer
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.app.AppTab
import eu.advantly.ui.phone.app.LocalHealthConsent
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.play
import eu.advantly.ui.phone.setupFor
import eu.advantly.ui.phone.testModel
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Match-Detail: Reihenfolge der Abschnitte und Spielverlauf (Sätze untereinander, Balken, Highlights, Puls). */
@OptIn(ExperimentalTestApi::class)
class MatchTimelineUiTest {
    private fun inLocale(
        locale: Locale = Locale.GERMANY,
        block: () -> Unit,
    ) {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun german(block: () -> Unit) = inLocale(Locale.GERMANY, block)

    /** Tennis, Jonas (B) breakt zu Beginn bei 30:40, danach beendet wie es steht. */
    private fun AdvantlyAppModel.breakMatch(): MatchId {
        val id = live.start(setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        live.play("AABBBB")
        live.endMatch(EndReason.STOPPED)
        return id
    }

    private fun ComposeUiTest.y(tag: String): Float = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

    private fun ComposeUiTest.showResult(
        record: MatchRecord,
        pulse: PulseTrack? = null,
        transfer: PulseTransfer? = null,
        consent: Boolean = false,
        onEnablePulse: (() -> Unit)? = null,
    ) {
        setContent {
            CompositionLocalProvider(LocalHealthConsent provides consent) {
                AdvantlyTheme(darkTheme = true) {
                    ResultScreen(
                        record = record,
                        onBack = {},
                        onPlayAgain = {},
                        onDelete = {},
                        pulse = pulse,
                        pulseTransfer = transfer,
                        onEnablePulse = onEnablePulse,
                    )
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun detailShowsSetsThenFlowThenDetailsThenPeopleAndFormatLast() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                // Satz 1 zu null, dann Break in Satz 2
                val id = model.live.start(model.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
                model.live.play("A".repeat(24) + "AABBBB")
                model.live.endMatch(EndReason.STOPPED)
                val opponent =
                    model.history
                        .record(id)!!
                        .summary.setup.lineup.b
                        .single()
                model.openResult(id)
                setContent { AdvantlyApp(model = model, darkTheme = true) }
                waitForIdle()
                val order =
                    listOf(
                        "result_sets",
                        "result_set_0",
                        "timeline",
                        "timeline_set_0",
                        "timeline_set_1",
                        "show_game_history",
                        "result_compare",
                        "h2h_person_${opponent.value}",
                        "result_format_title",
                        "result_play_again",
                    ).map { it to y(it) }
                assertTrue(order.zipWithNext().all { (a, b) -> a.second < b.second }, order.toString())
                onNodeWithTag("timeline_set_0").assertTextContains("Satz 1 · 6:0", substring = true)
                onNodeWithTag("timeline_set_1").assertTextContains("Satz 2 · 0:1", substring = true)
                // Die Highlights eines Satzes stehen unter dessen Grafik
                val stats = MatchStatistics.compute(model.history.record(id)!!)
                val breakIndex = stats.moments.indexOfLast { MomentKind.BREAK in it.moment.kinds }
                assertTrue(y("timeline_marker_$breakIndex") > y("timeline_set_1"))
            }
        }

    @Test
    fun tappingMarkerShowsScoreBeforeThePointOutcomeAndServer() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                model.openResult(model.breakMatch())
                setContent { AdvantlyApp(model = model, darkTheme = true) }
                waitForIdle()
                onNodeWithTag("timeline_card").assertDoesNotExist()
                onNodeWithTag("timeline_pulse").assertDoesNotExist()
                onNodeWithTag("timeline_marker_0").performScrollTo().assertContentDescriptionContains("Breakball", substring = true)
                onNodeWithTag("timeline_marker_0").performClick()
                onNodeWithTag("timeline_card_score").assertTextContains("30:40", substring = true)
                onNodeWithTag("timeline_card_outcome").assertTextContains("Verwertet von Jonas")
                onNodeWithTag("timeline_card_serve").assertTextContains("Aufschlag: ", substring = true)
                // Ohne Pulsverlauf: kein Ø-Puls, kein Qualitätshinweis
                onNodeWithTag("timeline_card_pulse").assertDoesNotExist()
                onNodeWithTag("timeline_quality").assertDoesNotExist()
                onNodeWithTag("timeline_card").assert(hasAnyDescendant(hasText("Break · Breakball", substring = true)))
            }
        }

    @Test
    fun tappingAGameBarShowsTheGameAndTappingAgainClosesIt() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                val record = model.history.record(model.breakMatch())!!
                val start = record.summary.startedAtMs
                showResult(record, pulse = PulseTrack(start, 1_000, List(20) { 140 + it }))
                onNodeWithTag("timeline_game_0").performScrollTo().assertContentDescriptionContains("Break", substring = true)
                onNodeWithTag("timeline_game_0").performClick()
                onNodeWithTag("timeline_game_card").assert(hasAnyDescendant(hasText("Satz 1 · Spiel 1 · Jonas · Break")))
                onNodeWithTag("timeline_game_rallies").assertTextContains("Ballwechsel: 2:4")
                onNodeWithTag("timeline_game_duration").assertTextContains("Dauer: ", substring = true)
                onNodeWithTag("timeline_game_pulse").assertTextContains("Puls Ø ", substring = true)
                onNodeWithTag("timeline_game_serve").assertTextContains("Aufschlag: ", substring = true)
                // ein Highlight ersetzt die Game-Karte
                onNodeWithTag("timeline_marker_0").performClick()
                onNodeWithTag("timeline_game_card").assertDoesNotExist()
                onNodeWithTag("timeline_card").assertExists()
                onNodeWithTag("timeline_game_0").performClick()
                onNodeWithTag("timeline_card").assertDoesNotExist()
                onNodeWithTag("timeline_game_0").performClick()
                onNodeWithTag("timeline_game_card").assertDoesNotExist()
            }
        }

    @Test
    fun pulseCurveIsDrawnOnlyWhenPassed() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                val record = model.history.record(model.breakMatch())!!
                val start = record.summary.startedAtMs
                showResult(record, pulse = PulseTrack(start, 1_000, listOf(96, 120, null, 150, 171, 160, 140, 155)))
                onNodeWithTag("timeline_pulse").performScrollTo().assertIsDisplayed().assertTextContains("Max 171", substring = true)
                onNodeWithTag("timeline_set_0").assertExists()
            }
        }

    @Test
    fun cardShowsAveragePulseAndCurveShowsQualityHint() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                val record = model.history.record(model.breakMatch())!!
                val start = record.summary.startedAtMs
                showResult(record, pulse = PulseTrack(start - 60_000, 5_000, List(60) { 150 }, HealthQuality.GAPS))
                onNodeWithTag("timeline_quality").performScrollTo().assertTextContains("Lücken", substring = true)
                onNodeWithTag("timeline_marker_0").performScrollTo().performClick()
                onNodeWithTag("timeline_card_pulse").performScrollTo().assertTextContains("Ø-Puls (Trend): 150 bpm")
            }
        }

    @Test
    fun enablePulseLinkOpensTheProfileOnlyWithoutConsentAndPulse() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                model.openResult(model.breakMatch())
                setContent { AdvantlyApp(model = model, darkTheme = true) }
                waitForIdle()
                onNodeWithTag("timeline_enable_pulse").performScrollTo().assertTextContains("Pulsverlauf aktivieren").performClick()
                waitForIdle()
                assertNull(model.navigator.top)
                assertEquals(AppTab.Profile, model.navigator.tab)
                onNodeWithTag("health_record_toggle").performScrollTo().assertIsDisplayed()
                // mit Einwilligung kein Link mehr
                model.telemetry.setHealthConsent(true)
                model.openResult(
                    model.history.matches.value
                        .first()
                        .matchId,
                )
                waitForIdle()
                onNodeWithTag("timeline").assertExists()
                onNodeWithTag("timeline_enable_pulse").assertDoesNotExist()
                onNodeWithTag("timeline_pulse_transfer").assertDoesNotExist()
            }
        }

    @Test
    fun enablePulseLinkAlsoWithoutHighlights() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                val id = model.live.start(model.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
                model.live.play("AA")
                model.live.endMatch(EndReason.STOPPED)
                model.openResult(id)
                setContent { AdvantlyApp(model = model, darkTheme = true) }
                waitForIdle()
                onNodeWithTag("timeline_game_0").performScrollTo().assertIsDisplayed()
                onNodeWithTag("timeline_marker_0").assertDoesNotExist()
                onNodeWithTag("timeline_enable_pulse").performScrollTo().assertIsDisplayed()
            }
        }

    @Test
    fun withoutRalliesTheFlowSaysSo() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                val id = model.live.start(model.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
                model.live.endMatch(EndReason.STOPPED)
                showResult(model.history.record(id)!!, onEnablePulse = {})
                onNodeWithTag("timeline_empty").performScrollTo().assertTextContains("Keine Ballwechsel", substring = true)
                onNodeWithTag("timeline_enable_pulse").assertExists()
                onNodeWithTag("show_game_history").assertDoesNotExist()
            }
        }

    @Test
    fun nearbyMarkersGetTheirOwnTouchTargetsInsideTheFlow() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                // Breakball abgewehrt (Punkt 6), zwei Punkte später verwertet; danach lange ohne Highlight
                val id = model.live.start(model.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
                model.live.play("AABBB" + "A" + "BB" + ("BBBB" + "AAAA").repeat(4))
                model.live.endMatch(EndReason.STOPPED)
                model.openResult(id)
                setContent { AdvantlyApp(model = model, darkTheme = true) }
                waitForIdle()
                val first = onNodeWithTag("timeline_marker_0").performScrollTo()
                val second = onNodeWithTag("timeline_marker_1")
                first.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                val a = first.getBoundsInRoot()
                val b = second.getBoundsInRoot()
                val flow = onNodeWithTag("timeline").getBoundsInRoot()
                assertTrue(a.right <= b.left, "auseinandergerückt: $a / $b")
                assertTrue(a.left >= flow.left && b.right <= flow.right, "$a $b in $flow")
                first.performClick()
                onNodeWithTag("timeline_card_outcome").assertTextContains("Abgewehrt", substring = true)
                second.performClick()
                onNodeWithTag("timeline_card_outcome").assertTextContains("Verwertet von Jonas")
            }
        }

    @Test
    fun correctedResultGetsAHintBelowTheFlow() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                val record = model.history.record(model.breakMatch())!!
                var shown by mutableStateOf(record)
                setContent { AdvantlyTheme { ResultScreen(record = shown, onBack = {}, onPlayAgain = {}, onDelete = {}) } }
                waitForIdle()
                onNodeWithTag("timeline").assertExists()
                onNodeWithTag("timeline_corrected").assertDoesNotExist()
                shown = record.copy(summary = record.summary.copy(result = record.summary.result.copy(corrected = true)))
                waitForIdle()
                onNodeWithTag("timeline_corrected").performScrollTo().assertTextContains("nachträglich korrigiert", substring = true)
            }
        }

    @Test
    fun noEnablePulseLinkWhenAPulseCurveExists() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                val record = model.history.record(model.breakMatch())!!
                showResult(record, pulse = PulseTrack(record.summary.startedAtMs, 1_000, listOf(96, 120)), onEnablePulse = {})
                onNodeWithTag("timeline_pulse").performScrollTo().assertIsDisplayed()
                onNodeWithTag("timeline_enable_pulse").assertDoesNotExist()
            }
        }

    @Test
    fun pendingPulseFromTheWatchIsAnnouncedAndLaterFlaggedAsOverdue() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                val id = model.breakMatch()
                model.telemetry.setPulseVisibility(id, TelemetryVisibility.PRIVATE)
                model.telemetry.setHealthConsent(true)
                model.openResult(id)
                setContent { AdvantlyApp(model = model, darkTheme = true) }
                waitForIdle()
                onNodeWithTag(
                    "timeline_pulse_transfer",
                ).performScrollTo().assertTextContains("wird von der Uhr übertragen", substring = true)
            }
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                showResult(model.history.record(model.breakMatch())!!, transfer = PulseTransfer.OVERDUE, consent = true)
                onNodeWithTag(
                    "timeline_pulse_transfer",
                ).performScrollTo().assertTextContains("Uhr in der Nähe des Handys öffnen", substring = true)
            }
        }

    @Test
    fun rallySportShowsOneChartWithASetPerBar() =
        german {
            for (preset in listOf(
                Presets.BADMINTON_SINGLES_BO3_21,
                Presets.TABLE_TENNIS_SINGLES_BO5,
                Presets.PICKLEBALL_DOUBLES_11_SIDEOUT,
            )) {
                runDesktopComposeUiTest(width = 720, height = 1280) {
                    val model = testModel()
                    val id = model.live.start(model.setupFor(preset.format()))
                    model.live.play("ABBA")
                    model.live.endMatch(EndReason.STOPPED)
                    showResult(model.history.record(id)!!)
                    onNodeWithTag("timeline_set_0").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("timeline_set_1").assertDoesNotExist()
                    val description =
                        onNodeWithTag("timeline_game_0").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
                    assertTrue(
                        description.startsWith(
                            if (preset.sport ==
                                eu.advantly.core.model.Sport.PICKLEBALL
                            ) {
                                "Spiel 1"
                            } else {
                                "Satz 1"
                            },
                        ),
                        description,
                    )
                    assertTrue(!description.contains("Break"), description)
                    onNodeWithTag("timeline_game_0").performClick()
                    onNodeWithTag("timeline_game_serve").assertDoesNotExist()
                }
            }
        }

    @Test
    fun englishLabels() =
        inLocale(Locale.US) {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val (model, id) = tennisMatch()
                showResult(model.history.record(id)!!)
                onNodeWithTag("timeline_set_0").performScrollTo().assertTextContains("Set 1 · 6–4", substring = true)
                onNodeWithTag("timeline_marker_0").performScrollTo().performClick()
                onNodeWithTag("timeline_card_score").assertTextContains("Score before the point", substring = true)
                onNodeWithTag("timeline_game_0").performClick()
                onNodeWithTag("timeline_game_serve").assertTextContains("Serve: ", substring = true)
            }
        }

    /** Review N6: Ein Game, kürzer als die Mindest-Tippbreite, bleibt neben langen Games gut treffbar. */
    @Test
    fun shortGamesStayHittableNextToLongOnes() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val (model, clock) = realisticModel()
                val id = model.live.start(model.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
                for ((winner, stepMs) in listOf(Side.A to 120_000L, Side.B to 1_000L, Side.A to 120_000L)) {
                    repeat(4) {
                        clock.now += stepMs
                        model.live.recordPoint(winner)
                    }
                }
                model.live.endMatch(EndReason.STOPPED)
                showResult(model.history.record(id)!!)
                val short = onNodeWithTag("timeline_game_1").performScrollTo()
                val bounds = short.getBoundsInRoot()
                assertTrue(bounds.right - bounds.left < 48.dp, "schmaler Balken: $bounds")
                short.assertContentDescriptionContains("Spiel 2", substring = true)
                // knapp neben dem schmalen Balken (schon im langen Nachbarn): trifft trotzdem Spiel 2
                short.performTouchInput { click(center + Offset(16.dp.toPx(), 0f)) }
                onNodeWithTag("timeline_game_card").assert(hasAnyDescendant(hasText("Spiel 2", substring = true)))
                // tief im langen Nachbarn: dessen Game
                onNodeWithTag("timeline_game_2").performClick()
                onNodeWithTag("timeline_game_card").assert(hasAnyDescendant(hasText("Spiel 3", substring = true)))
                // per Semantik (Screenreader) bleibt jeder Balken einzeln auslösbar
                onNodeWithTag("timeline_game_1").performSemanticsAction(SemanticsActions.OnClick)
                onNodeWithTag("timeline_game_card").assert(hasAnyDescendant(hasText("Spiel 2", substring = true)))
            }
        }

    @Test
    fun gameAtGivesNarrowBarsTheMinimumWidthAndKeepsEveryGameReachable() {
        val bars = listOf(0f..100f, 100f..102f, 102f..202f)
        assertEquals(0, gameAt(50f, bars, 48f))
        assertEquals(1, gameAt(95f, bars, 48f), "innerhalb der Mindestbreite um den schmalen Balken")
        assertEquals(1, gameAt(118f, bars, 48f))
        assertEquals(2, gameAt(130f, bars, 48f))
        assertEquals(2, gameAt(250f, bars, 48f), "rechts außerhalb: nächstes Game")
        // viele kurze Games dicht beieinander: jedes an seiner Mitte erreichbar
        val dense = List(10) { (it * 3f)..(it * 3f + 3f) }
        assertEquals(dense.indices.toList(), dense.map { gameAt((it.start + it.endInclusive) / 2, dense, 48f) })
        assertNull(gameAt(10f, emptyList(), 48f))
    }

    // --- Screenshots zur Sichtprüfung (nur mit ADVANTLY_SCREENSHOT_DIR) ------------------------------------------------

    private class ManualClock(
        var now: Long = 1_760_000_000_000,
    ) : WallClock {
        override fun nowMs(): Long = now
    }

    private fun realisticModel(): Pair<AdvantlyAppModel, ManualClock> {
        val clock = ManualClock()
        var n = 0L
        val store = InMemoryMatchStore(clock, { Uuid7.fromParts(1_760_000_000_000, 0, ++n) }, selfName = "Alex")
        return AdvantlyAppModel(store, store, store, clock = clock) to clock
    }

    /** Tennis 6:4 6:3 wie im Entwurf: Breaks in Satz 1 Spiel 10 und Satz 2 Spiel 1, 2 und 8, Ballwechsel 20–45 s. */
    private fun tennisMatch(): Pair<AdvantlyAppModel, MatchId> {
        val (model, clock) = realisticModel()
        val id = model.live.start(model.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        val patterns = listOf("WWWW", "WLWWW", "WWLLWW", "LWWWW", "WLWLWW", "WWLWW", "WLWLWLWW")
        var k = 0
        var i = 0

        fun game(winner: Char) {
            patterns[k++ % patterns.size].forEach { c ->
                clock.now += (20 + (i++ * 13 + k * 7) % 26) * 1_000L
                model.live.recordPoint(if ((c == 'W') == (winner == 'A')) Side.A else Side.B)
            }
            clock.now += 40_000 // Seitenwechsel/Pause zwischen den Games
        }
        "ABABABABAA".forEach(::game)
        clock.now += 60_000
        model.live.pause()
        clock.now += 120_000
        model.live.resume()
        "BAABABAAA".forEach(::game)
        return model to id
    }

    /** Badminton 21:15 18:21 21:19, Ballwechsel 10–30 s. */
    private fun badmintonMatch(): Pair<AdvantlyAppModel, MatchId> {
        val (model, clock) = realisticModel()
        val id = model.live.start(model.setupFor(Presets.BADMINTON_SINGLES_BO3_21.format()))
        val random = java.util.Random(7)
        var i = 0
        for ((winner, a, b) in listOf(Triple('A', 21, 15), Triple('B', 18, 21), Triple('A', 21, 19))) {
            val (w, l) = if (winner == 'A') a to b else b to a
            val points = (List(w - 1) { winner } + List(l) { if (winner == 'A') 'B' else 'A' }).shuffled(random) + winner
            points.forEach { c ->
                clock.now += (10 + (i++ * 11) % 21) * 1_000L
                model.live.recordPoint(if (c == 'A') Side.A else Side.B)
            }
            clock.now += 90_000
        }
        return model to id
    }

    /** Pulsverlauf im 5-s-Raster über das ganze Match: Anstieg zu Beginn, Wellen, leichtes Rauschen. */
    private fun pulseFor(record: MatchRecord): PulseTrack {
        val start = record.summary.startedAtMs
        val count = ((record.summary.finishedAtMs - start) / 5_000 + 1).toInt()
        val bpm =
            List(count) { i ->
                val wave = 140 + 16 * sin(i / 9.0) + 10 * sin(i / 37.0) + (i * 37 % 7) - 3
                minOf(wave, 96.0 + i * 2).toInt()
            }
        return PulseTrack(start, 5_000, bpm)
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val dir = System.getenv("ADVANTLY_SCREENSHOT_DIR")?.takeIf { it.isNotBlank() } ?: return
        waitForIdle()
        val file = File(dir, "$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(onNodeWithTag("timeline").captureToImage().toAwtImage(), "png", file)
    }

    /** 412 dp breit (Density 2), dunkles Theme, hoch genug für den ganzen Spielverlauf. */
    private fun shots(block: ComposeUiTest.() -> Unit) = german { runDesktopComposeUiTest(width = 824, height = 3_600) { block() } }

    private fun ComposeUiTest.showLarge(
        record: MatchRecord,
        pulse: PulseTrack?,
    ) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                AdvantlyTheme(darkTheme = true) {
                    ResultScreen(record = record, onBack = {}, onPlayAgain = {}, onDelete = {}, pulse = pulse, onEnablePulse = {})
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun screenshotsOfTheFlow() {
        shots {
            val (model, id) = tennisMatch()
            val record = model.history.record(id)!!
            assertEquals("6:4", record.summary.sets[0].let { "${it.games?.a}:${it.games?.b}" })
            showLarge(record, pulseFor(record))
            onNodeWithTag("timeline_set_1").assertTextContains("Satz 2 · 6:3", substring = true)
            screenshot("tennis-puls")
            val stats = MatchStatistics.compute(record)
            val breakInSet2 = stats.moments.indexOfFirst { MomentKind.BREAK in it.moment.kinds && it.before.sets.size == 1 }
            onNodeWithTag("timeline_marker_$breakInSet2").performClick()
            onNodeWithTag("timeline_card").assertExists()
            screenshot("tennis-symbol-angetippt")
            onNodeWithTag("timeline_game_${stats.progression.indexOfFirst { it.set == 2 && it.game == 8 }}").performClick()
            onNodeWithTag("timeline_game_card").assertExists()
            screenshot("tennis-game-angetippt")
        }
        shots {
            val (model, id) = tennisMatch()
            showLarge(model.history.record(id)!!, null)
            screenshot("tennis-ohne-puls")
        }
        shots {
            val (model, id) = badmintonMatch()
            val record = model.history.record(id)!!
            showLarge(record, pulseFor(record))
            onNodeWithTag("timeline_set_0").assertTextContains("21:15", substring = true)
            screenshot("badminton")
        }
    }
}
