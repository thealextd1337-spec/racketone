package eu.advantly.umbrella.watch

import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Facade-Tests. Auf dem watchOS-Simulator (CI) läuft [AdvantlyWatch.store] mit dem native-driver auf einer
 * echten Datei – derselbe Pfad wie in der Watch-App (Spike S1).
 */
class WatchFacadeTest {
    // Datei-DB kann Läufe überdauern → eindeutige Match-IDs je Test
    private val matchId = "test-" + Random.nextLong().toULong().toString(16)

    @Test
    fun storeAppendIsIdempotentAndOrdered() {
        val store = AdvantlyWatch.store()
        assertTrue(store.append("$matchId-b", matchId, "watch", deviceSeq = 2, lamport = 2, payload = "{}"))
        assertTrue(store.append("$matchId-a", matchId, "phone", deviceSeq = 1, lamport = 1, payload = "{}"))
        assertFalse(store.append("$matchId-a", matchId, "phone", deviceSeq = 1, lamport = 1, payload = "{}"))
        assertFalse(store.append("$matchId-c", matchId, "phone", deviceSeq = 1, lamport = 3, payload = "{}"), "Sequenzkonflikt")
        assertEquals(2, store.count(matchId))
        assertEquals(listOf("$matchId-a", "$matchId-b"), store.eventIds(matchId))
    }

    @Test
    fun smokeCheckIsStableAcrossCalls() {
        assertEquals(1, AdvantlyWatch.store().smokeCheck())
        assertEquals(1, AdvantlyWatch.store().smokeCheck())
    }

    @Test
    fun linkDebugRetriesBelongToCurrentSeriesOnly() {
        val scheduler = ManualScheduler()
        val network = InMemoryLinkNetwork(scheduler, latencyMs = 30)
        val noop =
            object : LinkDebugListener {
                override fun onUpdate(snapshot: LinkDebugSnapshot) = Unit
            }
        val watch = LinkDebug(ReliableDeviceLink(network.transport("phone-peer"), scheduler), scheduler).apply { start(noop) }
        LinkDebug(ReliableDeviceLink(network.transport("watch-peer"), scheduler), scheduler).apply { start(noop) }
        scheduler.runUntilIdle()
        var acksDropped = 0
        // erstes Ack (kurzer Frame) der Gegenseite verwerfen → der Ping wird wiederholt
        network.dropFilter = { from, _, frame -> !(from == "watch-peer" && frame.size < 40 && acksDropped++ == 0) }
        watch.sendPings(count = 1, intervalMs = 10)
        scheduler.advanceBy(100)
        watch.reset()
        scheduler.advanceBy(5_000)
        assertEquals(0, watch.snapshot().pingsSent)
        assertEquals(0, watch.snapshot().retries)
    }

    @Test
    fun linkDebugReportsSimpleSnapshots() {
        val scheduler = ManualScheduler()
        val network = InMemoryLinkNetwork(scheduler, latencyMs = 30)
        val snapshots = mutableListOf<LinkDebugSnapshot>()
        val watch = LinkDebug(ReliableDeviceLink(network.transport("phone-peer"), scheduler), scheduler)
        val phone = LinkDebug(ReliableDeviceLink(network.transport("watch-peer"), scheduler), scheduler)
        phone.start(
            object : LinkDebugListener {
                override fun onUpdate(snapshot: LinkDebugSnapshot) = Unit
            },
        )
        watch.start(
            object : LinkDebugListener {
                override fun onUpdate(snapshot: LinkDebugSnapshot) {
                    snapshots += snapshot
                }
            },
        )
        scheduler.runUntilIdle()
        assertEquals(-1, watch.snapshot().rttP50Ms)
        assertTrue(watch.sendPings(count = 5, intervalMs = 50))
        scheduler.advanceBy(2_000)
        val last = snapshots.last()
        assertEquals("CONNECTED", last.state)
        assertTrue(last.connected)
        assertEquals(5, last.pongsReceived)
        assertEquals(0, last.lost)
        assertEquals(60, last.rttP95Ms)
        assertEquals(5, phone.snapshot().pingsAnswered)
        assertEquals(0, last.retries)
        watch.stop()
        assertEquals("DISCONNECTED", watch.snapshot().state)
    }
}
