package eu.advantly.ui.phone.home

import androidx.compose.material3.Surface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.match.session.DeviceStatus
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.sync.protocol.AppBuild
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Plan 0.5.1 §3: Hinweis „Uhr-App aktualisieren“. */
@OptIn(ExperimentalTestApi::class)
class WatchUpdateHintUiTest {
    private val phone = AppBuild("0.5.1", "abc1234")

    private fun watch(
        version: String?,
        reachable: Boolean = true,
        kind: PeerKind = PeerKind.WATCH,
    ) = DeviceStatus("w", kind, appBuild = version?.let { AppBuild(it, "") }, reachable = reachable)

    @Test
    fun onlyConnectedWatchesWithAnOlderVersion() {
        assertEquals(1, watchUpdateTargets(listOf(watch("0.5.0")), phone).size, "älter")
        assertEquals(1, watchUpdateTargets(listOf(watch("0.4.2")), phone).size)
        assertEquals(0, watchUpdateTargets(listOf(watch("0.5.1")), phone).size, "gleich")
        assertEquals(0, watchUpdateTargets(listOf(watch("0.6.0")), phone).size, "neuer")
        assertEquals(0, watchUpdateTargets(listOf(watch(null)), phone).size, "unbekannt")
        assertEquals(0, watchUpdateTargets(listOf(watch("dev")), phone).size, "unlesbar")
        assertEquals(0, watchUpdateTargets(listOf(watch("0.5.0")), null).size, "eigene Version unbekannt")
        assertEquals(0, watchUpdateTargets(listOf(watch("0.5.0", reachable = false)), phone).size, "nicht verbunden")
        assertEquals(0, watchUpdateTargets(listOf(watch("0.5.0", kind = PeerKind.PHONE)), phone).size)
        assertEquals(1, watchUpdateTargets(listOf(watch("0.10.0")), AppBuild("0.11.0", "")).size, "numerisch, nicht lexikografisch")
    }

    private class FakeHost(
        var result: Boolean,
    ) : WatchInstallHost {
        override val targets = MutableStateFlow(emptyList<DeviceStatus>())
        val opened = mutableListOf<String>()

        override fun install(
            peer: String,
            onResult: (Boolean) -> Unit,
        ) {
            opened += peer
            onResult(result)
        }

        override fun dismiss(peer: String) = Unit
    }

    @Test
    fun opensTheStoreOnTheWatchAndLaterHidesItForThisVersion() =
        withLocale(Locale.GERMANY) {
            val store = InMemoryMatchStore().apply { localAppBuild = phone }
            store.setDeviceStatuses(listOf(watch("0.4.2")))
            val host = FakeHost(result = false)
            runDesktopComposeUiTest(width = 412, height = 892) {
                setContent { AdvantlyTheme { Surface { HomeScreen(AdvantlyAppModel(store, store, store), watchInstall = host) } } }
                onNodeWithText("Auf deiner Uhr läuft noch Version 0.4.2.").assertIsDisplayed()
                onNodeWithTag("watch_update_pulse_w").assertIsDisplayed()
                onNodeWithTag("watch_update_action_w").performClick()
                assertEquals(listOf("w"), host.opened)
                onNodeWithText("Play Store → Apps verwalten → Advantly", substring = true).assertIsDisplayed()
                host.result = true
                onNodeWithTag("watch_update_action_w").performClick()
                onNodeWithText("Auf der Uhr geöffnet – dort auf Aktualisieren tippen.").assertIsDisplayed()
                onNodeWithTag("watch_update_later_w").performClick()
                onNodeWithTag("watch_update_w").assertDoesNotExist()
                // Neuerer Uhr-Stand, aber noch älter als das Handy: Karte kommt wieder, ohne Puls-Hinweis
                runOnIdle { store.setDeviceStatuses(listOf(watch("0.5.0"))) }
                onNodeWithTag("watch_update_w").assertIsDisplayed()
                onNodeWithTag("watch_update_pulse_w").assertDoesNotExist()
            }
        }

    @Test
    fun withoutHostOnlyTheInstructionIsShown() =
        withLocale(Locale.US) {
            val store = InMemoryMatchStore().apply { localAppBuild = phone }
            store.setDeviceStatuses(listOf(watch("0.5.0")))
            runDesktopComposeUiTest(width = 412, height = 892) {
                setContent { AdvantlyTheme { Surface { HomeScreen(AdvantlyAppModel(store, store, store)) } } }
                onNodeWithText("Update Advantly on your watch in the App Store.").assertIsDisplayed()
                onNodeWithTag("watch_update_action_w").assertDoesNotExist()
            }
        }

    private fun withLocale(
        locale: Locale,
        block: () -> Unit,
    ) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
