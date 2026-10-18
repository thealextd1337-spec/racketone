package eu.advantly.data

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.PlayerAssigned
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.db.AdvantlyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudMetadataReviewRegressionTest {
    private val driver = createTestDriver()
    private val data = LocalData(driver)
    private val me = PlayerId("phone-self")
    private val guest = PlayerId("guest")
    private val format = Presets.TENNIS_SINGLES_BO3.format()

    private fun phone() {
        data.players.ensureSelf("Alex", 0) { me }
        data.accountIdentities.initialize("phone", me.value, "PHONE")
    }

    private fun session(
        profile: String,
        vararg sources: String,
    ): CloudUploadSession = data.cloudUploads.activate(profile).also { data.cloudUploads.bindOwnedDevices(it, sources.toSet()) }

    private fun createMatch(
        id: String,
        source: String,
        creator: PlayerId,
        opponent: PlayerId = guest,
    ) {
        val lineup = Lineup(listOf(creator), listOf(opponent), creator)
        data.events.append(
            ScoreEvent(
                EventId("$id-genesis"),
                MatchId(id),
                DeviceId(source),
                1,
                1,
                1,
                null,
                MatchCreated(format, lineup, createdBy = creator),
            ).toStoredEvent(),
        )
        data.matches.insertIfAbsent(MatchId(id), format, lineup, MatchMeta(), 1)
    }

    private fun ack(snapshot: CloudUploadSnapshot) {
        data.cloudUploads.acknowledge(snapshot, snapshot.events.mapTo(mutableSetOf()) { it.id }, metadata = snapshot.metadataRevisions)
    }

    @Test
    fun insertingPreviouslyMissingLineupPlayerDuringUploadKeepsNewNamePending() {
        phone()
        val session = session("alice", "phone")
        createMatch("match", "phone", me)
        val selected = data.cloudUploads.capture(session, 100)
        val old = selected.participants.single()
        assertEquals(
            "",
            old.value.participants
                .last()
                .displayName,
        )
        data.players.upsertRemote(Player(guest, "Beatrice"), 2)
        ack(selected)
        val state = data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")!!
        assertTrue(state.selected.revision > old.version.revision, "Player insert must advance the participant revision")
        assertEquals(old.version.revision, state.uploadedRevision)
        val pending =
            data.cloudUploads
                .capture(session, 100)
                .participants
                .single()
        assertEquals(
            "Beatrice",
            pending.value.participants
                .last()
                .displayName,
        )
        assertEquals(
            "",
            old.value.participants
                .last()
                .displayName,
        )
    }

    @Test
    fun insertingPreviouslyMissingAssignedPlayerDuringUploadKeepsNewNamePending() {
        phone()
        val session = session("alice", "phone")
        createMatch("match", "phone", me)
        val assigned = PlayerAssigned(guest, Player(PlayerId("assigned"), "Snapshot name"), me, legacySlot = true)
        data.events.append(
            ScoreEvent(
                EventId("assignment"),
                MatchId("match"),
                DeviceId("phone"),
                2,
                2,
                2,
                EventId("match-genesis"),
                assigned,
            ).toStoredEvent(),
        )
        val selected = data.cloudUploads.capture(session, 100)
        val old = selected.participants.single()
        assertEquals(
            "Snapshot name",
            old.value.participants
                .last()
                .displayName,
        )
        data.players.upsertRemote(Player(PlayerId("assigned"), "Beatrice"), 3)
        ack(selected)
        val state = data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")!!
        assertTrue(state.selected.revision > old.version.revision, "Assigned player insert must advance the participant revision")
        assertEquals(old.version.revision, state.uploadedRevision)
        assertEquals(
            "Beatrice",
            data.cloudUploads
                .capture(session, 100)
                .participants
                .single()
                .value.participants
                .last()
                .displayName,
        )
    }

    @Test
    fun adoptedWatchGuestsUseSnapshotNamesWithoutInventingPlayerOwnership() {
        phone()
        val watchSelf = PlayerId("watch-self")
        data.accountIdentities.recordSource("watch", watchSelf.value, "WATCH", "old-watch", local = false)
        data.players.upsertRemote(Player(watchSelf, "Alex"), 0)
        data.players.upsertRemote(Player(guest, "Beatrice"), 0)
        createMatch("watch-match", "watch", watchSelf)
        val session = session("alice", "watch")
        val selected = data.cloudUploads.capture(session, 100)
        val person =
            selected.participants
                .single()
                .value.participants
                .last()
        assertEquals("Beatrice", person.displayName)
        assertEquals(guest.value, person.slotId)
        assertNull(person.playerId, "An imported guest must not reference an unowned cloud player")
        assertFalse(selected.players.any { it.value.id == guest.value })
        assertEquals(CloudMetadataOrigin.UNKNOWN, data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value)?.origin)
        ack(selected)
        assertTrue(
            data.cloudUploads
                .capture(session, 100)
                .participants
                .isEmpty(),
        )
    }

    @Test
    fun participantPlayerReferencesRequireAuthorityForTheCurrentProfile() {
        phone()
        val session = session("alice", "phone")
        data.players.addGuest(PlayerId("local"), "Local", 0)
        data.cloud.storeDownloadedPlayer(LocalPlayer(PlayerId("own-cloud"), "Own", PlayerKind.GUEST, 0, null), "alice", owned = true)
        data.cloud.storeDownloadedPlayer(LocalPlayer(PlayerId("foreign"), "Foreign", PlayerKind.GUEST, 0, null), "bob", owned = true)
        listOf("local", "own-cloud", "foreign", "missing").forEach { createMatch(it, "phone", me, PlayerId(it)) }
        val people =
            data.cloudUploads
                .capture(session, 100)
                .participants
                .associate { it.value.matchId to it.value.participants.last() }
        assertEquals("local", people.getValue("local").playerId)
        assertEquals("own-cloud", people.getValue("own-cloud").playerId)
        assertNull(people.getValue("foreign").playerId)
        assertEquals("Foreign", people.getValue("foreign").displayName)
        assertNull(people.getValue("missing").playerId)
    }

    @Test
    fun bindingVerifiedPlayerSourceDuringUploadKeepsNewReferencePending() {
        phone()
        val alias = PlayerId("watch-self")
        data.accountIdentities.recordSource("watch", alias.value, "WATCH", "old-watch", local = false)
        data.players.upsertRemote(Player(alias, "Alex"), 0)
        val session = session("alice", "phone")
        createMatch("match", "phone", me, alias)
        val selected = data.cloudUploads.capture(session, 100)
        assertNull(
            selected.participants
                .single()
                .value.participants
                .last()
                .playerId,
        )
        data.cloudUploads.bindOwnedDevices(session, setOf("watch"))
        ack(selected)
        val pending = data.cloudUploads.capture(session, 100).participants
        assertEquals(1, pending.size, "Newly authorized reference must survive an old participant ack")
        assertEquals(
            alias.value,
            pending
                .single()
                .value.participants
                .last()
                .playerId,
        )
        assertTrue(
            pending.single().version.revision >
                selected.participants
                    .single()
                    .version.revision,
        )
    }

    @Test
    fun verifyingAlreadyBoundPlayerSourceAfterUploadKeepsNewReferencePending() {
        phone()
        val alias = PlayerId("watch-self")
        data.players.upsertRemote(Player(alias, "Alex"), 0)
        val session = session("alice", "phone", "watch")
        createMatch("match", "phone", me, alias)
        val selected = data.cloudUploads.capture(session, 100)
        assertNull(
            selected.participants
                .single()
                .value.participants
                .last()
                .playerId,
        )
        data.accountIdentities.recordSource("watch", alias.value, "WATCH", "old-watch", local = false)
        ack(selected)
        val pending = data.cloudUploads.capture(session, 100).participants
        assertEquals(1, pending.size, "A later provenance proof must produce a new participant revision")
        assertEquals(
            alias.value,
            pending
                .single()
                .value.participants
                .last()
                .playerId,
        )
        assertTrue(
            pending.single().version.revision >
                selected.participants
                    .single()
                    .version.revision,
        )
    }

    @Test
    fun watchGenesisWithAdoptedPhoneSelfRequiresBothVerifiedSourcesToHaveTheSameOwner() {
        phone()
        data.accountIdentities.recordSource("watch", "historical-watch-self", "WATCH", "old-watch", local = false)
        val session = session("alice", "watch")
        createMatch("watch-match", "watch", me)
        val beforeBindingAlias = data.cloudUploads.capture(session, 100)
        assertEquals(listOf("watch-match-genesis"), beforeBindingAlias.events.map { it.id })
        assertTrue(beforeBindingAlias.headers.isEmpty())
        data.cloudUploads.bindOwnedDevices(session, setOf("phone"))
        val selected = data.cloudUploads.capture(session, 100)
        assertEquals(listOf("watch-match"), selected.headers.map { it.value.id })
        assertEquals(1, selected.participants.size)
        assertEquals(1, selected.projections.size)
        assertEquals(CloudMetadataOrigin.PEER, data.cloud.metadataState(CloudMetadataKind.HEADER, "watch-match")?.origin)
        assertEquals("watch", data.cloud.metadataState(CloudMetadataKind.HEADER, "watch-match")?.sourceDeviceId)
        assertEquals(
            "historical-watch-self",
            AdvantlyDatabase(driver)
                .localIdentityQueries
                .source("watch")
                .executeAsOne()
                .self_id,
        )
        assertEquals(beforeBindingAlias.events.single().payload, selected.events.single().payload)
    }

    @Test
    fun foreignAndClaimedSelfAliasesCannotAuthorizeMatchMetadata() {
        phone()
        session("bob", "phone")
        data.accountIdentities.recordSource("watch", "historical-watch-self", "WATCH", "old-watch", local = false)
        val session = session("alice", "watch", "unverified")
        val claimed = PlayerId("claimed-self")
        data.addSelfAliases(listOf(claimed))
        createMatch("foreign-alias", "watch", me)
        createMatch("claimed-alias", "watch", claimed)
        createMatch("unverified-source", "unverified", me)
        val selected = data.cloudUploads.capture(session, 100)
        assertEquals(3, selected.events.size)
        assertTrue(selected.headers.isEmpty() && selected.participants.isEmpty() && selected.projections.isEmpty())
    }
}
