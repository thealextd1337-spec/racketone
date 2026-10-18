package eu.advantly.data

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudMetadataSelectionRegressionTest {
    private val data = LocalData(createTestDriver())
    private val me = PlayerId("me")
    private val format = Presets.TENNIS_SINGLES_BO3.format()
    private val guest = PlayerId("guest")
    private val lineup = Lineup(listOf(me), listOf(guest), me)

    private fun initialize(): CloudUploadSession {
        data.players.ensureSelf("Alex", 0) { me }
        data.accountIdentities.initialize("phone", me.value, "PHONE")
        data.players.addGuest(guest, "Opponent", 0)
        return data.cloudUploads.activate("alice").also { data.cloudUploads.bindOwnedDevices(it, setOf("phone")) }
    }

    private fun createMatch(id: String) {
        data.events.append(
            ScoreEvent(
                EventId("$id-genesis"),
                MatchId(id),
                DeviceId("phone"),
                1,
                1,
                1,
                null,
                MatchCreated(format, lineup, createdBy = me),
            ).toStoredEvent(),
        )
        data.matches.insertIfAbsent(MatchId(id), format, lineup, MatchMeta(), 1)
        data.matches.updateProgress(MatchId(id), MatchStatus.IN_PROGRESS, ScoreSummary(eventCount = 1), 1)
    }

    private fun ack(snapshot: CloudUploadSnapshot) {
        data.cloudUploads.acknowledge(snapshot, snapshot.events.mapTo(mutableSetOf()) { it.id }, metadata = snapshot.metadataRevisions)
    }

    private fun point(
        id: String,
        sequence: Long,
    ) = StoredEvent("$id-point-$sequence", id, "phone", sequence, sequence, """{"type":"PointWon","side":"A"}""", type = "PointWon")

    @Test
    fun placeholderDoesNotHidePendingMetadataAtLimitOne() = placeholdersDoNotBlockSelection(1)

    @Test
    fun fiveHundredPlaceholdersDoNotBlockTheStandardSelectionLimit() = placeholdersDoNotBlockSelection(500)

    private fun placeholdersDoNotBlockSelection(limit: Int) {
        val session = initialize()
        val blocked = listOf(guest) + (1 until limit).map { PlayerId("blocked-$it") }
        blocked.drop(1).forEach { data.players.addGuest(it, "Opponent", 0) }
        createMatch("match")
        ack(data.cloudUploads.capture(session, 10_000))
        val receipts = blocked.associateWith { data.cloud.metadataState(CloudMetadataKind.PLAYER, it.value)!!.uploadedRevision }
        blocked.forEach { data.players.upsertRemote(Player(it, "Opponent", isPlaceholder = true), 2) }
        data.matches.updateMeta(MatchId("match"), MatchMeta(venue = "Court 2"))
        data.events.append(point("match", 2))
        data.matches.updateProgress(MatchId("match"), MatchStatus.PAUSED, ScoreSummary(eventCount = 2), 2)

        val selected = data.cloudUploads.capture(session, limit)
        assertEquals(listOf("match"), selected.headers.map { it.value.id }, "Placeholders must not consume the metadata limit")
        assertTrue(selected.players.isEmpty())
        assertTrue(selected.metadataRevisions.size <= limit && selected.events.size <= limit)
        assertEquals(listOf("match-point-2"), selected.events.map { it.id })
        val headerRevision =
            selected.headers
                .single()
                .version.revision
        data.matches.updateMeta(MatchId("match"), MatchMeta(venue = "Court 3"))
        ack(selected)
        assertEquals(headerRevision, data.cloud.metadataState(CloudMetadataKind.HEADER, "match")!!.uploadedRevision)
        assertTrue(
            data.cloud
                .metadataState(CloudMetadataKind.HEADER, "match")!!
                .selected.revision > headerRevision,
        )

        val remainingKinds = mutableSetOf<CloudMetadataKind>()
        repeat(3) {
            val next = data.cloudUploads.capture(session, limit)
            assertTrue(next.metadataRevisions.size <= limit && next.events.size <= limit)
            assertTrue(next.players.isEmpty())
            remainingKinds += next.metadataRevisions.map { it.kind }
            ack(next)
        }
        assertTrue(CloudMetadataKind.HEADER in remainingKinds)
        if (limit == 1) {
            assertTrue(CloudMetadataKind.PARTICIPANTS in remainingKinds && CloudMetadataKind.PROJECTION in remainingKinds)
        }
        assertTrue(
            data.cloudUploads
                .capture(session, limit)
                .metadataRevisions
                .isEmpty(),
        )
        blocked.forEach {
            val state = data.cloud.metadataState(CloudMetadataKind.PLAYER, it.value)!!
            assertEquals(receipts.getValue(it), state.uploadedRevision, "Skipped players must not receive a fake ack")
            assertTrue(state.selected.revision > state.uploadedRevision)
        }

        data.players.upsertRemote(Player(guest, "Beatrice"), 3)
        val restored = data.cloudUploads.capture(session, 1)
        assertEquals(listOf(guest.value), restored.players.map { it.value.id })
        assertEquals(
            "Beatrice",
            restored.players
                .single()
                .value.displayName,
        )
        assertTrue(
            restored.players
                .single()
                .version.revision > receipts.getValue(guest),
        )
        assertEquals(receipts.getValue(guest), data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value)!!.uploadedRevision)
        data.players.rename(guest, "B.")
        ack(restored)
        val newer = data.cloudUploads.capture(session, 1)
        assertEquals(
            "B.",
            newer.players
                .single()
                .value.displayName,
        )
        assertTrue(
            newer.players
                .single()
                .version.revision >
                restored.players
                    .single()
                    .version.revision,
        )
        ack(newer)
        val participant = data.cloudUploads.capture(session, 1)
        assertEquals(
            "B.",
            participant.participants
                .single()
                .value.participants
                .last()
                .displayName,
        )
        ack(participant)
        assertTrue(
            data.cloudUploads
                .capture(session, 1)
                .metadataRevisions
                .isEmpty(),
        )
    }

    @Test
    fun incompleteProjectionDoesNotConsumeLimitAheadOfAReadyProjection() {
        val session = initialize()
        createMatch("a")
        createMatch("b")
        ack(data.cloudUploads.capture(session, 100))
        data.events.appendAll(listOf(point("a", 2), point("a", 3)))
        data.matches.updateProgress(MatchId("a"), MatchStatus.PAUSED, ScoreSummary(eventCount = 3), 3)
        data.matches.updateProgress(MatchId("b"), MatchStatus.PAUSED, ScoreSummary(eventCount = 1), 3)
        val selected = data.cloudUploads.capture(session, 1)
        assertEquals(listOf("a-point-2"), selected.events.map { it.id })
        assertEquals(listOf("b"), selected.projections.map { it.value.matchId }, "An incomplete projection must not hide a ready one")
        assertEquals(setOf("b"), selected.completeEventMatches)
        ack(selected)
        val last = data.cloudUploads.capture(session, 1)
        assertEquals(listOf("a-point-3"), last.events.map { it.id })
        assertEquals(listOf("a"), last.projections.map { it.value.matchId })
        assertEquals(setOf("a"), last.completeEventMatches)
    }

    @Test
    fun rejectedEventKeepsOnlyItsProjectionBlockedWithoutAcknowledgingIt() {
        val session = initialize()
        createMatch("a")
        createMatch("b")
        ack(data.cloudUploads.capture(session, 100))
        val receipt = data.cloud.metadataState(CloudMetadataKind.PROJECTION, "a")!!.uploadedRevision
        val rejected = point("a", 2)
        data.events.append(rejected)
        data.cloud.acknowledge(emptySet(), mapOf(rejected.id to "forbidden"))
        data.matches.updateProgress(MatchId("a"), MatchStatus.PAUSED, ScoreSummary(eventCount = 2), 2)
        data.matches.updateProgress(MatchId("b"), MatchStatus.PAUSED, ScoreSummary(eventCount = 1), 2)
        val selected = data.cloudUploads.capture(session, 1)
        assertTrue(selected.events.isEmpty())
        assertEquals(listOf("b"), selected.projections.map { it.value.matchId })
        ack(selected)
        assertEquals(receipt, data.cloud.metadataState(CloudMetadataKind.PROJECTION, "a")!!.uploadedRevision)
        assertEquals(mapOf(rejected.id to "forbidden"), data.cloud.rejectedEvents())
        assertTrue(
            data.cloudUploads
                .capture(session, 1)
                .metadataRevisions
                .isEmpty(),
        )
        data.cloud.storeDownloaded(listOf(rejected))
        val ready = data.cloudUploads.capture(session, 1)
        assertEquals(listOf("a"), ready.projections.map { it.value.matchId })
        assertTrue(
            ready.projections
                .single()
                .version.revision > receipt,
        )
    }
}
