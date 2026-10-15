package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.scoreEvents
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Die zählende Uhr synchronisiert sofort aufs Handy (Nutzeranforderung 2026-10-15): jedes Event direkt nach dem Tap,
 * offline gepuffert und beim Wiederverbinden sofort nachgeliefert; das Handy persistiert alles.
 */
class ImmediateSyncTest {
    private val format = Presets.TENNIS_SINGLES_BO3.format()

    private fun watchScoring(): Cluster {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH, latencyMs = 20)
        cluster["phone"].session.create(format, SINGLES, scorer = ScorerChoice.Device(DeviceId("watch")))
        cluster.settle()
        return cluster
    }

    @Test
    fun everyTapReachesThePhoneWithinOneRoundTrip() {
        val cluster = watchScoring()
        val phone = cluster["phone"]
        val watch = cluster["watch"]
        val id = watch.match.id
        assertEquals(0, watch.session.state.value.sync.pendingToPeers)
        repeat(5) { i ->
            watch.session.recordPoint(Side.A)
            assertEquals(1, watch.session.state.value.sync.pendingToPeers, "Tap $i: noch nicht bestätigt")
            cluster.settle(25) // eine Einweg-Latenz: kein Sammeln, kein Debounce
            assertEquals(watch.data.events.count(id.value), phone.data.events.count(id.value), "Tap $i persistiert")
            cluster.settle(25) // Ack zurück
            assertEquals(0, watch.session.state.value.sync.pendingToPeers, "Tap $i bestätigt")
        }
        assertEquals(watch.score, phone.score)
        assertNotNull(phone.session.state.value.sync.lastEventReceivedAtMs)
    }

    @Test
    fun offlineBufferIsDeliveredImmediatelyOnReconnect() {
        val cluster = watchScoring()
        val phone = cluster["phone"]
        val watch = cluster["watch"]
        val id = watch.match.id
        cluster.offline("watch")
        cluster.settle(30_000)
        repeat(6) {
            watch.session.recordPoint(if (it % 2 == 0) Side.A else Side.B)
            cluster.settle(2_000)
        }
        assertEquals(6, watch.session.state.value.sync.pendingToPeers, "lokal gepuffert")
        val lastSeen = phone.session.state.value.sync.lastEventReceivedAtMs
        val reconnectAt = cluster.scheduler.nowMs()
        cluster.online("watch")
        cluster.settle(200) // sofortige Nachlieferung, nicht erst im 30-s-Takt
        assertTrue(cluster.scheduler.nowMs() - reconnectAt < 30_000)
        assertEquals(watch.data.events.scoreEvents(id), phone.data.events.scoreEvents(id), "Handy hat alle Events persistiert")
        assertEquals(watch.score, phone.score)
        assertEquals(0, watch.session.state.value.sync.pendingToPeers)
        assertTrue(phone.session.state.value.sync.lastEventReceivedAtMs!! > (lastSeen ?: 0))
    }
}
