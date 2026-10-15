package eu.advantly.cloud.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vertrag Kotlin-DTOs ↔ Supabase-RPCs. Die Antworten unten stammen unverändert aus einem Lauf der Migrationen
 * (supabase/migrations, Postgres 17); ändern sich Schlüssel in SQL, muss dieser Test mitgezogen werden.
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

    @Test
    fun decodesRealSyncPushResponse() {
        val response = CloudJson.decodeFromString(PushResponse.serializer(), SYNC_PUSH_RESPONSE)
        assertEquals(listOf("accepted", "duplicate", "rejected"), response.events.map { it.result })
        assertEquals("device_not_owned", response.events[2].reason)
        assertEquals("created", response.matches.single().result)
        assertEquals("applied", response.matches.single().projection)
    }

    @Test
    fun decodesRealSyncPullResponse() {
        val pull = CloudJson.decodeFromString(PullResponse.serializer(), SYNC_PULL_RESPONSE)
        assertEquals(21, pull.cursor)
        val match = pull.matches.single()
        assertEquals("tennis", match.sport)
        assertEquals(listOf("self", "pending"), match.participants.map { it.linkStatus })
        assertEquals("Bob", match.participants[1].displayName)
        assertNull(match.participants[0].shareWithFriends, "fremde Einstellungen liefert der Server nicht")
        assertEquals("invited", match.roles.single().status)
        assertEquals(2, pull.friendships.size)
        assertEquals("2026-10-16T12:00:00+02:00", match.startedAt, "Zeitpunkte als ISO-8601 mit Offset")
    }

    private companion object {
        const val SYNC_PUSH_RESPONSE =
            """{"events": [{"id": "e1000000-0000-7000-8000-000000000001", "result": "accepted"}, {"id": "e1000000-0000-7000-8000-000000000001", "result": "duplicate"}, {"id": "e1000000-0000-7000-8000-000000000003", "reason": "device_not_owned", "result": "rejected"}], "devices": [], "matches": [{"id": "a1000000-0000-7000-8000-000000000001", "notes": [], "result": "created", "projection": "applied"}], "players": []}"""

        const val SYNC_PULL_RESPONSE =
            """{"cursor": 21, "hasMore": false, "matches": [{"id": "a1000000-0000-7000-8000-000000000001", "meta": {}, "roles": [{"role": "scorer", "alias": "Bob", "status": "invited", "deviceId": null, "profileId": "da81b2aa-eaff-4c4b-bf10-ed5787f4b0e4"}], "sport": "tennis", "format": {"sport": "TENNIS", "setsToWin": 2}, "status": "in_progress", "winner": null, "courtId": null, "deleted": false, "endedAt": null, "summary": null, "createdBy": "4c722074-215c-496d-a82c-c6559b363a9a", "liveState": {"sets": [[1, 0]]}, "startedAt": "2026-10-16T12:00:00+02:00", "discipline": "singles", "scheduledAt": null, "participants": [{"team": "A", "hidden": null, "slotId": "a1000000-0000-7000-8000-0000000000f1", "playerId": null, "position": 1, "profileId": "4c722074-215c-496d-a82c-c6559b363a9a", "linkStatus": "self", "displayName": "Alice", "deletedProfile": false, "shareWithFriends": null}, {"team": "B", "hidden": false, "slotId": "a1000000-0000-7000-8000-0000000000f2", "playerId": null, "position": 1, "profileId": "da81b2aa-eaff-4c4b-bf10-ed5787f4b0e4", "linkStatus": "pending", "displayName": "Bob", "deletedProfile": false, "shareWithFriends": false}], "resultReason": null, "tournamentId": null, "serverVersion": 21, "previousMatchId": null, "projectionEventCount": 2}], "players": [], "friendships": [{"alias": "Alice", "status": "accepted", "outgoing": false, "profileId": "4c722074-215c-496d-a82c-c6559b363a9a", "serverVersion": 11}, {"alias": "Carol", "status": "accepted", "outgoing": true, "profileId": "5f3aff24-f856-4df7-a426-336b6d73cd92", "serverVersion": 13}]}"""
    }
}
