package eu.advantly.ui.phone.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.advantly.presentation.auth.PhoneExportAction
import eu.advantly.presentation.auth.PhoneExportError
import eu.advantly.presentation.auth.PhoneExportPhase
import eu.advantly.presentation.auth.PhoneExportPort
import eu.advantly.presentation.auth.PhoneExportState
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CloudExportSectionTest {
    private class Export : PhoneExportPort {
        override val state = MutableStateFlow(PhoneExportState())
        val calls = mutableListOf<PhoneExportAction>()

        override fun requestExport() {
            calls += PhoneExportAction.REQUEST
        }

        override fun retry() {
            calls += PhoneExportAction.RETRY
        }

        override fun refreshStatus() {
            calls += PhoneExportAction.REFRESH_STATUS
        }

        override fun download() {
            calls += PhoneExportAction.DOWNLOAD
        }

        override fun cancel() {
            calls += PhoneExportAction.CANCEL
        }

        override fun discard() {
            calls += PhoneExportAction.DISCARD
        }

        override fun close(onDrained: () -> Unit) {
            state.value = PhoneExportState(phase = PhoneExportPhase.CLOSED)
            onDrained()
        }
    }

    @Test fun everyStateAndErrorIsReadableScrollableAndIndependentOfAccountActions() {
        val original = Locale.getDefault()
        try {
            for (language in listOf("de", "en")) {
                Locale.setDefault(Locale.forLanguageTag(language))
                for (dark in listOf(false, true)) {
                    runDesktopComposeUiTest(width = 360, height = 640) {
                        val export = Export()
                        var background = Color.Unspecified
                        var shares = 0
                        setContent {
                            CompositionLocalProvider(
                                LocalDensity provides Density(1f, 2f),
                                LocalContentColor provides Color.Black,
                            ) {
                                AdvantlyTheme(darkTheme = dark) {
                                    background = MaterialTheme.colorScheme.background
                                    Box(Modifier.fillMaxSize().background(background)) {
                                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                                            CloudExportSection(export) { shares++ }
                                            Button({}, Modifier.testTag("test_logout")) { Text("Logout") }
                                            Button({}, Modifier.testTag("test_delete")) { Text("Delete") }
                                        }
                                    }
                                }
                            }
                        }
                        val directory = File("build/reports/cloud-export").also { it.mkdirs() }
                        val states =
                            PhoneExportPhase.entries.map { phase ->
                                val action =
                                    when (phase) {
                                        PhoneExportPhase.IDLE -> PhoneExportAction.REQUEST

                                        PhoneExportPhase.OUTCOME_UNKNOWN,
                                        PhoneExportPhase.SERVER_BUSY,
                                        PhoneExportPhase.FAILED,
                                        -> PhoneExportAction.RETRY

                                        PhoneExportPhase.PENDING -> PhoneExportAction.REFRESH_STATUS

                                        PhoneExportPhase.READY -> PhoneExportAction.DOWNLOAD

                                        PhoneExportPhase.LOCAL_FILE -> PhoneExportAction.SHARE

                                        else -> null
                                    }
                                PhoneExportState(
                                    phase = phase,
                                    allowedActions =
                                        if (phase in
                                            listOf(PhoneExportPhase.REQUESTING, PhoneExportPhase.CHECKING, PhoneExportPhase.DOWNLOADING)
                                        ) {
                                            setOf(PhoneExportAction.CANCEL)
                                        } else {
                                            setOfNotNull(action)
                                        },
                                )
                            } +
                                PhoneExportError.entries.map {
                                    PhoneExportState(PhoneExportPhase.FAILED, setOf(PhoneExportAction.DISCARD), error = it)
                                } +
                                listOf(
                                    PhoneExportState(PhoneExportPhase.SERVER_BUSY, retryAtMs = 2000L),
                                    PhoneExportState(PhoneExportPhase.PENDING, retryAtMs = 2000L),
                                    PhoneExportState(
                                        PhoneExportPhase.READY,
                                        setOf(PhoneExportAction.DOWNLOAD),
                                        exportedAtMs = 0L,
                                        expiresAtMs = 86400000L,
                                        byteLength = 1234L,
                                    ),
                                )
                        for ((index, state) in states.withIndex()) {
                            runOnIdle { export.state.value = state }
                            val status = onNodeWithTag("cloud_export_status").performScrollTo().assertIsDisplayed()
                            val layouts = mutableListOf<TextLayoutResult>()
                            status.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                            val layout = layouts.single()
                            assertFalse(
                                layout.hasVisualOverflow,
                                "${state.phase} $language size=${layout.size} lines=${layout.lineCount} " +
                                    "widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight}",
                            )
                            val foreground = layout.layoutInput.style.color
                            val ratio =
                                (maxOf(foreground.luminance(), background.luminance()) + 0.05f) /
                                    (minOf(foreground.luminance(), background.luminance()) + 0.05f)
                            assertTrue(ratio >= 4.5f, "Export contrast $ratio, dark=$dark")
                            if (state.phase in
                                listOf(
                                    PhoneExportPhase.OUTCOME_UNKNOWN,
                                    PhoneExportPhase.SERVER_BUSY,
                                    PhoneExportPhase.READY,
                                    PhoneExportPhase.LOCAL_FILE,
                                ) &&
                                index < PhoneExportPhase.entries.size
                            ) {
                                ImageIO.write(
                                    onRoot().captureToImage().toAwtImage(),
                                    "png",
                                    File(directory, "${state.phase}-$language-$dark-200.png"),
                                )
                            }
                            if (state.error != null) {
                                val errorLayouts = mutableListOf<TextLayoutResult>()
                                onNodeWithTag("cloud_export_error")
                                    .performScrollTo()
                                    .assertIsDisplayed()
                                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(errorLayouts) }
                                assertFalse(errorLayouts.single().hasVisualOverflow)
                                val errorColor =
                                    errorLayouts
                                        .single()
                                        .layoutInput.style.color
                                val errorRatio =
                                    (maxOf(errorColor.luminance(), background.luminance()) + 0.05f) /
                                        (minOf(errorColor.luminance(), background.luminance()) + 0.05f)
                                assertTrue(errorRatio >= 4.5f, "Export error contrast $errorRatio, dark=$dark")
                            }
                            if (state.retryAtMs != null) {
                                onNodeWithTag("cloud_export_wait").performScrollTo().assertTextContains("1970-01-01", substring = true)
                                onNodeWithTag("cloud_export_primary").performScrollTo().assertIsNotEnabled()
                            }
                            if (state.allowedActions.any {
                                    it !in listOf(PhoneExportAction.CANCEL, PhoneExportAction.DISCARD)
                                }
                            ) {
                                onNodeWithTag("cloud_export_primary").performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp)
                                if (state.phase in listOf(PhoneExportPhase.READY, PhoneExportPhase.LOCAL_FILE)) {
                                    ImageIO.write(
                                        onRoot().captureToImage().toAwtImage(),
                                        "png",
                                        File(directory, "${state.phase}-action-$language-$dark-200.png"),
                                    )
                                }
                            }
                            if (state.busy) {
                                onNodeWithTag("cloud_export_primary").performScrollTo().assertIsNotEnabled()
                                onNodeWithTag("cloud_export_cancel").performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp)
                            }
                            onNodeWithTag("test_logout").performScrollTo().assertIsEnabled()
                            onNodeWithTag("test_delete").performScrollTo().assertIsEnabled()
                        }
                        assertTrue(export.calls.isEmpty())
                        assertEquals(0, shares)
                    }
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun actionsRequirePermissionAndSharingUsesTheCurrentCallbackOnlyForLocalFiles() =
        runDesktopComposeUiTest(width = 360, height = 640) {
            val export = Export()
            var oldShares = 0
            var newShares = 0
            val callback = mutableStateOf<(() -> Unit)?>(null)
            setContent {
                AdvantlyTheme {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                            CloudExportSection(export, callback.value)
                        }
                    }
                }
            }
            for ((phase, action) in listOf(
                PhoneExportPhase.IDLE to PhoneExportAction.REQUEST,
                PhoneExportPhase.OUTCOME_UNKNOWN to PhoneExportAction.RETRY,
                PhoneExportPhase.PENDING to PhoneExportAction.REFRESH_STATUS,
                PhoneExportPhase.READY to PhoneExportAction.DOWNLOAD,
            )) {
                runOnIdle { export.state.value = PhoneExportState(phase) }
                onNodeWithTag("cloud_export_primary").performScrollTo().assertIsNotEnabled()
                runOnIdle { export.state.value = PhoneExportState(phase, setOf(action)) }
                val primary = onNodeWithTag("cloud_export_primary").performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp)
                assertEquals(Role.Button, primary.fetchSemanticsNode().config[SemanticsProperties.Role])
                primary.performClick()
                assertEquals(action, export.calls.last())
            }
            runOnIdle {
                export.state.value =
                    PhoneExportState(PhoneExportPhase.OUTCOME_UNKNOWN, setOf(PhoneExportAction.RETRY, PhoneExportAction.REFRESH_STATUS))
            }
            onNodeWithTag("cloud_export_check").performScrollTo().performClick()
            assertEquals(PhoneExportAction.REFRESH_STATUS, export.calls.last())
            runOnIdle {
                export.state.value =
                    PhoneExportState(PhoneExportPhase.DOWNLOADING, setOf(PhoneExportAction.DOWNLOAD, PhoneExportAction.CANCEL))
            }
            onNodeWithTag("cloud_export_primary").performScrollTo().assertIsNotEnabled()
            onNodeWithTag("cloud_export_cancel").performScrollTo().performClick()
            assertEquals(PhoneExportAction.CANCEL, export.calls.last())
            runOnIdle {
                export.state.value =
                    PhoneExportState(PhoneExportPhase.LOCAL_FILE, setOf(PhoneExportAction.SHARE, PhoneExportAction.DISCARD))
            }
            onNodeWithTag("cloud_export_primary").performScrollTo().assertIsNotEnabled()
            runOnIdle { callback.value = { oldShares++ } }
            onNodeWithTag("cloud_export_primary").performScrollTo().assertIsEnabled()
            runOnIdle { callback.value = { newShares++ } }
            onNodeWithTag("cloud_export_primary").performScrollTo().performClick()
            assertEquals(0, oldShares)
            assertEquals(1, newShares)
            runOnIdle { export.state.value = export.state.value.copy(allowedActions = setOf(PhoneExportAction.DISCARD)) }
            onNodeWithTag("cloud_export_primary").performScrollTo().assertIsNotEnabled()
            onNodeWithTag("cloud_export_discard").performScrollTo().performClick()
            assertEquals(PhoneExportAction.DISCARD, export.calls.last())
            runOnIdle { export.state.value = PhoneExportState(PhoneExportPhase.READY, setOf(PhoneExportAction.SHARE)) }
            onNodeWithTag("cloud_export_primary").performScrollTo().assertIsNotEnabled()
            assertEquals(1, newShares)
            runOnIdle { export.close {} }
            onNodeWithTag("cloud_export_primary").assertDoesNotExist()
            onNodeWithTag("cloud_export_discard").assertDoesNotExist()
            for (phase in listOf(PhoneExportPhase.CLOSED, PhoneExportPhase.UNAVAILABLE)) {
                runOnIdle { export.state.value = PhoneExportState(phase, PhoneExportAction.entries.toSet()) }
                onNodeWithTag("cloud_export_primary").assertDoesNotExist()
                onNodeWithTag("cloud_export_cancel").assertDoesNotExist()
                onNodeWithTag("cloud_export_discard").assertDoesNotExist()
                onNodeWithTag("cloud_export_check").assertDoesNotExist()
            }
        }

    @Test fun busyConflictIsNotPendingAndWaitNeverStartsAnAction() =
        runDesktopComposeUiTest {
            val export = Export()
            setContent { AdvantlyTheme { CloudExportSection(export) } }
            runOnIdle { export.state.value = PhoneExportState(PhoneExportPhase.SERVER_BUSY, retryAtMs = 0L) }
            onNodeWithTag("cloud_export_status").assertTextContains(
                if (Locale.getDefault().language == "de") "Diese Anfrage wurde nicht angenommen." else "This request was not accepted.",
                substring = true,
            )
            onNodeWithTag("cloud_export_primary").assertIsNotEnabled()
            mainClock.advanceTimeBy(60000L)
            assertTrue(export.calls.isEmpty())
            runOnIdle { export.state.value = PhoneExportState(PhoneExportPhase.OUTCOME_UNKNOWN, setOf(PhoneExportAction.RETRY)) }
            onNodeWithTag("cloud_export_status").assertTextContains(
                if (Locale.getDefault().language == "de") "Ausgang der Anfrage ist unbekannt" else "outcome is unknown",
                substring = true,
            )
            assertTrue(export.calls.isEmpty())
        }
}
