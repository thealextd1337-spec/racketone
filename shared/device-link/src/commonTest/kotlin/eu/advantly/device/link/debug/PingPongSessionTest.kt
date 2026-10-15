package eu.advantly.device.link.debug

import eu.advantly.device.link.LinkState
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.RetryPolicy
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PingPongSessionTest {
    private val scheduler = ManualScheduler()
    private val network = InMemoryLinkNetwork(scheduler, latencyMs = 25)
    private val policy = RetryPolicy(ackTimeoutMs = 200, maxAttempts = 3)
    private var phoneStats: PingPongStats? = null
    private val phone = session("phone") { phoneStats = it }
    private val watch = session("watch") {}

    private fun session(
        node: String,
        onUpdate: (PingPongStats) -> Unit,
    ) = PingPongSession(ReliableDeviceLink(network.transport(node), scheduler, policy), scheduler, onUpdate)

    init {
        phone.start()
        watch.start()
        scheduler.runUntilIdle()
    }

    @Test
    fun pingsAreAnsweredAndMeasured() {
        assertTrue(phone.startPings(count = 10, intervalMs = 100))
        scheduler.advanceBy(5_000)
        val stats = phone.snapshot()
        assertEquals(LinkState.CONNECTED, stats.state)
        assertEquals("watch", stats.target)
        assertEquals(10, stats.pingsSent)
        assertEquals(10, stats.pingsAcked)
        assertEquals(10, stats.pongsReceived)
        assertEquals(0, stats.lost)
        assertEquals(0, stats.inFlight)
        assertEquals(50, stats.rttP50Ms) // 2 × 25 ms Einweg-Latenz
        assertEquals(50, stats.rttP95Ms)
        assertEquals(50, stats.ackP95Ms)
        assertFalse(stats.running)
        assertEquals(10, watch.snapshot().pingsAnswered)
        assertEquals(stats, phoneStats, "Callback liefert denselben Stand")
    }

    @Test
    fun lossIsCompensatedByRetries() {
        network.lossRate = 0.2
        phone.startPings(count = 50, intervalMs = 50)
        scheduler.advanceBy(30_000)
        val stats = phone.snapshot()
        // Jeder Ping endet mit Ack oder endgültigem Fehlschlag; ein „failed“ Ping kann trotzdem angekommen sein
        assertEquals(50, stats.pingsAcked + stats.pingsFailed)
        assertTrue(stats.pongsReceived in stats.pingsAcked - stats.pongsFailed..50)
        assertTrue(stats.pingRetries > 0)
        assertTrue(stats.link.retries > 0)
    }

    @Test
    fun flightModeWithQueueLosesNothing() {
        network.queueWhenDisconnected = true
        phone.startPings(count = 20, intervalMs = 100)
        scheduler.advanceBy(500)
        network.setConnected("watch", false) // Flugmodus an der Uhr
        scheduler.advanceBy(5_000)
        assertTrue(phone.snapshot().pingsQueued > 0)
        network.setConnected("watch", true)
        scheduler.advanceBy(5_000)
        val stats = phone.snapshot()
        assertEquals(20, stats.pingsAcked)
        assertEquals(20, stats.pongsReceived)
        assertEquals(0, stats.lost)
        assertTrue(stats.rttMaxMs!! > 1_000, "nachgelieferte Pings haben große RTT")
    }

    @Test
    fun noPeerMeansNoStart() {
        val otherScheduler = ManualScheduler()
        val alone = InMemoryLinkNetwork(otherScheduler)
        val lonely = PingPongSession(ReliableDeviceLink(alone.transport("lonely"), otherScheduler, policy), otherScheduler) {}
        lonely.start()
        otherScheduler.runUntilIdle()
        assertFalse(lonely.startPings(count = 1, intervalMs = 10))
    }

    @Test
    fun resetStartsNewSeriesAndIgnoresOldPongs() {
        phone.startPings(count = 3, intervalMs = 10)
        scheduler.advanceBy(30) // Pongs noch unterwegs
        phone.reset()
        scheduler.advanceBy(2_000)
        val stats = phone.snapshot()
        assertEquals(0, stats.pingsSent)
        assertEquals(0, stats.pongsReceived)
        assertNull(stats.rttP50Ms)
    }

    @Test
    fun percentileUsesNearestRank() {
        assertNull(percentile(emptyList(), 95))
        assertEquals(7, percentile(listOf(7), 50))
        val values = (1L..100L).shuffled(Random(1))
        assertEquals(50, percentile(values, 50))
        assertEquals(95, percentile(values, 95))
        assertEquals(100, percentile(values, 100))
        assertEquals(1, percentile(values, 1))
        assertEquals(2, percentile(listOf(1, 2, 3), 50))
    }
}
