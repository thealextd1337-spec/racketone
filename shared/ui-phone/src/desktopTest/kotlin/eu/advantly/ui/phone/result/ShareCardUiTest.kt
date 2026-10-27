package eu.advantly.ui.phone.result

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.core.model.Environment
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Side
import eu.advantly.core.model.Surface
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.Presets
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.play
import eu.advantly.ui.phone.setupFor
import eu.advantly.ui.phone.testModel
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ShareCardUiTest {
    private fun SemanticsNode.texts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            children.flatMap { it.texts() }

    private fun ComposeUiTest.show(
        model: AdvantlyAppModel,
        share: (suspend (ImageBitmap, String) -> Unit)?,
    ) {
        setContent { AdvantlyApp(model = model, onShareMatchImage = share) }
        waitForIdle()
    }

    private fun withLocale(block: () -> Unit) {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun cardShowsMatchDataAndLinkButNoVenueAndSharesBitmapWithLink() =
        withLocale {
            runDesktopComposeUiTest(width = 720, height = 1600) {
                val model = testModel()
                val meta = MatchMeta(surface = Surface.CLAY, environment = Environment.INDOOR, venue = "Center Court Musterstadt")
                val id = model.live.start(model.setupFor(Presets.TENNIS_SINGLES_BO3.format(), meta = meta))
                model.live.play("AAAA")
                model.live.endMatch(EndReason.RETIRED, Side.B)
                model.openResult(id)
                var shared: Pair<ImageBitmap, String>? = null
                show(model) { image, text -> shared = image to text }

                onNodeWithTag("result_share").performClick()
                val card = onNodeWithTag("share_card", useUnmergedTree = true).fetchSemanticsNode().texts().joinToString(" | ")
                for (expected in listOf("TENNIS · EINZEL", "Sieg", "Alex", "Jonas", "Aufg.", "advantly.eu")) {
                    assertTrue(expected in card, "„$expected“ fehlt: $card")
                }
                for (forbidden in listOf("Center Court", "Musterstadt", "Sand", "Halle", "bpm", "kcal", id.value)) {
                    assertFalse(forbidden in card, "„$forbidden“ auf der Karte: $card")
                }

                onNodeWithTag("share_confirm").performClick()
                waitUntil { shared != null }
                val (image, text) = shared!!
                assertTrue(image.width > 0 && image.height > 0)
                // Zum Ansehen, kein Pixelvergleich
                val file = File("build/reports/screenshots/share/card-de.png").apply { parentFile.mkdirs() }
                ImageIO.write(image.toAwtImage(), "png", file)
                assertEquals("Mein Match, gezählt mit Advantly: https://advantly.eu", text)
                waitUntil { onAllNodesWithTagCount("share_dialog") == 0 }
            }
        }

    @Test
    fun shareOnlyForFinishedMatchesAndOnlyWithPlatformSupport() =
        withLocale {
            runDesktopComposeUiTest(width = 720, height = 1600) {
                val model = testModel()
                val id = model.live.start(model.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
                model.live.play("AAAA")
                model.openResult(id)
                show(model) { _, _ -> }
                assertEquals(0, onAllNodesWithTagCount("result_share"), "laufendes Match")
                runOnIdle { model.live.endMatch(EndReason.STOPPED) }
                waitUntil { onAllNodesWithTagCount("result_share") == 1 }
            }
            runDesktopComposeUiTest(width = 720, height = 1600) {
                val model = testModel()
                val id = model.live.start(model.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
                model.live.endMatch(EndReason.STOPPED)
                model.openResult(id)
                show(model, share = null)
                assertEquals(0, onAllNodesWithTagCount("result_share"), "ohne Plattform-Teilen")
            }
        }

    private fun ComposeUiTest.onAllNodesWithTagCount(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
}
