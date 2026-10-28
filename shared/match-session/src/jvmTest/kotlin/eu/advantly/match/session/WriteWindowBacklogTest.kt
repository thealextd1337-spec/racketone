package eu.advantly.match.session

import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.CloudPullMatch
import eu.advantly.data.CloudPullMetadataPage
import eu.advantly.sync.protocol.Summary
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Eigene ungesicherte Punkte im Fenster „schreiben ja, lesen nein“ (Vertrag W3 §4): Sie zählen als Rückstand und gehen
 * per Backlog an die Uhr. Braucht D-1 (Rückstandsabfragen der Schreibspur für eigene Quellen). Unter Protokoll 4 gibt Data
 * ihren Inhalt erst nach angewandter Redaktion frei; bis dahin wartet der Rückstand sichtbar.
 */
class WriteWindowBacklogTest {
    private val pair = LifecyclePair()

    @AfterTest
    fun tearDown() = pair.close()

    private fun catalog(
        version: Long,
        epoch: Long,
        record: eu.advantly.data.MatchRecord,
    ): Long {
        val data = pair.phoneData
        val run = data.cloudPulls.beginRun("alice") {}.runSerial
        val entry = CloudPullMatch(record.id.value, version, """{"version":$version}""", "member", 1, epoch, false, record)
        val page = CloudPullMetadataPage(listOf(entry), emptyList(), emptyList(), version, false, 1)
        assertTrue(data.cloudPulls.commitMetadata("alice", run, data.cloudPulls.progress("alice").cursor, page) {})
        return run
    }

    /**
     * Verborgene Events (Redaktion ausstehend) lösen kein Ping-Pong aus: Das Handy bestätigt Duplikate nur, wenn seine Sicht sie
     * zeigt. Vorher je Richtung ca. 6100 Pakete pro Minute, sobald die Uhr im Fenster zählte.
     */
    @Test
    fun hiddenEventsInTheWriteWindowCauseNoPingPong() {
        pair.activate("alice")
        val match = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.settle()
        val upload = pair.phoneData.cloudUploads.capture(pair.token!!, 100)
        pair.phoneData.cloudUploads.acknowledge(upload, upload.events.mapTo(mutableSetOf()) { it.id }, metadata = upload.metadataRevisions)
        val record = pair.phoneData.matches.get(match)!!
        catalog(1, 0, record)
        pair.settle()
        catalog(2, 1, record)
        pair.settle()
        assertTrue(pair.watch.takeOverScoring())
        assertTrue(pair.watch.recordPoint(Side.A))
        pair.settle()
        val sent = pair.phoneLink.sent.size + pair.watchLink.sent.size
        pair.scheduler.advanceBy(60_000)
        val perMinute = pair.phoneLink.sent.size + pair.watchLink.sent.size - sent
        assertTrue(perMinute < 30, "begrenzt (Takt), nicht im Kreis: $perMinute Pakete pro Minute")
    }

    /**
     * Review A1: Die Uhr beendet das Match, solange die Redaktion am Handy aussteht. Danach ist das Handy weder zurück noch
     * aktiv und tickt nicht; erst die Antwort auf den Takt der Uhr bestätigt deren Events (vorher 4 offen bis zum Link-Up).
     */
    @Test
    fun watchEventsOfAnEndedMatchAreConfirmedAfterTheRedaction() {
        pair.activate("alice")
        val match = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.settle()
        val upload = pair.phoneData.cloudUploads.capture(pair.token!!, 100)
        pair.phoneData.cloudUploads.acknowledge(upload, upload.events.mapTo(mutableSetOf()) { it.id }, metadata = upload.metadataRevisions)
        val record = pair.phoneData.matches.get(match)!!
        catalog(1, 0, record)
        pair.settle()
        val run = catalog(2, 1, record)
        pair.settle()
        assertTrue(pair.watch.takeOverScoring())
        assertTrue(pair.watch.recordPoint(Side.A))
        assertTrue(pair.watch.recordPoint(Side.A))
        assertTrue(pair.watch.endMatch())
        pair.settle()
        assertTrue((pair.watchData.events.unconfirmedCountByMatch()[match.value] ?: 0) > 0, "verborgen, also unbestätigt")

        val data = pair.phoneData
        val work = checkNotNull(data.cloudPulls.nextEventWork("alice", run))
        val redaction = checkNotNull(data.cloudPulls.redactionWork(work, run))
        assertTrue(data.cloudPulls.stageRedaction(work, run, redaction, 2, emptyList(), emptyList(), true, true) {})
        pair.settle()
        repeat(2) { pair.scheduler.advanceBy(31_000) }
        assertEquals(null, pair.watchData.events.unconfirmedCountByMatch()[match.value], "nach einem Takt bestätigt")
        assertTrue(pair.watch.state.value.sync.settled)
    }

    /** Review A1 (Probe LA): Eine verlorene Antwort-Summary nach dem Matchende heilt der nächste Takt, nicht erst ein Link-Up. */
    @Test
    fun aLostReplyAfterTheEndIsRepairedByTheNextTick() {
        pair.activate("alice")
        val match = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.settle()
        assertTrue(pair.watch.takeOverScoring())
        assertTrue(pair.watch.recordPoint(Side.A))
        pair.settle()
        pair.phoneLink.lose = { it is Summary && it.reply }
        assertTrue(pair.watch.endMatch())
        pair.settle()
        pair.phoneLink.lose = { false }
        assertTrue((pair.watchData.events.unconfirmedCountByMatch()[match.value] ?: 0) > 0)

        repeat(2) { pair.scheduler.advanceBy(31_000) }
        assertEquals(null, pair.watchData.events.unconfirmedCountByMatch()[match.value])
        assertTrue(pair.watch.state.value.sync.settled)
    }

    /**
     * Die Bestätigungsantwort löst keine Antwort aus: je Takt und Gegenstelle höchstens eine; im aktiven Match bleibt es beim
     * Takt beider Seiten (2 Pakete), nach dem Ende und bestätigt ist es still.
     */
    @Test
    fun confirmingRepliesCauseNoReplyLoop() {
        pair.activate("alice")
        pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.settle()
        assertTrue(pair.watch.takeOverScoring())
        assertTrue(pair.watch.recordPoint(Side.A))
        pair.settle()

        fun replies(link: RecordingLink) = link.decoded().count { (it.message as? Summary)?.reply == true }
        val ticks = 10
        val sent = pair.phoneLink.sent.size + pair.watchLink.sent.size
        val phoneReplies = replies(pair.phoneLink)
        val watchReplies = replies(pair.watchLink)
        repeat(ticks) { pair.scheduler.advanceBy(30_000) }
        assertTrue(replies(pair.phoneLink) - phoneReplies <= ticks, "Handy: höchstens eine Antwort je Takt")
        assertTrue(replies(pair.watchLink) - watchReplies <= ticks, "Uhr: höchstens eine Antwort je Takt")
        val perTick = (pair.phoneLink.sent.size + pair.watchLink.sent.size - sent) / ticks
        assertTrue(perTick <= 2, "aktiv bestätigt der Takt beider Seiten, keine Zusatzantwort: $perTick Pakete je Takt")

        assertTrue(pair.watch.endMatch())
        pair.settle()
        repeat(2) { pair.scheduler.advanceBy(30_000) }
        val quiet = pair.phoneLink.sent.size + pair.watchLink.sent.size
        repeat(ticks) { pair.scheduler.advanceBy(30_000) }
        assertEquals(quiet, pair.phoneLink.sent.size + pair.watchLink.sent.size, "nach dem Ende und bestätigt: still")
    }

    @Test
    fun ownPointsInTheWriteWindowCountAsPendingAndReachTheWatch() {
        pair.activate("alice")
        val match = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES, NO_SERVE_TRACKING)
        pair.settle()
        val upload = pair.phoneData.cloudUploads.capture(pair.token!!, 100)
        pair.phoneData.cloudUploads.acknowledge(upload, upload.events.mapTo(mutableSetOf()) { it.id }, metadata = upload.metadataRevisions)
        val record = pair.phoneData.matches.get(match)!!
        catalog(1, 0, record)
        val run = catalog(2, 1, record)
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
        assertEquals(1L, pair.watchData.events.count(match.value), "vor der Redaktion nur die Genesis auf der Uhr")
        assertTrue(pair.phone.state.value.sync.pendingToPeers >= 2, "wartet auf Redaktion, nicht verloren")

        val data = pair.phoneData
        val work = checkNotNull(data.cloudPulls.nextEventWork("alice", run))
        val redaction = checkNotNull(data.cloudPulls.redactionWork(work, run))
        assertTrue(data.cloudPulls.stageRedaction(work, run, redaction, 2, emptyList(), emptyList(), true, true) {})
        repeat(2) { pair.scheduler.advanceBy(31_000) }
        assertEquals(3L, pair.watchData.events.count(match.value), "Genesis und beide Punkte auf der Uhr")
        assertEquals(0, pair.phone.state.value.sync.pendingToPeers)
    }
}
