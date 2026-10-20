package eu.advantly.cloud.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudRedactionBoundaryTest {
    private val match = "10000000-0000-0000-0000-000000000001"
    private val player = "20000000-0000-0000-0000-000000000001"
    private val otherPlayer = "20000000-0000-0000-0000-000000000002"
    private val request = CloudRedactionRequest(match, generation = 7, redactionEpoch = 3)

    private fun page() =
        CloudRedactionPage(
            match,
            7,
            3,
            12,
            listOf(player),
            listOf(pullEvent(4, match).copy(redacted = true)),
            player,
            4,
            hasMorePlayers = true,
            hasMoreEvents = true,
        )

    @Test
    fun requestKeepsServerGenerationAndFixedWatermarkAcrossIndependentCursors() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                val attempt = f.backend.capturePull()
                f.redaction = { CloudJson.encodeToString(CloudRedactionPage.serializer(), page()) }
                assertEquals(page(), f.backend.pullRedactionPage(attempt, request))
                val first = f.calls.last().second
                assertEquals(7L, first.long("p_generation"))
                assertEquals(3L, first.long("p_redaction_epoch"))
                assertFalse("p_high_watermark" in first)
                assertFalse("p_after_player_id" in first)
                val next = request.copy(afterPlayerId = player, afterServerSeq = 4, highWatermark = 12, playerLimit = 1, eventLimit = 2)
                val end = page().copy(redactedPlayerIds = emptyList(), events = emptyList(), hasMorePlayers = false, hasMoreEvents = false)
                f.redaction = { CloudJson.encodeToString(CloudRedactionPage.serializer(), end) }
                assertEquals(end, f.backend.pullRedactionPage(attempt, next))
                val body = f.calls.last().second
                assertEquals(player, body.getValue("p_after_player_id").jsonPrimitive.content)
                assertEquals(4L, body.long("p_after_server_seq"))
                assertEquals(12L, body.long("p_high_watermark"))
                assertEquals(1L, body.long("p_player_limit"))
                assertEquals(2L, body.long("p_event_limit"))
            }
        }

    @Test
    fun invalidContextBoundsOrderingAndNonProgressResponsesAreRejected() =
        runBlocking<Unit> {
            val original = page()
            val invalid =
                listOf(
                    original.copy(matchId = player),
                    original.copy(generation = 8),
                    original.copy(redactionEpoch = 4),
                    original.copy(highWatermark = -1),
                    original.copy(redactedPlayerIds = listOf(otherPlayer, player)),
                    original.copy(redactedPlayerIds = listOf(player, player)),
                    original.copy(redactedPlayerIds = listOf(player.uppercase().replace('2', 'A'))),
                    original.copy(nextPlayerId = otherPlayer),
                    original.copy(redactedPlayerIds = emptyList(), nextPlayerId = null),
                    original.copy(events = listOf(pullEvent(4, player).copy(redacted = true))),
                    original.copy(events = listOf(pullEvent(4, match))),
                    original.copy(events = listOf(pullEvent(13, match).copy(redacted = true)), nextServerSeq = 13),
                    original.copy(events = listOf(original.events.single(), original.events.single())),
                    original.copy(nextServerSeq = 3),
                    original.copy(events = emptyList(), nextServerSeq = 0),
                )
            PullFixture().use { f ->
                f.bind()
                val attempt = f.backend.capturePull()
                for (bad in invalid) {
                    f.redaction = { CloudJson.encodeToString(CloudRedactionPage.serializer(), bad) }
                    val error = assertFailsWith<CloudException> { f.backend.pullRedactionPage(attempt, request) }
                    assertEquals("cloud_response_invalid", error.reason)
                }
                f.redaction = { CloudJson.encodeToString(CloudRedactionPage.serializer(), original) }
                assertFailsWith<CloudException> {
                    f.backend.pullRedactionPage(attempt, request.copy(highWatermark = 11))
                }
                assertFailsWith<CloudException> {
                    f.backend.pullRedactionPage(attempt, request.copy(afterPlayerId = player, highWatermark = 12))
                }
                f.redaction = { """{"matchId":"$match"}""" }
                assertFailsWith<CloudException> { f.backend.pullRedactionPage(attempt, request) }
            }
        }

    @Test
    fun staleNonCooperativeResponsesFailAfterEveryAuthorityTransition() =
        runBlocking {
            for (transition in listOf("account", "same-account-login", "logout", "consent", "consent-regain", "deletion", "rebind")) {
                PullFixture().use { f ->
                    f.bind()
                    val attempt = f.backend.capturePull()
                    f.redaction = {
                        withContext(NonCancellable) {
                            when (transition) {
                                "account" -> {
                                    f.credentials = CloudCredentials("bob", "bob-session", "bob-token")
                                }

                                "same-account-login" -> {
                                    f.credentials = CloudCredentials("alice", "new-session", "new-token")
                                }

                                "logout" -> {
                                    f.gate.stop()
                                    f.credentials = null
                                }

                                "consent" -> {
                                    f.publish(ConsentStatus.REQUIRED)
                                }

                                "consent-regain" -> {
                                    f.publish(ConsentStatus.REQUIRED)
                                    f.publish()
                                }

                                "deletion" -> {
                                    f.publish(deleting = true)
                                }

                                "rebind" -> {
                                    f.backend.bindDevice("phone")
                                }
                            }
                            CloudJson.encodeToString(CloudRedactionPage.serializer(), page())
                        }
                    }
                    var rejected = false
                    try {
                        f.backend.pullRedactionPage(attempt, request)
                    } catch (_: CloudException) {
                        rejected = true
                    } catch (_: CancellationException) {
                        rejected = true
                    }
                    assertTrue(rejected, transition)
                }
            }
        }

    @Test
    fun requestRejectsUnboundedOrAmbiguousContinuationAndWireKeepsLegacyBatchUnchanged() {
        assertFailsWith<IllegalArgumentException> { request.copy(playerLimit = 201) }
        assertFailsWith<IllegalArgumentException> { request.copy(eventLimit = 501) }
        assertFailsWith<IllegalArgumentException> { request.copy(afterServerSeq = 1) }
        assertFailsWith<IllegalArgumentException> { request.copy(afterPlayerId = player) }
        assertFailsWith<IllegalArgumentException> { request.copy(afterServerSeq = 5, highWatermark = 4) }
        assertFailsWith<IllegalArgumentException> { request.copy(generation = -1) }
        assertFailsWith<IllegalArgumentException> { request.copy(redactionEpoch = -1) }
        assertFailsWith<IllegalArgumentException> {
            request.copy(
                afterPlayerId = "00000000-0000-0000-0000-000000000000",
                highWatermark = 0,
            )
        }
        assertEquals(
            """{"devices":[],"players":[],"matches":[],"events":[]}""",
            CloudJson.encodeToString(PushBatch.serializer(), PushBatch()),
        )
        val proof = CloudPushLifecycle(7, mapOf(match to 0))
        val encoded = CloudJson.encodeToString(PushBatch.serializer(), PushBatch(lifecycle = proof))
        assertEquals(proof, CloudJson.decodeFromString<PushBatch>(encoded).lifecycle)
        val epochs =
            CloudJson
                .parseToJsonElement(encoded)
                .jsonObject
                .getValue("lifecycle")
                .jsonObject
                .getValue("redactionEpochs")
                .jsonObject
        assertEquals("0", epochs.getValue(match).jsonPrimitive.content)
    }
}
