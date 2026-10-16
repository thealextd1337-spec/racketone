package eu.advantly.cloud.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Vertrag Kotlin → Supabase (Eingabe von `sync_push`). Die Gegenrichtung (echte RPC-Antworten) prüft
 * `WireFixturesTest` (jvmTest) an den Fixtures unter `supabase/tests/fixtures`.
 */
class WireContractTest {
    @Test
    fun pushBatchUsesTheKeysTheServerReads() {
        val batch =
            PushBatch(
                devices = listOf(DeviceDto(id = "d1", kind = "phone", platform = "android", pairedWith = "d0")),
                players = listOf(PlayerDto(id = "p1", kind = "guest", displayName = "Max")),
                matches =
                    listOf(
                        MatchDto(
                            id = "m1",
                            participants = listOf(ParticipantDto("s1", "A", 1, "Max", playerId = "p1")),
                            projection = ProjectionDto(status = "in_progress", eventCount = 3),
                        ),
                    ),
                events =
                    listOf(
                        EventRow("e1", "m1", "d1", 1, 1, 1000, null, "PointWon", JsonObject(mapOf("type" to JsonPrimitive("PointWon")))),
                    ),
            )
        val json = CloudJson.encodeToJsonElement(PushBatch.serializer(), batch).jsonObject
        val event =
            json
                .getValue("events")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(
            setOf("id", "matchId", "deviceId", "deviceSeq", "lamport", "wallClockMs", "type", "payload"),
            event.keys,
            "parentId = null wird weggelassen (explicitNulls = false), der Server liest es als NULL",
        )
        val match =
            json
                .getValue("matches")
                .jsonArray
                .single()
                .jsonObject
        assertTrue(match.keys.containsAll(listOf("id", "participants", "projection", "deleted")))
        val device =
            json
                .getValue("devices")
                .jsonArray
                .single()
                .jsonObject
        assertTrue(device.keys.containsAll(listOf("id", "kind", "platform", "pairedWith", "standalone")))
    }
}
