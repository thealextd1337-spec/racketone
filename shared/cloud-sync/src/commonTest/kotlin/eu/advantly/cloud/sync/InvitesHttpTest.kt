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

private const val BIND =
    """{"contractVersion":1,"profileId":"alice","sessionId":"session-a","installationId":"installation","deviceId":"phone"}"""
private val TOKEN = "ab12".repeat(16)
private const val PREVIEW_OK =
    """{"status":"ok","kind":"friend","inviterId":"bob","inviterAlias":"Bob","expiresAt":"2026-11-18T10:00:00.5+00:00"}"""
private const val OPEN_ROWS =
    """[{"id":"inv-1","kind":"claim_player","player_id":"guest-1","expires_at":"2026-11-18T10:00:00+00:00"}]"""

/** plan-einladungen §6 „HTTP-Tests cloud-sync“: Rumpf und Name je RPC, strikte Antworten, Fehlercodes, Kontowechsel. */
class InvitesHttpTest {
    private val installation = CloudInstallation("installation", "proof-".repeat(12))

    private class Call(
        val path: String,
        val query: String,
        val method: String,
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
                        request.url.encodedQuery,
                        request.method.value,
                        text?.let { Json.parseToJsonElement(it) as JsonObject },
                        request.headers["Authorization"],
                        request.headers["X-Advantly-Installation-Proof"],
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

    private fun invalid(block: suspend () -> Unit) =
        assertEquals("cloud_response_invalid", assertFailsWith<CloudException> { runBlocking { block() } }.reason)

    @Test fun eachRpcSendsNameAndBodyUnderTheBoundSession(): Unit =
        runBlocking {
            val (backend, calls) =
                backend { call ->
                    when (call.path) {
                        "rpc/create_invite" -> "\"$TOKEN\""
                        "rpc/preview_invite" -> PREVIEW_OK
                        "rpc/redeem_invite" -> """{"kind":"friend","friendId":"bob","playerId":null,"matchCount":0}"""
                        "invites" -> OPEN_ROWS
                        else -> ""
                    } to HttpStatusCode.OK
                }
            backend.bindDevice("phone")
            assertEquals(TOKEN, backend.createInvite("alice", InviteKind.FRIEND, null, 10))
            assertEquals(TOKEN, backend.createInvite("alice", InviteKind.CLAIM_PLAYER, "guest-1", MAX_INVITE_TTL_MINUTES))
            val preview = backend.previewInvite("alice", TOKEN)
            assertEquals(InvitePreview(InviteStatus.OK, InviteKind.FRIEND, "bob", "Bob", expiresAtMillis = 1_794_996_000_500), preview)
            assertEquals(InviteRedemption(InviteKind.FRIEND, "bob"), backend.redeemInvite("alice", TOKEN))
            backend.revokeInvite("alice", "inv-1")
            assertEquals(listOf(OpenInvite("inv-1", InviteKind.CLAIM_PLAYER, "guest-1", 1_794_996_000_000)), backend.openInvites("alice"))

            assertEquals(
                listOf(
                    "rpc/create_invite",
                    "rpc/create_invite",
                    "rpc/preview_invite",
                    "rpc/redeem_invite",
                    "rpc/revoke_invite",
                    "invites",
                ),
                calls.map { it.path },
            )
            assertTrue(calls.all { it.bearer == "Bearer token-a" && it.proof == installation.proof })
            assertEquals(
                JsonObject(mapOf("p_kind" to JsonPrimitive("friend"), "p_player_id" to JsonNull, "p_ttl_minutes" to JsonPrimitive(10))),
                calls[0].body,
            )
            assertEquals(
                JsonObject(
                    mapOf(
                        "p_kind" to JsonPrimitive("claim_player"),
                        "p_player_id" to JsonPrimitive("guest-1"),
                        "p_ttl_minutes" to JsonPrimitive(20160),
                    ),
                ),
                calls[1].body,
            )
            assertEquals(JsonObject(mapOf("p_token" to JsonPrimitive(TOKEN))), calls[2].body)
            assertEquals(JsonObject(mapOf("p_token" to JsonPrimitive(TOKEN))), calls[3].body)
            assertEquals(JsonObject(mapOf("p_invite_id" to JsonPrimitive("inv-1"))), calls[4].body)
            // Nur lesen, nur eigene offene Zeilen, nie token_hash.
            assertEquals("GET", calls[5].method)
            assertTrue(
                calls[5].query.startsWith("created_by=eq.alice&used_at=is.null&revoked_at=is.null&select=id,kind,player_id,expires_at"),
            )
        }

    @Test fun everyPreviewStatusDecodesAndInvalidCarriesNothingElse(): Unit =
        runBlocking {
            var reply = ""
            val (backend, _) = backend { reply to HttpStatusCode.OK }
            backend.bindDevice("phone")
            for (status in InviteStatus.entries.filter { it != InviteStatus.INVALID }) {
                reply =
                    """{"status":"${status.wire}","kind":"friend","inviterId":"bob","inviterAlias":null,"guestName":null,"matchCount":null}"""
                assertEquals(InvitePreview(status, InviteKind.FRIEND, "bob"), backend.previewInvite("alice", TOKEN))
            }
            reply = """{"status":"invalid"}"""
            assertEquals(InvitePreview(InviteStatus.INVALID), backend.previewInvite("alice", TOKEN))
            reply = """{"status":"ok","kind":"claim_player","inviterId":"bob","inviterAlias":"Bob","guestName":"Test1","matchCount":7}"""
            assertEquals(
                InvitePreview(InviteStatus.OK, InviteKind.CLAIM_PLAYER, "bob", "Bob", guestName = "Test1", matchCount = 7),
                backend.previewInvite("alice", TOKEN),
            )
            reply = """{"kind":"claim_player","friendId":"bob","playerId":"guest-1","matchCount":7}"""
            assertEquals(InviteRedemption(InviteKind.CLAIM_PLAYER, "bob", "guest-1", 7), backend.redeemInvite("alice", TOKEN))
            reply = """{"kind":"friend","friendId":"bob","alreadyFriends":true}"""
            assertEquals(InviteRedemption(InviteKind.FRIEND, "bob", alreadyFriends = true), backend.redeemInvite("alice", TOKEN))
        }

    @Test fun malformedAnswersAreInvalidResponses(): Unit =
        runBlocking {
            var reply = ""
            val (backend, _) = backend { reply to HttpStatusCode.OK }
            backend.bindDevice("phone")
            for (bad in listOf("\"${TOKEN.dropLast(1)}\"", "\"${TOKEN.uppercase()}\"", "\"\"", "null", "{}")) {
                reply = bad
                invalid { backend.createInvite("alice", InviteKind.FRIEND, null, 10) }
            }
            for (bad in listOf(
                """{"status":"maybe"}""",
                """{"status":"ok"}""",
                """{"status":"ok","kind":"friend"}""",
                """{"status":"ok","kind":"party","inviterId":"bob"}""",
                """{"status":"ok","kind":"claim_player","inviterId":"bob","matchCount":7}""",
                """{"status":"ok","kind":"claim_player","inviterId":"bob","guestName":"T","matchCount":-1}""",
                """{"status":"ok","kind":"claim_player","inviterId":"bob","guestName":"T","matchCount":"7"}""",
                """{"status":"ok","kind":"friend","inviterId":"bob","expiresAt":"morgen"}""",
                """[]""",
            )) {
                reply = bad
                invalid { backend.previewInvite("alice", TOKEN) }
            }
            for (bad in listOf(
                """{"kind":"friend"}""",
                """{"kind":"friend","friendId":"bob"}""",
                """{"kind":"claim_player","friendId":"bob","matchCount":3}""",
                """{"kind":"friend","friendId":"bob","playerId":"g","matchCount":0}""",
                """{"kind":"claim_player","friendId":"bob","alreadyFriends":true}""",
            )) {
                reply = bad
                invalid { backend.redeemInvite("alice", TOKEN) }
            }
            for (bad in listOf(
                """[{"id":"","kind":"friend","player_id":null,"expires_at":"2026-11-18T10:00:00Z"}]""",
                """[{"id":"x","kind":"claim_player","player_id":null,"expires_at":"2026-11-18T10:00:00Z"}]""",
                """[{"id":"x","kind":"friend","player_id":null}]""",
                """{}""",
            )) {
                reply = bad
                invalid { backend.openInvites("alice") }
            }
        }

    @Test fun serverReasonsPassThroughAndNothingLeavesWithoutBindingOrForAnotherAccount(): Unit =
        runBlocking {
            var reason = ""
            val (backend, calls) = backend { """{"code":"P0001","message":"$reason"}""" to HttpStatusCode.BadRequest }
            assertEquals("device_not_authorized", assertFailsWith<CloudException> { backend.previewInvite("alice", TOKEN) }.reason)
            assertTrue(calls.isEmpty())
            backend.bindDevice("phone")
            assertEquals("cloud_session_changed", assertFailsWith<CloudException> { backend.redeemInvite("bob", TOKEN) }.reason)
            assertEquals(
                "cloud_session_changed",
                assertFailsWith<CloudException> { backend.createInvite("bob", InviteKind.FRIEND, null, 10) }.reason,
            )
            assertEquals("cloud_session_changed", assertFailsWith<CloudException> { backend.openInvites("bob") }.reason)
            assertTrue(calls.isEmpty())
            for (code in listOf(
                "invalid_invite",
                "own_invite",
                "already_assigned",
                "player_redacted",
                "rate_limit_exceeded",
                "consent_required",
            )) {
                reason = code
                assertEquals(code, assertFailsWith<CloudException> { backend.redeemInvite("alice", TOKEN) }.reason)
            }
            for (code in listOf("too_many_open_invites", "player_not_found", "invalid_invite_kind")) {
                reason = code
                assertEquals(
                    code,
                    assertFailsWith<CloudException> {
                        backend.createInvite(
                            "alice",
                            InviteKind.CLAIM_PLAYER,
                            "g",
                            MAX_INVITE_TTL_MINUTES,
                        )
                    }.reason,
                )
            }
            reason = "invite_not_found"
            assertEquals(reason, assertFailsWith<CloudException> { backend.revokeInvite("alice", "inv") }.reason)
            reason = "secret detail"
            assertEquals("cloud_connection_failed", assertFailsWith<CloudException> { backend.previewInvite("alice", TOKEN) }.reason)
            // Ungültige Eingaben gehen gar nicht erst hinaus.
            val before = calls.size
            assertFailsWith<IllegalArgumentException> { backend.previewInvite("alice", "x") }
            assertFailsWith<IllegalArgumentException> { backend.createInvite("alice", InviteKind.CLAIM_PLAYER, null, 10) }
            assertFailsWith<IllegalArgumentException> { backend.createInvite("alice", InviteKind.FRIEND, null, MAX_INVITE_TTL_MINUTES + 1) }
            assertEquals(before, calls.size)
        }
}
