package eu.advantly.wear.live

import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.wear.WatchHarness
import org.junit.Assert.assertEquals
import org.junit.Test

/** P2-J: Startliste der Uhr folgt der Einstellung des Handys, und eine Änderung auf der Uhr erreicht das Handy. */
class WatchTelemetryVisibilityTest {
    private val h = WatchHarness()

    private fun homeVisibility() = (h.controller.state.value.screen as WatchScreen.Home).telemetryVisibility

    @Test
    fun homeFollowsThePhoneAndWatchChangesReachThePhone() {
        assertEquals(TelemetryVisibility.PRIVATE, homeVisibility())
        h.phone.setTelemetryVisibility(TelemetryVisibility.OFF)
        h.settle()
        assertEquals(TelemetryVisibility.OFF, homeVisibility())
        h.controller.setTelemetryVisibility(TelemetryVisibility.SHARE)
        assertEquals(TelemetryVisibility.SHARE, homeVisibility())
        h.settle()
        assertEquals(TelemetryVisibility.SHARE, h.phone.state.value.telemetryVisibility)
    }
}
