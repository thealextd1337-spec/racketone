package eu.advantly.cloud.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CloudDataExportHttpTest {
    private val id = "11111111-1111-1111-1111-111111111111"
    private val context = CloudDataExportContext("alice", 7, 0)
    private val ready = CloudDataExportStatus.Ready(id, 0, 86_400_000, 3, "a".repeat(64))
    private val hash = "a".repeat(64)
    private val metadata get() = """{"contractVersion":1,"requestId":"$id","status":"ready",
        "exportedAt":"1970-01-01T00:00:00Z","expiresAt":"1970-01-02T00:00:00Z",
        "byteLength":3,"sha256":"$hash","downloadUrl":"/functions/v1/export-my-data/$id/download"}"""

    private fun adapter(
        http: CloudHttp,
        timeout: Long = 20_000,
    ): SupabaseDataExport =
        SupabaseDataExport(http, { CloudCredentials("alice", "session", "fixed-token") }, {}, timeout, timeout)
            .also { it.publish(CloudSession("alice", false, false, generation = 7)) }

    private fun http(engine: MockEngine) =
        CloudHttp(
            "https://auth.test",
            "public-key",
            HttpClient(engine) { followRedirects = false },
            exportFactory = ExportMockEngine(engine),
        )

    @Test fun methodsPathsAndBearerAreExplicitAndRetriesKeepTheId(): Unit =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            val http =
                http(
                    MockEngine { request ->
                        requests += request
                        if (requests.size == 1) {
                            respond("private detail", HttpStatusCode.ServiceUnavailable)
                        } else {
                            respond(metadata, headers = headersOf("Content-Type", "application/json"))
                        }
                    },
                )
            try {
                val api = adapter(http)
                assertEquals("export_unavailable", assertFailsWith<CloudException> { api.request(context, id) }.reason)
                assertEquals(ready, api.request(context, id))
                assertEquals(ready, api.status(context, id))
                assertEquals(listOf(HttpMethod.Post, HttpMethod.Post, HttpMethod.Get), requests.map { it.method })
                assertEquals(listOf("", "", "/$id").map { "/functions/v1/export-my-data$it" }, requests.map { it.url.encodedPath })
                assertTrue(requests.all { it.headers["Authorization"] == "Bearer fixed-token" })
                assertEquals("""{"requestId":"$id"}""", (requests[0].body as TextContent).text)
            } finally {
                http.close()
            }
        }

    @Test fun pendingAndBusyNeverInventAnotherRequestsPendingState(): Unit =
        runBlocking {
            for (code in listOf(202, 409)) {
                val body =
                    if (code == 202) {
                        """{"contractVersion":1,"requestId":"$id","status":"pending","retryAfterSeconds":3}"""
                    } else {
                        """{"contractVersion":1,"error":"export_busy"}"""
                    }
                val http =
                    http(
                        MockEngine {
                            respond(
                                body,
                                HttpStatusCode.fromValue(code),
                                headersOf("Content-Type" to listOf("application/json"), "Retry-After" to listOf("3")),
                            )
                        },
                    )
                try {
                    val result = adapter(http).request(context, id)
                    if (code == 202) {
                        assertEquals(CloudDataExportStatus.Pending(id, 3), result)
                    } else {
                        assertEquals(CloudDataExportStatus.Busy(3), result)
                    }
                } finally {
                    http.close()
                }
            }
        }

    @Test fun statusErrorsAreStableAndDoNotLeakProviderBodies(): Unit =
        runBlocking {
            for ((status, reason) in mapOf(
                401 to "export_not_authenticated",
                404 to "export_not_found",
                410 to "export_expired",
                413 to "export_too_large",
                503 to "export_unavailable",
                302 to "export_unavailable",
            )) {
                val http =
                    http(
                        MockEngine {
                            respond(
                                "secret body",
                                HttpStatusCode.fromValue(status),
                                headersOf("Location", "https://other.test"),
                            )
                        },
                    )
                try {
                    val error = assertFailsWith<CloudException> { adapter(http).status(context, id) }
                    assertEquals(reason, error.reason)
                    assertEquals(status == 401, error.permission)
                } finally {
                    http.close()
                }
            }
        }

    @Test fun invalidMetadataNeverBecomesReady(): Unit =
        runBlocking {
            val invalid =
                listOf(
                    metadata.replace("contractVersion\":1", "contractVersion\":2"),
                    metadata.replace("requestId\":\"$id", "requestId\":\"22222222-2222-2222-2222-222222222222"),
                    metadata.replace("byteLength\":3", "byteLength\":16777217"),
                    metadata.replace("byteLength\":3", "byteLength\":\"3\""),
                    metadata.replace("a".repeat(64), "x".repeat(64)),
                    metadata.replace("1970-01-02T00:00:00Z", "1969-01-01T00:00:00Z"),
                    metadata.replace("1970-01-02T00:00:00Z", "invalid"),
                    metadata.replace("/functions/v1/export-my-data/$id/download", "https://evil.test/download"),
                    metadata.replace("/download", "/download?token=secret"),
                    metadata.replace("/download", "/download#fragment"),
                    metadata.replace("/download", "/../download"),
                    "{}",
                    "not json",
                    " ".repeat(65_537),
                )
            for (body in invalid) {
                val http = http(MockEngine { respond(body, headers = headersOf("Content-Type", "application/json")) })
                try {
                    assertEquals(
                        "export_response_invalid",
                        assertFailsWith<CloudException> { adapter(http).status(context, id) }.reason,
                        body.take(100),
                    )
                } finally {
                    http.close()
                }
            }
        }

    @Test fun pendingIdAndRetryHeadersMustMatchTheConfirmedContract(): Unit =
        runBlocking {
            val pending = """{"contractVersion":1,"requestId":"$id","status":"pending","retryAfterSeconds":3}"""
            for ((body, retry) in listOf(
                pending.replace(id, "22222222-2222-2222-2222-222222222222") to "3",
                pending to "0",
                pending to "date",
                pending.replace("Seconds\":3", "Seconds\":4") to "3",
            )) {
                val http =
                    http(
                        MockEngine {
                            respond(
                                body,
                                HttpStatusCode.Accepted,
                                headersOf(
                                    "Content-Type" to listOf("application/json"),
                                    "Retry-After" to listOf(retry),
                                ),
                            )
                        },
                    )
                try {
                    assertEquals("export_response_invalid", assertFailsWith<CloudException> { adapter(http).request(context, id) }.reason)
                } finally {
                    http.close()
                }
            }
        }

    @Test fun streamRequiresExactBytesMimeAndOptionalHonestLength(): Unit =
        runBlocking {
            for ((body, mime, length) in listOf(
                Triple("zip", "application/zip", null),
                Triple("zi", "application/zip", null),
                Triple("zipp", "application/zip", null),
                Triple("zip", "text/plain", null),
                Triple("zip", "application/zip", "2"),
                Triple("zip", "application/zip", "invalid"),
                Triple("zip", "application/zip", "3"),
            )) {
                val headers =
                    io.ktor.http.Headers.build {
                        append("Content-Type", mime)
                        if (length != null) append("Content-Length", length)
                    }
                val http = http(MockEngine { respond(body, headers = headers) })
                try {
                    val received = mutableListOf<Byte>()
                    val result =
                        runCatching {
                            adapter(http).download(
                                context,
                                ready,
                                CloudDataExportSink { bytes, offset, count ->
                                    assertTrue(count <= 65_536)
                                    received.addAll(bytes.copyOfRange(offset, offset + count).toList())
                                },
                            )
                        }
                    if (body == "zip" && mime == "application/zip" && (length == null || length == "3")) {
                        result.getOrThrow()
                        assertEquals("zip", received.toByteArray().decodeToString())
                    } else {
                        assertIs<CloudException>(result.exceptionOrNull())
                    }
                } finally {
                    http.close()
                }
            }
        }

    @Test fun untrustedLengthCannotExceedZipBudgetAndFramesStaySmall(): Unit =
        runBlocking {
            val channel = ByteChannel(autoFlush = true)
            val http = http(MockEngine { respond(channel, headers = headersOf("Content-Type", "application/zip")) })
            val producer =
                launch {
                    try {
                        val chunk = ByteArray(8192)
                        repeat(2048) { channel.writeFully(chunk) }
                        channel.writeFully(byteArrayOf(1))
                        channel.close()
                    } catch (_: Exception) {
                        channel.cancel(null)
                    }
                }
            try {
                var received = 0L
                val maximum = ready.copy(byteLength = 16_777_216)
                assertEquals(
                    "export_response_invalid",
                    assertFailsWith<CloudException> {
                        adapter(http).download(
                            context,
                            maximum,
                            CloudDataExportSink { _, _, count ->
                                assertTrue(count in 1..65_536)
                                received += count
                            },
                        )
                    }.reason,
                )
                assertTrue(received <= maximum.byteLength)
            } finally {
                producer.cancel()
                channel.cancel(null)
                http.close()
            }
        }

    @Test fun invalidationCancelsOnlyJobsRegisteredBeforeTheNewRevisionWasPublished(): Unit =
        runBlocking {
            val body = """{"contractVersion":1,"requestId":"$id","status":"pending","retryAfterSeconds":3}"""
            val http =
                http(
                    MockEngine {
                        respond(
                            body,
                            HttpStatusCode.Accepted,
                            headersOf("Content-Type" to listOf("application/json"), "Retry-After" to listOf("3")),
                        )
                    },
                )
            val result = CompletableDeferred<Result<CloudDataExportStatus>>()
            val api = adapter(http)
            val collector =
                launch(Dispatchers.Unconfined) {
                    api.context.filterNotNull().drop(1).take(1).collect { next ->
                        launch(start = CoroutineStart.UNDISPATCHED) { result.complete(runCatching { api.request(next, id) }) }
                    }
                }
            try {
                api.invalidate(CloudSession("alice", false, false, generation = 7))
                assertEquals(CloudDataExportStatus.Pending(id, 3), result.await().getOrThrow())
                assertFailsWith<CloudException> { api.requireCurrent(context) }
            } finally {
                collector.cancel()
                http.close()
            }
        }

    @Test fun malformedIdsAndReadyBoundsNeverSendRequests(): Unit =
        runBlocking {
            var requests = 0
            val http =
                http(
                    MockEngine {
                        requests++
                        respond("unexpected")
                    },
                )
            try {
                val api = adapter(http)
                for (invalid in listOf("../$id", "$id?x=y", "$id#fragment", "", "not-a-uuid")) {
                    assertFailsWith<CloudException> { api.request(context, invalid) }
                    assertFailsWith<CloudException> { api.status(context, invalid) }
                }
                for (invalid in listOf(
                    ready.copy(byteLength = 16_777_217),
                    ready.copy(byteLength = 0),
                    ready.copy(expiresAtMs = 0),
                    ready.copy(sha256 = "not-a-hash"),
                )) {
                    assertFailsWith<CloudException> {
                        api.download(
                            context,
                            invalid,
                            CloudDataExportSink {
                                _,
                                _,
                                _,
                                ->
                                error("must not write")
                            },
                        )
                    }
                }
                assertEquals(0, requests)
            } finally {
                http.close()
            }
        }

    @Test fun pendingDownloadSinkFailureAndCancellationCannotSucceed(): Unit =
        runBlocking {
            for (status in listOf(202, 409, 413)) {
                val http = http(MockEngine { respond("{}", HttpStatusCode.fromValue(status)) })
                try {
                    assertFailsWith<CloudException> {
                        adapter(http).download(
                            context,
                            ready,
                            CloudDataExportSink {
                                _,
                                _,
                                _,
                                ->
                                error("must not write")
                            },
                        )
                    }
                } finally {
                    http.close()
                }
            }
            val http = http(MockEngine { respond("zip", headers = headersOf("Content-Type", "application/zip")) })
            try {
                assertEquals(
                    "export_unavailable",
                    assertFailsWith<CloudException> {
                        adapter(http).download(
                            context,
                            ready,
                            CloudDataExportSink {
                                _,
                                _,
                                _,
                                ->
                                error("private filesystem detail")
                            },
                        )
                    }.reason,
                )
                assertFailsWith<CancellationException> {
                    adapter(http).download(
                        context,
                        ready,
                        CloudDataExportSink {
                            _,
                            _,
                            _,
                            ->
                            throw CancellationException("cancel")
                        },
                    )
                }
            } finally {
                http.close()
            }
        }

    @Test fun metadataAndSinkWritingHaveWholeOperationDeadlines(): Unit =
        runBlocking {
            val slow =
                http(
                    MockEngine {
                        delay(200)
                        respond(metadata, headers = headersOf("Content-Type", "application/json"))
                    },
                )
            try {
                assertEquals("export_timeout", assertFailsWith<CloudException> { adapter(slow, 30).request(context, id) }.reason)
            } finally {
                slow.close()
            }
            val http = http(MockEngine { respond("zip", headers = headersOf("Content-Type", "application/zip")) })
            try {
                assertEquals(
                    "export_timeout",
                    assertFailsWith<CloudException> {
                        adapter(http, 30).download(
                            context,
                            ready,
                            CloudDataExportSink {
                                _,
                                _,
                                _,
                                ->
                                delay(200)
                            },
                        )
                    }.reason,
                )
            } finally {
                http.close()
            }
        }

    @Test fun uninstrumentedInjectedEngineNeverStartsExportButKeepsRpc(): Unit =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    respond("{}", headers = headersOf("Content-Type", "application/json"))
                }
            val http =
                CloudHttp(
                    "https://auth.test",
                    "public-key",
                    HttpClient(engine),
                    exportFactory = exportHttpTransportFactory(engine),
                )
            try {
                assertEquals("export_unavailable", assertFailsWith<CloudException> { adapter(http).request(context, id) }.reason)
                assertEquals(0, requests)
                assertEquals("{}", http.rpc("export_my_data", buildJsonObject {}, "fixed-token"))
                assertEquals(1, requests)
            } finally {
                http.close()
            }
        }

    @Test fun exportCancellationAndLeaseCloseDoNotCancelParallelRpc(): Unit =
        runBlocking {
            val rpcEntered = CompletableDeferred<Unit>()
            val releaseRpc = CompletableDeferred<Unit>()
            val pending = """{"contractVersion":1,"requestId":"$id","status":"pending","retryAfterSeconds":3}"""
            val http =
                http(
                    MockEngine { request ->
                        if (request.url.encodedPath.startsWith("/rest/")) {
                            rpcEntered.complete(Unit)
                            releaseRpc.await()
                            respond("{}", headers = headersOf("Content-Type", "application/json"))
                        } else {
                            respond(
                                pending,
                                HttpStatusCode.Accepted,
                                headersOf("Content-Type" to listOf("application/json"), "Retry-After" to listOf("3")),
                            )
                        }
                    },
                )
            val rpc = async { http.rpc("export_my_data", buildJsonObject {}, "rpc-token") }
            try {
                withTimeout(5_000) { rpcEntered.await() }
                val api = adapter(http)
                assertEquals(CloudDataExportStatus.Pending(id, 3), api.request(context, id))
                api.invalidate()
                assertFalse(rpc.isCompleted)
                releaseRpc.complete(Unit)
                assertEquals("{}", withTimeout(5_000) { rpc.await() })
            } finally {
                releaseRpc.complete(Unit)
                http.close()
            }
        }

    /** Fuehrt ausschliesslich die Export-/Engine-Tasks aus; Zeit vergeht nur durch advanceBy. */
    @OptIn(InternalCoroutinesApi::class)
    private class ExportDispatcher :
        CoroutineDispatcher(),
        Delay {
        private val pending = ArrayDeque<Runnable>()

        private data class Timer(
            val at: Long,
            val action: Runnable,
        )

        private val timers = mutableListOf<Timer>()
        private var now = 0L

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            pending.addLast(block)
        }

        fun runUntilIdle() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }

        fun advanceBy(millis: Long) {
            val target = now + millis
            while (true) {
                val next = timers.filter { it.at <= target }.minByOrNull { it.at } ?: break
                timers.remove(next)
                now = next.at
                next.action.run()
                runUntilIdle()
            }
            now = target
            runUntilIdle()
        }

        override fun scheduleResumeAfterDelay(
            timeMillis: Long,
            continuation: CancellableContinuation<Unit>,
        ) {
            val handle = invokeOnTimeout(timeMillis, Runnable { continuation.resume(Unit) }, continuation.context)
            continuation.invokeOnCancellation { handle.dispose() }
        }

        override fun invokeOnTimeout(
            timeMillis: Long,
            block: Runnable,
            context: CoroutineContext,
        ): DisposableHandle {
            val timer = Timer(now + timeMillis, block)
            timers += timer
            return object : DisposableHandle {
                override fun dispose() {
                    timers.remove(timer)
                }
            }
        }
    }

    private enum class TransportPhase { HEADERS, BODY, HEADER_FAILURE, SINK_FAILURE, MIME_FAILURE }

    @OptIn(io.ktor.utils.io.InternalAPI::class)
    private fun transportDrainRace(
        phase: TransportPhase,
        timeout: Boolean,
        cancelCaller: Boolean = false,
    ) = runBlocking {
        val dispatcher = ExportDispatcher()
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val requestEntered = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val transportCompleted = CompletableDeferred<Unit>()
        val bodyReceived = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        var engineRequest: Job? = null
        val channel = ByteChannel(autoFlush = true)
        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    this.dispatcher = dispatcher
                    addHandler {
                        suspend fun holdCleanup() {
                            withContext(NonCancellable) {
                                cancellationObserved.complete(Unit)
                                releaseCleanup.await()
                                transportCompleted.complete(Unit)
                            }
                        }
                        engineRequest = it.executionContext
                        requestEntered.complete(Unit)
                        if (phase == TransportPhase.HEADERS) {
                            try {
                                never.await()
                            } finally {
                                holdCleanup()
                            }
                            error("Headers must not be delivered")
                        } else {
                            CoroutineScope(
                                io.ktor.client.engine
                                    .callContext(),
                            ).launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    never.await()
                                } finally {
                                    holdCleanup()
                                }
                            }
                            if (phase == TransportPhase.HEADER_FAILURE) error("Pre-header transport failure")
                            channel.writeFully(byteArrayOf(1))
                            val mime = if (phase == TransportPhase.MIME_FAILURE) "text/plain" else "application/zip"
                            respond(channel, headers = headersOf("Content-Type", mime))
                        }
                    }
                },
            )
        val http = http(engine)
        val api = adapter(http)
        var failure: Throwable? = null
        val export =
            scope.launch {
                failure =
                    runCatching {
                        if (phase == TransportPhase.HEADERS) {
                            api.request(context, id)
                        } else {
                            api.download(
                                context,
                                ready,
                                CloudDataExportSink { _, _, _ ->
                                    bodyReceived.complete(Unit)
                                    if (phase == TransportPhase.SINK_FAILURE) error("Sink failure")
                                },
                            )
                        }
                    }.exceptionOrNull()
            }
        try {
            dispatcher.runUntilIdle()
            assertTrue(requestEntered.isCompleted)
            if (phase == TransportPhase.BODY) assertTrue(bodyReceived.isCompleted)
            assertFalse(export.isCompleted)
            if (timeout) {
                dispatcher.advanceBy(20_000)
            } else if (cancelCaller) {
                export.cancel()
                dispatcher.runUntilIdle()
            } else if (phase == TransportPhase.HEADERS || phase == TransportPhase.BODY) {
                api.invalidate()
                dispatcher.runUntilIdle()
            }
            assertTrue(cancellationObserved.isCompleted, "Transport must observe cancellation before its teardown is released")
            assertFalse(transportCompleted.isCompleted)
            assertFalse(engineRequest!!.isCompleted)
            assertFalse(export.isCompleted, "Export completed while transport cleanup was still held")
        } finally {
            releaseCleanup.complete(Unit)
            dispatcher.runUntilIdle()
            assertTrue(transportCompleted.isCompleted)
            assertTrue(engineRequest!!.isCompleted)
            assertTrue(export.isCompleted)
            if (phase != TransportPhase.HEADERS && phase != TransportPhase.HEADER_FAILURE) assertTrue(channel.isClosedForRead)
            assertTrue(failure != null)
            if (timeout) assertEquals("export_timeout", assertIs<CloudException>(failure).reason)
            channel.cancel(null)
            http.close()
            scope.cancel()
            dispatcher.runUntilIdle()
        }
    }

    @Test fun invalidationBeforeHeadersWaitsForTransportCleanup() = transportDrainRace(TransportPhase.HEADERS, false)

    @Test fun metadataTimeoutWaitsForTransportCleanup() = transportDrainRace(TransportPhase.HEADERS, true)

    @Test fun invalidationDuringBodyWaitsForTransportCleanup() = transportDrainRace(TransportPhase.BODY, false)

    @Test fun callerCancellationBeforeHeadersWaitsForTransportCleanup() = transportDrainRace(TransportPhase.HEADERS, false, true)

    @Test fun failureBeforeHeadersWaitsForTransportCleanup() = transportDrainRace(TransportPhase.HEADER_FAILURE, false)

    @Test fun sinkFailureWaitsForTransportCleanup() = transportDrainRace(TransportPhase.SINK_FAILURE, false)

    @Test fun invalidMimeWaitsForTransportCleanup() = transportDrainRace(TransportPhase.MIME_FAILURE, false)
}

/** Nur MockHTTP: dessen Arbeit ist vollstaendig unter dem von R1 gejointen Request-Job strukturiert. */
internal class ExportMockEngine(
    private val mock: MockEngine,
) : HttpClientEngine by mock,
    ExportHttpTransportFactory {
    override fun open(): ExportHttpTransport =
        object : ExportHttpTransport {
            override val client =
                HttpClient(mock) {
                    followRedirects = false
                    install(HttpTimeout)
                }

            override fun cancel() = Unit

            override suspend fun awaitCompletion() = Unit

            override fun close() = client.close()
        }
}
