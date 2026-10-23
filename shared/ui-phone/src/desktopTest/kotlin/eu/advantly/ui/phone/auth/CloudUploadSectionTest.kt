package eu.advantly.ui.phone.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.PhoneUploadAction
import eu.advantly.presentation.auth.PhoneUploadError
import eu.advantly.presentation.auth.PhoneUploadPhase
import eu.advantly.presentation.auth.PhoneUploadPort
import eu.advantly.presentation.auth.PhoneUploadResult
import eu.advantly.presentation.auth.PhoneUploadState
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CloudUploadSectionTest {
    private class Upload : PhoneUploadPort {
        override val state = MutableStateFlow(PhoneUploadState(available = true, action = PhoneUploadAction.UPLOAD))
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

    @Test fun everyStateIsReadableAndScrollableInBothLanguagesAtTwoHundredPercentAndAccountActionsStayEnabled() {
        val original = Locale.getDefault()
        try {
            for (language in listOf("de", "en")) {
                Locale.setDefault(Locale.forLanguageTag(language))
                runDesktopComposeUiTest(width = 360, height = 640) {
                    val auth = FakePhoneAuth().also { it.session.value = CloudSession("alice", true) }
                    val upload = Upload()
                    val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined), upload = upload)
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                            AdvantlyTheme {
                                Box(Modifier.background(AdvantlyTheme.colors.bg)) { AccountScreen(model, null) {} }
                            }
                        }
                    }
                    val screenshots = File("build/reports/cloud-upload").also { it.mkdirs() }
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(screenshots, "account-top-$language-200.png"))
                    onNodeWithTag(
                        "cloud_upload_unknown",
                    ).performScrollTo().assertTextEquals(if (language == "de") "Noch nicht ermittelt" else "Not checked yet")
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(screenshots, "unknown-$language-200.png"))
                    onNodeWithTag("cloud_upload_events").assertDoesNotExist()
                    assertEquals(0, upload.uploads)
                    assertEquals(0, upload.refreshes)
                    onNodeWithTag("cloud_upload_refresh").performScrollTo().assertIsNotEnabled()
                    assertEquals(0, upload.refreshes)
                    onNodeWithTag("cloud_upload_now").performScrollTo().performClick()
                    assertEquals(1, upload.uploads)

                    val observed =
                        PhoneUploadState(
                            available = true,
                            pendingEvents = 12,
                            pendingMetadata = 3,
                            rejectedEvents = 2,
                            observedAtMs = 1000L,
                            lastResponseAtMs = 500L,
                            action = PhoneUploadAction.UPLOAD,
                            canRefreshStatus = true,
                        )
                    val states =
                        listOf(
                            observed.copy(busy = true, phase = PhoneUploadPhase.BINDING, canRefreshStatus = false),
                            observed.copy(busy = true, phase = PhoneUploadPhase.UPLOADING),
                        ) +
                            PhoneUploadResult.entries.map {
                                observed.copy(
                                    result = it,
                                    stale = true,
                                    error = if (it == PhoneUploadResult.FAILED) PhoneUploadError.CONNECTION else null,
                                    action = if (it == PhoneUploadResult.FAILED) PhoneUploadAction.RETRY else PhoneUploadAction.UPLOAD,
                                )
                            } +
                            PhoneUploadError.entries.map {
                                observed.copy(
                                    result = PhoneUploadResult.FAILED,
                                    error = it,
                                    action = if (it == PhoneUploadError.PERMISSION) PhoneUploadAction.NONE else PhoneUploadAction.RETRY,
                                )
                            }
                    for (state in states) {
                        runOnIdle { upload.state.value = state }
                        onNodeWithTag("cloud_upload_events").performScrollTo().assertTextContains("12", substring = true)
                        onNodeWithTag("cloud_upload_metadata").performScrollTo().assertTextContains("3", substring = true)
                        onNodeWithTag("cloud_upload_rejected").performScrollTo().assertTextContains("2", substring = true)
                        onNodeWithTag("cloud_upload_observed").performScrollTo().assertTextContains(timestamp(1000), substring = true)
                        onNodeWithTag(
                            "cloud_upload_last_response",
                        ).performScrollTo().assertTextContains(
                            if (language == "de") "Letzte bestätigte Uploadantwort" else "Last confirmed upload response",
                            substring = true,
                        )
                        onNodeWithTag("cloud_upload_result").performScrollTo().assertExists()
                        if (state.result == PhoneUploadResult.FAILED && state.error == PhoneUploadError.CONNECTION) {
                            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(screenshots, "failed-$language-200.png"))
                        }
                        if (state.stale) onNodeWithTag("cloud_upload_stale").performScrollTo().assertExists()
                        if (state.error != null) onNodeWithTag("cloud_upload_error").performScrollTo().assertExists()
                        if (state.busy || state.action == PhoneUploadAction.NONE) {
                            onNodeWithTag("cloud_upload_now").performScrollTo().assertIsNotEnabled()
                        } else {
                            onNodeWithTag("cloud_upload_now").performScrollTo().assertIsEnabled()
                        }
                        if (state.canRefreshStatus && !state.busy) {
                            onNodeWithTag("cloud_upload_refresh").performScrollTo().assertIsEnabled().performClick()
                        } else {
                            onNodeWithTag("cloud_upload_refresh").performScrollTo().assertIsNotEnabled()
                        }
                        onNodeWithTag("account_export").performScrollTo().assertIsEnabled()
                        onNodeWithTag("account_delete").performScrollTo().assertIsEnabled()
                        onNodeWithTag("auth_sign_out").performScrollTo().assertIsEnabled()
                    }
                    runOnIdle { upload.state.value = observed.copy(busy = true, phase = PhoneUploadPhase.UPLOADING) }
                    onNodeWithTag("auth_sign_out").performScrollTo().performClick()
                    assertTrue("logout" in auth.calls)
                    onNodeWithTag("cloud_upload_section").assertDoesNotExist()
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
