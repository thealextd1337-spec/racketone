package eu.advantly.data

import eu.advantly.core.model.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Mit aktivem Replikatkontext (angemeldet) bleibt ein Freund wiederholt wählbar, ohne Cloud-Schreiber der Zeile. */
class FriendPlayerReplicaTest {
    @Test
    fun friendCanBePickedAgainWhileTheReplicaIsActive() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount("alice")
            val bob = data.players.rememberFriend("alice", "bob-profile", "Bob", 1) { PlayerId("bob-player") }
            assertEquals(PlayerId("bob-player"), bob?.id)
            assertEquals("Bobby", data.players.rememberFriend("alice", "bob-profile", "Bobby", 2) { PlayerId("unused") }?.displayName)
        }
    }
}
