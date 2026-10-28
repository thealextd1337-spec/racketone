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

private const val BIND =
    """{"contractVersion":1,"profileId":"alice","sessionId":"session-a","installationId":"installation","deviceId":"phone"}"""

class VisitorsHttpTest {
    private val installation = CloudInstallation("installation", "proof-".repeat(12))

    private class Call(
        val path: String,
        val body: JsonObject?,
        val bearer: String?,
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
                    )
                val (body, status) =
                    if (call.path.endsWith("bind_cloud_session_v1")) {
                        BIND to HttpStatusCode.OK
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

    @Test fun visitorRpcsUseBoundSessionAndDecodeLists(): Unit =
        runBlocking {
            val (backend, calls) =
                backend { call ->
                    when (call.path) {
                        "rpc/cloud_match_visitors_v1" -> {
                            """[{"profileId":"vera","alias":"Vera","addedBy":"alice","canRevoke":true}]"""
                        }

                        "rpc/my_visitor_matches" -> {
                            """[{"matchId":"m9","sport":"tennis","discipline":"singles","status":"in_progress",
                               "channel":"match-live:m9:1","teams":[{"team":"A","position":1,"name":"Bob"}],
                               "live":{"sets":[[6,4]],"games":[2,1],"points":["30","15"],"serving":"A"}}]"""
                        }

                        else -> {
                            ""
                        }
                    } to HttpStatusCode.OK
                }
            backend.bindDevice("phone")
            backend.addVisitor("alice", "m1", "vera")
            backend.revokeVisitor("alice", "m1", "vera")
            assertEquals(listOf(MatchVisitor("vera", "Vera", "alice", true)), backend.matchVisitors("alice", "m1"))
            val watching = backend.myVisitorMatches("alice").single()
            assertEquals("m9", watching.matchId)
            assertEquals("Bob", watching.teams.single().name)

            assertEquals(
                listOf("rpc/add_match_visitor", "rpc/revoke_match_visitor", "rpc/cloud_match_visitors_v1", "rpc/my_visitor_matches"),
                calls.map { it.path },
            )
            assertTrue(calls.all { it.bearer == "Bearer token-a" })
            val pair = JsonObject(mapOf("p_match_id" to JsonPrimitive("m1"), "p_profile_id" to JsonPrimitive("vera")))
            assertEquals(pair, calls[0].body)
            assertEquals(pair, calls[1].body)
            assertEquals(JsonObject(mapOf("p_match_id" to JsonPrimitive("m1"))), calls[2].body)
        }

    @Test fun serverErrorsSessionChangeAndBlankIdsAreStableReasons(): Unit =
        runBlocking {
            var reply = """{"code":"P0001","message":"visitor_must_be_friend"}""" to HttpStatusCode.BadRequest
            val (backend, calls) = backend { reply }
            assertEquals("device_not_authorized", assertFailsWith<CloudException> { backend.addVisitor("alice", "m1", "vera") }.reason)
            backend.bindDevice("phone")
            // Erwartet das ViewModel noch ein anderes Konto, geht nichts ins Netz.
            for (call in listOf<suspend () -> Unit>(
                { backend.addVisitor("bob", "m1", "vera") },
                { backend.revokeVisitor("bob", "m1", "vera") },
                { backend.matchVisitors("bob", "m1") },
                { backend.myVisitorMatches("bob") },
            )) {
                assertEquals("cloud_session_changed", assertFailsWith<CloudException> { call() }.reason)
            }
            assertTrue(calls.isEmpty())
            assertEquals("visitor_must_be_friend", assertFailsWith<CloudException> { backend.addVisitor("alice", "m1", "vera") }.reason)
            reply = """{"code":"P0001","message":"already_member"}""" to HttpStatusCode.BadRequest
            assertEquals("already_member", assertFailsWith<CloudException> { backend.addVisitor("alice", "m1", "bob") }.reason)
            reply = """[{"profileId":"","addedBy":"alice"}]""" to HttpStatusCode.OK
            assertEquals("cloud_response_invalid", assertFailsWith<CloudException> { backend.matchVisitors("alice", "m1") }.reason)
            reply = """{"not":"a list"}""" to HttpStatusCode.OK
            assertEquals("cloud_response_invalid", assertFailsWith<CloudException> { backend.myVisitorMatches("alice") }.reason)
            assertFailsWith<IllegalArgumentException> { backend.addVisitor("alice", "m1", "") }
        }
}
