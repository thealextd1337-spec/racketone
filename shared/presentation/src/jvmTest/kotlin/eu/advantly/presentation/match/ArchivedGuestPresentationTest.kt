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
import eu.advantly.presentation.stats.HistoryRole
import eu.advantly.presentation.stats.headToHead
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArchivedGuestPresentationTest {
    @Test
    fun listsAndOpenWizardDropArchivedGuestWhileHistoryAndHeadToHeadKeepNameAndResults() {
        val data = createInMemoryJvmLocalData()
        val me = data.players.ensureSelf("Alex", 1) { PlayerId("me") }.id
        val session = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), { 10 })
        val store = SessionMatchStore(session)
        val guest = store.addGuest("Sam")
        val id = session.create(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(me), listOf(guest.id), me))
        repeat(15) { session.recordPoint(Side.A) }
        val wizard = MatchWizard(store).apply { selectSport(Sport.BADMINTON) }
        assertTrue(wizard.playerSuggestions().any { it.id == guest.id })
        val events = data.events.count(id.value)
        assertTrue(store.deleteGuest(guest.id))
        assertFalse(store.deleteGuest(me))
        assertFalse(store.deleteGuest(Placeholders.id("b")))
        assertTrue(store.guests.value.isEmpty())
        assertTrue(store.recentPlayers(Sport.BADMINTON).isEmpty())
        assertTrue(wizard.playerSuggestions().isEmpty())
        assertEquals(setOf(guest.id), store.archivedPlayerIds.value)
        assertEquals(
            "Sam",
            store.matches.value
                .single()
                .setup
                .player(guest.id)
                ?.displayName,
        )
        assertEquals(
            "Sam",
            store
                .record(id)
                ?.summary
                ?.setup
                ?.player(guest.id)
                ?.displayName,
        )
        assertEquals(1, headToHead(store.matches.value, guest.id, HistoryRole.OPPONENT, setOf(me)).total)
        assertEquals(events, data.events.count(id.value))
        assertTrue(
            store.lastSetup.value!!
                .players
                .none { it.id == guest.id },
        )
        val newGuest = store.addGuest("Sam")
        assertNotEquals(guest.id, newGuest.id)
        store.close()
        val reopened = SessionMatchStore(session)
        assertEquals(setOf(guest.id), reopened.archivedPlayerIds.value)
        assertEquals(
            "Sam",
            reopened
                .record(id)
                ?.summary
                ?.setup
                ?.player(guest.id)
                ?.displayName,
        )
        reopened.close()
    }
}
