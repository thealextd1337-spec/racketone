package eu.advantly.ui.phone.result

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.GameServersCorrected
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ResultCorrected
import eu.advantly.core.scoring.ResultReason
import eu.advantly.core.scoring.SidePair
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.play
import eu.advantly.ui.phone.setupFor
import eu.advantly.ui.phone.testModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** „Ergebnis bearbeiten“: Satz eintippen, Plausibilität, Aufschläger je Spiel, Kennzeichnung im Ergebnis. */
@OptIn(ExperimentalTestApi::class)
class ResultEditUiTest {
    @Test
    fun editSetsAndServersFromTheResultScreen() =
        runDesktopComposeUiTest(width = 720, height = 2400) {
            val model = testModel()
            val id = model.live.start(model.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
            model.live.play("AAAA".repeat(12))
            model.openResult(id)
            setContent { AdvantlyApp(model = model) }
            waitForIdle()

            onNodeWithTag("result_edit").performScrollTo().performClick()
            onNodeWithTag("result_editor").assertExists()
            // 6:0 → 6:5 ist unplausibel: Speichern gesperrt, Meldung sichtbar
            repeat(5) { onNodeWithTag("edit_score_0_B_plus").performScrollTo().performClick() }
            onNodeWithTag("edit_save").performScrollTo().assertIsNotEnabled()
            val status =
                onNodeWithTag("edit_status")
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .joinToString { it.text }
            assertTrue("1" in status, status)
            onNodeWithTag("edit_score_0_B_minus").performScrollTo().performClick()
            // Aufschläger des ersten Spiels: B statt A
            onNodeWithTag("edit_server_0_0_1").performScrollTo().performClick()
            onNodeWithTag("edit_save").performScrollTo().performClick()
            waitForIdle()

            onNodeWithTag("result_editor").assertDoesNotExist()
            onNodeWithTag("result_corrected").assertExists()
            val record = checkNotNull(model.history.record(id))
            assertEquals(
                SidePair(6, 4),
                record.summary.sets
                    .first()
                    .games,
            )
            assertEquals(Side.A, record.summary.result.winner)
            assertEquals(ResultReason.COMPLETED, record.summary.result.reason)
            assertTrue(record.summary.result.corrected)
            val payloads = record.events.map { it.payload }
            assertTrue(payloads[payloads.size - 2] is ResultCorrected && payloads.last() is GameServersCorrected, "$payloads")
        }
}
