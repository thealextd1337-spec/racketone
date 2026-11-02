package eu.advantly.presentation.match

import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScorerChanged
import eu.advantly.data.CloudPullMatch
import eu.advantly.data.CloudPullMetadataPage
import eu.advantly.data.CloudUploadSession
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmLocalData
import eu.advantly.data.scoreEvents
import eu.advantly.data.toStoredEvent
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.RandomIdGenerator
import eu.advantly.match.session.ScorerChoice
import eu.advantly.match.session.SessionConfig
import eu.advantly.sync.protocol.PeerKind
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import eu.advantly.match.session.WallClock as SessionClock

private const val T0 = 1_760_000_000_000L

/**
 * PR 0 (plan-live-mitspieler §2): Zwei Konten. Alex (Konto A) zählt am Handy ein Match mit Jonas (Konto B) als verknüpftem
 * Mitspieler; Jonas' Handy zieht es per Cloud-Pull als MEMBER-Replikat (Metadaten über den echten Pull-Seam
 * `commitMetadata`, Events und Readiness wie der Event-Pull). Jonas' Handy samt Uhr darf es nur anzeigen.
 */
class ForeignMemberMatchTest {
    private val scheduler = ManualScheduler()
    private val network = InMemoryLinkNetwork(scheduler, latencyMs = 20)
    private val clock = SessionClock { T0 + scheduler.nowMs() }
    private var seed = 0
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() = closers.reversed().forEach { it() }

    private fun session(
        data: LocalData,
        kind: PeerKind,
        name: String? = null,
    ): MatchSession {
        val link = name?.let { ReliableDeviceLink(network.transport(it), scheduler, newMessageId = { "$it-${++seed}" }) }
        return MatchSession(
            data,
            link,
            scheduler,
            SessionConfig(MatchSessions.deviceId(data), kind),
            clock,
            RandomIdGenerator(Random(++seed)),
        ).also {
            it.start()
            closers += it::close
        }
    }

    private fun signIn(
        data: LocalData,
        session: MatchSession,
        profile: String,
    ): CloudUploadSession {
        val token = data.cloudUploads.activate(profile)
        session.activateAccount(profile, token)
        scheduler.advanceBy(2_000)
        data.replicas.activate(data.replicas.captureContext()) {}
        scheduler.advanceBy(2_000)
        return token
    }

    /** Alex zählt am eigenen Handy (Konto A) zwei Punkte; geliefert wird, was der Server danach hält. */
    private fun alexScores(): Pair<MatchId, LocalData> {
        val data = createInMemoryJvmLocalData()
        val alex = data.players.ensureSelf("Alex", 0) { PlayerId("alex") }.id
        val phone = session(data, PeerKind.PHONE)
        signIn(data, phone, "A")
        val match =
            phone.create(
                Presets.TENNIS_SINGLES_BO3.format(),
                Lineup(listOf(alex), listOf(PlayerId("jonas-bei-alex")), alex),
                MatchMeta(),
                ScorerChoice.ThisDevice,
            )
        assertTrue(phone.recordPoint(Side.A))
        assertTrue(phone.recordPoint(Side.B))
        return match to data
    }

    /** Jonas' Handy (Konto B) zieht Alex' laufendes Match als Mitglied (`member`, G 1, E 0, Ersteller A). */
    private fun pullAsMember(
        data: LocalData,
        match: MatchId,
        alex: LocalData,
        createdBy: String = "A",
        profile: String = "B",
    ) {
        val record = alex.matches.get(match)!!
        val run = data.cloudPulls.beginRun(profile) {}.runSerial
        val page =
            CloudPullMetadataPage(
                listOf(CloudPullMatch(match.value, 1, """{"createdBy":"$createdBy"}""", "member", 1, 0, false, record)),
                emptyList(),
                emptyList(),
                1,
                false,
                1,
            )
        assertTrue(data.cloudPulls.commitMetadata(profile, run, data.cloudPulls.progress(profile).cursor, page) {})
        data.cloud.storeDownloaded(alex.events.scoreEvents(match).map { it.toStoredEvent() }) {}
        data.replicas.markReady(data.replicas.captureContext(), match.value, 1, 0) {}
    }

    private class Jonas(
        val data: LocalData,
        val phone: MatchSession,
        val token: CloudUploadSession,
        var store: SessionMatchStore,
    )

    /** Jonas: Handy mit eigener, verbundener Uhr; nach dem Pull startet die App neu (Adapter öffnet laufende Matches). */
    private fun jonasAfterPull(
        match: MatchId,
        alex: LocalData,
    ): Jonas {
        val data = createInMemoryJvmLocalData()
        data.players.ensureSelf("Jonas", 0) { PlayerId("jonas") }
        val watchData = createInMemoryJvmLocalData()
        watchData.players.ensureSelf("Jonas", 0) { PlayerId("jonas-watch") }
        val phone = session(data, PeerKind.PHONE, "jonas-phone")
        session(watchData, PeerKind.WATCH, "jonas-watch")
        val token = signIn(data, phone, "B")
        pullAsMember(data, match, alex)
        val store = SessionMatchStore(phone, { clock.nowMs() }, monotonic = { scheduler.nowMs() })
        closers += store::close
        scheduler.advanceBy(2_000)
        return Jonas(data, phone, token, store)
    }

    private fun Jonas.ownEvents(match: MatchId) = data.events.scoreEvents(match).filter { it.deviceId == phone.deviceId }

    private fun Jonas.uploadedOwnEvents(match: MatchId) =
        data.cloudUploads
            .capture(token, 100)
            .events
            .filter { it.matchId == match.value }

    /** Vor dem Fix: Neustart öffnete das Match im Hintergrund, die Session nahm Übernahme und Uhr-Zuweisung an. */
    @Test
    fun aPulledMatchOfAnotherAccountIsOnlyShownNeverOpenedForScoring() {
        val (match, alex) = alexScores()
        val jonas = jonasAfterPull(match, alex)
        val store = jonas.store

        // Detail bleibt lesbar (der Verlauf zeigt nur beendete Matches)
        assertEquals(2, store.record(match)!!.events.count { it.payload is PointWon })
        assertTrue(store.openMatches.value.none { it.matchId == match }, "keine Fortsetzen-Karte für das fremde Match")
        assertFalse(store.active.value?.matchId == match)
        assertFalse(store.select(match))

        scheduler.advanceBy(60_000)
        assertFalse(store.takeOverScoring())
        assertFalse(store.stopMatch())
        assertFalse(store.abandonMatch())
        assertFalse(jonas.phone.resume(match))
        assertFalse(jonas.phone.openInBackground(match))
        assertFalse(jonas.phone.takeOverScoring(match))
        assertFalse(jonas.phone.assignToWatch(match, watchScores = true))
        assertFalse(jonas.phone.assignToWatch(match))
        scheduler.advanceBy(2_000)

        assertTrue(jonas.ownEvents(match).isEmpty(), "Events von Jonas' Gerät: ${jonas.ownEvents(match).map { it.payload }}")
        assertTrue(jonas.uploadedOwnEvents(match).isEmpty(), "Upload vorgemerkt")
    }

    /**
     * Vor dem Fix (UI-Weg): Jonas schließt sein eigenes beendetes Match, der Fokus rückte auf Alex' Match; nach 30 s bot die
     * Live-Ansicht „Zählen übernehmen“ an und „Beenden“ schrieb `ScorerChanged` + `MatchEnded` von Jonas' Handy zum Upload.
     */
    @Test
    fun closingAnOwnMatchNeverFocusesTheForeignMatch() {
        val (match, alex) = alexScores()
        val jonas = jonasAfterPull(match, alex)
        val store = jonas.store
        val own = store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()), StartOptions(ScorerOption.THIS_PHONE))
        assertEquals(own, store.active.value?.matchId)
        assertTrue(store.abandonMatch())
        assertTrue(store.dismiss())

        assertFalse(store.active.value?.matchId == match, "Fokus rückt nicht auf das fremde Match")
        scheduler.advanceBy(60_000)
        assertFalse(
            store.active.value
                ?.sync
                ?.offersTakeOver(scheduler.nowMs()) == true,
        )
        assertFalse(store.stopMatch())
        assertFalse(store.abandonMatch())
        assertTrue(jonas.ownEvents(match).isEmpty(), "Events von Jonas' Gerät: ${jonas.ownEvents(match).map { it.payload }}")
        assertTrue(jonas.uploadedOwnEvents(match).isEmpty(), "Upload vorgemerkt")
    }

    /** Alex' neu installiertes Handy (gleiches Konto, neue Geräte-ID) zieht sein eigenes Match und darf es übernehmen. */
    @Test
    fun anOwnPulledMatchFromAnEarlierInstallationCanStillBeTakenOver() {
        val (match, alex) = alexScores()
        val data = createInMemoryJvmLocalData()
        data.players.ensureSelf("Alex", 0) { PlayerId("alex-neu") }
        val phone = session(data, PeerKind.PHONE)
        signIn(data, phone, "A")
        pullAsMember(data, match, alex, createdBy = "A", profile = "A")
        val store = SessionMatchStore(phone, { clock.nowMs() }, monotonic = { scheduler.nowMs() })
        closers += store::close

        assertTrue(store.openMatches.value.any { it.matchId == match }, "eigenes laufendes Match wieder erreichbar")
        assertTrue(phone.takeOverScoring(match))
        assertEquals(
            listOf(phone.deviceId),
            data.events
                .scoreEvents(match)
                .map { it.deviceId }
                .filter { it == phone.deviceId },
        )
    }

    /** Zählt schon ein eigenes Gerät das fremde Match (z. B. angenommene Zähler-Rolle), bleibt es hier zählbar. */
    @Test
    fun aForeignMatchHandedToMyDeviceStaysScorable() {
        val data = createInMemoryJvmLocalData()
        data.players.ensureSelf("Jonas", 0) { PlayerId("jonas") }
        val phone = session(data, PeerKind.PHONE)
        signIn(data, phone, "B")
        val (match, alex) = alexScores()
        val last = alex.events.scoreEvents(match).last()
        alex.events.append(
            ScoreEvent(
                EventId("handover"),
                match,
                last.deviceId,
                last.deviceSeq + 1,
                last.lamport + 1,
                last.wallClockMs,
                last.id,
                ScorerChanged(phone.deviceId),
            ).toStoredEvent(),
        )
        pullAsMember(data, match, alex)
        val store = SessionMatchStore(phone, { clock.nowMs() }, monotonic = { scheduler.nowMs() })
        closers += store::close

        assertTrue(store.openMatches.value.any { it.matchId == match && it.isScorer })
    }
}
