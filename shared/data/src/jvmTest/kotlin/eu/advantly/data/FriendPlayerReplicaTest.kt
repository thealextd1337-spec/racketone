package eu.advantly.data

import eu.advantly.core.model.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Mit aktivem Replikatkontext (angemeldet) bleibt ein Freund wiederholt und auch unter einem zweiten Konto wählbar. */
class FriendPlayerReplicaTest {
    @Test
    fun friendCanBePickedAgainAndByAnotherAccountOnTheSameDevice() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount("alice")
            val bob = PlayerId("bob-profile")
            assertEquals("Bob", data.players.rememberFriend(bob, "Bob", 1)?.displayName)
            assertEquals("Bobby", data.players.rememberFriend(bob, "Bobby", 2)?.displayName)
            data.selectPullAccount("carol")
            assertEquals(PlayerKind.FRIEND, data.players.rememberFriend(bob, "Bob B.", 3)?.kind)
        }
    }
}
