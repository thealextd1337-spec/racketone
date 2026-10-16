package eu.advantly.ui.phone.support

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.SupportDraft
import eu.advantly.cloud.sync.SupportDraftStorage
import eu.advantly.cloud.sync.SupportIdentity
import eu.advantly.cloud.sync.SupportPort
import eu.advantly.cloud.sync.SupportReceipt
import eu.advantly.cloud.sync.SupportTicket
import eu.advantly.presentation.support.SupportViewModel
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SupportScreenTest {
    @Test fun smallScreenWithLargeTextCanSendAndRetryWithoutDiagnostics() = check(280, 1.6f, false)

    @Test fun darkScreenCanSendAndRetryWithoutDiagnostics() = check(412, 1f, true)

    private fun check(
        width: Int,
        fontScale: Float,
        dark: Boolean,
    ) = runDesktopComposeUiTest(width = width, height = 800) {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val submissions = mutableListOf<SupportDraft>()
        val port =
            object : SupportPort {
                override val owners = flowOf<String?>(null)

                override suspend fun identity() = SupportIdentity(null, null)

                override suspend fun tickets(identity: SupportIdentity) = emptyList<SupportTicket>()

                override suspend fun submit(
                    draft: SupportDraft,
                    identity: SupportIdentity,
                ): SupportReceipt {
                    submissions += draft
                    if (submissions.size == 1) throw CloudException("support_connection_failed", false)
                    return SupportReceipt(draft.id, "ADV-${draft.id}")
                }
            }
        val storage =
            object : SupportDraftStorage {
                var value: String? = null

                override fun loadDraft(ownerId: String?) = value

                override fun loadReceipt(ownerId: String?) = null

                override fun saveDraft(
                    ownerId: String?,
                    value: String?,
                ) {
                    this.value = value
                }

                override fun saveReceipt(
                    ownerId: String?,
                    value: String,
                ) = Unit
            }
        val model = SupportViewModel(port, storage, scope)
        try {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    AdvantlyTheme(darkTheme = dark) {
                        Box(Modifier.width(width.dp)) { SupportScreen(model, mapOf("os" to "test")) {} }
                    }
                }
            }
            onNodeWithTag("support_title").performScrollTo().performTextInput("Small display")
            onNodeWithTag("support_body").performScrollTo().performTextInput("Steps to reproduce")
            System.getenv("ADVANTLY_SCREENSHOT_DIR")?.let { directory ->
                File(directory).mkdirs()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "support-$width-$dark.png"))
            }
            onNodeWithTag("support_send").performScrollTo().performClick()
            onNodeWithTag("support_title").assertIsNotEnabled()
            onNodeWithTag("support_send").performScrollTo().performClick()
            onNodeWithTag("support_receipt").performScrollTo().assertExists()
            assertEquals(submissions[0], submissions[1])
            assertTrue(submissions[0].diagnostics.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
