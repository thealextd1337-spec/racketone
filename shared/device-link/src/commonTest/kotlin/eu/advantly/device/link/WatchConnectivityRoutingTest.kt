package eu.advantly.device.link

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchConnectivityRoutingTest {
    private fun state(
        activated: Boolean = true,
        reachable: Boolean = true,
        installed: Boolean = true,
    ) = WcSessionState(activated, reachable, installed)

    @Test
    fun reachableActivatedSessionSendsLive() {
        assertEquals(WcRoute.LIVE, wcRoute(state(), useQueue = true))
        assertEquals(listOf(WATCH_PEER), wcPeers(state(), WATCH_PEER))
    }

    @Test
    fun unreachableSessionQueuesOnlyWhenAllowed() {
        assertEquals(WcRoute.QUEUE, wcRoute(state(reachable = false), useQueue = true))
        assertEquals(WcRoute.FAIL, wcRoute(state(reachable = false), useQueue = false))
        assertEquals(emptyList(), wcPeers(state(reachable = false), WATCH_PEER))
    }

    @Test
    fun inactiveSessionNeverQueues() {
        // iOS-Uhrenwechsel: zwischen sessionDidBecomeInactive und Re-Aktivierung kein transferUserInfo
        assertEquals(WcRoute.FAIL, wcRoute(state(activated = false, reachable = false), useQueue = true))
        assertEquals(WcRoute.FAIL, wcRoute(state(activated = false, reachable = true), useQueue = true))
        assertEquals(emptyList(), wcPeers(state(activated = false), WATCH_PEER))
    }

    @Test
    fun missingCounterpartAppNeverQueues() {
        assertEquals(WcRoute.FAIL, wcRoute(state(installed = false, reachable = false), useQueue = true))
        assertEquals(emptyList(), wcPeers(state(installed = false), PHONE_PEER))
    }

    @Test
    fun liveErrorFallbackRechecksActivation() {
        assertEquals(WcRoute.QUEUE, wcFallbackAfterLiveError(state(reachable = false), useQueue = true))
        assertEquals(WcRoute.FAIL, wcFallbackAfterLiveError(state(activated = false), useQueue = true))
        assertEquals(WcRoute.FAIL, wcFallbackAfterLiveError(state(installed = false), useQueue = true))
        assertEquals(WcRoute.FAIL, wcFallbackAfterLiveError(state(), useQueue = false))
    }
}
