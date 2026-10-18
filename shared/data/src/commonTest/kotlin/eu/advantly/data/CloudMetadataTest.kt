package eu.advantly.data

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.PlayerAssigned
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudMetadataTest {
    private val data = LocalData(createTestDriver())
    private val id = MatchId("match")
    private val me = PlayerId("me")
    private val guest = PlayerId("guest")
    private val format = Presets.TENNIS_SINGLES_BO3.format()
    private val lineup = Lineup(listOf(me), listOf(guest), me)

    private fun setup(): CloudUploadSession {
        data.players.ensureSelf("Alex", 0) { me }
        data.accountIdentities.initialize("phone", me.value, "PHONE")
        data.players.addGuest(guest, "Bea", 0)
        val session = data.cloudUploads.activate("alice")
        data.cloudUploads.bindOwnedDevices(session, setOf("phone"))
        data.events.append(
            ScoreEvent(
                EventId("genesis"),
                id,
                DeviceId("phone"),
                1,
                1,
                1,
                null,
                MatchCreated(format, lineup, createdBy = me),
            ).toStoredEvent(),
        )
        data.matches.insertIfAbsent(id, format, lineup, MatchMeta(), 1)
        return session
    }

    private fun ack(snapshot: CloudUploadSnapshot) =
        data.cloudUploads.acknowledge(snapshot, snapshot.events.mapTo(mutableSetOf()) { it.id }, metadata = snapshot.metadataRevisions)

    @Test
    fun delayedAckConfirmsOnlySelectedHeaderParticipantsPlayersAndProjection() {
        val session = setup()
        val selected = data.cloudUploads.capture(session, 100)
        val payloads = data.events.eventsFor(id.value)
        data.matches.updateMeta(id, MatchMeta(venue = "Court 2"))
        data.players.rename(guest, "Beatrice")
        data.matches.updateProgress(id, MatchStatus.PAUSED, ScoreSummary(setsWonA = 1, eventCount = 2), 20)
        val assigned = PlayerAssigned(guest, Player(PlayerId("replacement"), "Cem"), me, legacySlot = true)
        data.events.append(ScoreEvent(EventId("assigned"), id, DeviceId("phone"), 2, 2, 20, EventId("genesis"), assigned).toStoredEvent())
        ack(selected)
        assertEquals(
            "Bea",
            selected.players
                .single { it.value.id == guest.value }
                .value.displayName,
        )
        assertEquals(
            "Bea",
            selected.participants
                .single()
                .value.participants
                .last()
                .displayName,
        )
        val pending = data.cloudUploads.capture(session, 100)
        assertEquals(listOf("assigned"), pending.events.map { it.id })
        val changed = pending.metadataRevisions.associateBy { it.kind to it.id }
        selected.metadataRevisions.filterNot { it.kind == CloudMetadataKind.PLAYER && it.id == me.value }.forEach {
            assertTrue(changed.getValue(it.kind to it.id).revision > it.revision)
            assertEquals(it.revision, data.cloud.metadataState(it.kind, it.id)?.uploadedRevision)
        }
        assertEquals(
            "replacement",
            pending.participants
                .single()
                .value.participants
                .last()
                .playerId,
        )
        assertEquals(
            payloads.single().payload,
            data.events
                .eventsFor(id.value)
                .first()
                .payload,
        )
        assertEquals(2, data.events.unconfirmedCount())
        ack(pending)
        assertTrue(
            data.cloudUploads
                .capture(session, 100)
                .metadataRevisions
                .isEmpty(),
        )
    }

    @Test
    fun metadataOnlyChangeIsSelectedWithoutEventsAndNoOpDoesNotMakeNewRevision() {
        val session = setup()
        ack(data.cloudUploads.capture(session, 100))
        data.matches.updateMeta(id, MatchMeta(venue = "Halle"))
        val selected = data.cloudUploads.capture(session, 100)
        assertTrue(selected.events.isEmpty())
        assertEquals(setOf(CloudMetadataKind.HEADER), selected.metadataRevisions.map { it.kind }.toSet())
        data.matches.updateMeta(id, MatchMeta(venue = "Halle"))
        data.rebuildAssignments(id)
        data.players.markUsed(listOf(guest), 10)
        ack(selected)
        assertTrue(
            data.cloudUploads
                .capture(session, 100)
                .metadataRevisions
                .isEmpty(),
        )
    }

    @Test
    fun downloadedForeignMetadataNeverBecomesWritableThroughOwnEventsOrLocalEdits() {
        val session = setup()
        ack(data.cloudUploads.capture(session, 100))
        val foreign = MatchId("foreign")
        data.cloud.storeDownloadedMatch(data.matches.get(id)!!.copy(id = foreign), "alice")
        val player = LocalPlayer(PlayerId("foreign-player"), "Dana", PlayerKind.GUEST, 0, null)
        data.cloud.storeDownloadedPlayer(player, "alice")
        data.matches.updateMeta(foreign, MatchMeta(venue = "Local edit"))
        data.matches.updateProgress(foreign, MatchStatus.PAUSED, ScoreSummary(setsWonB = 1), 20)
        data.players.rename(player.id, "Changed")
        data.events.append(
            ScoreEvent(
                EventId("own-foreign-genesis"),
                foreign,
                DeviceId("phone"),
                1,
                1,
                1,
                null,
                MatchCreated(format, lineup, createdBy = me),
            ).toStoredEvent(),
        )
        val selected = data.cloudUploads.capture(session, 100)
        assertEquals(listOf("own-foreign-genesis"), selected.events.map { it.id })
        assertTrue(selected.metadataRevisions.isEmpty())
        assertEquals(CloudMetadataOrigin.CLOUD, data.cloud.metadataState(CloudMetadataKind.HEADER, foreign.value)?.origin)
        assertEquals(CloudMetadataOrigin.CLOUD, data.cloud.metadataState(CloudMetadataKind.PLAYER, player.id.value)?.origin)
    }

    @Test
    fun ownedDeviceDoesNotGrantForeignCreatorHeaderRights() {
        val session = setup()
        val foreign = MatchId("foreign")
        data.events.append(
            ScoreEvent(
                EventId("other"),
                foreign,
                DeviceId("phone"),
                1,
                1,
                1,
                null,
                MatchCreated(format, lineup, createdBy = PlayerId("someone-else")),
            ).toStoredEvent(),
        )
        data.matches.insertIfAbsent(foreign, format, lineup, MatchMeta(), 1)
        assertFalse(
            data.cloudUploads
                .capture(session, 100)
                .metadataRevisions
                .any { it.id == foreign.value },
        )
    }

    @Test
    fun explicitDownloadPermissionsAllowProjectionWithoutGrantingHeaderAndParticipantWrites() {
        val session = setup()
        ack(data.cloudUploads.capture(session, 100))
        val foreign = MatchId("foreign")
        data.cloud.storeDownloadedMatch(data.matches.get(id)!!.copy(id = foreign), "alice", setOf(CloudMetadataKind.PROJECTION))
        data.matches.updateMeta(foreign, MatchMeta(venue = "Local edit"))
        data.matches.updateProgress(foreign, MatchStatus.PAUSED, ScoreSummary(setsWonB = 1), 20)
        val owned = LocalPlayer(PlayerId("owned-guest"), "Dana", PlayerKind.GUEST, 0, null)
        data.cloud.storeDownloadedPlayer(owned, "alice", owned = true)
        data.players.rename(owned.id, "Dani")
        val snapshot = data.cloudUploads.capture(session, 100)
        assertEquals(setOf(CloudMetadataKind.PROJECTION, CloudMetadataKind.PLAYER), snapshot.metadataRevisions.map { it.kind }.toSet())
        assertEquals(
            "Dani",
            snapshot.players
                .single()
                .value.displayName,
        )
        val bob = data.cloudUploads.activate("bob")
        assertTrue(
            data.cloudUploads
                .capture(bob, 100)
                .metadataRevisions
                .isEmpty(),
        )
    }

    @Test
    fun metadataResponsesCannotWidenSnapshotOrSurviveSessionChanges() {
        val session = setup()
        val selected = data.cloudUploads.capture(session, 100)
        val invented =
            selected.headers
                .single()
                .version
                .copy(revision = 999)
        assertFailsWith<IllegalArgumentException> { data.cloudUploads.acknowledge(selected, setOf("genesis"), metadata = setOf(invented)) }
        assertEquals(1, data.cloud.pendingCount())
        val bob = data.cloudUploads.activate("bob")
        assertTrue(
            data.cloudUploads
                .capture(bob, 100)
                .metadataRevisions
                .isEmpty(),
        )
        assertFailsWith<IllegalStateException> { ack(selected) }
        val alice = data.cloudUploads.activate("alice")
        assertEquals(selected.metadataRevisions, data.cloudUploads.capture(alice, 100).metadataRevisions)
        data.cloudUploads.deactivate()
        assertFailsWith<IllegalStateException> { ack(selected) }
    }

    @Test
    fun independentlyAcknowledgedProjectionDoesNotConfirmHeaderOrEvents() {
        val session = setup()
        val selected = data.cloudUploads.capture(session, 100)
        data.cloudUploads.acknowledge(selected, emptySet(), metadata = selected.projections.mapTo(mutableSetOf()) { it.version })
        assertEquals(1, data.cloud.pendingCount())
        assertEquals(1, data.events.unconfirmedCount())
        val pending = data.cloudUploads.capture(session, 100)
        assertTrue(pending.projections.isEmpty())
        assertTrue(pending.headers.isNotEmpty())
    }
}
