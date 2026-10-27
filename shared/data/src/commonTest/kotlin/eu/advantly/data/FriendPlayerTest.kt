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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P2-E: Freund als Mitspieler mit lokaler Spieler-ID je Konto; die Profil-ID steht nur in der Zuordnung. */
class FriendPlayerTest {
    private var next = 0

    private fun newId() = PlayerId("local-${next++}")

    @Test
    fun sameFriendKeepsOneLocalIdPerAccountWithCurrentAliasAndStaysOutOfGuestsAndUploads() {
        val data = LocalData(createTestDriver())
        val self = data.players.ensureSelf("Alex", 1) { PlayerId("self") }
        val bob = data.players.rememberFriend("alice", "bob-profile", "Bob", 2, ::newId)!!
        assertEquals(LocalPlayer(PlayerId("local-0"), "Bob", PlayerKind.FRIEND, 2, null), bob)
        // Zweites Match mit demselben Freund: dieselbe ID (Verlauf und Statistik wie bei Gästen), aktueller Alias.
        assertEquals(
            LocalPlayer(bob.id, "Bobby", PlayerKind.FRIEND, 2, null),
            data.players.rememberFriend("alice", "bob-profile", "Bobby", 3, ::newId),
        )
        assertEquals("bob-profile", data.players.friendProfile("alice", bob.id))
        // Ein anderes Konto auf demselben Gerät bekommt eine eigene Zuordnung und sieht die von Alice nicht.
        val forCarol = data.players.rememberFriend("carol", "bob-profile", "Bob", 4, ::newId)!!
        assertNotEquals(bob.id, forCarol.id)
        assertNull(data.players.friendProfile("carol", bob.id))
        assertEquals(listOf(self), data.players.all())
        assertNull(data.cloud.uploadPlayer(bob.id.value))
        // Fremde Metadaten (Uhr) benennen Freunde nicht um und machen sie nie zum Platzhalter.
        data.players.upsertRemote(Player(bob.id, "Old name"), 5)
        data.players.markPlaceholder(bob.id, 6)
        assertEquals(LocalPlayer(bob.id, "Bobby", PlayerKind.FRIEND, 2, null), data.players.get(bob.id))
    }

    @Test
    fun invalidInputsAndOccupiedIdsAreRefused() {
        val data = LocalData(createTestDriver())
        data.players.ensureSelf("Alex", 1) { PlayerId("self") }
        assertNull(data.players.rememberFriend("", "bob-profile", "Bob", 2, ::newId))
        assertNull(data.players.rememberFriend("alice", "bob-profile", " ", 2, ::newId))
        assertNull(data.players.rememberFriend("alice", "bob-profile", "Bob", 2) { PlayerId("self") })
        assertNull(data.players.rememberFriend("alice", "bob-profile", "Bob", 2) { Placeholders.id("slot") })
        assertNull(data.players.friendProfile("alice", PlayerId("self")))
    }

    @Test
    fun linkNotesDisappearWithTheMatch() {
        val driver = createTestDriver()
        val data = LocalData(driver)
        val self = data.players.ensureSelf("Alex", 1) { PlayerId("self") }.id
        val id = MatchId("m")
        data.matches.insertIfAbsent(
            id,
            Presets.BADMINTON_1GAME_15.format(),
            Lineup(listOf(self), listOf(PlayerId("x")), self),
            MatchMeta(),
            1,
        )
        driver.execute(null, """INSERT INTO friend_link_note VALUES ('alice', 'm', '{"x":"not_friend"}')""", 0)
        assertEquals(mapOf("x" to "not_friend"), data.cloudUploads.linkNotes("alice", "m"))
        assertTrue(data.cloudUploads.linkNotes("bob", "m").isEmpty())
        data.deleteMatch(id)
        assertTrue(data.cloudUploads.linkNotes("alice", "m").isEmpty())
    }
}
