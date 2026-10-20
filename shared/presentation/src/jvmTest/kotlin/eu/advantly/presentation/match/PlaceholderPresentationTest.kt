package eu.advantly.presentation.match

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.PlayerAssigned
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.CloudMetadataKind
import eu.advantly.data.MatchRecord
import eu.advantly.data.RedactionCursor
import eu.advantly.data.RedactionPage
import eu.advantly.data.RedactionTransfer
import eu.advantly.data.ReplicaAccessLevel
import eu.advantly.data.ReplicaRedactionEvent
import eu.advantly.data.ScoreSummary
import eu.advantly.data.createInMemoryJvmLocalData
import eu.advantly.data.metadataState
import eu.advantly.data.storeDownloadedMatch
import eu.advantly.data.toStoredEvent
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionConfig
import eu.advantly.presentation.stats.MatchStatistics
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceholderPresentationTest {
    @Test
    fun eventOnlyRedactionStaysMaskedInMatchSetupWithoutErasingDirectoryPlayer() {
        checkEventOnlyRedactionDuringAssignment(withDirectoryPlayer = true)
    }

    @Test
    fun eventOnlyRedactionDoesNotCreateBlankDirectoryPlayerDuringAssignment() {
        checkEventOnlyRedactionDuringAssignment(withDirectoryPlayer = false)
    }

    @Test
    fun eventOnlyRematchDoesNotInventLocalPlayerFromMaskedAssignment() {
        checkEventOnlyRedactionDuringAssignment(withDirectoryPlayer = false, repeatMatch = true)
    }

    @Test
    fun eventOnlyRematchPreservesExistingDirectoryIdentityAndRevision() {
        checkEventOnlyRedactionDuringAssignment(withDirectoryPlayer = true, repeatMatch = true)
    }

    private fun checkEventOnlyRedactionDuringAssignment(
        withDirectoryPlayer: Boolean,
        repeatMatch: Boolean = false,
    ) {
        val data = createInMemoryJvmLocalData()
        var identityIndex = 0
        data.accountIdentities.select("scope-A", "A", 0, "PHONE") { "A-${identityIndex++}" }
        val ctx = data.replicas.captureContext()
        val owner = data.players.ensureSelf("Alex", 1) { PlayerId("owner") }.id
        val slot = Placeholders.id("opponent")
        val secondSlot = Placeholders.id("second-opponent")
        val person = Player(PlayerId("living"), "Sensitive name")
        val partner = data.players.addGuest(PlayerId("partner"), "Partner", 1)
        val other = data.players.addGuest(PlayerId("other"), "Other", 1)
        val match = MatchId("redacted-match")
        val format = Presets.TENNIS_DOUBLES_MTB10.format()
        val lineup = Lineup(listOf(owner, partner.id), listOf(slot, secondSlot), owner)
        val genesis =
            ScoreEvent(EventId("genesis"), match, DeviceId("cloud"), 1, 1, 1, null, MatchCreated(format, lineup, createdBy = owner))
        val assigned =
            ScoreEvent(EventId("assigned"), match, DeviceId("cloud"), 2, 2, 2, genesis.id, PlayerAssigned(slot, person, owner))
                .toStoredEvent()
        data.replicas.applyAccess(ctx, match.value, 1, ReplicaAccessLevel.MEMBER) {}
        data.cloud.storeDownloadedMatch(
            MatchRecord(
                match,
                format,
                lineup,
                MatchMeta(),
                1,
                1,
                eu.advantly.core.scoring.MatchStatus.IN_PROGRESS,
                ScoreSummary.EMPTY,
                null,
            ),
            "A",
        )
        if (withDirectoryPlayer) data.players.addGuest(person.id, person.displayName, 1)
        data.cloud.storeDownloaded(listOf(genesis.toStoredEvent(), assigned)) {}
        val transfer = RedactionTransfer("redaction", match.value, 1, 1, 2)
        val cursor = data.replicas.beginRedaction(ctx, transfer) {}
        data.replicas.stageRedaction(
            ctx,
            transfer.id,
            RedactionPage(
                0,
                cursor,
                RedactionCursor(null, 2),
                emptyList(),
                listOf(ReplicaRedactionEvent(assigned.copy(payload = assigned.payload.replace("Sensitive name", "Gelöschter Spieler")), 2)),
                true,
                true,
            ),
        ) {}
        data.replicas.completeRedaction(ctx, transfer.id) {}
        data.replicas.markReady(ctx, match.value, 1, 1) {}
        val session = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), { 10 })
        val store = SessionMatchStore(session)
        assertEquals(
            "",
            store
                .record(match)!!
                .summary.setup.assignedPlayers[slot]!!
                .displayName,
        )
        val directoryBefore = data.players.get(person.id)
        if (withDirectoryPlayer) {
            assertEquals("Sensitive name", directoryBefore!!.displayName)
        } else {
            assertNull(directoryBefore)
        }
        val beforeAssignment = data.cloud.metadataState(CloudMetadataKind.PLAYER, person.id.value)
        if (repeatMatch) {
            store.start(store.record(match)!!.summary.setup)
        } else {
            assertTrue(store.assignPlayer(match, secondSlot, other.id))
        }
        assertEquals(directoryBefore?.displayName, data.players.get(person.id)?.displayName)
        assertEquals(beforeAssignment, data.cloud.metadataState(CloudMetadataKind.PLAYER, person.id.value))
        store.close()
    }

    @Test
    fun eventOnlyLegacySlotRemainsEditableAfterReleaseAndNeverBecomesARecentPerson() {
        val data = createInMemoryJvmLocalData()
        val owner = data.players.ensureSelf("Alex", 1) { PlayerId("owner") }.id
        val slot = PlayerId("old-watch-slot")
        val person = data.players.addGuest(PlayerId("bea"), "Bea", 1).toPlayer()
        val match = MatchId("legacy-match")
        val format = Presets.BADMINTON_1GAME_15.format()
        val lineup = Lineup(listOf(owner), listOf(slot), owner)
        val genesis =
            ScoreEvent(
                EventId("genesis"),
                match,
                DeviceId("watch"),
                1,
                1,
                1,
                null,
                MatchCreated(format, lineup, createdBy = owner),
            )
        val assigned =
            ScoreEvent(
                EventId("assigned"),
                match,
                DeviceId("watch"),
                2,
                2,
                2,
                genesis.id,
                PlayerAssigned(slot, person, owner, legacySlot = true),
            )
        data.events.appendAll(listOf(assigned, genesis).map { it.toStoredEvent() })
        data.matches.insertIfAbsent(match, format, lineup, MatchMeta(), 1)
        val session = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), { 10 })
        val store = SessionMatchStore(session)
        assertTrue(store.assignPlayer(match, slot, null))
        assertTrue(
            store
                .record(match)!!
                .summary.setup
                .player(slot)!!
                .isPlaceholder,
        )
        assertTrue(store.recentPlayers(Sport.BADMINTON).isEmpty())
        assertTrue(store.assignPlayer(match, slot, person.id))
        assertEquals(
            person.id,
            store
                .record(match)!!
                .summary.setup.assignedPlayers[slot]
                ?.id,
        )
        store.close()
    }

    @Test
    fun assignmentUpdatesLiveHistoryStatisticsRecentsAndRepeatWithoutChangingDuration() {
        val data = createInMemoryJvmLocalData()
        val owner = data.players.ensureSelf("Alex", 1) { PlayerId("me") }.id
        var now = 1_000L
        val session = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), { now })
        val store = SessionMatchStore(session, WallClock { now })
        val slot = Placeholders.id("opponent")
        val id = session.create(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(owner), listOf(slot), slot))
        assertTrue(
            store.active.value!!
                .setup
                .player(slot)!!
                .isPlaceholder,
        )
        assertTrue(store.guests.value.isEmpty())
        assertTrue(store.recentPlayers(Sport.BADMINTON).isEmpty())
        repeat(15) {
            now += 1_000
            session.recordPoint(Side.B)
        }
        val original = store.record(id)!!
        assertTrue(MatchStatistics.compute(original).servers.isEmpty())
        val withoutNames = original.copy(summary = original.summary.copy(setup = original.summary.setup.copy(players = emptyList())))
        assertTrue(MatchStatistics.compute(withoutNames).servers.isEmpty())
        val person = store.addGuest("Bea")
        now += 1_000_000
        assertTrue(store.assignPlayer(id, slot, person.id))
        val assigned = store.record(id)!!
        assertEquals(original.finalState, assigned.finalState)
        assertEquals(original.summary.durationMs, assigned.summary.durationMs)
        assertEquals(MatchStatistics.compute(original).netDurationMs, MatchStatistics.compute(assigned).netDurationMs)
        assertEquals(
            "Bea",
            store.active.value!!
                .setup
                .player(slot)
                ?.displayName,
        )
        assertEquals(
            "Bea",
            store.matches.value
                .single()
                .setup
                .player(slot)
                ?.displayName,
        )
        assertEquals(listOf(person), store.recentPlayers(Sport.BADMINTON))
        assertEquals(
            person.id,
            MatchStatistics
                .compute(assigned)
                .servers
                .single()
                .player,
        )
        assertFalse(store.guests.value.any { it.isPlaceholder })
        val next = store.start(assigned.summary.setup)
        assertEquals(
            listOf(person.id),
            data.matches
                .get(next)!!
                .lineup.b,
        )
        store.close()
        val reopened = SessionMatchStore(session, WallClock { now })
        assertEquals(
            "Bea",
            reopened
                .record(id)!!
                .summary.setup
                .player(slot)
                ?.displayName,
        )
        reopened.close()
    }
}
