package eu.advantly.ui.phone.home

import androidx.compose.material3.Surface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.match.session.DeviceStatus
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.match.LiveMatchPort
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Recovery-Paket: Neukopplung ist ein ausdrücklicher Schritt im Uhren-Detailblatt. */
@OptIn(ExperimentalTestApi::class)
class WatchRepairUiTest {
    @Test
    fun repairIsOfferedForKnownWatchesAndReachesThePort() {
        val store = InMemoryMatchStore().apply { setDeviceStatuses(listOf(DeviceStatus("pixel", PeerKind.WATCH))) }
        var repairs = 0
        val port =
            object : LiveMatchPort by store {
                override fun repairWatchPairing() {
                    repairs++
                }
            }
        runDesktopComposeUiTest(width = 720, height = 1280) {
            setContent { AdvantlyTheme(darkTheme = true) { Surface { WatchStatusIndicator(port) } } }
            onNodeWithTag("watch_status_pixel").performClick()
            onNodeWithTag("watch_repair").performScrollTo().performClick()
            assertEquals(1, repairs)
        }
    }
}
