package eu.advantly.data

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.Presets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArchivedGuestTest {
    @Test
    fun archivePreservesHistoricalNamesAndCannotBeResurrectedByRemoteMetadata() {
        val data = LocalData(createTestDriver())
        val self = data.players.ensureSelf("Alex", 1) { PlayerId("self") }
        val guest = data.players.addGuest(PlayerId("guest"), "Sam", 1)
        val slot = PlayerId("legacy-slot")
        data.players.markPlaceholder(slot, 1)
        val lineup = Lineup(listOf(self.id), listOf(guest.id), guest.id)
        data.matches.insertIfAbsent(MatchId("old"), Presets.BADMINTON_1GAME_15.format(), lineup, MatchMeta(), 1)
        assertEquals(listOf(guest), data.recentPeople())
        assertTrue(data.players.deleteGuest(guest.id))
        assertFalse(data.players.deleteGuest(guest.id))
        assertFalse(data.players.deleteGuest(self.id))
        assertFalse(data.players.deleteGuest(slot))
        assertFalse(data.players.deleteGuest(Placeholders.id("slot")))
        data.players.upsertRemote(Player(guest.id, "Stale name"), 2)
        data.players.markUsed(listOf(guest.id), 3)
        assertEquals(PlayerKind.ARCHIVED, data.players.get(guest.id)?.kind)
        assertEquals("Sam", data.players.get(guest.id)?.displayName)
        assertEquals(
            guest.id,
            data.matches
                .get(MatchId("old"))
                ?.lineup
                ?.b
                ?.single(),
        )
        assertTrue(data.players.all().none { it.id == guest.id })
        assertTrue(data.recentPeople().isEmpty())
        val replacement = data.players.addGuest(PlayerId("new"), "Sam", 4)
        assertNotEquals(guest.id, replacement.id)
        assertEquals(PlayerKind.GUEST, replacement.kind)
        val fresh = data.freshPlaceholders(lineup) { PlayerId("fresh") }
        assertTrue(data.isPlaceholder(fresh.b.single()))
        assertEquals(fresh.b.single(), fresh.firstServer)
    }

    @Test
    fun remoteArchiveArrivingBeforeNameKeepsTheNameResolvableButNotSelectable() {
        val data = LocalData(createTestDriver())
        val id = PlayerId("removed")
        data.players.archiveRemote(id, 1)
        data.players.upsertRemote(Player(id, "Sam"), 2)
        data.settings.put("players.recentRemote", id.value)
        assertEquals("Sam", data.players.get(id)?.displayName)
        assertEquals(PlayerKind.ARCHIVED, data.players.get(id)?.kind)
        assertTrue(data.watchPeople().isEmpty())
    }
}
