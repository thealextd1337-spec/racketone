package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unabhängige, generierte Referenz des bisherigen Fünf-Felder-Vertrags. */
@Serializable
@SerialName("PlayerAssigned")
private data class LegacyPlayerAssigned(
    val slot: PlayerId,
    val player: Player?,
    val assignedBy: PlayerId,
    val legacySlot: Boolean = false,
    val creatorAlias: PlayerId? = null,
)

class PlayerAssignedSerializationTest {
    private val slot = PlayerId("placeholder:opponent")
    private val owner = PlayerId("owner")
    private val person = Player(PlayerId("bea"), "Bea Private")

    // Dieselben Optionen wie SyncCodec; keine Abhängigkeit der Engine vom Sync-Modul.
    private val codec =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = true
            classDiscriminator = "type"
        }

    private val strict = Json(codec) { ignoreUnknownKeys = false }

    @Test
    fun legacyBytesAndElementsMatchGeneratedOldContractForEveryDefaultAndNullOption() {
        var cases = 0
        for (defaults in listOf(false, true)) {
            for (nulls in listOf(false, true)) {
                val json =
                    Json(codec) {
                        encodeDefaults = defaults
                        explicitNulls = nulls
                    }
                for (player in listOf(null, person, person.copy(displayName = ""), person.copy(isPlaceholder = true))) {
                    for (legacySlot in listOf(false, true)) {
                        for (alias in listOf(null, PlayerId("watch-self"))) {
                            val old = LegacyPlayerAssigned(slot, player, owner, legacySlot, alias)
                            val event = PlayerAssigned(slot, player, owner, legacySlot, alias)
                            val expected = json.encodeToString(LegacyPlayerAssigned.serializer(), old)
                            assertEquals(expected, json.encodeToString(PlayerAssigned.serializer(), event))
                            val element = json.encodeToJsonElement(PlayerAssigned.serializer(), event)
                            assertEquals(json.encodeToJsonElement(LegacyPlayerAssigned.serializer(), old), element)
                            assertFalse("assignmentVersion" in element.jsonObject)
                            assertFalse("playerId" in element.jsonObject)
                            assertEquals(old, json.decodeFromString(LegacyPlayerAssigned.serializer(), expected))
                            assertEquals(old, json.decodeFromJsonElement(LegacyPlayerAssigned.serializer(), element))
                            assertEquals(event, json.decodeFromString(PlayerAssigned.serializer(), expected))
                            assertEquals(event, json.decodeFromJsonElement(PlayerAssigned.serializer(), element))
                            cases++
                        }
                    }
                }
            }
        }
        assertEquals(64, cases)
        assertEquals(
            """{"type":"PlayerAssigned","slot":"placeholder:opponent","player":null,"assignedBy":"owner"}""",
            codec.encodeToString(EventPayload.serializer(), PlayerAssigned(slot, null, owner)),
        )
    }

    @Test
    fun versionlessLegacyWithoutPlayerMeansNullForEveryDecoderPolicy() {
        val raw = """{"slot":"placeholder:opponent","assignedBy":"owner"}"""
        val value = PlayerAssigned(slot, null, owner)
        for (nulls in listOf(false, true)) {
            for (unknownKeys in listOf(false, true)) {
                val json =
                    Json(codec) {
                        explicitNulls = nulls
                        ignoreUnknownKeys = unknownKeys
                    }
                // Pure-Core kann die Null-Policy nicht lesen; nur versionsloses Legacy erlaubt das fehlende nullable Feld.
                assertEquals(value, json.decodeFromString(PlayerAssigned.serializer(), raw))
                assertEquals(value, json.decodeFromJsonElement(PlayerAssigned.serializer(), json.parseToJsonElement(raw)))
            }
        }
    }

    @Test
    fun polymorphicScoreEventRetainsLegacyGoldenBytes() {
        val event =
            ScoreEvent(
                EventId("e1"),
                MatchId("m"),
                DeviceId("phone"),
                1,
                2,
                3,
                null,
                PlayerAssigned(slot, person, owner, true, PlayerId("watch-self")),
            )
        val golden =
            """{"id":"e1","matchId":"m","deviceId":"phone","deviceSeq":1,"lamport":2,"wallClockMs":3,"parentId":null,""" +
                """"payload":{"type":"PlayerAssigned","slot":"placeholder:opponent","player":{"id":"bea","displayName":"Bea Private"},""" +
                """"assignedBy":"owner","legacySlot":true,"creatorAlias":"watch-self"}}"""
        assertEquals(golden, codec.encodeToString(ScoreEvent.serializer(), event))
        assertEquals(codec.parseToJsonElement(golden), codec.encodeToJsonElement(ScoreEvent.serializer(), event))
        assertEquals(event, codec.decodeFromString(ScoreEvent.serializer(), golden))
    }

    @Test
    fun referencesAlwaysWriteVersionAndExplicitIdWithoutNamesOrPlayerField() {
        for (defaults in listOf(false, true)) {
            for (nulls in listOf(false, true)) {
                val json =
                    Json(codec) {
                        encodeDefaults = defaults
                        explicitNulls = nulls
                    }
                for (id in listOf(null, person.id)) {
                    for (legacy in listOf(false, true)) {
                        for (alias in listOf(null, PlayerId("watch-self"))) {
                            val value = PlayerAssigned.reference(slot, id, owner, legacy, alias)
                            val encoded = json.encodeToString(EventPayload.serializer(), value)
                            val fields = json.parseToJsonElement(encoded).jsonObject
                            assertEquals("2", fields.getValue("assignmentVersion").toString())
                            assertTrue("playerId" in fields)
                            if (id == null) assertEquals(JsonNull, fields["playerId"])
                            assertFalse("player" in fields)
                            assertFalse("displayName" in encoded)
                            assertFalse(person.displayName in encoded)
                            assertEquals(value, json.decodeFromString(EventPayload.serializer(), encoded))
                            assertEquals(value, json.decodeFromJsonElement(EventPayload.serializer(), fields))
                        }
                    }
                }
            }
        }
        assertEquals(
            """{"type":"PlayerAssigned","slot":"placeholder:opponent","assignedBy":"owner","assignmentVersion":2,"playerId":null}""",
            codec.encodeToString(EventPayload.serializer(), PlayerAssigned.reference(slot, null, owner)),
        )
    }

    @Test
    fun mixedMissingAndUnknownVersionsFailEvenWithUnknownKeysIgnored() {
        val invalidFields =
            listOf(
                """, "playerId":null""",
                """, "player":null,"playerId":null""",
                """, "assignmentVersion":1,"player":null,"playerId":"bea"""",
                """, "assignmentVersion":2""",
                """, "assignmentVersion":2,"player":null,"playerId":null""",
                """, "assignmentVersion":2,"player":{"id":"bea","displayName":"Bea"},"playerId":"bea"""",
                """, "assignmentVersion":0,"player":null""",
                """, "assignmentVersion":3,"playerId":"bea"""",
                """, "assignmentVersion":-1,"player":null""",
                """, "assignmentVersion":null,"player":null""",
            )
        for (json in listOf(codec, strict, Json(codec) { explicitNulls = false })) {
            for (fields in invalidFields) {
                val encoded = """{"type":"PlayerAssigned","slot":"placeholder:opponent","assignedBy":"owner"$fields}"""
                assertFailsWith<SerializationException>(encoded) { json.decodeFromString(EventPayload.serializer(), encoded) }
                assertFailsWith<SerializationException>(encoded) {
                    json.decodeFromJsonElement(EventPayload.serializer(), json.parseToJsonElement(encoded))
                }
            }
            for (missing in listOf("slot", "assignedBy")) {
                val valid = json.encodeToJsonElement(PlayerAssigned.serializer(), PlayerAssigned.reference(slot, null, owner)).jsonObject
                assertFailsWith<SerializationException>(missing) {
                    json.decodeFromJsonElement(PlayerAssigned.serializer(), kotlinx.serialization.json.JsonObject(valid - missing))
                }
            }
        }
    }

    @Test
    fun explicitLegacyVersionAndUnknownFieldsFollowExistingCodecPolicy() {
        val legacy = """{"slot":"placeholder:opponent","player":null,"assignedBy":"owner","assignmentVersion":1}"""
        val value = PlayerAssigned(slot, null, owner)
        for (json in listOf(codec, strict)) assertEquals(value, json.decodeFromString(PlayerAssigned.serializer(), legacy))
        val extra = legacy.dropLast(1) + ""","futureLegacyField":{"nested":true}}"""
        assertEquals(value, codec.decodeFromString(PlayerAssigned.serializer(), extra))
        assertFailsWith<SerializationException> { strict.decodeFromString(PlayerAssigned.serializer(), extra) }
        assertFalse("assignmentVersion" in codec.encodeToString(PlayerAssigned.serializer(), value))
        val missingPlayer = """{"slot":"placeholder:opponent","assignedBy":"owner","assignmentVersion":1}"""
        for (nulls in listOf(false, true)) {
            for (unknownKeys in listOf(false, true)) {
                val json =
                    Json(codec) {
                        explicitNulls = nulls
                        ignoreUnknownKeys = unknownKeys
                    }
                // Explizite Version 1 stammt nicht aus dem Legacy-Encoder; ihr Anwesenheitscheck bleibt erhalten.
                assertEquals(value, json.decodeFromString(PlayerAssigned.serializer(), legacy))
                assertEquals(value, json.decodeFromJsonElement(PlayerAssigned.serializer(), json.parseToJsonElement(legacy)))
                assertFailsWith<SerializationException> { json.decodeFromString(PlayerAssigned.serializer(), missingPlayer) }
                assertFailsWith<SerializationException> {
                    json.decodeFromJsonElement(PlayerAssigned.serializer(), json.parseToJsonElement(missingPlayer))
                }
            }
        }
    }

    @Test
    fun illegalKotlinFormsCannotBeEncodedDirectlyOrPolymorphically() {
        val invalid =
            listOf(
                PlayerAssigned(slot, person, owner, assignmentVersion = 2),
                PlayerAssigned(slot, null, owner, playerId = person.id),
                PlayerAssigned(slot, person, owner, playerId = person.id),
                PlayerAssigned.reference(slot, person.id, owner).copy(player = person),
                PlayerAssigned(slot, null, owner, assignmentVersion = 0),
                PlayerAssigned(slot, null, owner, assignmentVersion = 3),
            )
        for (value in invalid) {
            assertFailsWith<SerializationException> { codec.encodeToString(PlayerAssigned.serializer(), value) }
            assertFailsWith<SerializationException> { codec.encodeToString(EventPayload.serializer(), value) }
        }
    }
}
