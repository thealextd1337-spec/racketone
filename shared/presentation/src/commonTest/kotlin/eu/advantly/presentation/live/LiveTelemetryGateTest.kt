package eu.advantly.presentation.live

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.model.TelemetryVisibility.PRIVATE
import eu.advantly.match.session.PeerInfo
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.LiveTelemetry
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTelemetryGateTest {
    private val match = MatchId("match")
    private val value = LiveTelemetry(1, match, 140, 50)
    private val watch = PeerInfo("own-watch", null, PeerKind.WATCH, CompatibilityStatus.COMPATIBLE)

    @Test
    fun onlyOwnCompatibleWatchForEnabledMatchingWorkoutIsAccepted() {
        assertTrue(LiveTelemetryGate.accept(value, watch.peer, match, true, listOf(watch), PRIVATE))
        assertFalse(LiveTelemetryGate.accept(value, "foreign", match, true, listOf(watch), PRIVATE))
        assertFalse(LiveTelemetryGate.accept(value, watch.peer, MatchId("other"), true, listOf(watch), PRIVATE))
        assertFalse(LiveTelemetryGate.accept(value, watch.peer, null, true, listOf(watch), PRIVATE))
        assertFalse(LiveTelemetryGate.accept(value, watch.peer, match, false, listOf(watch), PRIVATE))
        assertFalse(LiveTelemetryGate.accept(value, watch.peer, match, true, listOf(watch.copy(kind = PeerKind.PHONE)), PRIVATE))
        assertFalse(
            LiveTelemetryGate.accept(
                value,
                watch.peer,
                match,
                true,
                listOf(watch.copy(compatibility = CompatibilityStatus.PEER_TOO_OLD)),
                PRIVATE,
            ),
        )
        assertFalse(LiveTelemetryGate.accept(value.copy(v = 99), watch.peer, match, true, listOf(watch), PRIVATE))
    }

    /** P2-J: „Teilen“ wirkt lokal wie „Nur für mich“; „Gar nicht“ verwirft auch Werte einer Uhr ohne die Einstellung. */
    @Test
    fun offDropsEveryValueAndShareBehavesLikePrivate() {
        assertTrue(LiveTelemetryGate.accept(value, watch.peer, match, true, listOf(watch), TelemetryVisibility.SHARE))
        assertFalse(LiveTelemetryGate.accept(value, watch.peer, match, true, listOf(watch), TelemetryVisibility.OFF))
    }

    @Test
    fun subscriptionRequiresSameMatchAndPhoneAndExpiresAfterThirtySeconds() {
        val subscription = ReceivedTelemetrySubscription(match, "phone", 100)
        assertTrue(subscription.isFresh(match, "phone", 100))
        assertTrue(subscription.isFresh(match, "phone", 30_100))
        assertFalse(subscription.isFresh(match, "phone", 30_101))
        assertFalse(subscription.isFresh(match, "phone", 99))
        assertFalse(subscription.isFresh(MatchId("other"), "phone", 200))
        assertFalse(subscription.isFresh(match, "foreign", 200))
    }
}
