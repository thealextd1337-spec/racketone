package eu.advantly.cloud.sync

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SupabasePhoneAuthTest {
    private fun client(engine: MockEngine) =
        createSupabaseClient("https://auth.test", "public-test-key") {
            httpEngine = engine
            defaultLogLevel = LogLevel.NONE
            install(Auth) {
                minimalConfig()
            }
            install(Postgrest)
        }

    private fun body(request: HttpRequestData) = Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    @Test fun emailCodeBindsBeforeAuthAndNeverSendsBirthdayToAuth() =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            val sdk =
                client(
                    MockEngine { request ->
                        requests += request
                        val content = if (request.url.encodedPath.endsWith("issue_age_ticket")) """{"ticket":"random-ticket"}""" else "{}"
                        respond(content, headers = headersOf("Content-Type", "application/json"))
                    },
                )
            try {
                val auth = SupabasePhoneAuth(sdk, "installation-secret", scope = CoroutineScope(coroutineContext + SupervisorJob()))
                val ticket = auth.issueAgeTicket("AT", "2000-01-01")
                auth.sendEmailCode("adult@example.com", ticket)
                assertEquals(
                    listOf("/rest/v1/rpc/issue_age_ticket", "/rest/v1/rpc/bind_age_ticket", "/auth/v1/otp"),
                    requests.map { it.url.encodedPath },
                )
                assertEquals(listOf(HttpMethod.Post, HttpMethod.Post, HttpMethod.Post), requests.map { it.method })
                assertEquals("adult@example.com", body(requests[1]).getValue("p_identity").jsonPrimitive.content)
                assertEquals(
                    ticket,
                    body(requests[2])
                        .getValue("data")
                        .jsonObject
                        .getValue("age_ticket")
                        .jsonPrimitive.content,
                )
                assertFalse((requests[2].body as TextContent).text.contains("2000-01-01"))
            } finally {
                sdk.close()
            }
        }

    @Test fun rejectedBindingNeverReachesAuthEndpointAndErrorsStaySanitized() =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            val sdk =
                client(
                    MockEngine { request ->
                        requests += request
                        respond("""{"error":"age_ticket_expired"}""", headers = headersOf("Content-Type", "application/json"))
                    },
                )
            try {
                val auth = SupabasePhoneAuth(sdk, "installation-secret", scope = CoroutineScope(coroutineContext + SupervisorJob()))
                assertEquals(
                    "age_ticket_expired",
                    assertFailsWith<CloudException> { auth.sendEmailCode("adult@example.com", "old-ticket") }.reason,
                )
                assertEquals(listOf("/rest/v1/rpc/bind_age_ticket"), requests.map { it.url.encodedPath })
            } finally {
                sdk.close()
            }
        }

    @Test fun offlineGlobalSignOutStillClearsSdkSession() =
        runBlocking {
            val sdk =
                client(
                    MockEngine {
                        respond("sensitive-server-detail", HttpStatusCode.ServiceUnavailable)
                    },
                )
            try {
                sdk.auth.awaitInitialization()
                sdk.auth.importAuthToken("local-test-access-token", autoRefresh = false)
                val auth = SupabasePhoneAuth(sdk, "installation-secret", scope = CoroutineScope(coroutineContext + SupervisorJob()))
                assertEquals("connection_failed", assertFailsWith<CloudException> { auth.signOut() }.message)
                assertNull(sdk.auth.currentSessionOrNull())
                assertNull(auth.session.value)
            } finally {
                sdk.close()
            }
        }

    @Test fun googleBindsVerifiedSubjectRouteAndSendsRawNonceOnlyToAuth() =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            val sdk =
                client(
                    MockEngine { request ->
                        requests += request
                        if (request.url.encodedPath.endsWith("bind_age_ticket")) {
                            respond("{}", headers = headersOf("Content-Type", "application/json"))
                        } else {
                            respond(
                                """{"error":"invalid_request","error_description":"sensitive-token-detail"}""",
                                HttpStatusCode.BadRequest,
                                headersOf("Content-Type", "application/json"),
                            )
                        }
                    },
                )
            try {
                val auth = SupabasePhoneAuth(sdk, "installation-secret", scope = CoroutineScope(coroutineContext + SupervisorJob()))
                // Nur Transporttest; die Tokenprüfung bleibt beim Auth-Server.
                val token = "header.eyJzdWIiOiJnb29nbGUtc3ViIn0.signature"
                val error = assertFailsWith<CloudException> { auth.signInWithIdToken(IdTokenProvider.GOOGLE, token, "raw-nonce", "ticket") }
                assertEquals("connection_failed", error.message)
                assertEquals(listOf("/rest/v1/rpc/bind_age_ticket", "/auth/v1/token"), requests.map { it.url.encodedPath })
                assertEquals("google-sub", body(requests[0]).getValue("p_identity").jsonPrimitive.content)
                assertEquals("google", body(requests[1]).getValue("provider").jsonPrimitive.content)
                assertEquals("raw-nonce", body(requests[1]).getValue("nonce").jsonPrimitive.content)
                assertEquals(token, body(requests[1]).getValue("id_token").jsonPrimitive.content)
            } finally {
                sdk.close()
            }
        }
}
