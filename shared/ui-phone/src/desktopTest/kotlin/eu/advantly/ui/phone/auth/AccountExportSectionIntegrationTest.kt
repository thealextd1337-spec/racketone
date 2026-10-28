package eu.advantly.ui.phone.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentDocument
import eu.advantly.cloud.sync.ConsentDocumentType
import eu.advantly.cloud.sync.ConsentSnapshot
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.PhoneExportAction
import eu.advantly.presentation.auth.PhoneExportError
import eu.advantly.presentation.auth.PhoneExportPhase
import eu.advantly.presentation.auth.PhoneExportPort
import eu.advantly.presentation.auth.PhoneExportState
import eu.advantly.presentation.auth.PhoneUploadAction
import eu.advantly.presentation.auth.PhoneUploadPort
import eu.advantly.presentation.auth.PhoneUploadState
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.app.AppTab
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.testModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AccountExportSectionIntegrationTest {
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

        override fun close(onDrained: () -> Unit) = onDrained()
    }

    private class Upload : PhoneUploadPort {
        override val state =
            MutableStateFlow(PhoneUploadState(available = true, action = PhoneUploadAction.UPLOAD, canRefreshStatus = true))
        var uploads = 0
        var refreshes = 0

        override fun uploadNow() {
            uploads++
        }

        override fun refreshStatus() {
            refreshes++
        }

        override fun close(onDrained: () -> Unit) = onDrained()
    }

    @Test fun realAccountExportStatesAndIndependentActionsRemainReadableInBothThemesAndLanguagesAtLargeFont() {
        val original = Locale.getDefault()
        try {
            for (language in listOf("de", "en")) {
                Locale.setDefault(Locale.forLanguageTag(language))
                for (dark in listOf(false, true)) {
                    fixture { scope ->
                        runDesktopComposeUiTest(width = 360, height = 640) {
                            val auth = signedInAuth()
                            val export = Export()
                            val upload = Upload()
                            val model = AuthViewModel(auth, scope, upload = upload, export = export)
                            var shares = 0
                            var background = Color.Unspecified
                            setContent {
                                CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalContentColor provides Color.Black) {
                                    AdvantlyTheme(darkTheme = dark) {
                                        background = MaterialTheme.colorScheme.background
                                        Box(Modifier.fillMaxSize().background(background)) {
                                            ScrollingRights(model) { shares++ }
                                        }
                                    }
                                }
                            }
                            val directory = File("build/reports/account-export").also { it.mkdirs() }
                            for (phase in listOf(
                                PhoneExportPhase.PENDING,
                                PhoneExportPhase.OUTCOME_UNKNOWN,
                                PhoneExportPhase.READY,
                                PhoneExportPhase.LOCAL_FILE,
                                PhoneExportPhase.FAILED,
                            )) {
                                runOnIdle {
                                    export.state.value =
                                        PhoneExportState(
                                            phase,
                                            allowedActions =
                                                setOf(
                                                    PhoneExportAction.DISCARD,
                                                    when (phase) {
                                                        PhoneExportPhase.PENDING -> PhoneExportAction.REFRESH_STATUS
                                                        PhoneExportPhase.READY -> PhoneExportAction.DOWNLOAD
                                                        PhoneExportPhase.LOCAL_FILE -> PhoneExportAction.SHARE
                                                        else -> PhoneExportAction.RETRY
                                                    },
                                                ),
                                            error = if (phase == PhoneExportPhase.FAILED) PhoneExportError.CONNECTION else null,
                                        )
                                }
                                val layouts = mutableListOf<TextLayoutResult>()
                                onNodeWithTag("cloud_export_status")
                                    .performScrollTo()
                                    .assertIsDisplayed()
                                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                                val layout = layouts.single()
                                assertFalse(layout.hasVisualOverflow, "$phase $language dark=$dark")
                                val foreground = layout.layoutInput.style.color
                                val ratio =
                                    (maxOf(foreground.luminance(), background.luminance()) + 0.05f) /
                                        (minOf(foreground.luminance(), background.luminance()) + 0.05f)
                                assertTrue(ratio >= 4.5f, "Account export contrast $ratio")
                                ImageIO.write(
                                    onRoot().captureToImage().toAwtImage(),
                                    "png",
                                    File(directory, "$phase-$language-$dark-200.png"),
                                )
                                onNodeWithTag("account_export").assertDoesNotExist()
                                onNodeWithTag("account_export_data").assertDoesNotExist()
                                onNodeWithTag("account_delete").performScrollTo().assertIsEnabled()
                            }
                            assertTrue(export.calls.isEmpty())
                            assertEquals(0, upload.uploads)
                            assertEquals(0, upload.refreshes)
                            assertEquals(0, shares)
                            assertTrue(auth.calls.all { it == "refresh" }, auth.calls.toString())
                        }
                    }
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun explicitAccountExportActionsCallThePortExactlyOnceWithoutUsingLegacyJson() =
        fixture { scope ->
            runDesktopComposeUiTest(width = 360, height = 640) {
                val auth = signedInAuth()
                val export = Export()
                val model = AuthViewModel(auth, scope, export = export)
                setContent { AdvantlyTheme { Box(Modifier.fillMaxSize()) { ScrollingRights(model) } } }
                waitForIdle()
                assertTrue(export.calls.isEmpty())
                for ((phase, action, tag) in listOf(
                    Triple(PhoneExportPhase.IDLE, PhoneExportAction.REQUEST, "cloud_export_primary"),
                    Triple(PhoneExportPhase.OUTCOME_UNKNOWN, PhoneExportAction.RETRY, "cloud_export_primary"),
                    Triple(PhoneExportPhase.PENDING, PhoneExportAction.REFRESH_STATUS, "cloud_export_primary"),
                    Triple(PhoneExportPhase.READY, PhoneExportAction.DOWNLOAD, "cloud_export_primary"),
                    Triple(PhoneExportPhase.CHECKING, PhoneExportAction.CANCEL, "cloud_export_cancel"),
                    Triple(PhoneExportPhase.LOCAL_FILE, PhoneExportAction.DISCARD, "cloud_export_discard"),
                    Triple(PhoneExportPhase.OUTCOME_UNKNOWN, PhoneExportAction.REFRESH_STATUS, "cloud_export_check"),
                )) {
                    runOnIdle { export.state.value = PhoneExportState(phase, setOf(action)) }
                    val before = export.calls.toList()
                    onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()
                    runOnIdle { assertEquals(before + action, export.calls) }
                }
                assertFalse("export" in auth.calls)
                onNodeWithTag("account_export_data").assertDoesNotExist()
            }
        }

    @Test fun appProfileAccountPathUsesCurrentShareCallbackAndNeverSharesUnavailableOrWithoutCallback() =
        fixture { scope ->
            runDesktopComposeUiTest(width = 360, height = 640) {
                val auth = signedInAuth()
                val export = Export()
                val model = AuthViewModel(auth, scope, export = export)
                val app = testModel()
                val callback = mutableStateOf<(() -> Unit)?>(null)
                var oldShares = 0
                var newShares = 0
                setContent { AdvantlyApp(model = app, authModel = model, onShareExport = callback.value) }
                onNodeWithTag("cloud_export_section").assertDoesNotExist()
                runOnIdle { app.navigator.tab = AppTab.Profile }
                onNodeWithTag("profile_account_privacy").performScrollTo().performClick()
                onNodeWithTag("cloud_export_status").performScrollTo().assertIsDisplayed()
                runOnIdle { export.state.value = PhoneExportState(PhoneExportPhase.LOCAL_FILE, setOf(PhoneExportAction.SHARE)) }
                onNodeWithTag("cloud_export_primary").performScrollTo().assertIsNotEnabled()
                runOnIdle { callback.value = { oldShares++ } }
                onNodeWithTag("cloud_export_primary").performScrollTo().assertIsEnabled()
                runOnIdle { callback.value = { newShares++ } }
                onNodeWithTag("cloud_export_primary").performScrollTo().performClick()
                assertEquals(0, oldShares)
                assertEquals(1, newShares)
                runOnIdle { export.state.value = PhoneExportState(PhoneExportPhase.UNAVAILABLE, setOf(PhoneExportAction.SHARE)) }
                onNodeWithTag("cloud_export_primary").assertDoesNotExist()
                runOnIdle { export.state.value = PhoneExportState(PhoneExportPhase.READY, setOf(PhoneExportAction.SHARE)) }
                onNodeWithTag("cloud_export_primary").performScrollTo().assertIsNotEnabled()
                runOnIdle {
                    callback.value = null
                    export.state.value =
                        PhoneExportState(PhoneExportPhase.LOCAL_FILE, setOf(PhoneExportAction.SHARE))
                }
                onNodeWithTag("cloud_export_primary").performScrollTo().assertIsNotEnabled()
                assertEquals(1, newShares)
                assertTrue(export.calls.isEmpty())
                assertFalse("export" in auth.calls)
            }
        }

    @Test fun busyExportInTheAccountFlowDoesNotBlockConsentSupportOrSignOut() =
        fixture { scope ->
            runDesktopComposeUiTest(width = 360, height = 640) {
                val auth = signedInAuth().apply { consentSnapshot = snapshot(ConsentStatus.REQUIRED) }
                val export =
                    Export().apply {
                        state.value =
                            PhoneExportState(
                                PhoneExportPhase.DOWNLOADING,
                                setOf(PhoneExportAction.CANCEL),
                            )
                    }
                val model = AuthViewModel(auth, scope, export = export)
                var supports = 0
                setContent { AdvantlyTheme { Box(Modifier.fillMaxSize()) { AccountScreen(model, null, onSupport = { supports++ }) } } }
                onNodeWithTag("cloud_export_cancel").performScrollTo().assertIsEnabled()
                onNodeWithTag("account_support").performScrollTo().performClick()
                assertEquals(1, supports)
                onNodeWithTag("consent_decline").performScrollTo().performClick()
                assertEquals(1, auth.calls.count { it == "consent:false" })
                onNodeWithTag("consent_confirm_TERMS").performScrollTo().performClick()
                onNodeWithTag("consent_confirm_PRIVACY").performScrollTo().performClick()
                onNodeWithTag("consent_accept").performScrollTo().assertIsEnabled()
                onNodeWithTag("auth_sign_out").performScrollTo().performClick()
                assertEquals(1, auth.calls.count { it == "logout" })
                assertTrue(export.calls.isEmpty())
            }
        }

    @Test fun busyExportDoesNotBlockDeletion() =
        fixture { scope ->
            runDesktopComposeUiTest(width = 360, height = 640) {
                val auth = signedInAuth()
                val export = Export().apply { state.value = PhoneExportState(PhoneExportPhase.DOWNLOADING) }
                val model = AuthViewModel(auth, scope, export = export)
                setContent { AdvantlyTheme { Box(Modifier.fillMaxSize()) { ScrollingRights(model) } } }
                onNodeWithTag("account_delete").performScrollTo().performClick()
                onNodeWithTag("account_delete_confirm").performClick()
                assertEquals(1, auth.calls.count { it == "delete" })
                assertTrue(export.calls.isEmpty())
            }
        }

    @Test fun defaultViewModelRetainsLegacyJsonExport() =
        fixture { scope ->
            runDesktopComposeUiTest(width = 360, height = 640) {
                val auth = signedInAuth()
                val model = AuthViewModel(auth, scope)
                setContent { AdvantlyTheme { Box(Modifier.fillMaxSize()) { ScrollingRights(model) } } }
                onNodeWithTag("cloud_export_section").assertDoesNotExist()
                onNodeWithTag("account_export").performScrollTo().performClick()
                onNodeWithTag("account_export_data").assertTextEquals("{\"consentHistory\":[]}")
                assertEquals(1, auth.calls.count { it == "export" })
                runOnIdle { model.dismissExport() }
                onNodeWithTag("account_export_data").assertDoesNotExist()
            }
        }

    @Test fun exportActionsRemainIndependentOfPendingAuthAndUploadAndZipPortNeverDisplaysLegacyJson() =
        fixture { scope ->
            runDesktopComposeUiTest(width = 360, height = 640) {
                val fake = signedInAuth()
                val release = CompletableDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()
                var pending = false
                val auth =
                    object : CloudAuthPort by fake {
                        override suspend fun refreshProfile() {
                            fake.refreshProfile()
                            if (pending) {
                                entered.complete(Unit)
                                release.await()
                            }
                        }
                    }
                val export = Export()
                val upload = Upload()
                val model = AuthViewModel(auth, scope, upload = upload, export = export)
                setContent { AdvantlyTheme { Box(Modifier.fillMaxSize()) { ScrollingRights(model) } } }
                waitForIdle()
                runOnIdle {
                    pending = true
                    model.refresh()
                    assertTrue(entered.isCompleted)
                    assertTrue(model.state.value.busy)
                    upload.state.value = upload.state.value.copy(busy = true)
                    export.state.value = PhoneExportState(PhoneExportPhase.IDLE, setOf(PhoneExportAction.REQUEST))
                }
                onNodeWithTag("account_delete").performScrollTo().assertIsNotEnabled()
                onNodeWithTag("cloud_export_primary").performScrollTo().assertIsEnabled().performClick()
                assertEquals(listOf(PhoneExportAction.REQUEST), export.calls)
                runOnIdle {
                    pending = false
                    release.complete(Unit)
                    assertFalse(model.state.value.busy)
                    model.exportData()
                    assertEquals("{\"consentHistory\":[]}", model.state.value.exportedData)
                }
                onNodeWithTag("account_export_data").assertDoesNotExist()
                onNodeWithTag("cloud_export_status").performScrollTo().assertIsDisplayed()
            }
        }

    private fun signedInAuth() = FakePhoneAuth().also { it.session.value = CloudSession("alice", true) }

    private fun snapshot(status: ConsentStatus) =
        ConsentSnapshot(
            "alice",
            status,
            if (status == ConsentStatus.UNAVAILABLE) {
                emptyList()
            } else {
                ConsentDocumentType.entries.map {
                    ConsentDocument(it, "v1", "en", "https://advantly.eu/legal", approved = true)
                }
            },
        )

    private fun fixture(test: (CoroutineScope) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            test(scope)
        } finally {
            scope.cancel()
        }
    }
}
