package eu.advantly.presentation.live

import eu.advantly.core.model.MatchId
import eu.advantly.presentation.match.MonotonicClock
import eu.advantly.sync.protocol.LiveTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveTelemetryStoreTest {
    private var now = 0L
    private val store = LiveTelemetryStore(MonotonicClock { now })
    private val match = MatchId("match")
    private val sample = LiveTelemetry(1, match, 140, 123)

    @Test
    fun onlyMatchingWorkoutCanReadFreshValues() {
        assertNull(store.forMatch(match, true))
        store.receive(sample)
        assertEquals(sample, store.forMatch(match, true))
        assertNull(store.forMatch(MatchId("other"), true))
        assertNull(store.forMatch(match, false))
        now = 29_999
        assertEquals(1L, store.remainingLifetimeMs())
        assertEquals(sample, store.forMatch(match, true))
        now = 30_000
        assertNull(store.forMatch(match, true))
        store.expire()
        assertNull(store.state.value)
        assertEquals(0L, store.remainingLifetimeMs())
    }

    @Test
    fun newUpdateRenewsExpiryAndReplacesMissingMetrics() {
        store.receive(sample)
        now = 25_000
        val latest = LiveTelemetry(1, match, steps = 0)
        store.receive(latest)
        now = 30_000
        store.expire()
        assertEquals(latest, store.forMatch(match, true))
        now = 55_000
        store.expire()
        assertNull(store.state.value)
    }

    @Test
    fun matchSwitchAndProcessRestartCannotReuseOldValues() {
        store.receive(sample)
        val other = LiveTelemetry(1, MatchId("other"), 155, 9)
        store.receive(other)
        assertNull(store.forMatch(match, true))
        assertEquals(other, store.forMatch(other.matchId, true))
        assertNull(LiveTelemetryStore().state.value)
    }
}
