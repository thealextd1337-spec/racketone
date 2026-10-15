package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.data.scoreEvents
import eu.advantly.device.link.LinkState
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.Events
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.SyncCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mehrgeräte-Konvergenz (01 §4, §8): Live-Zählen, Verlust, Duplikate, Reihenfolge, Offline-Phasen, Hub. */
class MultiDeviceSyncTest {
    private val format = Presets.TENNIS_SINGLES_BO3.format()

    @Test
    fun liveCountingOnPhoneAndWatchConverges() {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        val phone = cluster["phone"]
        val watch = cluster["watch"]
        assertEquals(SyncIndicator.CONNECTED, phone.session.state.value.sync.indicator)
        assertEquals(
            PeerKind.WATCH,
            phone.session.state.value.sync.peers
                .single()
                .kind,
        )

        val id = phone.session.create(format, SINGLES)
        cluster.settle()
        assertEquals(id, watch.match.id, "Uhr folgt dem neuen Match automatisch")
        repeat(10) { i ->
            val device = if (i % 2 == 0) phone else watch
            device.session.recordPoint(if (i % 3 == 0) Side.B else Side.A)
            cluster.settle(500)
        }
        cluster.assertConverged()
        assertEquals(11, watch.match.eventCount)
        assertEquals(
            MatchStatus.IN_PROGRESS,
            watch.data.matches
                .get(id)
                ?.status,
        )
    }

    @Test
    fun lossDuplicatesAndRetriesConverge() {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        cluster["phone"].session.create(format, SINGLES)
        cluster.settle()
        cluster.network.lossRate = 0.3
        cluster.network.duplicateRate = 0.3
        val random = Random(11)
        repeat(60) {
            val device = cluster[if (random.nextBoolean()) "phone" else "watch"]
            device.session.recordPoint(if (random.nextBoolean()) Side.A else Side.B)
            if (random.nextInt(5) == 0) device.session.undo()
            cluster.settle(random.nextLong(10, 400))
        }
        cluster.network.lossRate = 0.0
        cluster.network.duplicateRate = 0.0
        cluster.settle(40_000)
        cluster.assertConverged()
    }

    @Test
    fun outOfOrderAndDuplicateDeliveryConverges() {
        val cluster = Cluster("watch" to PeerKind.WATCH)
        val lab = ReliableDeviceLink(cluster.network.transport("lab"), cluster.scheduler).apply { start() }
        // Handy zählt ohne Link; seine Events kommen einzeln, gemischt und doppelt bei der Uhr an
        val phoneData = testLocalData()
        val phone = MatchSession(phoneData, null, cluster.scheduler, SessionConfig(DeviceId("phone"), PeerKind.PHONE), cluster.clock)
        phone.start()
        val id = phone.create(format, SINGLES)
        repeat(12) { phone.recordPoint(if (it % 3 == 0) Side.B else Side.A) }
        phone.undo()
        phone.correct(
            eu.advantly.core.scoring.Correction
                .initialServer(SINGLES.b.single()),
        )
        cluster.settle()
        val events = phoneData.events.scoreEvents(id)
        (events + events.take(5)).shuffled(Random(3)).forEach { lab.send("watch", SyncCodec.encode(Events(id, listOf(it)))) }
        cluster.settle()
        assertEquals(
            phone.state.value.match
                ?.state,
            cluster["watch"].score,
        )
        assertEquals(
            events.toSet(),
            cluster["watch"]
                .data.events
                .scoreEvents(id)
                .toSet(),
        )
    }

    @Test
    fun offlineWatchCatchesUpInOneBatch() {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        cluster["phone"].session.create(format, SINGLES)
        cluster.settle()
        cluster.offline("watch")
        cluster.settle(30_000)
        assertEquals(
            SyncIndicator.LOCAL_ONLY,
            cluster["watch"]
                .session.state.value.sync.indicator,
        )
        repeat(30) {
            cluster["watch"].session.recordPoint(Side.A)
            cluster.settle(1_000)
        }
        assertNotEquals(cluster["phone"].score, cluster["watch"].score)
        cluster.online("watch")
        cluster.settle(5_000)
        cluster.assertConverged()
        assertEquals(1, cluster["phone"].score.sets.size, "30 Punkte = 7 Spiele → erster Satz 6:0 gewonnen")
    }

    @Test
    fun starTopologyForwardsThroughThePhone() {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch1" to PeerKind.WATCH, "watch2" to PeerKind.WATCH)
        // Uhren sprechen nie direkt miteinander (WatchConnectivity/Data Layer: nur Uhr ↔ Handy)
        cluster.network.dropFilter = { from, to, _ -> setOf(from, to) != setOf("watch1", "watch2") }
        cluster.offline("watch2")
        cluster["phone"].session.create(format, SINGLES)
        cluster.settle()
        repeat(6) {
            cluster["watch1"].session.recordPoint(Side.A)
            cluster.settle(500)
        }
        cluster.online("watch2")
        cluster.settle(5_000)
        cluster.assertConverged()
        // live: Uhr 2 zählt, Uhr 1 erfährt es nur über das Handy
        repeat(4) {
            cluster["watch2"].session.recordPoint(Side.B)
            cluster.settle(500)
        }
        cluster.assertConverged()
        assertEquals(11, cluster["watch1"].match.eventCount)
    }

    @Test
    fun periodicAntiEntropyRepairsSilentLoss() {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        cluster["phone"].session.create(format, SINGLES)
        cluster.settle()
        cluster.network.lossRate = 1.0
        repeat(3) { cluster["phone"].session.recordPoint(Side.A) }
        cluster.settle(45_000) // alle Wiederholungen erschöpft, Verbindung gilt weiter als bestehend
        assertEquals(
            LinkState.CONNECTED,
            cluster["watch"]
                .session.state.value.sync.link,
        )
        assertNotEquals(cluster["phone"].score, cluster["watch"].score)
        cluster.network.lossRate = 0.0
        cluster.settle(31_000) // nur der 30-s-Takt kann das reparieren
        cluster.assertConverged()
    }

    @Test
    fun incompatiblePeerIsIgnored() {
        val cluster = Cluster("watch" to PeerKind.WATCH)
        val lab = ReliableDeviceLink(cluster.network.transport("lab"), cluster.scheduler).apply { start() }
        cluster.settle()
        lab.send("watch", SyncCodec.encode(Hello(DeviceId("future"), PeerKind.PHONE, minV = 2, v = 2)))
        cluster.settle()
        val peer =
            cluster["watch"]
                .session.state.value.sync.peers
                .single { it.peer == "lab" }
        assertEquals(CompatibilityStatus.PEER_TOO_NEW, peer.compatibility)
        assertEquals(
            SyncIndicator.LOCAL_ONLY,
            cluster["watch"]
                .session.state.value.sync.indicator,
        )

        val phoneData = testLocalData()
        val phone = MatchSession(phoneData, null, cluster.scheduler, SessionConfig(DeviceId("phone"), PeerKind.PHONE), cluster.clock)
        val id = phone.create(format, SINGLES)
        lab.send("watch", SyncCodec.encode(Events(id, phoneData.events.scoreEvents(id))))
        cluster.settle()
        assertNull(
            cluster["watch"]
                .session.state.value.match,
        )
        assertNull(cluster["watch"].data.matches.get(id))
        assertTrue(cluster["watch"].data.events.count(id.value) == 0L)
    }
}
