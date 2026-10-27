package eu.advantly.cloud.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val BIND_REPLY =
    """{"contractVersion":1,"profileId":"alice","sessionId":"session-a","installationId":"installation","deviceId":"phone"}"""

class SharedMatchesHttpTest {
    private val installation = CloudInstallation("installation", "proof-".repeat(12))

    private class Call(
        val method: HttpMethod,
        val path: String,
        val query: String,
        val body: JsonObject?,
        val bearer: String?,
        val proof: String?,
        val prefer: String?,
    )

    private fun backend(reply: (Call) -> Pair<String, HttpStatusCode>): Pair<SupabaseCloudBackend, MutableList<Call>> {
        val calls = mutableListOf<Call>()
        val engine =
            MockEngine { request ->
                val text = (request.body as? TextContent)?.text
                val call =
                    Call(
                        request.method,
                        request.url.encodedPath.removePrefix("/rest/v1/"),
                        request.url.encodedQuery,
                        text?.let { Json.parseToJsonElement(it) as JsonObject },
                        request.headers["Authorization"],
                        request.headers["X-Advantly-Installation-Proof"],
                        request.headers["Prefer"],
                    )
                val bind = call.path.endsWith("bind_cloud_session_v1")
                if (!bind) calls += call
                val (body, status) =
                    if (bind) {
                        BIND_REPLY to HttpStatusCode.OK
                    } else {
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

    @Test fun participationRpcsAndShareDefaultUseBoundSessionAndOwnProfileRow(): Unit =
        runBlocking {
            val (backend, calls) =
                backend { call ->
                    when (call.path) {
                        "profile_settings" -> {
                            if (call.method ==
                                HttpMethod.Patch
                            ) {
                                """[{"share_matches_default":true}]"""
                            } else {
                                """[{"share_matches_default":false}]"""
                            }
                        }

                        else -> {
                            ""
                        }
                    } to HttpStatusCode.OK
                }
            backend.bindDevice("phone")
            backend.setMyParticipation("alice", "m1", shareWithFriends = true)
            backend.setMyParticipation("alice", "m2", hidden = true)
            backend.rejectMatchParticipation("alice", "m3")
            assertFalse(backend.shareMatchesDefault("alice"))
            backend.setShareMatchesDefault("alice", true)

            assertEquals(
                listOf(
                    "rpc/set_my_participation",
                    "rpc/set_my_participation",
                    "rpc/reject_match_participation",
                    "profile_settings",
                    "profile_settings",
                ),
                calls.map { it.path },
            )
            assertTrue(calls.all { it.bearer == "Bearer token-a" && it.proof == installation.proof })
            assertEquals(
                JsonObject(
                    mapOf("p_match_id" to JsonPrimitive("m1"), "p_share_with_friends" to JsonPrimitive(true), "p_hidden" to JsonNull),
                ),
                calls[0].body,
            )
            assertEquals(JsonPrimitive(true), calls[1].body?.get("p_hidden"))
            assertEquals(JsonNull, calls[1].body?.get("p_share_with_friends"))
            assertEquals(JsonObject(mapOf("p_match_id" to JsonPrimitive("m3"))), calls[2].body)
            assertEquals(HttpMethod.Get, calls[3].method)
            assertEquals("select=share_matches_default", calls[3].query)
            // Nur die eigene Zeile, nur die eine Spalte, mit Rückgabe zur Prüfung.
            assertEquals(HttpMethod.Patch, calls[4].method)
            assertEquals("profile_id=eq.alice&select=share_matches_default", calls[4].query)
            assertEquals(JsonObject(mapOf("share_matches_default" to JsonPrimitive(true))), calls[4].body)
            assertEquals("return=representation", calls[4].prefer)
        }

    @Test fun missingOwnRowServerErrorsAndUnboundSessionAreStableReasons(): Unit =
        runBlocking {
            var settings = "[]"
            val (backend, calls) =
                backend { call ->
                    when (call.path) {
                        "profile_settings" -> settings to HttpStatusCode.OK
                        else -> """{"code":"P0001","message":"no_participation"}""" to HttpStatusCode.BadRequest
                    }
                }
            // Ohne Gerätebindung geht nichts ins Netz.
            assertEquals(
                "device_not_authorized",
                assertFailsWith<CloudException> { backend.rejectMatchParticipation("alice", "m1") }.reason,
            )
            assertTrue(calls.isEmpty())
            backend.bindDevice("phone")
            // Erwartet das ViewModel noch ein anderes Konto als die Sitzung trägt, geht nichts ins Netz.
            for (call in listOf<suspend () -> Unit>(
                { backend.rejectMatchParticipation("bob", "m1") },
                { backend.setShareMatchesDefault("bob", true) },
                { backend.setMyParticipation("bob", "m1", true) },
                { backend.shareMatchesDefault("bob") },
            )) {
                assertEquals("cloud_session_changed", assertFailsWith<CloudException> { call() }.reason)
            }
            assertTrue(calls.isEmpty())
            assertEquals("no_participation", assertFailsWith<CloudException> { backend.rejectMatchParticipation("alice", "m1") }.reason)
            assertEquals("no_participation", assertFailsWith<CloudException> { backend.setMyParticipation("alice", "m1", true) }.reason)
            assertEquals("cloud_response_invalid", assertFailsWith<CloudException> { backend.shareMatchesDefault("alice") }.reason)
            // Ein Update, das die Zeile nicht (oder anders) zurückgibt, gilt nicht als gespeichert.
            assertEquals("cloud_response_invalid", assertFailsWith<CloudException> { backend.setShareMatchesDefault("alice", true) }.reason)
            settings = """[{"share_matches_default":false}]"""
            assertEquals("cloud_response_invalid", assertFailsWith<CloudException> { backend.setShareMatchesDefault("alice", true) }.reason)
            assertFailsWith<IllegalArgumentException> { backend.setMyParticipation("alice", "m1") }
        }
}
