package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Effect
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.SidePair
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Single-Scorer in der Session (02 §7.5): Anzeige-Geräte zählen nicht, Übergabe online/offline/gleichzeitig. */
class ScorerHandoverTest {
    private val format = Presets.TENNIS_SINGLES_BO3.format()

    private fun watchScoring(): Cluster {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        cluster["phone"].session.create(format, SINGLES, NO_SERVE_TRACKING, scorer = ScorerChoice.Device(DeviceId("watch")))
        cluster.settle()
        return cluster
    }

    @Test
    fun displayDevicesCannotCountAndWriteNothing() {
        val cluster = watchScoring()
        val phone = cluster["phone"]
        val before = phone.data.events.count(phone.match.id.value)
        assertFalse(phone.session.recordPoint(Side.A))
        assertFalse(phone.session.undo())
        assertFalse(phone.session.redo())
        assertFalse(phone.session.pause())
        assertFalse(phone.session.endMatch())
        assertFalse(phone.session.continueDecision(true))
        assertFalse(phone.match.canUndo)
        assertEquals(before, phone.data.events.count(phone.match.id.value))
        assertEquals(DeviceId("watch"), phone.match.scorerDeviceId)
    }

    @Test
    fun onlineHandoverMovesTheRole() {
        val cluster = watchScoring()
        val phone = cluster["phone"]
        val watch = cluster["watch"]
        watch.session.recordPoint(Side.A)
        cluster.settle()
        assertTrue(phone.session.takeOverScoring())
        assertFalse(phone.session.takeOverScoring(), "zählt schon")
        cluster.settle()
        assertTrue(phone.match.isScorer)
        assertFalse(watch.match.isScorer)
        assertEquals(PeerKind.PHONE, watch.match.scorerKind)
        assertEquals(listOf(Effect.SCORER_CHANGED), watch.score.effects)
        assertFalse(watch.session.recordPoint(Side.B))
        assertTrue(phone.session.recordPoint(Side.B))
        // der neue Zähler darf auch Punkte des Vorgängers zurücknehmen
        assertTrue(phone.session.undo())
        assertTrue(phone.session.undo())
        cluster.settle()
        cluster.assertConverged()
        assertEquals(SidePair(0, 0), watch.score.current?.points)
        assertEquals(0, watch.match.rejectedByRoleCount)
    }

    @Test
    fun offlineOldScorerInputsAreRejectedAndReported() {
        val cluster = watchScoring()
        val phone = cluster["phone"]
        val watch = cluster["watch"]
        watch.session.recordPoint(Side.A)
        cluster.settle()
        cluster.offline("watch")
        cluster.settle(30_000)
        assertTrue(phone.session.takeOverScoring())
        phone.session.recordPoint(Side.B)
        cluster.settle(1_000)
        repeat(3) {
            assertTrue(watch.session.recordPoint(Side.A), "Uhr weiß noch nichts von der Übernahme")
            cluster.settle(1_000)
        }
        cluster.online("watch")
        cluster.settle(5_000)
        cluster.assertConverged()
        assertEquals(3, watch.match.rejectedByRoleCount)
        assertEquals(3, phone.match.rejectedByRoleCount)
        assertEquals(SidePair(1, 1), phone.score.current?.points)
        assertFalse(watch.match.isScorer)
    }

    @Test
    fun simultaneousTakeoversConvergeToOneScorer() {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch1" to PeerKind.WATCH, "watch2" to PeerKind.WATCH)
        cluster["phone"].session.create(format, SINGLES, NO_SERVE_TRACKING)
        cluster.settle()
        assertTrue(cluster["watch1"].session.takeOverScoring())
        assertTrue(cluster["watch2"].session.takeOverScoring())
        cluster.settle()
        cluster.assertConverged()
        val scorers =
            cluster.devices.values
                .filter { it.match.isScorer }
                .map { it.name }
        assertEquals(1, scorers.size, "genau ein Zähler: $scorers")
        assertEquals(DeviceId(scorers.single()), cluster["phone"].match.scorerDeviceId)
    }

    @Test
    fun followUpKeepsTheScorer() {
        val cluster = watchScoring()
        repeat(24) { cluster["watch"].session.recordPoint(Side.A) }
        cluster.settle()
        assertEquals(1, cluster["phone"].score.setsWon.a)
        assertTrue(cluster["watch"].session.endMatch(eu.advantly.core.scoring.EndReason.RETIRED, Side.B))
        cluster.settle()
        val next = cluster["phone"].session.createFollowUp()
        cluster.settle()
        assertEquals(next, cluster["watch"].match.id)
        assertTrue(cluster["watch"].match.isScorer)
        assertEquals(DeviceId("watch"), cluster["phone"].match.scorerDeviceId)
    }
}
