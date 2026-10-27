package eu.advantly.ui.phone.home

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import eu.advantly.match.session.DeviceStatus
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WatchInstallHintUiTest {
    private class FakeHost(
        var result: Boolean = true,
    ) : WatchInstallHost {
        override val targets = MutableStateFlow(listOf(DeviceStatus("galaxy", PeerKind.WATCH, name = "Galaxy", appInstalled = false)))
        val installed = mutableListOf<String>()

        override fun install(
            peer: String,
            onResult: (Boolean) -> Unit,
        ) {
            installed += peer
            onResult(result)
        }

        override fun dismiss(peer: String) {
            targets.value = targets.value.filterNot { it.peer == peer }
        }
    }

    @Test fun germanAtLargeFont() = check(Locale.GERMANY)

    @Test fun englishAtLargeFont() = check(Locale.US)

    private fun check(locale: Locale) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            val de = locale.language == "de"
            val host = FakeHost()
            val store = InMemoryMatchStore()
            runDesktopComposeUiTest(width = 720, height = 1280) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
                        AdvantlyTheme(darkTheme = true) {
                            Surface { HomeScreen(AdvantlyAppModel(store, store, store), watchInstall = host) }
                        }
                    }
                }
                onNodeWithText(if (de) "Advantly auf deiner Uhr installieren" else "Install Advantly on your watch").assertIsDisplayed()
                onNodeWithTag("watch_install_action_galaxy").performClick()
                assertEquals(listOf("galaxy"), host.installed)
                onNodeWithText(
                    if (de) "Auf der Uhr geöffnet – bitte dort installieren." else "Opened on the watch – please install it there.",
                ).assertIsDisplayed()
                onNodeWithTag("watch_install_result_galaxy").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
                host.result = false
                onNodeWithTag("watch_install_action_galaxy").performClick()
                onNodeWithText(if (de) "Uhr nicht erreichbar" else "Couldn’t reach the watch", substring = true).assertIsDisplayed()
                onNodeWithText(if (de) "Nicht mehr anzeigen" else "Don’t show again").performClick()
                onNodeWithTag("watch_install_galaxy").assertDoesNotExist()
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun nameShownAsSoonAsSeveralWatchesAreKnown() {
        // Nur eine Uhr ohne App, aber zwei bekannte Uhren: „deine Uhr“ wäre mehrdeutig
        val host = FakeHost()
        val store =
            InMemoryMatchStore().apply {
                setDeviceStatuses(
                    listOf(
                        DeviceStatus("pixel", PeerKind.WATCH, name = "Pixel", appInstalled = true),
                        host.targets.value.single(),
                    ),
                )
            }
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            runDesktopComposeUiTest(width = 720, height = 1280) {
                setContent { AdvantlyTheme { HomeScreen(AdvantlyAppModel(store, store, store), watchInstall = host) } }
                onNodeWithText("Install Advantly on Galaxy").assertIsDisplayed()
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun withoutHostNoHintEvenIfWatchLacksApp() {
        // iOS/Desktop übergeben keinen Host: der Hinweis erscheint nie
        val store = InMemoryMatchStore().apply { setDeviceStatuses(listOf(DeviceStatus("galaxy", PeerKind.WATCH, appInstalled = false))) }
        runDesktopComposeUiTest(width = 720, height = 1280) {
            setContent { AdvantlyTheme { HomeScreen(AdvantlyAppModel(store, store, store)) } }
            onNodeWithTag("watch_status_galaxy").assertIsDisplayed()
            onNodeWithTag("watch_install_galaxy").assertDoesNotExist()
        }
    }
}
