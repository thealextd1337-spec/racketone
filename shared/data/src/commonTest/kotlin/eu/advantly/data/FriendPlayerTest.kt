package eu.advantly.data

import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** P2-E: Freund als Mitspieler mit Profil-ID als Spieler-ID, ohne Gast- oder Upload-Eintrag zu werden. */
class FriendPlayerTest {
    @Test
    fun friendUsesProfileIdKeepsCurrentAliasAndStaysOutOfGuestListsAndUploads() {
        val data = LocalData(createTestDriver())
        val self = data.players.ensureSelf("Alex", 1) { PlayerId("self") }
        val bob = PlayerId("bob-profile")
        assertEquals(PlayerKind.FRIEND, data.players.rememberFriend(bob, "Bob", 2)?.kind)
        assertEquals("Bobby", data.players.rememberFriend(bob, "Bobby", 3)?.displayName)
        assertEquals(listOf(self), data.players.all())
        assertNull(data.cloud.uploadPlayer(bob.value))
        // Fremde Metadaten (Uhr, gezogene Matches) benennen Freunde nicht um und machen sie nie zum Platzhalter.
        data.players.upsertRemote(Player(bob, "Old name"), 4)
        data.players.markPlaceholder(bob, 5)
        assertEquals(LocalPlayer(bob, "Bobby", PlayerKind.FRIEND, 2, null), data.players.get(bob))
    }

    @Test
    fun guestOfTheSameProfileBecomesFriendButSelfBlankAndPlaceholdersNever() {
        val data = LocalData(createTestDriver())
        val self = data.players.ensureSelf("Alex", 1) { PlayerId("self") }
        val carol = PlayerId("carol-profile")
        data.players.upsertRemote(Player(carol, "Carol"), 2)
        assertEquals(PlayerKind.GUEST, data.players.get(carol)?.kind)
        assertEquals(PlayerKind.FRIEND, data.players.rememberFriend(carol, "Caro", 3)?.kind)
        assertEquals(listOf(self), data.players.all())
        assertNull(data.players.rememberFriend(self.id, "Me", 4))
        assertNull(data.players.rememberFriend(PlayerId("dan-profile"), " ", 4))
        assertNull(data.players.rememberFriend(Placeholders.id("slot"), "Slot", 4))
        assertEquals(PlayerKind.SELF, data.players.get(self.id)?.kind)
    }
}
