package eu.advantly.cloud.sync

import eu.advantly.core.model.TelemetryPreference
import eu.advantly.core.model.TelemetryVisibility
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FriendsHttpTest {
    private val installation = CloudInstallation("installation", "proof-".repeat(12))
    private val bindReply =
        """{"contractVersion":1,"profileId":"alice","sessionId":"session-a","installationId":"installation","deviceId":"phone"}"""

    private class Call(
        val method: HttpMethod,
        val path: String,
        val query: String,
        val body: JsonObject?,
        val bearer: String?,
        val installationId: String?,
        val proof: String?,
    )

    private fun backend(
        credentials: () -> CloudCredentials? = { CloudCredentials("alice", "session-a", "token-a") },
        reply: (Call) -> Pair<String, HttpStatusCode>,
    ): Triple<SupabaseCloudBackend, MutableList<Call>, CloudHttp> {
        val calls = mutableListOf<Call>()
        val engine =
            MockEngine { request: HttpRequestData ->
                val text = (request.body as? TextContent)?.text
                val call =
                    Call(
                        request.method,
                        request.url.encodedPath,
                        request.url.encodedQuery,
                        text?.let { Json.parseToJsonElement(it) as JsonObject },
                        request.headers["Authorization"],
                        request.headers["X-Advantly-Installation-Id"],
                        request.headers["X-Advantly-Installation-Proof"],
                    )
                val (body, status) = if (call.path.endsWith("bind_cloud_session_v1")) bindReply to HttpStatusCode.OK else reply(call)
                if (!call.path.endsWith("bind_cloud_session_v1")) calls += call
                respond(body, status, headersOf("Content-Type", "application/json"))
            }
        val http = CloudHttp("https://cloud.test", "public-key", HttpClient(engine) { followRedirects = false })
        val gate =
            CloudSessionGate(credentials).also {
                it.publish(it.capture(requireProfile = false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
            }
        return Triple(SupabaseCloudBackend(gate, http, installation), calls, http)
    }

    private fun Call.string(key: String) = (body?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content

    @Test fun eachFriendRpcSendsExpectedRequestWithBearerAndInstallationProof(): Unit =
        runBlocking {
            val (friends, calls, http) =
                backend { call ->
                    when (call.path.substringAfterLast('/')) {
                        "my_friend_code" -> {
                            "\"K7QF92MX\""
                        }

                        "find_profile" -> {
                            """[{"id":"bob","alias":"Bob","username":"bob_99"}]"""
                        }

                        "send_friend_request" -> {
                            "\"accepted\""
                        }

                        "cloud_my_blocks_v1" -> {
                            """[{"profileId":"eve","alias":"Eve","createdAt":"2026-10-27T10:00:00+00:00"},""" +
                                """{"profileId":"gone","alias":null,"createdAt":"2026-10-26T10:00:00+00:00"}]"""
                        }

                        else -> {
                            ""
                        } // void-RPCs
                    } to HttpStatusCode.OK
                }
            try {
                friends.bindDevice("phone")
                assertEquals("K7QF92MX", friends.friendCode())
                assertEquals(FoundProfile("bob", "Bob", "bob_99"), friends.findProfile("  bob_99 "))
                assertEquals(FriendRequestResult.ACCEPTED, friends.sendFriendRequest("bob", viaCode = true))
                friends.respondFriendRequest("carol", accept = false)
                friends.removeFriend("dave")
                friends.blockUser("eve")
                friends.unblockUser("eve")
                assertEquals(
                    listOf(BlockedProfile("eve", "2026-10-27T10:00:00+00:00", "Eve"), BlockedProfile("gone", "2026-10-26T10:00:00+00:00")),
                    friends.blockedProfiles(),
                )

                assertEquals(
                    listOf(
                        "rpc/my_friend_code",
                        "rpc/find_profile",
                        "rpc/send_friend_request",
                        "rpc/respond_friend_request",
                        "rpc/remove_friend",
                        "rpc/block_user",
                        "rpc/unblock_user",
                        "rpc/cloud_my_blocks_v1",
                    ),
                    calls.map { it.path.removePrefix("/rest/v1/") },
                )
                assertTrue(
                    calls.all {
                        it.bearer == "Bearer token-a" && it.installationId == "installation" &&
                            it.proof == installation.proof
                    },
                )
                assertEquals(List(8) { HttpMethod.Post }, calls.map { it.method })
                assertEquals("bob_99", calls[1].string("p_query"))
                assertEquals("bob", calls[2].string("p_target"))
                assertEquals("code", calls[2].string("p_source"))
                assertEquals("carol", calls[3].string("p_requester"))
                assertEquals("false", calls[3].string("p_accept"))
                assertEquals(listOf("dave", "eve", "eve"), calls.subList(4, 7).map { it.string("p_other") })
                assertEquals(JsonObject(emptyMap()), calls[7].body)
            } finally {
                http.close()
            }
        }

    @Test fun searchResultsAndRequestOutcomesAreDecodedStrictly(): Unit =
        runBlocking {
            var reply = "[]"
            val (friends, _, http) = backend { reply to HttpStatusCode.OK }
            try {
                friends.bindDevice("phone")
                assertNull(friends.findProfile("nobody"))
                reply = """[{"id":"a"},{"id":"b"}]"""
                assertEquals("cloud_response_invalid", assertFailsWith<CloudException> { friends.findProfile("x") }.reason)
                reply = "\"requested\""
                assertEquals(FriendRequestResult.REQUESTED, friends.sendFriendRequest("bob", viaCode = false))
                reply = "\"already_friends\""
                assertEquals(FriendRequestResult.ALREADY_FRIENDS, friends.sendFriendRequest("bob", viaCode = false))
                reply = "\"something_else\""
                assertEquals("cloud_response_invalid", assertFailsWith<CloudException> { friends.sendFriendRequest("bob", false) }.reason)
                reply = "{\"unexpected\":true}"
                assertEquals("cloud_response_invalid", assertFailsWith<CloudException> { friends.friendCode() }.reason)
                reply = "\"\""
                assertEquals("cloud_response_invalid", assertFailsWith<CloudException> { friends.friendCode() }.reason)
            } finally {
                http.close()
            }
        }

    @Test fun serverErrorKeysMapToStableReasonsAndUnknownOnesStayGeneric(): Unit =
        runBlocking {
            var message = ""
            val (friends, _, http) =
                backend { """{"code":"P0001","details":null,"hint":null,"message":"$message"}""" to HttpStatusCode.BadRequest }
            try {
                friends.bindDevice("phone")
                for (reason in listOf("rate_limit_exceeded", "profile_not_found", "request_cooldown", "cannot_befriend_self")) {
                    message = reason
                    assertEquals(reason, assertFailsWith<CloudException> { friends.sendFriendRequest("bob", false) }.reason)
                }
                message = "no_pending_request"
                assertEquals(message, assertFailsWith<CloudException> { friends.respondFriendRequest("bob", true) }.reason)
                message = "rate_limit_exceeded"
                assertEquals(message, assertFailsWith<CloudException> { friends.findProfile("bob") }.reason)
                for (reason in listOf("consent_required", "device_not_authorized", "device_revoked")) {
                    message = reason
                    assertEquals(reason, assertFailsWith<CloudException> { friends.removeFriend("bob") }.reason)
                }
                message = "duplicate key value violates unique constraint \"secret\""
                assertEquals("cloud_connection_failed", assertFailsWith<CloudException> { friends.blockUser("bob") }.reason)
            } finally {
                http.close()
            }
        }

    @Test fun withoutDeviceBindingNothingIsSent(): Unit =
        runBlocking {
            val (friends, calls, http) = backend { "\"X\"" to HttpStatusCode.OK }
            try {
                assertEquals("device_not_authorized", assertFailsWith<CloudException> { friends.friendCode() }.reason)
                assertEquals("device_not_authorized", assertFailsWith<CloudException> { friends.blockedProfiles() }.reason)
                assertTrue(calls.isEmpty())
            } finally {
                http.close()
            }
        }

    @Test fun answerForAccountAArrivingAfterSwitchToBIsDiscarded(): Unit =
        runBlocking {
            var credentials = CloudCredentials("alice", "session-a", "token-a")
            val (friends, calls, http) =
                backend({ credentials }) {
                    // Kontowechsel, während die Antwort für Alice unterwegs ist.
                    credentials = CloudCredentials("bob", "session-b", "token-b")
                    """[{"id":"carol","alias":"Carol"}]""" to HttpStatusCode.OK
                }
            try {
                friends.bindDevice("phone")
                assertEquals("cloud_session_changed", assertFailsWith<CloudException> { friends.findProfile("carol") }.reason)
                assertEquals(1, calls.size)
                // Danach gilt die alte Bindung nicht für Bob.
                assertFailsWith<CloudException> { friends.friendCode() }
                assertEquals(1, calls.size)
            } finally {
                http.close()
            }
        }

    @Test fun telemetrySyncSendsLocalStateAndReturnsTheAccountState(): Unit =
        runBlocking {
            var reply = """{"visibility":"off","counter":3,"origin":"watch-1"}"""
            val (backend, calls, http) = backend { reply to HttpStatusCode.OK }
            try {
                backend.bindDevice("phone")
                val local = TelemetryPreference(TelemetryVisibility.SHARE, 2, "phone-1")
                assertEquals(TelemetryPreference(TelemetryVisibility.OFF, 3, "watch-1"), backend.syncTelemetry("alice", local))
                assertEquals("rpc/cloud_set_telemetry_visibility_v1", calls.single().path.removePrefix("/rest/v1/"))
                assertEquals(listOf("share", "2", "phone-1"), listOf("p_visibility", "p_counter", "p_origin").map { calls[0].string(it) })
                assertEquals("installation", calls[0].installationId)

                // Standard: nur lesen (counter 0, origin null); Konto ohne Änderung bzw. unbekannter Wert → nichts übernehmen.
                reply = """{"visibility":"private","counter":0,"origin":null}"""
                assertNull(backend.syncTelemetry("alice", TelemetryPreference()))
                assertEquals(JsonNull, calls[1].body?.get("p_origin"))
                assertEquals("0", calls[1].string("p_counter"))
                reply = """{"visibility":"friends-only","counter":9,"origin":"x"}"""
                assertNull(backend.syncTelemetry("alice", TelemetryPreference()))
                // Zähler ab 2^53 nimmt der Server nicht an: nur lesen, Serverwerte darüber nie übernehmen.
                reply = """{"visibility":"off","counter":${1L shl 53},"origin":"x"}"""
                assertNull(backend.syncTelemetry("alice", TelemetryPreference(TelemetryVisibility.OFF, Long.MAX_VALUE, "phone-1")))
                assertEquals("0", calls[3].string("p_counter"))
                reply = "[]"
                assertEquals("cloud_response_invalid", assertFailsWith<CloudException> { backend.syncTelemetry("alice", local) }.reason)
            } finally {
                http.close()
            }
        }

    @Test fun telemetryOfAnotherAccountOrWithoutBindingIsNeverSent(): Unit =
        runBlocking {
            val (backend, calls, http) = backend { "{}" to HttpStatusCode.OK }
            try {
                val local = TelemetryPreference(TelemetryVisibility.OFF, 1, "phone-1")
                assertEquals("device_not_authorized", assertFailsWith<CloudException> { backend.syncTelemetry("alice", local) }.reason)
                backend.bindDevice("phone")
                assertEquals("cloud_session_changed", assertFailsWith<CloudException> { backend.syncTelemetry("bob", local) }.reason)
                assertTrue(calls.isEmpty())
            } finally {
                http.close()
            }
        }
}
