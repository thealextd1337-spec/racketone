package eu.advantly.cloud.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AndroidExportHttpTransportTest {
    private val id = "11111111-1111-1111-1111-111111111111"
    private val context = CloudDataExportContext("alice", 7, 0)
    private val ready = CloudDataExportStatus.Ready(id, 0, 1, 3, "a".repeat(64))

    private class Signals : EventListener() {
        val start = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val headers = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Unit>()
        val terminalReturned = CompletableDeferred<Unit>()
        val releaseStart = CountDownLatch(1)
        val releaseHeaders = CountDownLatch(1)
        val releaseTerminal = CountDownLatch(1)
        var holdStart = false
        var holdHeaders = false
        var holdTerminal = false
        var beforeHeadersReturn: () -> Unit = {}

        override fun callStart(call: Call) {
            start.complete(Unit)
            if (holdStart) check(releaseStart.await(10, TimeUnit.SECONDS))
        }

        override fun canceled(call: Call) {
            cancelled.complete(Unit)
        }

        override fun responseHeadersEnd(
            call: Call,
            response: Response,
        ) {
            headers.complete(Unit)
            if (holdHeaders) check(releaseHeaders.await(10, TimeUnit.SECONDS))
            beforeHeadersReturn()
        }

        private fun finished() {
            terminal.complete(Unit)
            if (holdTerminal) check(releaseTerminal.await(10, TimeUnit.SECONDS))
            terminalReturned.complete(Unit)
        }

        override fun callEnd(call: Call) = finished()

        override fun callFailed(
            call: Call,
            ioe: IOException,
        ) = finished()

        fun release() {
            releaseStart.countDown()
            releaseHeaders.countDown()
            releaseTerminal.countDown()
        }
    }

    private class NativeFactory(
        val dns: Dns,
        val signals: Signals,
        val requestDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : ExportHttpTransportFactory {
        lateinit var native: AndroidExportHttpTransport
        val drainEntered = CompletableDeferred<Unit>()
        val drained = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val dispatcherIdle = CompletableDeferred<Unit>()

        override fun open(): ExportHttpTransport {
            native = AndroidExportHttpTransport(dns, signals, requestDispatcher)
            val idle = native.dispatcher.idleCallback
            native.dispatcher.idleCallback =
                Runnable {
                    idle?.run()
                    dispatcherIdle.complete(Unit)
                }
            return object : ExportHttpTransport by native {
                override suspend fun awaitCompletion() {
                    drainEntered.complete(Unit)
                    native.awaitCompletion()
                    drained.complete(Unit)
                }

                override fun close() {
                    native.close()
                    closed.complete(Unit)
                }
            }
        }
    }

    private fun transport(
        base: String,
        factory: ExportHttpTransportFactory?,
    ): CloudHttp =
        CloudHttp(
            base,
            "public-key",
            HttpClient(MockEngine { respond("{}", headers = headersOf("Content-Type", "application/json")) }),
            exportFactory = factory,
        )

    private fun api(
        http: CloudHttp,
        timeout: Long = 20_000,
    ) = SupabaseDataExport(http, { CloudCredentials("alice", "session", "fixed-token") }, {}, timeout, timeout)
        .also { it.publish(CloudSession("alice", false, false, generation = 7)) }

    private fun registered(api: SupabaseDataExport): Int =
        (
            api.javaClass
                .getDeclaredField("jobs")
                .apply { isAccessible = true }
                .get(api) as Set<*>
        ).size

    @Test fun defaultFactoryIsTheInstrumentedAndroidTransport(): Unit =
        runBlocking {
            val transport = exportHttpTransportFactory(null).open()
            assertIs<AndroidExportHttpTransport>(transport)
            transport.cancel()
            transport.awaitCompletion()
            assertEquals(0, transport.dispatcher.runningCallsCount())
            transport.close()
        }

    private fun dnsRace(
        timeout: Boolean,
        cancelCaller: Boolean = false,
    ): Unit =
        runBlocking {
            val dnsEntered = CompletableDeferred<Unit>()
            val dnsExited = CompletableDeferred<Unit>()
            val releaseDns = CountDownLatch(1)
            val signals = Signals().apply { holdTerminal = true }
            val factory =
                NativeFactory(
                    Dns {
                        dnsEntered.complete(Unit)
                        check(releaseDns.await(10, TimeUnit.SECONDS))
                        dnsExited.complete(Unit)
                        throw UnknownHostException("controlled DNS failure")
                    },
                    signals,
                )
            val http = transport("https://auth.test", factory)
            val api = api(http, if (timeout) 1_000 else 20_000)
            val export = async { runCatching { api.request(context, id) } }
            try {
                withTimeout(5_000) { dnsEntered.await() }
                when {
                    timeout -> Unit
                    cancelCaller -> export.cancel()
                    else -> api.invalidate()
                }
                withTimeout(5_000) {
                    signals.cancelled.await()
                    factory.drainEntered.await()
                }
                assertFalse(dnsExited.isCompleted)
                assertFalse(signals.terminal.isCompleted)
                assertEquals(1, factory.native.dispatcher.runningCallsCount())
                assertEquals(1, registered(api))
                assertFalse(export.isCompleted)
                assertFalse(factory.closed.isCompleted)
                releaseDns.countDown()
                withTimeout(5_000) { signals.terminal.await() }
                assertTrue(dnsExited.isCompleted)
                assertEquals(
                    0,
                    (
                        factory.native.javaClass
                            .getDeclaredField("calls")
                            .apply { isAccessible = true }
                            .get(factory.native) as Set<*>
                    ).size,
                )
                // Der Terminalevent ist eingetreten; der native Callback/Dispatcher ist noch separat gehalten.
                assertEquals(1, factory.native.dispatcher.runningCallsCount())
                assertFalse(factory.drained.isCompleted)
                assertFalse(export.isCompleted)
                assertEquals(1, registered(api))
                signals.releaseTerminal.countDown()
                withTimeout(5_000) { export.join() }
                assertTrue(signals.terminalReturned.isCompleted)
                assertTrue(factory.drained.isCompleted)
                assertTrue(factory.closed.isCompleted)
                assertEquals(0, factory.native.dispatcher.runningCallsCount())
                assertEquals(0, registered(api))
                if (timeout) {
                    assertEquals("export_timeout", assertIs<CloudException>(export.await().exceptionOrNull()).reason)
                } else if (!cancelCaller) {
                    assertTrue(export.await().isFailure)
                }
            } finally {
                releaseDns.countDown()
                signals.release()
                withContext(NonCancellable) { withTimeout(5_000) { export.join() } }
                http.close()
            }
        }

    @Test fun invalidationWaitsForDnsThenTerminalAndDispatcher() = dnsRace(false)

    @Test fun timeoutWaitsForDnsThenTerminalAndDispatcher() = dnsRace(true)

    @Test fun callerCancellationWaitsForDnsThenTerminalAndDispatcher() = dnsRace(false, true)

    @Test fun cancellationDuringCallStartCannotPublishAnEmptyCompletionBeforeEnqueue(): Unit =
        runBlocking {
            val signals = Signals().apply { holdStart = true }
            val dnsCalls = AtomicInteger()
            val factory =
                NativeFactory(
                    Dns {
                        dnsCalls.incrementAndGet()
                        throw UnknownHostException("cancelled before DNS")
                    },
                    signals,
                )
            val http = transport("https://auth.test", factory)
            val api = api(http)
            val export = async { runCatching { api.request(context, id) } }
            try {
                withTimeout(5_000) { signals.start.await() }
                api.invalidate()
                withTimeout(5_000) { signals.cancelled.await() }
                assertEquals(0, factory.native.dispatcher.runningCallsCount())
                assertFalse(factory.drainEntered.isCompleted)
                assertEquals(1, registered(api))
                assertFalse(export.isCompleted)
                signals.releaseStart.countDown()
                withTimeout(5_000) { export.join() }
                assertTrue(signals.terminal.isCompleted)
                assertTrue(factory.drained.isCompleted)
                assertEquals(0, factory.native.dispatcher.runningCallsCount())
                assertEquals(0, registered(api))
            } finally {
                signals.release()
                withContext(NonCancellable) { withTimeout(5_000) { export.join() } }
                http.close()
            }
        }

    /** Kontrollierter lokaler HTTP-Peer; keine gehostete Abnahme und kein Entpacken des Produktpfads. */
    private class Peer(
        private val writeResponse: (Socket) -> Unit,
    ) : AutoCloseable {
        val server = ServerSocket(0)
        val requestRead = CompletableDeferred<String>()
        val done = CompletableDeferred<Unit>()
        val worker =
            Thread {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val request =
                            buildString {
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    append(line).append('\n')
                                    if (line.isEmpty()) break
                                }
                            }
                        requestRead.complete(request)
                        writeResponse(socket)
                    }
                } catch (
                    error: Throwable,
                ) {
                    if (!requestRead.isCompleted) requestRead.completeExceptionally(error)
                } finally {
                    done.complete(Unit)
                }
            }.apply {
                isDaemon = true
                start()
            }
        val url get() = "http://127.0.0.1:${server.localPort}"

        override fun close() {
            server.close()
        }
    }

    private fun write(
        socket: Socket,
        body: String,
        mime: String = "application/zip",
        length: Int = body.length,
    ) {
        socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: $length\r\nConnection: close\r\n\r\n$body".toByteArray())
            flush()
        }
    }

    @Test fun successWaitsForBodyTerminalEvenAfterHeaderCallbackIsIdle(): Unit =
        runBlocking {
            val releaseBody = CountDownLatch(1)
            val peer =
                Peer { socket ->
                    write(socket, "z")
                    check(releaseBody.await(10, TimeUnit.SECONDS))
                }
            val signals = Signals().apply { holdTerminal = true }
            val factory = NativeFactory(Dns.SYSTEM, signals)
            val http = transport(peer.url, factory)
            val api = api(http)
            val received = CompletableDeferred<Unit>()
            val releaseSink = CompletableDeferred<Unit>()
            val export =
                async {
                    runCatching {
                        api.download(
                            context,
                            ready.copy(byteLength = 1),
                            CloudDataExportSink { _, _, _ ->
                                received.complete(Unit)
                                releaseSink.await()
                            },
                        )
                    }
                }
            try {
                withTimeout(5_000) {
                    signals.terminal.await()
                    factory.dispatcherIdle.await()
                }
                assertEquals(0, factory.native.dispatcher.runningCallsCount())
                assertFalse(export.isCompleted)
                assertEquals(1, registered(api))
                releaseSink.complete(Unit)
                signals.releaseTerminal.countDown()
                releaseBody.countDown()
                withTimeout(5_000) { export.join() }
                export.await().getOrThrow()
                val request = withTimeout(5_000) { peer.requestRead.await() }
                assertTrue(request.startsWith("GET /functions/v1/export-my-data/$id/download HTTP/1.1"))
                assertTrue(request.contains("Authorization: Bearer fixed-token", ignoreCase = true))
                assertTrue(factory.drained.isCompleted)
                assertEquals(0, registered(api))
            } finally {
                releaseBody.countDown()
                releaseSink.complete(Unit)
                signals.release()
                withContext(NonCancellable) { withTimeout(5_000) { export.join() } }
                http.close()
                peer.close()
            }
        }

    private enum class BodyCase { CANCEL, TIMEOUT, MIME, SINK, LOST_HEADERS }

    private fun bodyRace(case: BodyCase): Unit =
        runBlocking {
            val releasePeer = CountDownLatch(1)
            val peer =
                Peer { socket ->
                    write(socket, "z", if (case == BodyCase.MIME) "text/plain" else "application/zip", 3)
                    check(releasePeer.await(10, TimeUnit.SECONDS))
                }
            val signals =
                Signals().apply {
                    holdHeaders = case == BodyCase.LOST_HEADERS
                    holdTerminal = true
                }
            val factory = NativeFactory(Dns.SYSTEM, signals)
            val http = transport(peer.url, factory)
            val api = api(http, if (case == BodyCase.TIMEOUT) 1_000 else 20_000)
            val received = CompletableDeferred<Unit>()
            val export =
                async {
                    runCatching {
                        api.download(
                            context,
                            ready,
                            CloudDataExportSink { _, _, _ ->
                                received.complete(Unit)
                                if (case == BodyCase.SINK) error("controlled sink failure")
                            },
                        )
                    }
                }
            try {
                if (case == BodyCase.LOST_HEADERS) {
                    withTimeout(5_000) { signals.headers.await() }
                    api.invalidate()
                    withTimeout(5_000) { signals.cancelled.await() }
                    assertFalse(export.isCompleted)
                    assertEquals(1, registered(api))
                    signals.releaseHeaders.countDown()
                } else if (case == BodyCase.CANCEL) {
                    withTimeout(5_000) { received.await() }
                    api.invalidate()
                }
                withTimeout(5_000) { signals.terminal.await() }
                assertFalse(export.isCompleted)
                assertEquals(1, registered(api))
                signals.releaseTerminal.countDown()
                releasePeer.countDown()
                withTimeout(5_000) { export.join() }
                assertTrue(export.await().isFailure)
                assertTrue(factory.drained.isCompleted)
                assertTrue(factory.closed.isCompleted)
                assertEquals(0, factory.native.dispatcher.runningCallsCount())
                assertEquals(0, registered(api))
            } finally {
                releasePeer.countDown()
                signals.release()
                withContext(NonCancellable) { withTimeout(5_000) { export.join() } }
                http.close()
                peer.close()
            }
        }

    @Test fun bodyCancellationKeepsOperationRegisteredUntilNativeCleanup() = bodyRace(BodyCase.CANCEL)

    @Test fun bodyTimeoutKeepsOperationRegisteredUntilNativeCleanup() = bodyRace(BodyCase.TIMEOUT)

    @Test fun invalidMimeKeepsOperationRegisteredUntilNativeCleanup() = bodyRace(BodyCase.MIME)

    @Test fun sinkFailureKeepsOperationRegisteredUntilNativeCleanup() = bodyRace(BodyCase.SINK)

    @Test fun lostHeadersStillCloseTheNativeBodyAndDrain() = bodyRace(BodyCase.LOST_HEADERS)

    @Test fun nativeDnsFailureWithoutCancellationStillWaitsForCallbackCompletion(): Unit =
        runBlocking {
            val signals = Signals().apply { holdTerminal = true }
            val factory = NativeFactory(Dns { throw UnknownHostException("controlled natural failure") }, signals)
            val http = transport("https://auth.test", factory)
            val api = api(http)
            val export = async { runCatching { api.request(context, id) } }
            try {
                withTimeout(5_000) { signals.terminal.await() }
                assertEquals(1, factory.native.dispatcher.runningCallsCount())
                assertEquals(1, registered(api))
                assertFalse(export.isCompleted)
                signals.releaseTerminal.countDown()
                withTimeout(5_000) { export.join() }
                assertEquals("export_unavailable", assertIs<CloudException>(export.await().exceptionOrNull()).reason)
                assertTrue(factory.closed.isCompleted)
                assertEquals(0, factory.native.dispatcher.runningCallsCount())
                assertEquals(0, registered(api))
            } finally {
                signals.release()
                withContext(NonCancellable) { withTimeout(5_000) { export.join() } }
                http.close()
            }
        }

    @Test fun cancellationWhileReadingHeadersWaitsForNativeSocketCallback(): Unit =
        runBlocking {
            val releasePeer = CountDownLatch(1)
            val peer = Peer { check(releasePeer.await(10, TimeUnit.SECONDS)) }
            val signals = Signals().apply { holdTerminal = true }
            val factory = NativeFactory(Dns.SYSTEM, signals)
            val http = transport(peer.url, factory)
            val api = api(http)
            val export = async { runCatching { api.status(context, id) } }
            try {
                withTimeout(5_000) { peer.requestRead.await() }
                assertFalse(signals.headers.isCompleted)
                api.invalidate()
                withTimeout(5_000) { signals.terminal.await() }
                assertFalse(export.isCompleted)
                assertEquals(1, registered(api))
                signals.releaseTerminal.countDown()
                releasePeer.countDown()
                withTimeout(5_000) { export.join() }
                assertTrue(factory.drained.isCompleted)
                assertEquals(0, factory.native.dispatcher.runningCallsCount())
                assertEquals(0, registered(api))
            } finally {
                releasePeer.countDown()
                signals.release()
                withContext(NonCancellable) { withTimeout(5_000) { export.join() } }
                http.close()
                peer.close()
            }
        }

    private class ContinuationGate : CoroutineDispatcher() {
        val held = AtomicBoolean(false)
        val queued = CompletableDeferred<Unit>()
        private val lock = Any()
        private val tasks = ArrayDeque<Pair<CoroutineContext, Runnable>>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            val submit =
                synchronized(lock) {
                    if (held.get()) {
                        tasks.addLast(context to block)
                        false
                    } else {
                        true
                    }
                }
            if (submit) Dispatchers.IO.dispatch(context, block) else queued.complete(Unit)
        }

        fun release() {
            val pending =
                synchronized(lock) {
                    held.set(false)
                    tasks.toList().also { tasks.clear() }
                }
            pending.forEach { Dispatchers.IO.dispatch(it.first, it.second) }
        }
    }

    @Test fun lostResumedResponseIsClosedBeforeNativeDrainFinishes(): Unit =
        runBlocking {
            val releasePeer = CountDownLatch(1)
            val peer =
                Peer { socket ->
                    write(socket, "z", length = 3)
                    check(releasePeer.await(10, TimeUnit.SECONDS))
                }
            val gate = ContinuationGate()
            val signals =
                Signals().apply {
                    holdTerminal = true
                    beforeHeadersReturn = { gate.held.set(true) }
                }
            val factory = NativeFactory(Dns.SYSTEM, signals, gate)
            val http = transport(peer.url, factory)
            val api = api(http)
            var writes = 0
            val export = async { runCatching { api.download(context, ready, CloudDataExportSink { _, _, _ -> writes++ }) } }
            try {
                withTimeout(5_000) {
                    gate.queued.await()
                    factory.dispatcherIdle.await()
                }
                assertFalse(signals.terminal.isCompleted)
                api.invalidate()
                withTimeout(5_000) { signals.cancelled.await() }
                assertEquals(1, registered(api))
                gate.release()
                withTimeout(5_000) { signals.terminal.await() }
                assertFalse(export.isCompleted)
                signals.releaseTerminal.countDown()
                releasePeer.countDown()
                withTimeout(5_000) { export.join() }
                assertTrue(factory.closed.isCompleted)
                assertEquals(0, writes)
                assertEquals(0, registered(api))
                assertEquals(0, factory.native.dispatcher.runningCallsCount())
            } finally {
                signals.release()
                gate.release()
                releasePeer.countDown()
                withContext(NonCancellable) { withTimeout(5_000) { export.join() } }
                http.close()
                peer.close()
            }
        }

    @Test fun uninstrumentedNativeEngineIsRejectedBeforeDnsOrCallStart(): Unit =
        runBlocking {
            val dnsCalls = AtomicInteger()
            val raw =
                OkHttp.create {
                    dns =
                        Dns {
                            dnsCalls.incrementAndGet()
                            throw UnknownHostException("must not execute")
                        }
                }
            val http = CloudHttp("https://auth.test", "public-key", HttpClient(raw), exportFactory = exportHttpTransportFactory(raw))
            try {
                assertEquals("export_unavailable", assertFailsWith<CloudException> { api(http).request(context, id) }.reason)
                assertEquals(0, dnsCalls.get())
            } finally {
                http.close()
                raw.close()
            }
        }

    @Test fun preEngineFailureHasNoPhantomCallToWaitFor(): Unit =
        runBlocking {
            var requests = 0
            val http =
                transport(
                    "https://auth.test",
                    ExportHttpTransportFactory {
                        requests++
                        throw IOException("before engine creation")
                    },
                )
            try {
                assertEquals("export_unavailable", assertFailsWith<CloudException> { api(http).request(context, id) }.reason)
                assertEquals(1, requests)
            } finally {
                http.close()
            }
            val native = AndroidExportHttpTransport()
            native.cancel()
            native.awaitCompletion()
            assertEquals(0, native.dispatcher.runningCallsCount())
            native.close()
        }
}
