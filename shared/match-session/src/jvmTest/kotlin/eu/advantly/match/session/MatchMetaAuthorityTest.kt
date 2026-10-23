package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.PlayerAssigned
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.CloudPullEntityKind
import eu.advantly.data.CloudPullMatch
import eu.advantly.data.CloudPullMetadataPage
import eu.advantly.data.CloudPullReadinessResult
import eu.advantly.data.CloudPullReceipt
import eu.advantly.data.CloudPullWatermark
import eu.advantly.data.MatchRecord
import eu.advantly.data.ScoreSummary
import eu.advantly.data.StoredEvent
import eu.advantly.data.assignments
import eu.advantly.data.scoreEvents
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.LocalMatchStamp
import eu.advantly.sync.protocol.MatchHeader
import eu.advantly.sync.protocol.MatchMetaMessage
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.ProtocolVersion
import eu.advantly.sync.protocol.SyncCodec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MatchMeta-Quellautorität (P3-3, Vertrag W3 §5) und Teilnehmerautorität (P3-C, E6 Variante a). */
class MatchMetaAuthorityTest {
    private var pair: LifecyclePair? = null

    @AfterTest
    fun tearDown() {
        pair?.close()
    }

    private val format = Presets.TENNIS_SINGLES_BO3.format()
    private val slot = Placeholders.id("opponent")
    private val guest = PlayerId("guest-bea")

    private fun managed(versions: IntRange = 3..4): LifecyclePair =
        LifecyclePair(versions).also {
            pair = it
            it.activate("alice")
        }

    private fun LifecyclePair.selfLineup(): Lineup {
        val self = phoneData.players.self()!!.id
        return Lineup(listOf(self), listOf(slot), self)
    }

    /**
     * P3-C, Variante (a) gelockert (W3 Runde 3): Teilnehmerautorität sind die Genesis-Quelle und jedes eigene Handy desselben
     * Kontos. Eine Uhr im Handy-Match und fremde Konten bleiben ausgeschlossen; der Belegverlust am Kopf der Genesis-Quelle
     * heilt mit deren nächstem Upload ([aSecondOwnPhoneAssigningTakesTheOwnHeaderProofUntilTheOwnUpload]).
     */
    @Test
    fun participantAuthorityIsTheGenesisSourceOrAnOwnPhone() {
        val pair = managed()
        val phoneMatch = pair.phone.create(format, pair.selfLineup())
        pair.settle()
        pair.watchData.players.addGuest(guest, "Bea", 0)
        assertFalse(pair.watch.assignPlayer(phoneMatch, slot, guest), "Uhr im Handy-Match")
        assertEquals(PlayerAssignmentFailure.NOT_OWNER, pair.watch.assignmentFailure(phoneMatch, slot, guest))
        pair.phoneData.players.addGuest(guest, "Bea", 0)
        assertTrue(pair.phone.assignPlayer(phoneMatch, slot, guest))
        val assigned =
            pair.phoneData.events
                .scoreEvents(phoneMatch)
                .map { it.payload }
                .filterIsInstance<PlayerAssigned>()
                .single()
        assertEquals(PlayerAssigned.reference(slot, guest, assigned.assignedBy), assigned, "mit Konto und V4 namenfrei")
        assertEquals("Bea", pair.phoneData.assignments(phoneMatch)[slot]?.displayName, message = "Name kommt aus dem Verzeichnis")

        pair.watch.closeMatch()
        val watchSelf =
            pair.watchData.players
                .self()!!
                .id
        val watchMatch = pair.watch.create(format, Lineup(listOf(watchSelf), listOf(slot), watchSelf))
        pair.settle()
        assertTrue(pair.watch.canAssignPlayer(watchMatch), "Genesis-Quelle")

        // Match eines zweiten eigenen Handys, per Pull materialisiert: dieses Handy desselben Kontos darf zuweisen
        val foreign = foreignOwnMatch(pair)
        assertTrue(pair.phoneData.matchCreator(foreign)?.let(pair.phoneData::isSelf) == true, "Ersteller ist der Besitzer selbst")
        assertTrue(pair.phone.canAssignPlayer(foreign), "eigenes Handy desselben Kontos")

        // Match eines fremden Kontos (geteilt, MEMBER): nie
        val bob = PlayerId("bob-self")
        val shared =
            foreignOwnMatch(
                pair,
                MatchId("0199c82c-0000-7000-8000-00000000b002"),
                Lineup(listOf(bob), listOf(slot), bob),
                serverCreator = "bob",
            )
        assertFalse(pair.phone.canAssignPlayer(shared), "fremdes Konto")
        assertEquals(PlayerAssignmentFailure.NOT_OWNER, pair.phone.assignmentFailure(shared, slot, guest))
    }

    /**
     * P2-2 (Review r2, Probe F): Die Kontobindung hängt am Match (eigene Genesis-Quelle oder serverseitig `created_by` =
     * eigenes Profil), nie am `createdBy` im Payload. Ein geteiltes Match eines fremden Kontos mit gefälschtem bzw. fehlendem
     * Payload-Ersteller (Alt-Payload, Besitzer vorn) bleibt gesperrt – der Server wiese die Zuweisung ab (`assignment_forbidden`),
     * und der Upload hinge dauerhaft auf BLOCKED. Ohne Server-Ersteller im Snapshot: lieber ablehnen.
     */
    @Test
    fun aForeignMatchNeverBecomesOwnByItsPayloadCreator() {
        val pair = managed()
        pair.phoneData.players.addGuest(guest, "Bea", 0)
        val self =
            pair.phoneData.players
                .self()!!
                .id
        val cases =
            mapOf(
                "gefälschtes createdBy" to (self to "bob"),
                "Alt-Payload ohne createdBy" to (null to "bob"),
                "kein Server-Ersteller" to (self to null),
            )
        cases.entries.forEachIndexed { i, (case, creators) ->
            val match =
                foreignOwnMatch(
                    pair,
                    MatchId("0199c82c-0000-7000-8000-00000000c00$i"),
                    createdBy = creators.first,
                    serverCreator = creators.second,
                )
            assertTrue(pair.phoneData.isSelf(pair.phoneData.matchCreator(match)!!), "$case: Payload zeigt auf den Besitzer")
            assertEquals(PlayerAssignmentFailure.NOT_OWNER, pair.phone.assignmentFailure(match, slot, guest), case)
            assertFalse(pair.phone.assignPlayer(match, slot, guest), case)
        }
    }

    /**
     * P3-C-Repro (E6, Data-/Kopfstand ohne Cloud-Stack): Ein zweites eigenes Handy weist im Handy-Match einen Spieler zu.
     * Sein heruntergeladenes Event macht die Teilnehmer des eigenen Kopfs ungesendet; der nächste Katalog nimmt dem Kopf den
     * Beleg (materializedVersion = null), Readiness meldet STALE_SNAPSHOT. Ein noch nicht bereites Match (hier neues G) bleibt
     * damit ausstehend, der Pull meldet BLOCKED. Mit dem integrierten Data-Stand heilt der nächste eigene Upload (U4) das;
     * dauerhaft wäre es nur, wenn der Server diesen Teilnehmer-Upload abweist (ohne Cloud-Stack nicht prüfbar).
     */
    @Test
    fun aSecondOwnPhoneAssigningTakesTheOwnHeaderProofUntilTheOwnUpload() {
        val pair = managed()
        val data = pair.phoneData
        val match = pair.phone.create(format, pair.selfLineup())
        pair.settle()
        val record = data.matches.get(match)!!
        val genesis = data.events.scoreEvents(match).first()

        fun upload() {
            val upload = data.cloudUploads.capture(pair.token!!, 100)
            data.cloudUploads.acknowledge(upload, upload.events.mapTo(mutableSetOf()) { it.id }, metadata = upload.metadataRevisions)
        }
        upload()
        val server = mutableListOf(CloudPullReceipt(genesis.stored(), 1))

        fun round(
            version: Long,
            generation: Long = 1,
        ): CloudPullReadinessResult {
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            val entry = CloudPullMatch(match.value, version, """{"version":$version}""", "member", generation, 0, false, record)
            val page = CloudPullMetadataPage(listOf(entry), emptyList(), emptyList(), version, false, generation)
            if (!data.cloudPulls.commitMetadata("alice", run, data.cloudPulls.progress("alice").cursor, page) {}) {
                assertTrue(data.cloudPulls.commitMetadata("alice", run, data.cloudPulls.progress("alice").cursor, page) {})
            }
            val work = checkNotNull(data.cloudPulls.nextEventWork("alice", run))
            val known = data.cloudPulls.receivedWatermarks(work).associate { it.deviceId to it.maxSeq }
            val missing = server.filter { it.event.deviceSeq > (known[it.event.deviceId] ?: 0) }
            data.cloudPulls.commitEvents(work, run, missing, {}, endOfStream = true)
            val current = checkNotNull(data.cloudPulls.eventWork("alice", match.value))
            val marks =
                server.groupBy { it.event.deviceId }.map { (d, l) ->
                    CloudPullWatermark(d, l.maxOf { it.event.deviceSeq }, l.size.toLong())
                }
            val target = checkNotNull(data.cloudPulls.readinessWork(current, run))
            val result = data.cloudPulls.applyReadiness(current, run, target, server.maxOf { it.serverSeq }, version, marks) {}
            data.cloudPulls.finishRound("alice", run) {}
            return result
        }

        fun proof() = data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, match.value)!!.materializedVersion
        assertEquals(CloudPullReadinessResult.READY, round(1))
        assertEquals(1, proof())

        val self = data.players.self()!!.id
        val assign =
            ScoreEvent(
                EventId("b-assign"),
                match,
                DeviceId("phone-b"),
                1,
                5,
                2,
                genesis.id,
                PlayerAssigned(slot, Player(guest, "Bea"), self),
            )
        server += CloudPullReceipt(assign.stored("PlayerAssigned"), 2)
        round(2)
        assertTrue(
            data.cloudUploads
                .capture(pair.token!!, 100)
                .metadataRevisions
                .any { it.kind.name == "PARTICIPANTS" },
            "Teilnehmer ungesendet",
        )
        assertEquals(CloudPullReadinessResult.STALE_SNAPSHOT, round(3, generation = 2), "Kopf ohne Beleg")
        assertNull(proof())
        assertEquals(1, data.cloudPulls.pendingReadiness("alice"), "neues G bleibt unbereit: Pull BLOCKED")

        upload()
        assertEquals(CloudPullReadinessResult.READY, round(4, generation = 2), "eigener Upload trägt die Teilnehmer wieder")
        assertEquals(4, proof())
        assertEquals(0, data.cloudPulls.pendingReadiness("alice"))
    }

    /**
     * Ein neu installiertes Handy (neue deviceId, Genesis der früheren Installation) weist in eigenen älteren Matches zu, mit
     * und ohne V4 (früher Nebenwirkung von (a) unter V4).
     */
    @Test
    fun aReinstalledPhoneMayAssignInOwnOlderMatches() {
        for (versions in listOf(3..3, 3..4)) {
            val pair = managed(versions)
            val older = foreignOwnMatch(pair)
            pair.phoneData.players.addGuest(guest, "Bea", 0)
            assertTrue(pair.phone.assignPlayer(older, slot, guest), "Bereich $versions")
            val assigned =
                pair.phoneData.events
                    .scoreEvents(older)
                    .single { it.payload is PlayerAssigned }
            assertEquals(MatchSessions.deviceId(pair.phoneData), assigned.deviceId)
            pair.close()
            this.pair = null
        }
    }

    /**
     * Handy der eigenen Uhr ist Teilnehmerautorität für deren Matches. Am aktivierten Handy kommt ein auf der Uhr
     * angelegtes Match heute nicht an (Kopf vor Genesis → UNKNOWN, Genesis einer Fremdquelle abgewiesen; auch Protokoll 3).
     */
    @Test
    fun aWatchCreatedMatchReachesTheActivatedPhoneWhichMayAssign() {
        val pair = managed()
        val watchSelf =
            pair.watchData.players
                .self()!!
                .id
        val watchMatch = pair.watch.create(format, Lineup(listOf(watchSelf), listOf(slot), watchSelf))
        pair.settle()
        assertEquals(1L, pair.phoneData.events.count(watchMatch.value))
        assertTrue(pair.phone.canAssignPlayer(watchMatch), "Handy der eigenen Uhr")
    }

    /**
     * P3-2 (Review r3): Eine lokal bekannte Uhr-Quelle aus einem anderen Scope (früheres Konto B) belegt kein Match des
     * aktuellen Kontos – nur `currentSources()` zählt. Sonst wiese Handy A in B-Matches zu, der Server lehnte ab, und der
     * Upload hinge dauerhaft auf BLOCKED. Gegenprobe: dieselbe Quelle im aktuellen Scope belegt das Match.
     */
    @Test
    fun aWatchSourceFromAnotherScopeNeverMakesAMatchOwn() {
        val pair = managed()
        val identities = pair.phoneData.accountIdentities
        identities.recordSource("old-watch", "old-watch-self", "WATCH", "scope-of-b", local = false)
        identities.recordSource("own-watch", "own-watch-self", "WATCH", identities.scope!!, local = false)
        assertEquals("scope-of-b", identities.sourceScope("old-watch"), "Quelle lokal bekannt")
        pair.phoneData.players.addGuest(guest, "Bea", 0)

        val otherScope =
            foreignOwnMatch(pair, MatchId("0199c82c-0000-7000-8000-00000000d001"), serverCreator = null, source = DeviceId("old-watch"))
        assertEquals(PlayerAssignmentFailure.NOT_OWNER, pair.phone.assignmentFailure(otherScope, slot, guest))
        assertFalse(pair.phone.assignPlayer(otherScope, slot, guest))

        val ownScope =
            foreignOwnMatch(pair, MatchId("0199c82c-0000-7000-8000-00000000d002"), serverCreator = null, source = DeviceId("own-watch"))
        assertTrue(pair.phone.canAssignPlayer(ownScope), "Uhr-Quelle im aktuellen Scope")
    }

    /**
     * Match eines anderen Geräts ([source], Standard „phone-b“) über Katalog und Event-Download am Handy. Im Payload steht
     * [createdBy] (Standard: erster Spieler von [lineup], hier der Besitzer selbst), im Server-Snapshot [serverCreator]
     * (`created_by`).
     */
    private fun foreignOwnMatch(
        pair: LifecyclePair,
        match: MatchId = MatchId("0199c82c-0000-7000-8000-00000000b001"),
        lineup: Lineup = pair.selfLineup(),
        createdBy: PlayerId? = lineup.a.first(),
        serverCreator: String? = "alice",
        source: DeviceId = DeviceId("phone-b"),
    ): MatchId {
        val data = pair.phoneData
        val record = MatchRecord(match, format, lineup, MatchMeta(), 1, 1, MatchStatus.IN_PROGRESS, ScoreSummary.EMPTY, null)
        val run = data.cloudPulls.beginRun("alice") {}.runSerial
        val page =
            CloudPullMetadataPage(
                listOf(
                    CloudPullMatch(match.value, 1, serverCreator?.let { "{\"createdBy\":\"$it\"}" } ?: "{}", "member", 1, 0, false, record),
                ),
                emptyList(),
                emptyList(),
                1,
                false,
                1,
            )
        assertTrue(data.cloudPulls.commitMetadata("alice", run, data.cloudPulls.progress("alice").cursor, page) {})
        data.replicas.markReady(data.replicas.captureContext(), match.value, 1, 0) {}
        val genesis =
            ScoreEvent(
                EventId("b-genesis-${match.value.takeLast(4)}"),
                match,
                source,
                1,
                1,
                1,
                null,
                MatchCreated(format, lineup, createdBy = createdBy),
            )
        data.cloud.storeDownloaded(listOf(genesis.stored()))
        return match
    }

    private fun ScoreEvent.stored(type: String = "MatchCreated") =
        StoredEvent(
            id.value,
            matchId.value,
            deviceId.value,
            deviceSeq,
            lamport,
            SyncCodec.json.encodeToString(EventPayload.serializer(), payload),
            wallClockMs,
            parentId?.value,
            type,
        )

    @Test
    fun emptyNamesNeverTravelAndNeverRename() {
        val watch = WireNode("watch", 3, kind = PeerKind.WATCH)
        watch.data.players.addGuest(guest, "Bea", 0)
        watch.link.deliver(Hello(DeviceId("lab"), PeerKind.PHONE))
        watch.link.deliver(MatchMetaMessage(players = listOf(Player(guest, "")), recentPlayers = listOf(Player(guest, ""))))
        assertEquals(
            "Bea",
            watch.data.players
                .get(guest)
                ?.displayName,
            "leerer Name ist keine Redaktion",
        )

        val phone = WireNode("phone", 4)
        phone.data.players.addGuest(guest, "", 0)
        phone.session.create(format, Lineup(listOf(PlayerId("me")), listOf(guest), PlayerId("me")))
        val sent = phone.link.sent.filterIsInstance<MatchMetaMessage>()
        assertTrue(sent.isNotEmpty())
        assertTrue(
            sent.all { meta ->
                (meta.players + meta.recentPlayers.orEmpty()).none { it.displayName.isBlank() && !it.isPlaceholder }
            },
        )
    }

    @Test
    fun withAnAccountNamesComeOnlyFromThePhone() {
        val pair = managed(ProtocolVersion.MIN_SUPPORTED..ProtocolVersion.CURRENT)
        pair.phoneData.players.addGuest(guest, "Bea", 0)
        val envelope =
            pair.watchLink
                .decoded()
                .last { it.identity != null }
                .identity!!
        // ausgehandelt ist 4: eine v3-Nachricht fiele schon an der Versionsprüfung heraus und prüfte die Namensregel nicht
        val forged =
            MatchMetaMessage(
                players = listOf(Player(guest, "Mallory")),
                recentPlayers = listOf(Player(PlayerId("new-guest"), "Eve")),
                v = 4,
            )
        pair.phoneLink.replay("watch", SyncCodec.encode(forged, envelope))
        assertEquals(
            "Bea",
            pair.phoneData.players
                .get(guest)
                ?.displayName,
        )
        assertNull(pair.phoneData.players.get(PlayerId("new-guest")))
    }

    @Test
    fun aHeaderCountsOnlyFromTheGenesisOwner() {
        val pair = managed()
        val previous = pair.phone.create(format, pair.selfLineup())
        val match = pair.phone.create(format, pair.selfLineup())
        pair.settle()
        val genesis =
            pair.phoneData.events
                .scoreEvents(match)
                .first { it.payload is MatchCreated }
        val envelope =
            pair.watchLink
                .decoded()
                .last { it.identity != null }
                .identity!!
        val scope = pair.phoneData.accountIdentities.scope!!
        val record = pair.phoneData.matches.get(match)!!
        val header = MatchHeader(match, record.format, record.lineup, record.meta, record.createdAtMs, previousMatchId = previous)
        val wrongGenesis = LocalMatchStamp(genesis.deviceId, EventId("other-genesis"), scope)
        pair.phoneLink.replay("watch", SyncCodec.encode(MatchMetaMessage(match = header, v = 4, stamp = wrongGenesis), envelope))
        val otherLineup = header.copy(lineup = Lineup(listOf(slot), listOf(record.lineup.a.first()), slot))
        val stamp = LocalMatchStamp(genesis.deviceId, genesis.id, scope)
        pair.phoneLink.replay("watch", SyncCodec.encode(MatchMetaMessage(match = otherLineup, v = 4, stamp = stamp), envelope))
        assertNull(
            pair.phoneData.matches
                .get(match)!!
                .previousMatchId,
            "nur der Kopf der Genesis gilt",
        )

        pair.phoneLink.replay("watch", SyncCodec.encode(MatchMetaMessage(match = header, v = 4, stamp = stamp), envelope))
        assertEquals(
            previous,
            pair.phoneData.matches
                .get(match)!!
                .previousMatchId,
        )
    }
}
