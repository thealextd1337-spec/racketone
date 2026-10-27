package eu.advantly.match.session

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P2-E: Ein Freund im Match erreicht die Uhr nur mit lokaler Spieler-ID und Namen, nie mit seiner Profil-ID. */
class FriendPlayerWireTest {
    @Test
    fun watchMessagesCarryTheFriendsLocalIdButNeverTheProfileId() {
        val phone = WireNode("phone", 1)
        val watch = WireNode("watch", 2, PeerKind.WATCH)
        val me =
            phone.data.players
                .ensureSelf("Alex", 1) { PlayerId("me") }
                .id
        val bob =
            phone.data.players
                .rememberFriend("alice", "bob-profile", "Bob", 1) { PlayerId("bob-player") }!!
                .id
        phone.session.create(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(me), listOf(bob), me))
        phone.points(Side.A, 2)
        phone.session.syncNow()
        val sent = phone.link.sent.toString()
        assertTrue(sent.contains("bob-player"))
        assertFalse(sent.contains("bob-profile"))
        phone.link.sent.forEach(watch.link::deliver)
        assertFalse(
            watch.data.players
                .all()
                .toString()
                .contains("bob-profile"),
        )
    }
}
