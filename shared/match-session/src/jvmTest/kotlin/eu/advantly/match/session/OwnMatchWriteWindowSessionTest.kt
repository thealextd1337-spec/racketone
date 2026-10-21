package eu.advantly.match.session

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.CloudPullMatch
import eu.advantly.data.CloudPullMetadataPage
import eu.advantly.data.LocalData
import eu.advantly.data.MatchRecord
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.device.link.DeviceLink
import eu.advantly.device.link.LinkTransport
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.PeerKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Vertragsergänzung eigene Matches, Teil 2 (P1-1) auf Session-Ebene: Im Fenster „schreiben ja, lesen nein“ (00900 hebt E)
 * vergibt die Session Seq und Lamport fortlaufend; jeder Punkt liegt im Store und geht hoch, Uhr-Events werfen nicht.
 */
class OwnMatchWriteWindowSessionTest {
    private val scheduler = ManualScheduler()
    private val network = InMemoryLinkNetwork(scheduler, queueWhenDisconnected = true)
    private val driver: SqlDriver = createInMemoryJvmDriver()
    private var seed = 0

    private fun link(name: String): DeviceLink {
        val transport = network.transport(name)
        val trusted =
            object : LinkTransport by transport {
                override fun isTrustedCounterpart(peer: String): Boolean = true
            }
        return ReliableDeviceLink(trusted, scheduler)
    }

    private val phoneLink = link("phone")
    private val watchLink = link("watch")

    private fun session(
        data: LocalData,
        link: DeviceLink,
        kind: PeerKind,
    ) = MatchSession(
        data,
        link,
        scheduler,
        SessionConfig(MatchSessions.deviceId(data), kind),
        WallClock { 1_760_000_000_000 + scheduler.nowMs() },
        RandomIdGenerator(Random(++seed)),
    )

    private fun settle() = scheduler.advanceBy(2_000)

    /** Eine Katalogseite bei G 1 mit dem lokalen Kopf als Serverkopf und Redaktionsepoche [epoch]. */
    private fun LocalData.catalog(
        version: Long,
        record: MatchRecord,
        epoch: Long,
    ): Long {
        val profile = accountIdentities.profile!!
        val run = cloudPulls.beginRun(profile) {}.runSerial
        val match = CloudPullMatch(record.id.value, version, """{"version":$version}""", "member", 1, epoch, false, record)
        val page = CloudPullMetadataPage(listOf(match), emptyList(), emptyList(), version, false, 1)
        assertTrue(cloudPulls.commitMetadata(profile, run, cloudPulls.progress(profile).cursor, page) {})
        return run
    }

    /** (Gerät, Seq, Lamport) aller gespeicherten Events an der Lesesperre vorbei. */
    private fun stored(match: MatchId): List<Triple<String, Long, Long>> =
        driver
            .executeQuery(
                null,
                "SELECT device_id, device_seq, lamport FROM match_event WHERE match_id = '${match.value}' ORDER BY lamport",
                { c ->
                    val rows = mutableListOf<Triple<String, Long, Long>>()
                    while (c.next().value) rows += Triple(c.getString(0)!!, c.getLong(1)!!, c.getLong(2)!!)
                    QueryResult.Value(rows)
                },
                0,
            ).value

    @Test
    fun pointsInTheWriteOnlyWindowAreStoredInOrderUploadedAndWatchEventsDoNotThrow() {
        val phoneData = LocalData(driver)
        val watchData = testLocalData()
        phoneData.players.ensureSelf("Alex", 0) { PlayerId("phone-self") }
        watchData.players.ensureSelf("Alex", 0) { PlayerId("watch-self") }
        var phone = session(phoneData, phoneLink, PeerKind.PHONE)
        val watch = session(watchData, watchLink, PeerKind.WATCH)
        phone.start()
        watch.start()
        settle()
        val token = phoneData.cloudUploads.activate("alice")
        phone.activateAccount("alice", token)
        settle()
        phoneData.replicas.activate(phoneData.replicas.captureContext()) {}
        val match = phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        assertTrue(phone.recordPoint(Side.A))
        settle()
        val uploaded = phoneData.cloudUploads.capture(token, 100)
        phoneData.cloudUploads.acknowledge(uploaded, uploaded.events.mapTo(mutableSetOf()) { it.id }, metadata = uploaded.metadataRevisions)
        val record = phoneData.matches.get(match)!!
        phoneData.catalog(1, record, epoch = 0)
        // 00900: Upload einer redigierten Identität hebt E; Lesen gesperrt bis zum Transfer, Schreiben über die eigene Spur.
        val run = phoneData.catalog(2, record, epoch = 1)
        assertFalse(phoneData.replicas.canReadMember(phoneData.replicas.captureContext(), match.value))

        assertTrue(phone.recordPoint(Side.A))
        assertTrue(phone.recordPoint(Side.B))
        settle()
        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            stored(match).filter { it.first == phone.deviceId.value }.map { it.second },
            "Punkte im Fenster liegen lückenlos im Store",
        )
        // Uhr übernimmt und zählt: Events der Uhr, Summary-Wasserstände und Acks treffen das gesperrte Match.
        assertTrue(watch.takeOverScoring())
        assertTrue(watch.recordPoint(Side.A))
        settle()
        assertTrue(phone.takeOverScoring())
        assertTrue(phone.recordPoint(Side.B))
        settle()

        val rows = stored(match)
        val phoneDevice = phone.deviceId.value
        val watchDevice = watch.deviceId.value
        // Genesis, drei Punkte, Rückübernahme (ScorerChanged), ein Punkt.
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), rows.filter { it.first == phoneDevice }.map { it.second })
        assertEquals(listOf(1L, 2L), rows.filter { it.first == watchDevice }.map { it.second })
        assertEquals(rows.map { it.third }.distinct(), rows.map { it.third }, "Lamport eindeutig")
        assertEquals(
            rows.size,
            phone.state.value.match!!
                .eventCount,
            "Engine zählt genau die gespeicherten Events",
        )

        // Nach dem Transfer (U4 wartet bis zur angewandten Redaktion) gehen alle neuen Events von Handy und Uhr hoch.
        val work = checkNotNull(phoneData.cloudPulls.nextEventWork("alice", run))
        val redaction = checkNotNull(phoneData.cloudPulls.redactionWork(work, run))
        assertTrue(phoneData.cloudPulls.stageRedaction(work, run, redaction, 2, emptyList(), emptyList(), true, true) {})
        assertEquals(rows.size.toLong(), phoneData.events.count(match.value))
        val next = phoneData.cloudUploads.capture(token, 100)
        assertEquals(rows.size - 2, next.events.count { it.matchId == match.value })

        // Neustart auf derselben Datenbank: kein Punkt verloren, der Score stimmt mit dem Log überein.
        phone.close()
        phone = session(LocalData(driver), phoneLink, PeerKind.PHONE)
        phone.start()
        settle()
        assertEquals(rows.size, stored(match).size)
        assertEquals(
            rows.size,
            phoneData.matches
                .get(match)!!
                .score.eventCount,
        )
        phone.close()
        watch.close()
        driver.close()
    }
}
