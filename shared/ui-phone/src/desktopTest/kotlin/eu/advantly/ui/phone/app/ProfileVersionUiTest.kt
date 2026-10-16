package eu.advantly.ui.phone.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import java.io.File
import java.util.Locale
import java.util.TimeZone
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class ProfileVersionUiTest {
    @Test fun germanProfile() = check(Locale.GERMANY, "Version 0.1.0 · 5e3db67-dirty · 16.10. 16:54")

    @Test fun englishProfile() = check(Locale.US, "Version 0.1.0 · 5e3db67-dirty · Oct 16, 16:54")

    private fun check(
        locale: Locale,
        expected: String,
    ) {
        val previous = Locale.getDefault()
        val previousZone = TimeZone.getDefault()
        try {
            Locale.setDefault(locale)
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            runDesktopComposeUiTest(width = 720, height = 1280) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                        AdvantlyTheme(darkTheme = true) {
                            ComingSoonScreen(AppTab.Profile, "0.1.0", buildCommit = "5e3db67-dirty", buildTimeUtc = "2026-10-16T14:54:00Z")
                        }
                    }
                }
                val node = onNodeWithTag("profile_version").performScrollTo().assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(
                    expected,
                    layouts
                        .single()
                        .layoutInput.text.text,
                )
                assertFalse(layouts.single().hasVisualOverflow)
                System.getenv("ADVANTLY_SCREENSHOT_DIR")?.let { directory ->
                    File(directory).mkdirs()
                    ImageIO.write(
                        onRoot().captureToImage().toAwtImage(),
                        "png",
                        File(directory, "profile-version-${locale.language}-font130.png"),
                    )
                }
            }
        } finally {
            Locale.setDefault(previous)
            TimeZone.setDefault(previousZone)
        }
    }
}
