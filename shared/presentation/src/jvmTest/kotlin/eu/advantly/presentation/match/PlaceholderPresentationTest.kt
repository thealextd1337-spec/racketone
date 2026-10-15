package eu.advantly.presentation.match

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Presets
import eu.advantly.data.createInMemoryJvmLocalData
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
