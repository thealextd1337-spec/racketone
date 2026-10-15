package eu.advantly.sync.protocol

import eu.advantly.core.model.MatchId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LiveTelemetryCodecTest {
    @Test
    fun roundTripKeepsMatchAndOptionalMetrics() {
        listOf(
            LiveTelemetry(1, MatchId("match"), 143, 12_345L),
            LiveTelemetry(1, MatchId("match"), null, 0),
            LiveTelemetry(1, MatchId("match"), 143, null),
            LiveTelemetry(1, MatchId("match")),
        ).forEach { value ->
            val encoded = LiveTelemetryCodec.encode(value)
            assertEquals(value, LiveTelemetryCodec.decode(encoded))
            assertFalse(encoded.size > 150)
        }
    }

    @Test
    fun invalidMessagesAreDropped() {
        listOf(
            "not json",
            "{}",
            """{"v":2,"matchId":"match","heartRateBpm":140}""",
            """{"matchId":"match","steps":1}""",
            """{"v":1,"matchId":"match","steps":-1}""",
            """{"v":1,"matchId":"match","heartRateBpm":0}""",
            """{"v":1,"matchId":"","steps":1}""",
            "x".repeat(1_025),
        ).forEach { assertNull(LiveTelemetryCodec.decode(it.encodeToByteArray())) }
    }

    @Test
    fun unknownFieldsInSameVersionAreIgnored() {
        assertEquals(
            LiveTelemetry(1, MatchId("match"), steps = 9),
            LiveTelemetryCodec.decode("""{"v":1,"matchId":"match","steps":9,"future":true}""".encodeToByteArray()),
        )
    }
}
