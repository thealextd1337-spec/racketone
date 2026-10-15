package eu.advantly.sync.protocol

import eu.advantly.core.model.MatchId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LiveTelemetrySubscriptionCodecTest {
    @Test
    fun subscriptionRoundTripsAndIgnoresAdditiveFields() {
        val value = LiveTelemetrySubscription(1, MatchId("match"))
        assertEquals(value, LiveTelemetrySubscriptionCodec.decode(LiveTelemetrySubscriptionCodec.encode(value)))
        assertEquals(value, LiveTelemetrySubscriptionCodec.decode("""{"v":1,"matchId":"match","future":true}""".encodeToByteArray()))
    }

    @Test
    fun malformedVersionsAndMatchesAreDropped() {
        listOf(
            "{}",
            "not json",
            """{"v":0,"matchId":"match"}""",
            """{"v":2,"matchId":"match"}""",
            """{"v":1,"matchId":""}""",
            "x".repeat(1025),
        ).forEach {
            assertNull(LiveTelemetrySubscriptionCodec.decode(it.encodeToByteArray()))
        }
    }

    @Test
    fun diagnosticsNeverContainMeasurementsOrMatch() {
        assertEquals("LiveTelemetry(<redacted>)", LiveTelemetry(1, MatchId("secret"), 143, 12345).toString())
        assertFalse(LiveTelemetrySubscription(1, MatchId("secret")).toString().contains("secret"))
    }
}
