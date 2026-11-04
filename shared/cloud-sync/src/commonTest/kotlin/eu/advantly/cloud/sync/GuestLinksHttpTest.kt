package eu.advantly.cloud.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val BIND_REPLY =
    """{"contractVersion":1,"profileId":"alice","sessionId":"session-a","installationId":"installation","deviceId":"phone"}"""

/** plan-0.6.3 §3.5: assign_player, unassign_player, reject_player_assignment unter Cloud-Bindung, Fehler mit Grund. */
class GuestLinksHttpTest {
    private val installation = CloudInstallation("installation", "proof-".repeat(12))

    private class Call(
        val path: String,
        val body: JsonObject?,
        val bearer: String?,
        val proof: String?,
    )

    private fun backend(reply: (Call) -> Pair<String, HttpStatusCode>): Pair<SupabaseCloudBackend, MutableList<Call>> {
        val calls = mutableListOf<Call>()
        val engine =
            MockEngine { request ->
                val text = (request.body as? TextContent)?.text
                val call =
                    Call(
                        request.url.encodedPath.removePrefix("/rest/v1/"),
                        text?.let { Json.parseToJsonElement(it) as JsonObject },
                        request.headers["Authorization"],
                        request.headers["X-Advantly-Installation-Proof"],
                    )
                val (body, status) =
                    if (call.path.endsWith("bind_cloud_session_v1")) {
                        BIND_REPLY to HttpStatusCode.OK
                    } else {
                        calls += call
                        reply(call)
                    }
                respond(body, status, headersOf("Content-Type", "application/json"))
            }
        val http = CloudHttp("https://cloud.test", "public-key", HttpClient(engine) { followRedirects = false })
        val gate =
            CloudSessionGate({ CloudCredentials("alice", "session-a", "token-a") }).also {
                it.publish(it.capture(requireProfile = false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
            }
        return SupabaseCloudBackend(gate, http, installation) to calls
    }

    @Test fun threeRpcsUseTheBoundSessionAndReturnTheMatchCount(): Unit =
        runBlocking {
            val (backend, calls) = backend { call -> (if (call.path == "rpc/assign_player") "3" else "") to HttpStatusCode.OK }
            backend.bindDevice("phone")
            assertEquals(3, backend.assignGuest("alice", "guest-1", "anja"))
            backend.unassignGuest("alice", "guest-1")
            backend.rejectGuestAssignment("alice", "guest-2")
            assertEquals(listOf("rpc/assign_player", "rpc/unassign_player", "rpc/reject_player_assignment"), calls.map { it.path })
            assertTrue(calls.all { it.bearer == "Bearer token-a" && it.proof == installation.proof })
            assertEquals(
                JsonObject(mapOf("p_player_id" to JsonPrimitive("guest-1"), "p_profile_id" to JsonPrimitive("anja"))),
                calls[0].body,
            )
            assertEquals(JsonObject(mapOf("p_player_id" to JsonPrimitive("guest-1"))), calls[1].body)
            assertEquals(JsonObject(mapOf("p_player_id" to JsonPrimitive("guest-2"))), calls[2].body)
        }

    @Test fun serverReasonsStayStableAndNothingLeavesWithoutBindingOrForAnotherAccount(): Unit =
        runBlocking {
            var reason = "assignee_must_be_friend"
            var reply = """"x""""
            val (backend, calls) =
                backend { _ ->
                    if (reason.isEmpty()) {
                        reply to HttpStatusCode.OK
                    } else {
                        """{"code":"P0001","message":"$reason"}""" to
                            HttpStatusCode.BadRequest
                    }
                }
            assertEquals("device_not_authorized", assertFailsWith<CloudException> { backend.assignGuest("alice", "g", "anja") }.reason)
            assertTrue(calls.isEmpty())
            backend.bindDevice("phone")
            assertEquals("cloud_session_changed", assertFailsWith<CloudException> { backend.unassignGuest("bob", "g") }.reason)
            assertTrue(calls.isEmpty())
            for (code in listOf(
                "assignee_must_be_friend",
                "already_assigned",
                "assignment_rejected_before",
                "player_not_found",
                "player_redacted",
                "rate_limit_exceeded",
            )) {
                reason = code
                assertEquals(code, assertFailsWith<CloudException> { backend.assignGuest("alice", "g", "anja") }.reason)
            }
            reason = "not_assigned_to_you"
            assertEquals(reason, assertFailsWith<CloudException> { backend.rejectGuestAssignment("alice", "g") }.reason)
            // Unbekannter Servertext geht nie an die UI; eine unlesbare Anzahl ist eine ungültige Antwort.
            reason = "secret detail"
            assertEquals("cloud_connection_failed", assertFailsWith<CloudException> { backend.unassignGuest("alice", "g") }.reason)
            reason = ""
            assertEquals("cloud_response_invalid", assertFailsWith<CloudException> { backend.assignGuest("alice", "g", "anja") }.reason)
            reply = "-1"
            assertEquals("cloud_response_invalid", assertFailsWith<CloudException> { backend.assignGuest("alice", "g", "anja") }.reason)
        }
}
