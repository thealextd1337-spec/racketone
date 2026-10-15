package eu.advantly.umbrella.watch

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Facade-Tests. Auf dem watchOS-Simulator (CI) läuft [AdvantlyWatch.store] mit dem native-driver auf einer
 * echten Datei – derselbe Pfad wie in der Watch-App (Spike S1).
 */
class WatchFacadeTest {
    // Datei-DB kann Läufe überdauern → eindeutige Match-IDs je Test
    private val matchId = "test-" + Random.nextLong().toULong().toString(16)

    @Test
    fun storeAppendIsIdempotentAndOrdered() {
        val store = AdvantlyWatch.store()
        assertTrue(store.append("$matchId-b", matchId, "watch", deviceSeq = 2, lamport = 2, payload = "{}"))
        assertTrue(store.append("$matchId-a", matchId, "phone", deviceSeq = 1, lamport = 1, payload = "{}"))
        assertFalse(store.append("$matchId-a", matchId, "phone", deviceSeq = 1, lamport = 1, payload = "{}"))
        assertFalse(store.append("$matchId-c", matchId, "phone", deviceSeq = 1, lamport = 3, payload = "{}"), "Sequenzkonflikt")
        assertEquals(2, store.count(matchId))
        assertEquals(listOf("$matchId-a", "$matchId-b"), store.eventIds(matchId))
    }

    @Test
    fun smokeCheckIsStableAcrossCalls() {
        assertEquals(1, AdvantlyWatch.store().smokeCheck())
        assertEquals(1, AdvantlyWatch.store().smokeCheck())
    }
}
