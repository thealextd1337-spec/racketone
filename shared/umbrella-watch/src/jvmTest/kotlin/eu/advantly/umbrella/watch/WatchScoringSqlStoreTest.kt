package eu.advantly.umbrella.watch

import eu.advantly.data.createInMemoryJvmEventStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Wie auf der Uhr: echtes SQLDelight-Schema (JDBC statt native-driver), Fortsetzen nach „Neustart“. */
class WatchScoringSqlStoreTest {
    private var clock = 1_760_000_000_000L

    private fun tick(): Long {
        clock += 700
        return clock
    }

    @Test
    fun fullOpenMatchSurvivesRestartOnSqlStore() {
        val store = createInMemoryJvmEventStore()
        val prefs = InMemoryWatchPrefs()
        val first = WatchScoring(store, prefs, now = ::tick, random = Random(3))
        first.startMatch("PADEL_OPEN", doubles = true, weServeFirst = true)
        repeat(24) { first.point(sideA = true) }
        val pending = assertNotNull(first.snapshot())
        assertTrue(pending.pendingDecision)

        val second = WatchScoring(store, prefs, now = ::tick, random = Random(4))
        val resumed = assertNotNull(second.resume())
        assertTrue(resumed.pendingDecision)
        assertEquals(listOf(6), resumed.setScoresA)
        val next = assertNotNull(second.continueDecision(anotherSet = true, matchTiebreak = false))
        assertEquals("REGULAR", next.setKind)
        assertEquals(1, next.setIndex)
        assertEquals(26L, store.count(resumed.matchId))
    }
}
