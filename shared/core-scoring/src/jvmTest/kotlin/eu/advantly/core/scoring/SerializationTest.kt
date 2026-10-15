package eu.advantly.core.scoring

import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.Uuid7
import eu.advantly.core.scoring.vectors.vectorJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JSON-Form von MatchFormat (02 §3.2, „weglassen statt null“), Events (§7) und Zustand (§8). */
class SerializationTest {
    private fun keys(format: MatchFormat): Set<String> = vectorJson.encodeToJsonElement(MatchFormat.serializer(), format).jsonObject.keys

    @Test
    fun sportForeignFieldsAreOmitted() {
        assertEquals(
            setOf("formatVersion", "presetId", "sport", "discipline", "match", "game", "set", "decidingSet", "matchTiebreak"),
            keys(Presets.TENNIS_SINGLES_BO3.format()),
        )
        assertEquals(
            setOf("formatVersion", "presetId", "sport", "discipline", "match", "pickleball"),
            keys(Presets.PICKLEBALL_OPEN.format()),
        )
        assertEquals(setOf("formatVersion", "presetId", "sport", "discipline", "match", "rally"), keys(Presets.TABLE_TENNIS_OPEN.format()))
    }

    @Test
    fun allPresetsRoundTrip() {
        for (preset in Presets.all) {
            for (d in preset.disciplines) {
                val format = preset.format(d)
                val json = vectorJson.encodeToString(MatchFormat.serializer(), format)
                assertEquals(format, vectorJson.decodeFromString(MatchFormat.serializer(), json), json)
            }
        }
    }

    @Test
    fun explicitNullForForeignFieldIsRejected() {
        val base = vectorJson.encodeToJsonElement(MatchFormat.serializer(), Presets.PICKLEBALL_OPEN.format()).jsonObject
        for (field in listOf("game", "set", "rally", "decidingSet", "matchTiebreak")) {
            val bad = JsonObject(base + (field to kotlinx.serialization.json.JsonNull))
            assertFailsWith<SerializationException>(field) { vectorJson.decodeFromJsonElement(MatchFormat.serializer(), bad) }
        }
        val tennis = vectorJson.encodeToJsonElement(MatchFormat.serializer(), Presets.TENNIS_OPEN.format()).jsonObject
        assertFailsWith<SerializationException> {
            vectorJson.decodeFromJsonElement(
                MatchFormat.serializer(),
                JsonObject(tennis + ("pickleball" to kotlinx.serialization.json.JsonNull)),
            )
        }
    }

    @Test
    fun decidingSetKindsAndRequiredTiebreakAt() {
        val json =
            """{"formatVersion":1,"sport":"TENNIS","discipline":"SINGLES","match":{"mode":"BEST_OF","bestOf":3},""" +
                """"game":{"deuceMode":"ADVANTAGE"},"set":{"gamesToWin":6,"tiebreakAt":6},"decidingSet":{"kind":"REGULAR","gamesToWin":6,"tiebreakAt":null}}"""
        val format = vectorJson.decodeFromString(MatchFormat.serializer(), json)
        assertEquals(
            eu.advantly.core.model
                .RegularSet(6, null),
            format.decidingSet,
        )
        assertFailsWith<SerializationException> {
            vectorJson.decodeFromString(MatchFormat.serializer(), json.replace(""","tiebreakAt":null""", ""))
        }
        assertFailsWith<SerializationException> {
            vectorJson.decodeFromString(MatchFormat.serializer(), json.replace("\"kind\":\"REGULAR\"", "\"kind\":\"MATCH_TIEBREAK\""))
        }
    }

    @Test
    fun eventPayloadsUseTypeDiscriminator() {
        val json =
            vectorJson.encodeToString(
                EventPayload.serializer(),
                Correction.servingOrder(eu.advantly.core.model.Side.A, listOf(p("A2"), p("A1"))),
            )
        assertEquals("""{"type":"Correction","kind":"SERVING_ORDER","side":"A","players":["A2","A1"]}""", json)
        assertEquals("""{"type":"Paused"}""", vectorJson.encodeToString(EventPayload.serializer(), Paused))
        val decoded = vectorJson.decodeFromString(EventPayload.serializer(), """{"type":"ContinueDecision","anotherSet":true}""")
        assertEquals(ContinueDecision(true, NextSetKind.REGULAR), decoded)
    }

    @Test
    fun canonicalStateHasAllKeysWithExplicitNulls() {
        val state = ScoringEngine.initial(Presets.TENNIS_SINGLES_BO3.format(), lineupFor(eu.advantly.core.model.Discipline.SINGLES))
        val obj = vectorJson.encodeToJsonElement(MatchState.serializer(), state).jsonObject
        assertEquals(
            listOf(
                "status",
                "pending",
                "result",
                "setsWon",
                "sets",
                "current",
                "serve",
                "scoreCall",
                "positions",
                "ends",
                "statusFlags",
                "effects",
                "ignoredEvents",
                "continuation",
                "scorerDeviceId",
                "rejectedByRole",
            ),
            obj.keys.toList(),
        )
        assertTrue(obj["pending"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun uuid7FromPartsIsValidAndOrdered() {
        val a = Uuid7.fromParts(1_700_000_000_000L, 0xABC, 0x1234_5678_9ABC_DEFL)
        assertEquals(a, Uuid7.parse(a.value))
        assertEquals(1_700_000_000_000L, a.unixTimeMs)
        val b = Uuid7.fromParts(1_700_000_000_001L, 0, 0)
        assertTrue(a < b)
        assertFalse(Uuid7.parseOrNull("01890a5d-ac96-4ed8-8000-000000000000") != null, "Version 4 statt 7")
    }
}
