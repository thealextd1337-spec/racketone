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
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.CloudPullMatch
import eu.advantly.data.CloudPullMetadataPage
import eu.advantly.data.MatchRecord
import eu.advantly.data.ScoreSummary
import eu.advantly.data.StoredEvent
import eu.advantly.data.scoreEvents
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.LocalMatchStamp
import eu.advantly.sync.protocol.MatchHeader
import eu.advantly.sync.protocol.MatchMetaMessage
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.ProtocolVersion
import eu.advantly.sync.protocol.SyncCodec
import kotlin.test.AfterTest
import kotlin.test.Ignore
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
     * P3-C: Ändert ein zweites eigenes Gerät die Teilnehmer, fehlt dem Handy danach der Beleg für den eigenen Kopf und der
     * Pull bleibt BLOCKED. Variante (a): nur Genesis-Quelle und das Handy ihrer eigenen Uhr sind Teilnehmerautorität.
     */
    @Test
    fun participantAuthorityStaysWithTheGenesisSourceAndItsPhone() {
        val pair = managed()
        val phoneMatch = pair.phone.create(format, pair.selfLineup())
        pair.settle()
        pair.watchData.players.addGuest(guest, "Bea", 0)
        assertFalse(pair.watch.assignPlayer(phoneMatch, slot, guest), "Uhr im Handy-Match")
        assertEquals(PlayerAssignmentFailure.NOT_OWNER, pair.watch.assignmentFailure(phoneMatch, slot, guest))
        pair.phoneData.players.addGuest(guest, "Bea", 0)
        assertTrue(pair.phone.assignPlayer(phoneMatch, slot, guest))

        pair.watch.closeMatch()
        val watchSelf =
            pair.watchData.players
                .self()!!
                .id
        val watchMatch = pair.watch.create(format, Lineup(listOf(watchSelf), listOf(slot), watchSelf))
        pair.settle()
        assertTrue(pair.watch.canAssignPlayer(watchMatch), "Genesis-Quelle")

        // Match eines zweiten eigenen Handys, per Pull materialisiert: Fremdquelle
        val foreign = foreignOwnMatch(pair)
        assertTrue(pair.phoneData.matchCreator(foreign)?.let(pair.phoneData::isSelf) == true, "Ersteller ist der Besitzer selbst")
        assertFalse(pair.phone.canAssignPlayer(foreign), "zweites eigenes Gerät ist keine Teilnehmerautorität")
    }

    /**
     * Handy der eigenen Uhr ist Teilnehmerautorität für deren Matches. Am aktivierten Handy kommt ein auf der Uhr
     * angelegtes Match heute nicht an (Kopf vor Genesis → UNKNOWN, Genesis einer Fremdquelle abgewiesen; auch Protokoll 3).
     */
    @Ignore("wartet auf Data: Uhr-Match am aktivierten Handy (Bericht, Anfrage an Integrator)")
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

    /** Match eines anderen eigenen Handys („phone-b“, gleicher Besitzer) über Katalog und Event-Download am Handy. */
    private fun foreignOwnMatch(pair: LifecyclePair): MatchId {
        val data = pair.phoneData
        val match = MatchId("0199c82c-0000-7000-8000-00000000b001")
        val lineup = pair.selfLineup()
        val record = MatchRecord(match, format, lineup, MatchMeta(), 1, 1, MatchStatus.IN_PROGRESS, ScoreSummary.EMPTY, null)
        val run = data.cloudPulls.beginRun("alice") {}.runSerial
        val page =
            CloudPullMetadataPage(
                listOf(CloudPullMatch(match.value, 1, "{}", "member", 1, 0, false, record)),
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
                EventId("b-genesis"),
                match,
                DeviceId("phone-b"),
                1,
                1,
                1,
                null,
                MatchCreated(format, lineup, createdBy = lineup.a.first()),
            )
        data.cloud.storeDownloaded(listOf(genesis.stored()))
        return match
    }

    private fun ScoreEvent.stored() =
        StoredEvent(
            id.value,
            matchId.value,
            deviceId.value,
            deviceSeq,
            lamport,
            SyncCodec.json.encodeToString(EventPayload.serializer(), payload),
            wallClockMs,
            parentId?.value,
            "MatchCreated",
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
        val forged =
            MatchMetaMessage(players = listOf(Player(guest, "Mallory")), recentPlayers = listOf(Player(PlayerId("new-guest"), "Eve")))
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
