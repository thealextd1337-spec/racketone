package eu.advantly.match.session

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.PlayerKind
import eu.advantly.data.watchPeople
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchivedGuestSyncTest {
    @Test
    fun archiveReachesWatchAfterReconnectWithoutLosingOldMatchNameOrReturningFromLocalHistory() {
        val c = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        val phone = c["phone"]
        val watch = c["watch"]
        val me =
            phone.data.players
                .ensureSelf("Alex", 1) { PlayerId("owner") }
                .id
        val guest = phone.data.players.addGuest(PlayerId("sam"), "Sam", 1)
        val id = phone.session.create(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(me), listOf(guest.id), me))
        repeat(15) { phone.session.recordPoint(Side.A) }
        c.settle()
        assertEquals(listOf(guest.id), watch.data.watchPeople().map { it.id })
        c.offline("watch")
        assertTrue(phone.data.players.deleteGuest(guest.id))
        phone.session.syncNow()
        c.online("watch")
        c.settle()
        assertEquals(
            PlayerKind.ARCHIVED,
            watch.data.players
                .get(guest.id)
                ?.kind,
        )
        assertTrue(watch.data.watchPeople().isEmpty())
        assertEquals(
            "Sam",
            watch.data.players
                .get(guest.id)
                ?.displayName,
        )
        assertEquals(
            guest.id,
            watch.data.matches
                .get(id)
                ?.lineup
                ?.b
                ?.single(),
        )
        assertEquals(
            PlayerKind.ARCHIVED,
            phone.data.players
                .get(guest.id)
                ?.kind,
        )
        // Ein weiterer Abgleich darf die Archivierung durch alte Match-Metadaten nicht rückgängig machen.
        watch.session.syncNow()
        c.settle()
        assertTrue(watch.data.watchPeople().isEmpty())
        assertEquals(
            "Sam",
            phone.data.players
                .get(guest.id)
                ?.displayName,
        )
    }
}
