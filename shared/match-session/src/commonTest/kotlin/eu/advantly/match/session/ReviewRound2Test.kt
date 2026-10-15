package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.scoreEvents
import eu.advantly.device.link.LinkState
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.Events
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regressionstests zu Review-Runde 2 (PR #13): Offline-Rückstand, Löschmarkierungen, Thread-Vorgabe der Callbacks. */
class ReviewRound2Test {
    private val format = Presets.TENNIS_SINGLES_BO3.format()

    /** Befund 1: viele offline angelegte Matches kommen vollständig an; Rückstand ist persistent und über alle Matches. */
    @Test
    fun offlineBacklogOfManyMatchesIsDeliveredCompletely() {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        cluster.offline("phone")
        cluster.settle()
        val watch = cluster["watch"]
        val ids = mutableListOf<MatchId>()
        repeat(11) { i ->
            ids += watch.session.create(format, SINGLES)
            watch.session.recordPoint(Side.A)
            if (i < 10) watch.session.endMatch()
            cluster.settle(1_000)
        }
        val backlog = watch.session.state.value.sync.pendingToPeers
        assertTrue(backlog >= 11 * 2, "Rückstand über alle Matches: $backlog")
        // Neustart der Uhr-App: der Rückstand ist persistent
        val restarted = MatchSession(watch.data, null, cluster.scheduler, watch.session.config, cluster.clock)
        assertEquals(backlog, restarted.state.value.sync.pendingToPeers)

        cluster.online("phone")
        cluster.settle(2_000)
        val phone = cluster["phone"]
        ids.forEach { id -> assertEquals(watch.data.events.scoreEvents(id), phone.data.events.scoreEvents(id), "Match $id") }
        assertEquals(
            11,
            phone.data.matches
                .history()
                .size,
        )
        assertEquals(0, watch.session.state.value.sync.pendingToPeers)
        assertEquals(0, phone.session.state.value.sync.pendingToPeers, "Empfangenes gilt als beim Absender vorhanden")
    }

    /** Befund 2: Löschen auf dem Handy entfernt das Match auch auf der Uhr, kein Wiederauftauchen beim Abgleich. */
    @Test
    fun deletionIsReplicatedAndNeverResurrected() {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        val phone = cluster["phone"]
        val watch = cluster["watch"]
        val id = watch.session.create(format, SINGLES)
        cluster.settle()
        watch.session.recordPoint(Side.A)
        cluster.settle()
        phone.session.deleteMatch(id)
        cluster.settle()
        assertNull(watch.session.state.value.match, "laufendes Match auf der Uhr geschlossen")
        assertNull(watch.data.matches.get(id))
        assertEquals(0L, watch.data.events.count(id.value))
        cluster.settle(65_000) // mehrere Abgleichrunden
        for (device in listOf(phone, watch)) {
            assertNull(device.data.matches.get(id), device.name)
            assertEquals(0L, device.data.events.count(id.value), device.name)
            assertTrue(device.data.matches.isDeleted(id), device.name)
            assertNull(device.session.state.value.match, device.name)
        }
    }

    /** Befund 2: Uhr zählt offline weiter, während das Handy löscht → nach dem Wiederverbinden ist es überall weg. */
    @Test
    fun deletionWinsOverAnOfflineBacklog() {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        val phone = cluster["phone"]
        val watch = cluster["watch"]
        val id = watch.session.create(format, SINGLES)
        cluster.settle()
        cluster.offline("watch")
        cluster.settle(30_000)
        repeat(3) { watch.session.recordPoint(Side.B) }
        phone.session.deleteMatch(id)
        cluster.online("watch")
        cluster.settle(5_000)
        assertNull(phone.data.matches.get(id))
        assertNull(watch.data.matches.get(id))
        assertEquals(0L, phone.data.events.count(id.value))
        assertEquals(0, watch.session.state.value.sync.pendingToPeers)
        assertNull(watch.session.state.value.match)
    }

    /** Befund 2: Events eines gelöschten Matches werden dauerhaft ignoriert, der Absender bekommt die Löschung. */
    @Test
    fun eventsOfADeletedMatchAreIgnored() {
        val source = WireNode("source", 7)
        val id = source.session.create(format, SINGLES)
        source.points(Side.A, 2)
        val node = WireNode("node", 8)
        node.link.deliver(Events(id, source.events()))
        assertEquals(id, node.match.id)
        node.session.deleteMatch(id)
        node.link.deliver(Events(id, source.events()))
        assertNull(node.data.matches.get(id))
        assertEquals(0L, node.data.events.count(id.value))
        assertNull(node.session.state.value.match)
        val replies = node.link.sent.filterIsInstance<eu.advantly.sync.protocol.Deleted>()
        assertTrue(
            replies.isNotEmpty() && replies
                .last()
                .matches
                .single()
                .matchId == id,
        )
        assertFalse(
            node.data.matches.insertIfAbsent(
                id,
                format,
                SINGLES,
                eu.advantly.core.model
                    .MatchMeta(),
                0,
            ),
        )
    }

    /** Befund 4: auch Callback-Eingänge (Empfang, Peers, Ack) prüfen den Thread. */
    @Test
    fun callbackEntriesEnforceTheSchedulerThread() {
        var allowed = true
        val node = WireNode("guard", 55, guard = { allowed })
        node.session.create(format, SINGLES)
        val source = WireNode("source", 56)
        val other = source.session.create(format, SINGLES)
        allowed = false
        assertFailsWith<IllegalStateException> { node.link.deliver(Events(other, source.events())) }
        assertFailsWith<IllegalStateException> {
            node.link.callbacks
                .first()
                .onAcked("fake", 1, 1)
        }
        assertFailsWith<IllegalStateException> { checkNotNull(node.link.sink).onStateChanged(LinkState.CONNECTED, listOf("lab")) }
        assertFailsWith<IllegalStateException> { node.scheduler.advanceBy(60_000) }
        assertEquals(0L, node.data.events.count(other.value), "nichts geschrieben")
        allowed = true
        val standalone = MatchSession(testLocalData(), null, ManualScheduler(), SessionConfig(DeviceId("x"), PeerKind.PHONE))
        assertTrue(standalone.state.value.match == null)
    }
}
