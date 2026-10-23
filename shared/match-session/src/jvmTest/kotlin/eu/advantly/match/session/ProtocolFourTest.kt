package eu.advantly.match.session

import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
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
import eu.advantly.sync.protocol.Hello
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

    /**
     * P2-1: Mit Konto ist 3 kein Rückfall mehr, sobald eine Seite 4 kann (Hello `minV = 4`). Sonst flössen Events über 3,
     * Access und Redaktion nie, und die Uhr behielte gelöschte Namen.
     */
    private fun mixedWithAccount(
        phoneVersions: IntRange,
        watchVersions: IntRange,
        phoneSees: CompatibilityStatus,
        watchSees: CompatibilityStatus,
    ) {
        val pair = LifecyclePair(phoneVersions, watchVersions).also { pair = it }
        pair.activate("alice")
        val self =
            pair.phoneData.players
                .self()!!
                .id
        pair.phoneData.players.addGuest(GUEST, "Bea Secret", 0)
        val match = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(self), listOf(GUEST), self))
        assertTrue(pair.phone.recordPoint(Side.A))
        pair.settle()
        repeat(2) { pair.scheduler.advanceBy(31_000) }

        assertEquals(phoneSees, pair.phone.state.value.sync.versionMismatch)
        assertEquals(watchSees, pair.watch.state.value.sync.versionMismatch)
        assertEquals(0L, pair.watchData.events.count(match.value), "keine Events über 3")
        assertEquals(null, pair.watchData.players.get(GUEST), "keine Namen über 3")
        assertTrue(pair.phoneLink.decoded().none { (it.message as? Events)?.events?.isNotEmpty() == true }, "nichts auf dem Draht")
        val sync = pair.phone.state.value.sync
        assertTrue(sync.pendingToPeers > 0 && sync.lifecycleBlocked && !sync.settled, "Rückstand sichtbar blockiert (Update nötig)")
    }

    @Test
    fun withAnAccountANewPhoneAndAnOldWatchAreAnUpdateState() =
        mixedWithAccount(3..4, 3..3, CompatibilityStatus.PEER_TOO_OLD, CompatibilityStatus.PEER_TOO_NEW)

    @Test
    fun withAnAccountAnOldPhoneAndANewWatchAreAnUpdateState() =
        mixedWithAccount(3..3, 3..4, CompatibilityStatus.PEER_TOO_NEW, CompatibilityStatus.PEER_TOO_OLD)

    /**
     * Wiederverbinden mit Konto, bei dem das erste Hello des Handys (und mit [loseWatchHello] auch das der Uhr) verloren geht
     * – der Link garantiert weder Zustellung noch Reihenfolge. Vorher: ein Match mit Gastnamen und ein Punkt im Rückstand.
     */
    private fun reconnectLosingHellos(
        phoneVersions: IntRange,
        watchVersions: IntRange,
        loseWatchHello: Boolean = false,
    ): LifecyclePair {
        val pair = LifecyclePair(phoneVersions, watchVersions).also { pair = it }
        pair.activate("alice")
        val self =
            pair.phoneData.players
                .self()!!
                .id
        pair.phoneData.players.addGuest(GUEST, "Bea Secret", 0)
        val match = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(self), listOf(GUEST), self))
        assertTrue(pair.phone.recordPoint(Side.A))
        pair.settle()
        assertEquals(0L, pair.watchData.events.count(match.value), "Updatezustand")
        pair.network.setConnected("watch", false)
        pair.settle()
        assertTrue(pair.phone.recordPoint(Side.B))
        var phoneHellos = 0
        var watchHellos = 0
        pair.phoneLink.lose = { it is Hello && phoneHellos++ == 0 }
        if (loseWatchHello) pair.watchLink.lose = { it is Hello && watchHellos++ == 0 }
        pair.network.setConnected("watch", true)
        pair.settle()
        pair.phoneLink.lose = { false }
        pair.watchLink.lose = { false }
        return pair
    }

    private fun LifecyclePair.assertNothingOverThree() {
        repeat(3) { scheduler.advanceBy(31_000) }
        val matches = phoneData.matches.recent(10).map { it.id }
        assertEquals(0L, matches.sumOf { watchData.events.count(it.value) }, "keine Events über 3")
        assertTrue(matches.none { watchData.matches.get(it) != null }, "keine Köpfe über 3")
        assertEquals(null, watchData.players.get(GUEST), "keine Namen über 3")
    }

    /** P2-1 (Review r2, Probe L): Ohne das Hello des Handys übernähme eine v3-Uhr Rückstand und Namen aus peersChanged. */
    @Test
    fun aLostHelloOnReconnectLetsNothingThroughOverThree() {
        val pair = reconnectLosingHellos(3..4, 3..3)
        assertEquals(CompatibilityStatus.PEER_TOO_OLD, pair.phone.state.value.sync.versionMismatch)
        pair.assertNothingOverThree()
    }

    /** P2-1: Auch Kommandos und der Takt erreichen eine nicht ausgehandelte Gegenstelle nicht (beide Hellos verloren). */
    @Test
    fun commandsNeverReachAPeerWithoutNegotiatedFour() {
        val pair = reconnectLosingHellos(3..4, 3..3, loseWatchHello = true)
        val sent = pair.phoneLink.sent.size
        assertTrue(pair.phone.recordPoint(Side.A))
        pair.assertNothingOverThree()
        assertTrue(
            pair.phoneLink
                .decoded()
                .drop(sent)
                .all { it.message is Hello },
            "nur Hello auf dem Draht",
        )
    }

    /**
     * P2-1, Empfang: Vor der Aushandlung nimmt eine V4-Uhr mit Konto nichts an – auch kein ungestempeltes v3-Event, wie es
     * ein alter Sender vor dem Hello schiebt (hier V4↔V4, die Hellos des Handys gingen beim Wiederverbinden verloren).
     */
    @Test
    fun aNewWatchAcceptsNothingBeforeTheNegotiation() {
        val pair = LifecyclePair(3..4).also { pair = it }
        pair.activate("alice")
        val match = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.settle()
        assertEquals(1L, pair.watchData.events.count(match.value), "ausgehandelt über 4")
        val envelope =
            pair.phoneLink
                .decoded()
                .last { it.identity != null }
                .identity!!
        pair.network.setConnected("watch", false)
        pair.settle()
        assertTrue(pair.phone.recordPoint(Side.A))
        val point =
            pair.phoneData.events
                .scoreEvents(match)
                .last()
        pair.phoneLink.lose = { it is Hello }
        pair.network.setConnected("watch", true)
        pair.settle()
        assertEquals(1L, pair.watchData.events.count(match.value), "gestempelt, aber an der Uhr nicht ausgehandelt")

        pair.watchLink.replay("phone", SyncCodec.encode(Events(match, listOf(point)), envelope))
        assertEquals(1L, pair.watchData.events.count(match.value), "kein v3-Event ohne Aushandlung")
    }

    /**
     * P2-1 unter Protokoll 3 mit Konto (heutiger Stand): beim Wiederverbinden vor dem Hello der Gegenstelle nur das eigene
     * Hello; den Rest schiebt die Aushandlung nach, und ohne sie holt der Takt alles nach.
     */
    @Test
    fun withAnAccountOnlyHelloPrecedesTheNegotiation() {
        val pair = LifecyclePair(3..3).also { pair = it }
        pair.activate("alice")
        val match = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.settle()
        pair.network.setConnected("watch", false)
        pair.settle()
        assertTrue(pair.phone.recordPoint(Side.A))
        val sent = pair.phoneLink.sent.size
        pair.watchLink.lose = { true }
        pair.network.setConnected("watch", true)
        pair.settle()
        assertEquals(
            listOf("Hello"),
            pair.phoneLink
                .decoded()
                .drop(sent)
                .map { it.message::class.simpleName },
        )
        pair.watchLink.lose = { false }
        repeat(2) { pair.scheduler.advanceBy(31_000) }
        assertEquals(pair.phoneData.events.count(match.value), pair.watchData.events.count(match.value), "Takt holt nach")
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

    private companion object {
        val GUEST = PlayerId("guest-secret")
    }
}
