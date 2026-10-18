package eu.advantly.cloud.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudSessionHttpTest {
    private val installation = CloudInstallation("installation", "proof-".repeat(12))

    private fun confirmation(
        device: String = "phone",
        profile: String = "alice",
        session: String = "session-a",
        install: String = "installation",
    ) = """{"contractVersion":1,"profileId":"$profile","sessionId":"$session","installationId":"$install","deviceId":"$device"}"""

    @Test fun unboundAndUnimplementedServerNeverFallBackToLegacyRpc(): Unit =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            val gate = gate()
            val http =
                http { request ->
                    requests += request
                    "missing" to HttpStatusCode.NotFound
                }
            try {
                val backend = SupabaseCloudBackend(gate, http, installation)
                assertEquals("device_not_authorized", assertFailsWith<CloudException> { backend.pull(0) }.reason)
                assertTrue(requests.isEmpty())
                assertFailsWith<CloudException> { backend.bindDevice("phone") }
                assertFailsWith<CloudException> { backend.push(PushBatch()) }
                assertEquals(listOf("/rest/v1/rpc/bind_cloud_session_v1"), requests.map { it.url.encodedPath })
            } finally {
                http.close()
            }
        }

    @Test fun mismatchedServerConfirmationsNeverAuthorizeCloud(): Unit =
        runBlocking {
            val invalid =
                listOf(
                    confirmation(profile = "bob"),
                    confirmation(session = "other"),
                    confirmation(install = "other"),
                    confirmation(device = "other"),
                    confirmation().replace("\"contractVersion\":1", "\"contractVersion\":2"),
                    "{}",
                )
            for (reply in invalid) {
                val paths = mutableListOf<String>()
                val http =
                    http {
                        paths += it.url.encodedPath
                        reply to HttpStatusCode.OK
                    }
                try {
                    val backend = SupabaseCloudBackend(gate(), http, installation)
                    assertFailsWith<CloudException> { backend.bindDevice("phone") }
                    assertFailsWith<CloudException> { backend.pull(0) }
                    assertEquals(1, paths.size)
                } finally {
                    http.close()
                }
            }
        }

    @Test fun delayedOlderConfirmationCannotRestoreBindingAfterNewerAttemptFailed(): Unit =
        runBlocking {
            val olderSent = CompletableDeferred<Unit>()
            val releaseOlder = CompletableDeferred<Unit>()
            val paths = mutableListOf<String>()
            val http =
                http { request ->
                    paths += request.url.encodedPath
                    if (paths.size == 1) {
                        olderSent.complete(Unit)
                        releaseOlder.await()
                        confirmation() to HttpStatusCode.OK
                    } else {
                        """{"error":"device_revoked"}""" to HttpStatusCode.Forbidden
                    }
                }
            try {
                val backend = SupabaseCloudBackend(gate(), http, installation)
                val older = async { runCatching { backend.bindDevice("phone") } }
                withTimeout(5_000) { olderSent.await() }
                assertEquals("device_revoked", assertFailsWith<CloudException> { backend.bindDevice("phone") }.reason)
                releaseOlder.complete(Unit)
                val failure = withTimeout(5_000) { older.await() }.exceptionOrNull()
                assertEquals("device_not_authorized", (failure as? CloudException)?.reason)
                assertEquals("device_not_authorized", assertFailsWith<CloudException> { backend.pull(0) }.reason)
                assertEquals(List(2) { "/rest/v1/rpc/bind_cloud_session_v1" }, paths)
            } finally {
                releaseOlder.complete(Unit)
                http.close()
            }
        }

    @Test fun refreshKeepsGenerationAndEachRequestUsesCapturedBearerAndProof(): Unit =
        runBlocking {
            var credentials = CloudCredentials("alice", "session-a", "token-a")
            val gate = gate { credentials }
            val generation = gate.session.value!!.generation
            val requests = mutableListOf<HttpRequestData>()
            val http =
                http { request ->
                    requests += request
                    (if (request.url.encodedPath.endsWith("bind_cloud_session_v1")) confirmation() else """{"cursor":0}""") to
                        HttpStatusCode.OK
                }
            try {
                val backend = SupabaseCloudBackend(gate, http, installation)
                backend.bindDevice("phone")
                credentials = CloudCredentials("alice", "session-a", "token-refreshed")
                backend.pull(0)
                assertEquals(generation, gate.session.value!!.generation)
                assertEquals(listOf("Bearer token-a", "Bearer token-refreshed"), requests.map { it.headers["Authorization"] })
                assertTrue(requests.all { it.headers["X-Advantly-Installation-Proof"] == installation.proof })
                assertFalse(installation.toString().contains(installation.proof))
                assertFalse(credentials.toString().contains("token-refreshed"))
            } finally {
                http.close()
            }
        }

    @Test fun currentSdkIdentityIsCheckedWithoutWaitingForCollector(): Unit =
        runBlocking {
            for (replacement in listOf(
                CloudCredentials("bob", "session-b", "token-b"),
                CloudCredentials("alice", "new-session-a", "token-new"),
            )) {
                var credentials = CloudCredentials("alice", "session-a", "token-a")
                val gate = gate { credentials }
                val requests = mutableListOf<HttpRequestData>()
                val http =
                    http {
                        requests += it
                        confirmation() to HttpStatusCode.OK
                    }
                try {
                    val backend = SupabaseCloudBackend(gate, http, installation)
                    backend.bindDevice("phone")
                    credentials = replacement
                    assertFailsWith<CloudException> { backend.push(PushBatch()) }
                    assertEquals(1, requests.size)
                    assertEquals("Bearer token-a", requests.single().headers["Authorization"])
                } finally {
                    http.close()
                }
            }
        }

    @Test fun switchDuringHttpNeverReturnsLateResponseOrUsesNewBearer(): Unit =
        runBlocking {
            var credentials = CloudCredentials("alice", "session-a", "token-a")
            val gate = gate { credentials }
            val sent = CompletableDeferred<HttpRequestData>()
            val release = CompletableDeferred<Unit>()
            val http =
                http { request ->
                    if (request.url.encodedPath.endsWith("bind_cloud_session_v1")) {
                        confirmation() to HttpStatusCode.OK
                    } else {
                        sent.complete(request)
                        release.await()
                        """{"cursor":0}""" to HttpStatusCode.OK
                    }
                }
            try {
                val backend = SupabaseCloudBackend(gate, http, installation)
                backend.bindDevice("phone")
                val pending = async { runCatching { backend.pull(0) } }
                assertEquals("Bearer token-a", withTimeout(5_000) { sent.await() }.headers["Authorization"])
                credentials = CloudCredentials("bob", "session-b", "token-b")
                release.complete(Unit)
                assertTrue(pending.await().isFailure)
            } finally {
                http.close()
            }
        }

    @Test fun stopCancelsRequestAndLateBindingCannotReopenGate(): Unit =
        runBlocking {
            val gate = gate()
            val sent = CompletableDeferred<Unit>()
            val http =
                http {
                    sent.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    confirmation() to HttpStatusCode.OK
                }
            try {
                val backend = SupabaseCloudBackend(gate, http, installation)
                val pending = async(start = CoroutineStart.UNDISPATCHED) { backend.bindDevice("phone") }
                withTimeout(5_000) { sent.await() }
                gate.stop()
                withTimeout(5_000) { pending.join() }
                assertTrue(pending.isCancelled)
                assertFailsWith<CloudException> { backend.pull(0) }
            } finally {
                http.close()
            }
        }

    @Test fun nonSuccessResponsesPreserveOnlyStructuredWhitelistedReasons(): Unit =
        runBlocking {
            val http = http { """{"code":"P0001","message":"username_taken","details":"secret-token"}""" to HttpStatusCode.BadRequest }
            try {
                val error = assertFailsWith<CloudException> { http.rpc("finish_onboarding", buildJsonObject {}, "token") }
                assertEquals("username_taken", error.message)
                assertFalse(error.toString().contains("secret-token"))
            } finally {
                http.close()
            }
        }

    @Test fun ownedTimeoutIsConnectionFailureAndCallerCancellationStillPropagates(): Unit =
        runBlocking {
            val engine =
                MockEngine {
                    CompletableDeferred<Unit>().await()
                    respond("{}")
                }
            val http = CloudHttp("https://cloud.test", "public-key", HttpClient(engine) { followRedirects = false }, timeoutMillis = 20)
            try {
                assertEquals(
                    "cloud_connection_failed",
                    assertFailsWith<CloudException> { http.rpc("test", buildJsonObject {}, "token") }.reason,
                )
                val cancelled = async { http.rpc("test", buildJsonObject {}, "token") }
                cancelled.cancelAndJoin()
                assertTrue(cancelled.isCancelled)
                assertTrue(coroutineContext[kotlinx.coroutines.Job]!!.isActive)
            } finally {
                http.close()
            }
        }

    @Test fun oversizedResponseStopsStreamingBeforeWholeProducerIsConsumed(): Unit =
        runBlocking {
            val channel = ByteChannel(autoFlush = true)
            var produced = 0
            val producer =
                launch {
                    runCatching {
                        repeat(1_000) {
                            channel.writeFully(ByteArray(32_768))
                            produced += 32_768
                        }
                        channel.close()
                    }
                }
            val engine = MockEngine { respond(channel, headers = headersOf("Content-Type", "application/json")) }
            val http = CloudHttp("https://cloud.test", "public-key", HttpClient(engine) { followRedirects = false })
            try {
                val error = assertFailsWith<CloudException> { http.rpc("test", buildJsonObject {}, "token") }
                assertEquals("cloud_response_invalid", error.reason)
                assertTrue(produced < 16_000_000, "Die Antwort wurde vor der Größenprüfung vollständig gepuffert")
            } finally {
                producer.cancelAndJoin()
                http.close()
            }
        }

    private fun gate(credentials: () -> CloudCredentials? = { CloudCredentials("alice", "session-a", "token-a") }): CloudSessionGate =
        CloudSessionGate(credentials).also {
            it.publish(it.capture(requireProfile = false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
        }

    private fun http(reply: suspend (HttpRequestData) -> Pair<String, HttpStatusCode>): CloudHttp {
        val engine =
            MockEngine { request ->
                val (body, status) = reply(request)
                respond(body, status, headersOf("Content-Type", "application/json"))
            }
        return CloudHttp("https://cloud.test", "public-key", HttpClient(engine) { followRedirects = false })
    }
}
