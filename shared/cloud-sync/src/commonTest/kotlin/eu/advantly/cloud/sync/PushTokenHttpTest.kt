package eu.advantly.cloud.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PushTokenHttpTest {
    private val installation = CloudInstallation("installation", "proof-".repeat(12))

    private fun backend(
        status: HttpStatusCode,
        reply: String,
    ): Pair<SupabaseCloudBackend, MutableList<Pair<String, JsonObject>>> {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath.removePrefix("/rest/v1/")
                if (path.endsWith("bind_cloud_session_v1")) {
                    return@MockEngine respond(
                        """{"contractVersion":1,"profileId":"alice","sessionId":"session-a","installationId":"installation","deviceId":"phone"}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", "application/json"),
                    )
                }
                assertEquals("Bearer token-a", request.headers["Authorization"])
                calls += path to Json.parseToJsonElement((request.body as TextContent).text) as JsonObject
                respond(reply, status, headersOf("Content-Type", "application/json"))
            }
        val http = CloudHttp("https://cloud.test", "public-key", HttpClient(engine) { followRedirects = false })
        val gate =
            CloudSessionGate({ CloudCredentials("alice", "session-a", "token-a") }).also {
                it.publish(it.capture(requireProfile = false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
            }
        return SupabaseCloudBackend(gate, http, installation) to calls
    }

    @Test fun setAndClearUseTheVoidRpcOfTheBoundDevice(): Unit =
        runBlocking {
            // PostgREST antwortet für void-RPCs mit 204 ohne Body.
            val (backend, calls) = backend(HttpStatusCode.NoContent, "")
            backend.bindDevice("phone")
            backend.setPushToken("alice", PUSH_PROVIDER_FCM, "fcm-token")
            backend.setPushToken("alice", PUSH_PROVIDER_FCM, null)
            assertEquals(
                listOf(
                    "rpc/cloud_set_push_token_v1" to
                        JsonObject(mapOf("p_provider" to JsonPrimitive("fcm"), "p_token" to JsonPrimitive("fcm-token"))),
                    "rpc/cloud_set_push_token_v1" to JsonObject(mapOf("p_provider" to JsonPrimitive("fcm"), "p_token" to JsonNull)),
                ),
                calls,
            )
        }

    @Test fun neverSendsWithoutBindingOrForAnotherAccount(): Unit =
        runBlocking {
            val (backend, calls) = backend(HttpStatusCode.BadRequest, """{"code":"P0001","message":"device_not_authorized"}""")
            assertEquals(
                "device_not_authorized",
                assertFailsWith<CloudException> { backend.setPushToken("alice", PUSH_PROVIDER_FCM, "t") }.reason,
            )
            backend.bindDevice("phone")
            assertEquals(
                "cloud_session_changed",
                assertFailsWith<CloudException> { backend.setPushToken("bob", PUSH_PROVIDER_FCM, "t") }.reason,
            )
            assertTrue(calls.isEmpty())
            assertFailsWith<IllegalArgumentException> { backend.setPushToken("alice", PUSH_PROVIDER_FCM, "") }
            assertEquals(
                "device_not_authorized",
                assertFailsWith<CloudException> { backend.setPushToken("alice", PUSH_PROVIDER_FCM, "t") }.reason,
            )
            assertEquals(1, calls.size)
        }
}
