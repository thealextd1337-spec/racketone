package eu.advantly.umbrella.ios

import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkDebugTest {
    @Test
    fun pingsAreAnsweredBetweenTwoFacades() {
        val scheduler = ManualScheduler()
        val network = InMemoryLinkNetwork(scheduler, latencyMs = 10)
        val noop =
            object : LinkDebugListener {
                override fun onUpdate(snapshot: LinkDebugSnapshot) = Unit
            }
        val phone = LinkDebug(ReliableDeviceLink(network.transport("watch"), scheduler), scheduler).apply { start(noop) }
        val watch = LinkDebug(ReliableDeviceLink(network.transport("phone"), scheduler), scheduler).apply { start(noop) }
        scheduler.runUntilIdle()
        phone.sendPings(count = 3, intervalMs = 20)
        scheduler.advanceBy(1_000)
        assertEquals(3, phone.snapshot().pongsReceived)
        assertEquals(20, phone.snapshot().rttP50Ms)
        assertEquals(3, watch.snapshot().pingsAnswered)
    }
}
