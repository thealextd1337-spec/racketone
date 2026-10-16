package eu.advantly.cloud.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SupabaseSupportTest {
    private val draft =
        SupportDraft("10000000-0000-4000-8000-000000000001", "a".repeat(64), title = "Title", body = "Body", submitted = true)

    @Test fun guestRequestHasNoInventedBearerOrOwnerAndKeepsStableId() =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            val http =
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        respond("""{"id":"${draft.id}","receipt":"ADV-${draft.id}"}""")
                    },
                )
            val port = SupabaseSupport("https://support.test", "sb_publishable_test", http = http)
            try {
                repeat(2) { assertEquals(draft.id, port.submit(draft, port.identity()).id) }
                assertEquals((requests[0].body as TextContent).text, (requests[1].body as TextContent).text)
                assertNull(requests[0].headers[HttpHeaders.Authorization])
                assertEquals("sb_publishable_test", requests[0].headers["apikey"])
                val payload = Json.parseToJsonElement((requests[0].body as TextContent).text).jsonObject
                assertEquals(draft.receiptSecret, payload.getValue("p_receipt_secret").jsonPrimitive.content)
                assertFalse(payload.containsKey("owner_id"))
                assertEquals("{}", payload.getValue("p_diagnostics").toString())
            } finally {
                port.close()
            }
        }

    @Test fun errorDetailsNeverLeakServerResponse() =
        runBlocking {
            val port =
                SupabaseSupport(
                    "https://support.test",
                    "key",
                    http =
                        HttpClient(
                            MockEngine {
                                respond("password=secret", HttpStatusCode.InternalServerError)
                            },
                        ),
                )
            try {
                assertEquals("support_connection_failed", assertFailsWith<CloudException> { port.submit(draft, port.identity()) }.reason)
            } finally {
                port.close()
            }
        }

    @Test fun budgetErrorsRemainActionable() =
        runBlocking {
            val port =
                SupabaseSupport(
                    "https://support.test",
                    "key",
                    http =
                        HttpClient(
                            MockEngine {
                                respond("""{"error":"support_rate_limited"}""")
                            },
                        ),
                )
            try {
                assertEquals("support_rate_limited", assertFailsWith<CloudException> { port.submit(draft, port.identity()) }.reason)
            } finally {
                port.close()
            }
        }

    @Test fun privilegedKeysAndRemoteCleartextAreRejected() {
        val http = HttpClient(MockEngine { error("Must not send") })
        try {
            assertFailsWith<IllegalArgumentException> { SupabaseSupport("http://remote.test", "key", http = http) }
            assertFailsWith<IllegalArgumentException> { SupabaseSupport("https://support.test", "sb_secret_test", http = http) }
            val claims = Base64.UrlSafe.encode("""{"role":"service_role"}""".encodeToByteArray())
            assertFailsWith<IllegalArgumentException> { SupabaseSupport("https://support.test", "header.$claims.signature", http = http) }
        } finally {
            http.close()
        }
    }
}
