package eu.advantly.ui.phone.result

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.MatchId
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.Presets
import eu.advantly.health.HealthQuality
import eu.advantly.presentation.stats.PulseTrack
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.app.AppTab
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.play
import eu.advantly.ui.phone.setupFor
import eu.advantly.ui.phone.testModel
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Match-Detail: Reihenfolge der Abschnitte und Zeitleiste (Marker → Stand vor dem Punkt, Pulskurve optional). */
@OptIn(ExperimentalTestApi::class)
class MatchTimelineUiTest {
    private fun german(block: () -> Unit) {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** Tennis, Jonas (B) breakt zu Beginn bei 30:40, danach beendet wie es steht. */
    private fun AdvantlyAppModel.breakMatch(): MatchId {
        val id = live.start(setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        live.play("AABBBB")
        live.endMatch(EndReason.STOPPED)
        return id
    }

    private fun ComposeUiTest.y(tag: String): Float = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

    @Test
    fun detailShowsSetsThenTimelineThenDetailsThenPeopleAndFormatLast() =
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
                        "momentum_chart",
                        "result_compare",
                        "h2h_person_${opponent.value}",
                        "result_format_title",
                        "result_play_again",
                    ).map { it to y(it) }
                assertTrue(order.zipWithNext().all { (a, b) -> a.second < b.second }, order.toString())
            }
        }

    @Test
    fun tappingMarkerShowsScoreBeforeThePointAndOutcome() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                model.openResult(model.breakMatch())
                setContent { AdvantlyApp(model = model, darkTheme = true) }
                waitForIdle()
                onNodeWithTag("timeline_card").assertDoesNotExist()
                onNodeWithTag("timeline_pulse").assertDoesNotExist()
                onNodeWithTag("timeline_marker_0").performScrollTo().performClick()
                onNodeWithTag("timeline_card_score").assertTextContains("30:40", substring = true)
                onNodeWithTag("timeline_card_outcome").assertTextContains("Verwertet von Jonas")
                // Ohne Pulsverlauf wie vor H8: kein Ø-Puls, kein Qualitätshinweis
                onNodeWithTag("timeline_card_pulse").assertDoesNotExist()
                onNodeWithTag("timeline_quality").assertDoesNotExist()
                onNodeWithTag("timeline_card").assert(hasAnyDescendant(hasText("Break · Breakball", substring = true)))
                // Barrierefreie Liste: dieselben Highlights als antippbare Zeilen
                onNodeWithTag("timeline_item_0").performScrollTo().assertTextContains("Breakball", substring = true)
            }
        }

    @Test
    fun pulseCurveIsDrawnOnlyWhenPassed() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                val record = model.history.record(model.breakMatch())!!
                val start = record.summary.startedAtMs
                setContent {
                    AdvantlyTheme {
                        ResultScreen(
                            record = record,
                            onBack = {},
                            onPlayAgain = {},
                            onDelete = {},
                            pulse = PulseTrack(start, 1_000, listOf(96, 120, null, 150, 171, 160, 140, 155)),
                        )
                    }
                }
                waitForIdle()
                onNodeWithTag("timeline_pulse").performScrollTo().assertIsDisplayed().assertTextContains("96–171", substring = true)
                onNodeWithTag("timeline_marker_0").performClick()
                // PNG zur Sichtprüfung, kein Pixelvergleich
                val file = File("build/reports/screenshots/timeline/pulse-de.png").apply { parentFile.mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
            }
        }

    @Test
    fun cardShowsAveragePulseAndCurveShowsQualityHint() =
        german {
            runDesktopComposeUiTest(width = 720, height = 1280) {
                val model = testModel()
                val record = model.history.record(model.breakMatch())!!
                val start = record.summary.startedAtMs
                setContent {
                    AdvantlyTheme {
                        ResultScreen(
                            record = record,
                            onBack = {},
                            onPlayAgain = {},
                            onDelete = {},
                            pulse = PulseTrack(start - 60_000, 5_000, List(60) { 150 }, HealthQuality.GAPS),
                        )
                    }
                }
                waitForIdle()
                onNodeWithTag("timeline_quality").performScrollTo().assertTextContains("Lücken", substring = true)
                onNodeWithTag("timeline_marker_0").performClick()
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
                onNodeWithTag("timeline_empty").performScrollTo().assertIsDisplayed()
                onNodeWithTag("timeline_enable_pulse").performScrollTo().assertIsDisplayed()
            }
        }

    @Test
    fun nearbyMarkersShareOneTouchTargetInsideTheTimelineAndTapsCycle() =
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
                onNodeWithTag("timeline_item_1").assertExists()
                onNodeWithTag("timeline_marker_1").assertDoesNotExist()
                val marker = onNodeWithTag("timeline_marker_0").performScrollTo()
                marker.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                val bounds = marker.getBoundsInRoot()
                val timeline = onNodeWithTag("timeline").getBoundsInRoot()
                assertTrue(bounds.left >= timeline.left && bounds.right <= timeline.right, "$bounds in $timeline")
                marker.performClick()
                onNodeWithTag("timeline_card_outcome").assertTextContains("Abgewehrt", substring = true)
                marker.performClick()
                onNodeWithTag("timeline_card_outcome").assertTextContains("Verwertet von Jonas")
                marker.performClick()
                onNodeWithTag("timeline_card_outcome").assertTextContains("Abgewehrt", substring = true)
            }
        }

    @Test
    fun correctedResultGetsAHintBelowTheTimeline() =
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
                setContent {
                    AdvantlyTheme {
                        ResultScreen(
                            record = record,
                            onBack = {},
                            onPlayAgain = {},
                            onDelete = {},
                            pulse = PulseTrack(record.summary.startedAtMs, 1_000, listOf(96, 120)),
                            onEnablePulse = {},
                        )
                    }
                }
                waitForIdle()
                onNodeWithTag("timeline_pulse").performScrollTo().assertIsDisplayed()
                onNodeWithTag("timeline_enable_pulse").assertDoesNotExist()
            }
        }
}
