package eu.advantly.data

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScoringEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceholderMigrationTest {
    @Test
    fun legacySlotsMigrateIdempotentlyWithoutRewritingLogsOrOrdinaryGuests() {
        for (names in listOf(listOf("Partner", "Gegner 1", "Gegner 2"), listOf("Partner", "Opponent 1", "Opponent 2"))) {
            val data = LocalData(createTestDriver())
            val slots = listOf("PARTNER", "OPPONENT_1", "OPPONENT_2")
            val ids = slots.map(::PlayerId)
            slots.forEachIndexed { index, slot ->
                data.players.addGuest(ids[index], names[index], 1)
                data.settings.put("wear.guest.$slot", ids[index].value)
            }
            val realPartner = data.players.addGuest(PlayerId("real"), "Partner", 1)
            val lineup = Lineup(listOf(PlayerId("owner")), listOf(ids[1]), PlayerId("owner"))
            val genesis =
                ScoreEvent(
                    EventId("g"),
                    MatchId("m"),
                    DeviceId("watch"),
                    1,
                    1,
                    1,
                    null,
                    MatchCreated(Presets.TENNIS_SINGLES_BO3.format(), lineup),
                )
            data.events.append(genesis.toStoredEvent())
            val before = ScoringEngine.fold(data.events.scoreEvents(genesis.matchId))
            repeat(2) { data.migrateWatchPlaceholders() }
            assertEquals(ids.toSet(), data.players.placeholderIds())
            assertEquals(listOf(realPartner), data.players.all())
            assertEquals(listOf(genesis), data.events.scoreEvents(genesis.matchId))
            assertEquals(before, ScoringEngine.fold(data.events.scoreEvents(genesis.matchId)))
            slots.forEach { assertNull(data.settings.get("wear.guest.$it")) }
            data.players.upsertRemote(Player(ids[1], names[1]), 2)
            assertTrue(data.isPlaceholder(ids[1]))
            assertTrue(data.players.all().none { it.id == ids[1] })
        }
    }

    @Test
    fun renamedLegacyGuestRemainsAPerson() {
        val data = LocalData(createTestDriver())
        val id = PlayerId("renamed")
        data.players.addGuest(id, "Bea", 1)
        data.settings.put("wear.guest.OPPONENT_1", id.value)
        data.migrateWatchPlaceholders()
        assertEquals(PlayerKind.GUEST, data.players.get(id)?.kind)
        assertNull(data.settings.get("wear.guest.OPPONENT_1"))
    }
}
