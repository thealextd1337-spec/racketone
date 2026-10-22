package eu.advantly.match.session

import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.CloudPullMatch
import eu.advantly.data.CloudPullMetadataPage
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Eigene ungesicherte Punkte im Fenster „schreiben ja, lesen nein“ (Vertrag W3 §4): Sie zählen als Rückstand und gehen
 * per Backlog an die Uhr. Braucht D-1 (Rückstandsabfragen der Schreibspur für eigene Quellen).
 */
class WriteWindowBacklogTest {
    private val pair = LifecyclePair()

    @AfterTest
    fun tearDown() = pair.close()

    private fun catalog(
        version: Long,
        epoch: Long,
        record: eu.advantly.data.MatchRecord,
    ) {
        val data = pair.phoneData
        val run = data.cloudPulls.beginRun("alice") {}.runSerial
        val entry = CloudPullMatch(record.id.value, version, """{"version":$version}""", "member", 1, epoch, false, record)
        val page = CloudPullMetadataPage(listOf(entry), emptyList(), emptyList(), version, false, 1)
        assertTrue(data.cloudPulls.commitMetadata("alice", run, data.cloudPulls.progress("alice").cursor, page) {})
    }

    @Ignore("wartet auf D-1")
    @Test
    fun ownPointsInTheWriteWindowCountAsPendingAndReachTheWatch() {
        pair.activate("alice")
        val match = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.settle()
        val upload = pair.phoneData.cloudUploads.capture(pair.token!!, 100)
        pair.phoneData.cloudUploads.acknowledge(upload, upload.events.mapTo(mutableSetOf()) { it.id }, metadata = upload.metadataRevisions)
        val record = pair.phoneData.matches.get(match)!!
        catalog(1, 0, record)
        catalog(2, 1, record)
        pair.settle()
        assertFalse(pair.phoneData.replicas.canReadMember(pair.phoneData.replicas.captureContext(), match.value))

        pair.network.setConnected("watch", false)
        assertTrue(pair.phone.recordPoint(Side.A))
        assertTrue(pair.phone.recordPoint(Side.B))
        pair.settle()
        assertTrue(pair.phone.state.value.sync.pendingToPeers >= 2, "eigene Punkte im Fenster sind Rückstand")
        assertFalse(pair.phone.state.value.sync.settled)

        pair.network.setConnected("watch", true)
        pair.settle()
        assertEquals(3L, pair.watchData.events.count(match.value), "Genesis und beide Punkte auf der Uhr")
        assertEquals(0, pair.phone.state.value.sync.pendingToPeers)
    }
}
