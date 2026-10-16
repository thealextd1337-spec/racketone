package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.PlayerKind
import eu.advantly.data.watchPeople
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.MatchMetaMessage
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchivedGuestSyncTest {
    @Test
    fun placeholderCorrectionCarriesArchiveAndCannotRestoreGuestOnWatch() {
        val phone = WireNode("phone", 1)
        val watch = WireNode("watch", 2, PeerKind.WATCH)
        val id = PlayerId("archived-slot")
        phone.data.players.addGuest(id, "Sam", 1)
        assertTrue(phone.data.players.deleteGuest(id))
        watch.data.players.markPlaceholder(id, 1)
        phone.link.deliver(MatchMetaMessage(placeholderIds = listOf(id)))
        val correction =
            phone.link.sent
                .filterIsInstance<MatchMetaMessage>()
                .single { it.players.any { player -> player.id == id } }
        assertEquals(listOf(id), correction.archivedPlayerIds)
        watch.link.deliver(correction)
        // Alte Antworten und Verzeichnisdaten dürfen das Archiv auch nach der Korrektur nicht aufheben.
        watch.link.deliver(MatchMetaMessage(players = listOf(Player(id, "Stale name"))))
        assertEquals(
            PlayerKind.ARCHIVED,
            watch.data.players
                .get(id)
                ?.kind,
        )
        assertEquals(
            "Sam",
            watch.data.players
                .get(id)
                ?.displayName,
        )
        assertTrue(watch.data.watchPeople().isEmpty())
    }

    @Test
    fun allArchivesReconnectInBoundedMessagesAndOrdinaryMetadataOnlyCarriesItsPlayers() {
        val phone = WireNode("phone", 1)
        val watch = WireNode("watch", 2, PeerKind.WATCH)
        val archived = (1..405).map { PlayerId("guest-$it") }
        archived.forEach { id ->
            phone.data.players.addGuest(id, "Sam ${id.value}", 1)
            watch.data.players.addGuest(id, "Sam ${id.value}", 1)
            assertTrue(phone.data.players.deleteGuest(id))
        }

        fun verify() {
            val messages = phone.link.sent.filterIsInstance<MatchMetaMessage>()
            assertTrue(messages.all { it.archivedPlayerIds.size <= 200 })
            assertEquals(archived.toSet(), messages.flatMap { it.archivedPlayerIds }.toSet())
            messages.reversed().forEach(watch.link::deliver)
            assertEquals(archived.toSet(), watch.data.players.archivedIds())
        }
        phone.session.syncNow()
        verify()
        assertTrue(
            phone.link.sent
                .filterIsInstance<MatchMetaMessage>()
                .last()
                .archivedPlayerIds
                .isEmpty(),
        )
        phone.link.sent.clear()
        phone.link.deliver(Hello(DeviceId("lab"), PeerKind.WATCH))
        verify()
    }

    @Test
    fun followUpKeepsGuestArchivedDuringPredecessorAndItsServerIdentity() {
        for (kind in listOf(PeerKind.PHONE, PeerKind.WATCH)) {
            val node = WireNode("scorer", 1, kind)
            val self =
                node.data.players
                    .ensureSelf("Alex", 1) { PlayerId("owner") }
                    .id
            val guest = node.data.players.addGuest(PlayerId("sam"), "Sam", 1)
            node.session.create(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(self), listOf(guest.id), guest.id))
            assertTrue(node.data.players.deleteGuest(guest.id))
            node.points(Side.B, 15)
            val next = checkNotNull(node.session.createFollowUp())
            val lineup = checkNotNull(node.data.matches.get(next)).lineup
            assertEquals(listOf(self), lineup.a)
            assertEquals(listOf(guest.id), lineup.b)
            assertEquals(guest.id, lineup.firstServer)
            assertEquals(
                PlayerKind.ARCHIVED,
                node.data.players
                    .get(guest.id)
                    ?.kind,
            )
        }
    }

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
