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
import eu.advantly.data.createInMemoryJvmLocalData
import eu.advantly.data.toStoredEvent
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionConfig
import eu.advantly.presentation.stats.MatchStatistics
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceholderPresentationTest {
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
