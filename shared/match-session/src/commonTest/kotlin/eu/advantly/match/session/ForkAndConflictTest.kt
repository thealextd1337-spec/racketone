package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.SidePair
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Doppel-Tap, Widerspruch und Fork (01 §4.4) mit echten Sessions im simulierten Netz. */
class ForkAndConflictTest {
    private val format = Presets.TENNIS_SINGLES_BO3.format()

    private fun twoDevices(): Cluster {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        cluster["phone"].session.create(format, SINGLES)
        cluster.settle()
        return cluster
    }

    /** Gemeinsamer Stand 30:0, dann zählen beide offline: Handy 2× A, Uhr 3× B; danach wieder verbunden. */
    private fun forkedCluster(): Pair<Cluster, eu.advantly.core.scoring.MatchState> {
        val cluster = twoDevices()
        cluster["phone"].session.recordPoint(Side.A)
        cluster.settle()
        cluster["watch"].session.recordPoint(Side.A)
        cluster.settle()
        cluster.offline("watch")
        cluster.settle(30_000)
        repeat(2) {
            cluster["phone"].session.recordPoint(Side.A)
            cluster.settle(3_000)
        }
        repeat(3) {
            cluster["watch"].session.recordPoint(Side.B)
            cluster.settle(3_000)
        }
        val watchOnly = cluster["watch"].score
        cluster.online("watch")
        cluster.settle(5_000)
        return cluster to watchOnly
    }

    @Test
    fun simultaneousSameSideTapsAreCountedOnce() {
        val cluster = twoDevices()
        cluster["phone"].session.recordPoint(Side.A)
        cluster["watch"].session.recordPoint(Side.A)
        cluster.settle()
        cluster.assertConverged()
        for (name in listOf("phone", "watch")) {
            val match = cluster[name].match
            assertEquals(SidePair(1, 0), match.state.current?.points, name)
            assertEquals(1, match.absorbedTaps.size, name)
            assertNull(match.fork, name)
        }
    }

    @Test
    fun contradictingTapsShowAConflictUntilOneIsUndone() {
        val cluster = twoDevices()
        cluster["phone"].session.recordPoint(Side.A)
        cluster["watch"].session.recordPoint(Side.B)
        cluster.settle()
        cluster.assertConverged()
        for (name in listOf("phone", "watch")) {
            assertEquals(1, cluster[name].match.conflicts.size, name)
            assertEquals(
                SyncIndicator.CONFLICT,
                cluster[name]
                    .session.state.value.sync.indicator,
                name,
            )
            assertNull(cluster[name].match.fork, name)
        }
        // 1-Tap-Undo auf einem Gerät löst den Konflikt überall
        assertTrue(cluster["watch"].session.undo())
        cluster.settle()
        cluster.assertConverged()
        for (name in listOf("phone", "watch")) {
            assertEquals(emptyList(), cluster[name].match.conflicts, name)
            assertEquals(
                SyncIndicator.CONNECTED,
                cluster[name]
                    .session.state.value.sync.indicator,
                name,
            )
        }
    }

    @Test
    fun offlineForkIsDetectedOnBothDevicesAndResolvedByChoosingAVersion() {
        val (cluster, watchOnly) = forkedCluster()
        cluster.assertConverged() // vorläufig zusammengeführt: alle Punkte
        val phoneFork = assertNotNull(cluster["phone"].match.fork)
        val watchFork = assertNotNull(cluster["watch"].match.fork)
        assertEquals(phoneFork.id, watchFork.id)
        assertEquals(
            SyncIndicator.CONFLICT,
            cluster["phone"]
                .session.state.value.sync.indicator,
        )
        assertEquals(emptyList(), cluster["phone"].match.conflicts, "Fork-Dialog statt zusätzlichem Konflikt-Banner")

        val watchVersion = phoneFork.branches.indexOfFirst { DeviceId("watch") in it.devices }
        val version = phoneFork.branches[watchVersion]
        assertEquals(listOf(PeerKind.WATCH), version.kinds)
        assertEquals(3, version.points)
        assertEquals(2, phoneFork.branches[1 - watchVersion].points)
        assertTrue(phoneFork.branches[1 - watchVersion].includesThisDevice)
        assertEquals(watchOnly.copy(effects = emptyList()), version.preview.copy(effects = emptyList()))

        assertTrue(cluster["phone"].session.resolveFork(watchVersion))
        cluster.settle()
        cluster.assertConverged()
        for (name in listOf("phone", "watch")) {
            assertNull(cluster[name].match.fork, name)
            assertEquals(watchOnly.copy(effects = emptyList()), cluster[name].score.copy(effects = emptyList()), name)
        }
        // Zählen geht normal weiter
        cluster["watch"].session.recordPoint(Side.A)
        cluster.settle()
        cluster.assertConverged()
        assertNull(cluster["phone"].match.fork)
    }

    @Test
    fun mergingAForkKeepsAllPointsAndIsAcknowledgedLocally() {
        val (cluster, _) = forkedCluster()
        val merged = cluster["phone"].score
        assertTrue(cluster["phone"].session.mergeFork())
        assertNull(cluster["phone"].match.fork)
        assertEquals(
            SyncIndicator.CONNECTED,
            cluster["phone"]
                .session.state.value.sync.indicator,
        )
        assertEquals(merged, cluster["phone"].score)
        // die Quittung ist gerätelokal: die Uhr zeigt den Hinweis weiter, bis dort entschieden wird
        assertNotNull(cluster["watch"].match.fork)
        cluster.assertConverged()
    }

    @Test
    fun forkSurvivesARestart() {
        val (cluster, _) = forkedCluster()
        val phone = cluster["phone"]
        val id = phone.match.id
        val restarted = MatchSession(phone.data, null, cluster.scheduler, phone.session.config, cluster.clock)
        assertTrue(restarted.resume(id))
        assertEquals(
            phone.match.fork,
            restarted.state.value.match
                ?.fork,
        )
    }
}
