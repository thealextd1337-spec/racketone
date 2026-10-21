package eu.advantly.cloud.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Vertrag Supabase → Kotlin (Review P2 #9): Die Fixtures sind echte RPC-Antworten, erzeugt mit
 * `supabase/tests/fixtures/generate.psql`; pgTAP (`095_wire_vertrag.test.sql`) prüft dieselben Schlüssel direkt an den
 * RPCs. Hier gilt: Jede DTO-Eigenschaft heißt exakt wie der JSON-Schlüssel – nicht mehr, nicht weniger.
 */
class WireFixturesTest {
    private val dir = File(System.getProperty("user.dir"), "../../supabase/tests/fixtures")

    private fun fixture(name: String): JsonElement = CloudJson.parseToJsonElement(File(dir, name).readText())

    private fun <T> keysMatch(
        serializer: KSerializer<T>,
        objects: List<JsonObject>,
        what: String,
    ) {
        assertTrue(objects.isNotEmpty(), "$what: Fixture ohne Beispiel")
        val descriptor = serializer.descriptor
        val dtoNames = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSortedSet()
        val jsonKeys = objects.flatMapTo(sortedSetOf()) { it.keys }
        assertEquals(jsonKeys, dtoNames, "$what: JSON-Schlüssel und DTO-Felder weichen ab")
    }

    @Test
    fun syncPushResponse() {
        val json = fixture("sync_push.json").jsonObject
        keysMatch(PushResponse.serializer(), listOf(json), "sync_push")
        val items = listOf("devices", "players", "matches", "events").flatMap { json.getValue(it).jsonArray.map { e -> e.jsonObject } }
        keysMatch(ItemResult.serializer(), items, "sync_push-Element")
        val response = CloudJson.decodeFromJsonElement(PushResponse.serializer(), json)
        assertEquals(listOf("accepted", "accepted", "duplicate", "rejected"), response.events.map { it.result })
        assertEquals("applied", response.matches.single().projection)
    }

    @Test
    fun syncPullResponse() {
        val json = fixture("sync_pull.json").jsonObject
        keysMatch(PullResponse.serializer(), listOf(json), "sync_pull")
        val matches = json.getValue("matches").jsonArray.map { it.jsonObject }
        keysMatch(PulledMatch.serializer(), matches, "sync_pull-Match")
        keysMatch(
            PulledParticipant.serializer(),
            matches.flatMap { m ->
                m.getValue("participants").jsonArray.map { it.jsonObject }
            },
            "Teilnehmer",
        )
        keysMatch(PulledRole.serializer(), matches.flatMap { m -> m.getValue("roles").jsonArray.map { it.jsonObject } }, "Rolle")
        keysMatch(PulledPlayer.serializer(), json.getValue("players").jsonArray.map { it.jsonObject }, "Spieler")
        keysMatch(PulledFriendship.serializer(), json.getValue("friendships").jsonArray.map { it.jsonObject }, "Freundschaft")

        val pull = CloudJson.decodeFromJsonElement(PullResponse.serializer(), json)
        val match = pull.matches.single()
        assertEquals("member", match.access)
        assertEquals("invited", match.roles.single().status)
        assertEquals(listOf("self", null), match.participants.map { it.linkStatus })
    }

    @Test
    fun pulledEventsAndWatermarks() {
        val events = fixture("pull_match_events.json").jsonArray.map { it.jsonObject }
        keysMatch(PulledEvent.serializer(), events, "pull_match_events")
        val decoded = CloudJson.decodeFromJsonElement(ListSerializer(PulledEvent.serializer()), fixture("pull_match_events.json"))
        assertEquals(listOf("MatchCreated", "PointWon"), decoded.map { it.type })
        assertTrue(decoded.none { it.redacted })
        val row = EventRows.toStored(decoded[1].toRow())
        assertEquals("PointWon", row.type)

        val marks = fixture("match_event_watermarks.json").jsonArray.map { it.jsonObject }
        keysMatch(EventWatermark.serializer(), marks, "match_event_watermarks")
    }

    @Test
    fun visitorView() {
        val json = fixture("get_visitor_match.json").jsonObject
        keysMatch(VisitorView.serializer(), listOf(json), "get_visitor_match")
        keysMatch(VisitorTeamMember.serializer(), json.getValue("teams").jsonArray.map { it.jsonObject }, "Team")
        val view = CloudJson.decodeFromJsonElement(VisitorView.serializer(), json)
        assertTrue(view.channel.startsWith("match-live:${view.matchId}:"))
    }

    @Test
    fun matchReadinessResponse() {
        val json = fixture("cloud_match_readiness.json").jsonObject
        keysMatch(CloudMatchReadiness.serializer(), listOf(json), "cloud_match_readiness")
        keysMatch(EventWatermark.serializer(), json.getValue("watermarks").jsonArray.map { it.jsonObject }, "Readiness-Wasserstand")
        val readiness = CloudJson.decodeFromJsonElement(CloudMatchReadiness.serializer(), json)
        // Die echte Serverantwort besteht dieselbe Clientprüfung wie im Pull-Pfad.
        readiness.validate(CloudReadinessRequest(readiness.matchId, readiness.generation, readiness.redactionEpoch))
    }
}
