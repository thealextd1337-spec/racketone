package eu.advantly.ui.phone.home

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import eu.advantly.match.session.DeviceStatus
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.sync.protocol.AppBuild
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class WatchStatusUiTest {
    @Test fun germanHeaderAndDetailsAtLargeFont() = check(Locale.GERMANY, 2f)

    @Test fun englishHeaderAndDetailsAtLargeFont() = check(Locale.US, 1.3f)

    @Test fun germanStatusHints() = checkHints(Locale.GERMANY)

    @Test fun englishStatusHints() = checkHints(Locale.US)

    private fun checkHints(locale: Locale) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            val de = locale.language == "de"
            val build = AppBuild("0.1.0", "223dff1-dirty")
            val store = InMemoryMatchStore().apply { localAppBuild = build }
            val model = AdvantlyAppModel(store, store, store)
            runDesktopComposeUiTest(width = 720, height = 1280) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                        AdvantlyTheme(darkTheme = true) { Surface(color = AdvantlyTheme.colors.bg) { HomeScreen(model) } }
                    }
                }
                val variants =
                    listOf(
                        DeviceStatus("pixel", PeerKind.WATCH, compatibility = CompatibilityStatus.PEER_TOO_OLD) to
                            if (de) "Advantly auf der Uhr aktualisieren." else "Update Advantly on the watch.",
                        DeviceStatus("pixel", PeerKind.WATCH, compatibility = CompatibilityStatus.PEER_TOO_NEW) to
                            if (de) "Advantly auf dem Handy aktualisieren." else "Update Advantly on the phone.",
                        DeviceStatus("pixel", PeerKind.WATCH, appInstalled = false) to
                            if (de) "Advantly ist auf deiner Uhr nicht installiert." else "Advantly is not installed on your watch.",
                        DeviceStatus(
                            "pixel",
                            PeerKind.WATCH,
                            appBuild = build,
                            compatibility = CompatibilityStatus.COMPATIBLE,
                            reachable = true,
                        ) to
                            if (de) "Entwicklungsbuild" else "Development build",
                    )
                variants.forEachIndexed { index, (device, hint) ->
                    runOnIdle { store.setDeviceStatuses(listOf(device)) }
                    onNodeWithTag("watch_status_pixel").assertContentDescriptionContains(hint, substring = true)
                    screenshot("phone-status-hint-$index-${locale.language}")
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun check(
        locale: Locale,
        fontScale: Float,
    ) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            val build = AppBuild("0.1.0", "223dff1", "2026-10-16T14:54:00Z")
            val store =
                InMemoryMatchStore().apply {
                    localAppBuild = build
                    setDeviceStatuses(
                        listOf(
                            DeviceStatus(
                                "pixel",
                                PeerKind.WATCH,
                                "Pixel Watch",
                                build,
                                CompatibilityStatus.COMPATIBLE,
                                1_760_000_000_000,
                                true,
                            ),
                            DeviceStatus("galaxy", PeerKind.WATCH, "Galaxy Watch", null, CompatibilityStatus.COMPATIBLE, null, true),
                        ),
                    )
                }
            val model = AdvantlyAppModel(store, store, store)
            runDesktopComposeUiTest(width = 720, height = 1280) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                        AdvantlyTheme(darkTheme = true) { Surface(color = AdvantlyTheme.colors.bg) { HomeScreen(model) } }
                    }
                }
                onNodeWithTag("watch_status_pixel")
                    .assertIsDisplayed()
                    .assertContentDescriptionContains(
                        if (locale.language ==
                            "de"
                        ) {
                            "gleicher App-Stand"
                        } else {
                            "same app build"
                        },
                        substring = true,
                    )
                onNodeWithTag("watch_status_galaxy")
                    .assertIsDisplayed()
                    .assertContentDescriptionContains(
                        if (locale.language ==
                            "de"
                        ) {
                            "Update empfohlen"
                        } else {
                            "update recommended"
                        },
                        substring = true,
                    )
                screenshot("phone-status-${locale.language}-font${(fontScale * 100).toInt()}")
                onNodeWithTag("watch_status_pixel").performClick()
                onNodeWithTag("watch_status_sheet").assertIsDisplayed()
                onNodeWithText("Pixel Watch").assertIsDisplayed()
                val watchBuild =
                    onNodeWithText(
                        (
                            if (locale.language ==
                                "de"
                            ) {
                                "Uhr: "
                            } else {
                                "Watch: "
                            }
                        ) + "0.1.0 · 223dff1",
                        substring = true,
                    ).performScrollTo()
                        .assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                watchBuild.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse(layouts.single().hasVisualOverflow)
                screenshot("phone-status-sheet-${locale.language}-font${(fontScale * 100).toInt()}", "watch_status_sheet")
                onNodeWithText("Galaxy Watch").performScrollTo().assertIsDisplayed()
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun emptyHeaderIsGrayAndThirdWatchIsGrouped() {
        val store = InMemoryMatchStore()
        runDesktopComposeUiTest(width = 720, height = 1280) {
            setContent { AdvantlyTheme { HomeScreen(AdvantlyAppModel(store, store, store)) } }
            onNodeWithTag("watch_status_none").assertIsDisplayed()
            runOnIdle {
                store.setDeviceStatuses(listOf("pixel", "galaxy", "third").map { DeviceStatus(it, PeerKind.WATCH, name = it) })
            }
            onNodeWithTag("watch_status_none").assertDoesNotExist()
            onNodeWithTag("watch_status_pixel").assertIsDisplayed()
            onNodeWithTag("watch_status_galaxy").assertIsDisplayed()
            onNodeWithTag("watch_status_group").assertIsDisplayed()
            onNodeWithText("+1").assertIsDisplayed().performClick()
            onNodeWithText("third").performScrollTo().assertIsDisplayed()
            assertEquals(3, store.deviceStatuses.value.size)
        }
    }

    private fun DesktopComposeUiTest.screenshot(
        name: String,
        tag: String? = null,
    ) {
        System.getenv("ADVANTLY_SCREENSHOT_DIR")?.let { directory ->
            File(directory).mkdirs()
            val image = (tag?.let { onNodeWithTag(it) } ?: onRoot()).captureToImage().toAwtImage()
            ImageIO.write(image, "png", File(directory, "$name.png"))
        }
    }
}
