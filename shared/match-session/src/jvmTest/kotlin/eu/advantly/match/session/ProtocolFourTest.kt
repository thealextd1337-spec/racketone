package eu.advantly.match.session

import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.CloudPullMatch
import eu.advantly.data.CloudPullMetadataPage
import eu.advantly.data.scoreEvents
import eu.advantly.sync.protocol.CloudMatchStamp
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.Deleted
import eu.advantly.sync.protocol.DeletedMatch
import eu.advantly.sync.protocol.Events
import eu.advantly.sync.protocol.LocalMatchStamp
import eu.advantly.sync.protocol.SyncCodec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Protokoll 4 an der Session: Updatezustand bei 3↔4 und Belege vor jeder Mutation. */
class ProtocolFourTest {
    private var pair: LifecyclePair? = null

    @AfterTest
    fun tearDown() {
        pair?.close()
    }

    @Test
    fun threeAgainstFourIsAnUpdateStateWithoutPartialSync() {
        val pair = LifecyclePair(phoneVersions = 4..4, watchVersions = 3..3).also { pair = it }
        val match = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        assertTrue(pair.phone.recordPoint(Side.A))
        pair.settle()
        assertEquals(CompatibilityStatus.PEER_TOO_OLD, pair.phone.state.value.sync.versionMismatch)
        assertEquals(CompatibilityStatus.PEER_TOO_NEW, pair.watch.state.value.sync.versionMismatch)
        assertEquals(0L, pair.watchData.events.count(match.value), "kein Teilsync")
        val sync = pair.phone.state.value.sync
        assertTrue(sync.pendingToPeers > 0 && sync.lifecycleBlocked && !sync.settled, "Rückstand sichtbar blockiert (Update nötig)")
    }

    /** Eigenes Match am Handy, per Pull Cloud-Match bei G 1 (MEMBER), an die Uhr zugestellt. */
    private fun cloudMatch(pair: LifecyclePair): MatchId {
        pair.activate("alice")
        val match = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.settle()
        val upload = pair.phoneData.cloudUploads.capture(pair.token!!, 100)
        pair.phoneData.cloudUploads.acknowledge(upload, upload.events.mapTo(mutableSetOf()) { it.id }, metadata = upload.metadataRevisions)
        val record = pair.phoneData.matches.get(match)!!
        val run =
            pair.phoneData.cloudPulls
                .beginRun("alice") {}
                .runSerial
        val page =
            CloudPullMetadataPage(
                listOf(CloudPullMatch(match.value, 1, "{}", "member", 1, 0, false, record)),
                emptyList(),
                emptyList(),
                1,
                false,
                1,
            )
        assertTrue(
            pair.phoneData.cloudPulls.commitMetadata(
                "alice",
                run,
                pair.phoneData.cloudPulls
                    .progress("alice")
                    .cursor,
                page,
            ) {},
        )
        pair.settle()
        return match
    }

    @Test
    fun aCloudMatchIsNeverLegitimizedByALocalStamp() {
        val pair = LifecyclePair(3..4).also { pair = it }
        val match = cloudMatch(pair)
        val envelope =
            pair.watchLink
                .decoded()
                .last { it.identity != null }
                .identity!!
        val genesis =
            pair.phoneData.events
                .scoreEvents(match)
                .first { it.payload is MatchCreated }
        val watch = pair.watch.deviceId
        val seq = pair.watchData.events.maxDeviceSeq(match.value, watch.value) + 1
        val point = ScoreEvent(EventId("watch-point"), match, watch, seq, 99, 1, genesis.id, PointWon(Side.A))
        val local = LocalMatchStamp(genesis.deviceId, genesis.id, pair.phoneData.accountIdentities.scope!!)

        pair.phoneLink.replay("watch", SyncCodec.encode(Events(match, listOf(point), v = 4, stamp = local), envelope))
        assertTrue(
            pair.phoneData.events
                .eventsFor(match.value)
                .none { it.id == point.id.value },
            "lokaler Beleg für Cloud-Match",
        )
        pair.phoneLink.replay(
            "watch",
            SyncCodec.encode(Deleted(listOf(DeletedMatch(match, 1)), v = 4, stamps = mapOf(match.value to local)), envelope),
        )
        assertFalse(pair.phoneData.matches.isDeleted(match), "Löschmarkierung braucht denselben Beleg")
        val stale = CloudMatchStamp(1, 7)
        pair.phoneLink.replay("watch", SyncCodec.encode(Events(match, listOf(point), v = 4, stamp = stale), envelope))
        assertTrue(
            pair.phoneData.events
                .eventsFor(match.value)
                .none { it.id == point.id.value },
            "anderes E",
        )

        pair.phoneLink.replay("watch", SyncCodec.encode(Events(match, listOf(point), v = 4, stamp = CloudMatchStamp(1, 0)), envelope))
        assertTrue(
            pair.phoneData.events
                .eventsFor(match.value)
                .any { it.id == point.id.value },
            "gültiger Beleg (G, E) wird angenommen",
        )
    }
}
