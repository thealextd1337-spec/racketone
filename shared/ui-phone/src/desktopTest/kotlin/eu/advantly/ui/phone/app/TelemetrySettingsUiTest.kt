package eu.advantly.ui.phone.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.presentation.match.InMemoryTelemetrySettings
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.testModel
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** P2-J: Einstellung „Puls & Schritte“ im Profil (de/en, Standard „Nur für mich“, Hinweis bei „Teilen“). */
@OptIn(ExperimentalTestApi::class)
class TelemetrySettingsUiTest {
    private fun <T> withLocale(
        locale: Locale,
        block: () -> T,
    ): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun profileTabShowsTheSettingAndChangesReachTheStore() =
        withLocale(Locale.GERMANY) {
            runDesktopComposeUiTest(width = 412, height = 892) {
                val model = testModel()
                model.navigator.tab = AppTab.Profile
                setContent { AdvantlyApp(model = model, darkTheme = false) }
                onNodeWithTag("telemetry_private").performScrollTo().assertIsSelected().assertTextContains("Nur für mich", substring = true)
                onNodeWithTag("telemetry_share").assertIsNotSelected()
                onNodeWithTag("telemetry_off").assertTextContains("die Uhr sendet nichts", substring = true).performClick()
                runOnIdle { assertEquals(TelemetryVisibility.OFF, model.telemetry.telemetryVisibility.value) }
                onNodeWithTag("telemetry_off").assertIsSelected()
                onNodeWithTag("telemetry_share_pending").assertDoesNotExist()
                onNodeWithTag("telemetry_share").performClick()
                onNodeWithTag("telemetry_share_pending").performScrollTo().assertIsDisplayed()
                runOnIdle { assertEquals(TelemetryVisibility.SHARE, model.telemetry.telemetryVisibility.value) }
            }
        }

    @Test
    fun englishLargeFontScreenshotWithShareHint() =
        withLocale(Locale.US) {
            runDesktopComposeUiTest(width = 360, height = 720) {
                val port = InMemoryTelemetrySettings(TelemetryVisibility.SHARE)
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                        AdvantlyTheme { TelemetryVisibilitySection(port) }
                    }
                }
                onNodeWithTag("telemetry_share").assertIsSelected().assertTextContains("Share", substring = true)
                onNodeWithTag("telemetry_private").assertTextContains("Only me (default)", substring = true)
                onNodeWithTag("telemetry_share_pending")
                    .assertTextContains("not uploaded to the cloud", substring = true)
                    .assertIsDisplayed()
                val output = File("build/reports/screenshots/telemetry-settings-en-large.png")
                output.parentFile.mkdirs()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", output)
            }
        }
}
