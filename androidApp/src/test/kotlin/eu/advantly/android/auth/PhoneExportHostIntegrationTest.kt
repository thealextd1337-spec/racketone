package eu.advantly.android.auth

import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import eu.advantly.cloud.sync.CloudDataExportContext
import eu.advantly.cloud.sync.CloudDataExportPort
import eu.advantly.cloud.sync.CloudDataExportSink
import eu.advantly.cloud.sync.CloudDataExportStatus
import eu.advantly.cloud.sync.CloudJson
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.SupabasePhoneAuth
import eu.advantly.presentation.auth.PhoneExportAction
import eu.advantly.presentation.auth.PhoneExportError
import eu.advantly.presentation.auth.PhoneExportPhase
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.encoding.Base64

/** Echte Sockets und produktiver Default-A-Transport; keine interne Factory oder SDK-Engine injiziert. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneExportHostIntegrationTest {
    private class ServerHold {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)

        fun await() {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
        }
    }

    private data class Request(
        val path: String,
        val method: String,
        val bearer: String?,
        val body: String,
    )

    private class Server : AutoCloseable {
        private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "export-test-http").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url get() = "http://127.0.0.1:${server.address.port}"
        val requests = CopyOnWriteArrayList<Request>()
        val bytes = ByteArray(20_000) { (it % 127).toByte() }

        @Volatile var profile = "alice"

        @Volatile var consent = ConsentStatus.REQUIRED

        @Volatile var deletion = false

        @Volatile var mode = "ready"

        @Volatile var headerHold: ServerHold? = null

        @Volatile var bodyHold: ServerHold? = null

        @Volatile var corruptHash = false

        @Volatile var wrongLength = false

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    handle(exchange)
                } catch (_: IOException) {
                    // Erwartet bei lokalem Cancel; die Gegenstelle darf keine weitere Ausgabe veröffentlichen.
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        private fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            val body = exchange.requestBody.use { it.readBytes().decodeToString() }
            requests += Request(path, exchange.requestMethod, exchange.requestHeaders.getFirst("Authorization"), body)
            val response: String
            if (path.startsWith("/functions/")) {
                headerHold?.await()
                if (mode == "lost") return
                if (path.endsWith("/download")) {
                    exchange.responseHeaders.add("Content-Type", "application/zip")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes, 0, 8192)
                    exchange.responseBody.flush()
                    bodyHold?.await()
                    exchange.responseBody.write(bytes, 8192, bytes.size - 8192)
                    return
                }
                val id =
                    if (exchange.requestMethod == "POST") {
                        CloudJson
                            .parseToJsonElement(body)
                            .jsonObject
                            .getValue("requestId")
                            .jsonPrimitive.content
                    } else {
                        path.substringAfterLast('/')
                    }
                if (mode == "busy") {
                    exchange.responseHeaders.add("Retry-After", "3")
                    json(exchange, 409, """{"contractVersion":1,"error":"export_busy"}""")
                    return
                }
                if (mode == "pending") {
                    exchange.responseHeaders.add("Retry-After", "3")
                    json(exchange, 202, """{"contractVersion":1,"status":"pending","requestId":"$id","retryAfterSeconds":3}""")
                    return
                }
                val sha = if (corruptHash) "0".repeat(64) else digest(bytes)
                val length = bytes.size + if (wrongLength) 1 else 0
                response = """{"contractVersion":1,"status":"ready","requestId":"$id",
                    "exportedAt":"1970-01-01T00:00:01Z","expiresAt":"1970-01-01T00:01:01Z",
                    "byteLength":$length,"sha256":"$sha","downloadUrl":"/functions/v1/export-my-data/$id/download"}"""
            } else {
                response =
                    when {
                        path.endsWith("my_auth_profile") -> {
                            """{"profileId":"$profile","onboarded":false,"deletionRequested":$deletion,"consentStatus":"$consent"}"""
                        }

                        path.endsWith("request_account_deletion") -> {
                            deletion = true
                            "{}"
                        }

                        else -> {
                            "{}"
                        }
                    }
            }
            json(exchange, 200, response)
        }

        private fun json(
            exchange: HttpExchange,
            status: Int,
            body: String,
        ) {
            val bytes = body.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }

        override fun close() {
            headerHold?.release?.countDown()
            bodyHold?.release?.countDown()
            server.stop(0)
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        val sinkHold: ExportHold? = null,
        authDispatcher: CoroutineDispatcher? = null,
    ) {
        val server = Server()
        val sdk =
            createSupabaseClient(server.url, "public-key") {
                defaultLogLevel = LogLevel.NONE
                install(Auth) { minimalConfig() }
            }
        private val owner = Thread.currentThread()
        val auth =
            SupabasePhoneAuth(
                sdk,
                "secret",
                scope = CoroutineScope(scope.coroutineContext + (authDispatcher ?: EmptyCoroutineContext)),
                threadGuard = { Thread.currentThread() === owner },
            )
        val api = auth.dataExports()
        private val observed =
            object : CloudDataExportPort by api {
                override suspend fun download(
                    context: CloudDataExportContext,
                    ready: CloudDataExportStatus.Ready,
                    sink: CloudDataExportSink,
                ) {
                    api.download(context, ready) { bytes, offset, length ->
                        sink.write(bytes, offset, length)
                        sinkHold?.await()
                    }
                }
            }
        val phone = ExportHostFixture(scope, observed)
        var accessToken = ""
        private var initial = true

        suspend fun signIn(
            profile: String = "alice",
            session: String = "session-a",
        ) {
            server.profile = profile
            accessToken = "h." +
                Base64.UrlSafe
                    .withPadding(Base64.PaddingOption.ABSENT)
                    .encode("""{"sub":"$profile","session_id":"$session"}""".encodeToByteArray()) + ".s"
            sdk.auth.awaitInitialization()
            sdk.auth.importAuthToken(accessToken, autoRefresh = false)
            auth.refreshProfile()
            if (initial) {
                initial = false
                phone.start()
            }
        }

        fun exports() = server.requests.filter { it.path.startsWith("/functions/") }

        suspend fun close() {
            sinkHold?.release?.complete(Unit)
            server.headerHold?.release?.countDown()
            server.bodyHold?.release?.countDown()
            phone.close()
            // C schließt Auth ausdrücklich nicht: Der Kontovertrag bleibt bis zur Komposition gültig.
            api.context.value?.let(api::requireCurrent)
            auth.close()
            sdk.close()
            server.close()
        }
    }

    @Test fun providerChecksRealSdkIdentityWhileTheExportContextFlowIsStillOld() =
        runBlocking {
            for (profile in listOf("bob", "alice")) {
                val dispatcher = ExportSerialDispatcher(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
                val f = Fixture(this, authDispatcher = dispatcher)
                val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                try {
                    f.signIn()
                    f.phone.host.requestExport()
                    f.phone.await(PhoneExportPhase.READY)
                    f.phone.host.download()
                    f.phone.await(PhoneExportPhase.LOCAL_FILE)
                    f.phone.host.share(activity.get())
                    f.phone.await(PhoneExportPhase.LOCAL_FILE)
                    val uri = AccountExportProvider.uriForFile(f.phone.application, f.phone.files().single())
                    val info =
                        f.phone.application.packageManager
                            .resolveContentProvider(uri.authority!!, PackageManager.GET_META_DATA)!!
                    val provider = Robolectric.buildContentProvider(AccountExportProvider::class.java).create(info).get()
                    val old = f.api.context.value
                    provider.openFile(uri, "r").close()
                    dispatcher.pause()
                    val replacement =
                        "h." +
                            Base64.UrlSafe
                                .withPadding(Base64.PaddingOption.ABSENT)
                                .encode("""{"sub":"$profile","session_id":"replacement-session"}""".encodeToByteArray()) + ".s"
                    f.sdk.auth.importAuthToken(replacement, autoRefresh = false)
                    assertEquals(old, f.api.context.value)
                    assertEquals(PhoneExportPhase.LOCAL_FILE, f.phone.host.state.value.phase)
                    assertThrows(SecurityException::class.java) { provider.openFile(uri, "r").close() }
                    assertThrows(SecurityException::class.java) { provider.getType(uri) }
                } finally {
                    dispatcher.resume()
                    activity.pause().stop().destroy()
                    f.close()
                }
            }
        }

    @Test fun defaultNativeAdapterRetainsIdsAcrossUnknownPendingBusyAndRetryWithoutCloudConsent() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.signIn()
                assertEquals(
                    ConsentStatus.REQUIRED,
                    f.auth.accountSession.value!!
                        .consentStatus,
                )
                f.server.mode = "lost"
                f.phone.host.requestExport()
                f.phone.await(PhoneExportPhase.OUTCOME_UNKNOWN)
                val initial = f.exports().first()
                val id =
                    CloudJson
                        .parseToJsonElement(initial.body)
                        .jsonObject
                        .getValue("requestId")
                        .jsonPrimitive.content
                f.server.mode = "pending"
                f.phone.host.retry()
                f.phone.await(PhoneExportPhase.PENDING)
                val beforeWait = f.exports().size
                f.phone.host.retry()
                f.phone.host.refreshStatus()
                assertEquals(beforeWait, f.exports().size)
                f.phone.clock.advance(3_000)
                withTimeout(10_000) {
                    f.phone.host.state
                        .first { it.retryAtMs == null }
                }
                assertEquals(beforeWait, f.exports().size)
                f.server.mode = "busy"
                f.phone.host.refreshStatus()
                f.phone.await(PhoneExportPhase.SERVER_BUSY)
                f.phone.clock.advance(3_000)
                withTimeout(10_000) {
                    f.phone.host.state
                        .first { it.retryAtMs == null }
                }
                assertFalse(PhoneExportAction.REFRESH_STATUS in f.phone.host.state.value.allowedActions)
                f.server.mode = "ready"
                f.phone.host.retry()
                f.phone.await(PhoneExportPhase.READY)
                f.phone.host.download()
                f.phone.await(PhoneExportPhase.LOCAL_FILE)
                assertEquals(
                    digest(f.server.bytes),
                    digest(
                        f.phone
                            .files()
                            .single()
                            .readBytes(),
                    ),
                )
                assertTrue(f.exports().all { it.bearer == "Bearer ${f.accessToken}" })
                assertTrue(f.exports().filter { it.method == "POST" }.all { it.body.contains(id) })
                assertTrue(f.exports().none { it.path.contains("bind") || it.path.contains("register") })
                val original = f.api.context.value
                f.server.consent = ConsentStatus.DECLINED
                f.auth.refreshProfile()
                assertEquals(original, f.api.context.value)
                f.auth.requestDeletion()
                withTimeout(10_000) {
                    f.phone.host.state
                        .first { PhoneExportAction.REQUEST in it.allowedActions }
                }
                assertNotEquals(original, f.api.context.value)
                assertTrue(
                    f.auth.accountSession.value!!
                        .deletionRequested,
                )
                f.phone.host.requestExport()
                f.phone.await(PhoneExportPhase.READY)
                val nextId =
                    CloudJson
                        .parseToJsonElement(f.exports().last().body)
                        .jsonObject
                        .getValue("requestId")
                        .jsonPrimitive.content
                assertNotEquals(id, nextId)
            } finally {
                f.close()
            }
        }

    @Test fun realAdapterAccountChangesAtHeadersBodyAndSinkFenceOldOutput() =
        runBlocking {
            for (stage in listOf("headers", "body", "sink")) {
                for (transition in listOf("bob", "alice-again", "logout", "delete")) {
                    val sink = if (stage == "sink") ExportHold() else null
                    val f = Fixture(this, sink)
                    val serverHold = ServerHold()
                    try {
                        f.signIn()
                        val oldToken = f.accessToken
                        if (stage == "headers") f.server.headerHold = serverHold
                        f.phone.host.requestExport()
                        if (stage != "headers") {
                            f.phone.await(PhoneExportPhase.READY)
                            if (stage == "body") f.server.bodyHold = serverHold
                            f.phone.host.download()
                        }
                        withTimeout(10_000) { if (stage == "sink") sink!!.entered.await() else serverHold.entered.await() }
                        when (transition) {
                            "bob" -> f.signIn("bob", "session-b")
                            "alice-again" -> f.signIn("alice", "session-a2")
                            "logout" -> f.auth.signOut()
                            else -> f.auth.requestDeletion()
                        }
                        if (stage == "sink") {
                            withTimeout(10_000) { sink!!.cancelled.await() }
                            sink!!.release.complete(Unit)
                        }
                        serverHold.release.countDown()
                        f.phone.await(if (transition == "logout") PhoneExportPhase.UNAVAILABLE else PhoneExportPhase.IDLE)
                        assertFalse(PhoneExportAction.SHARE in f.phone.host.state.value.allowedActions)
                        assertEquals(null, f.phone.host.state.value.byteLength)
                        assertTrue(f.exports().all { it.bearer == "Bearer $oldToken" })
                    } finally {
                        serverHold.release.countDown()
                        sink?.release?.complete(Unit)
                        f.close()
                    }
                }
            }
        }

    @Test fun realAdapterAndHostRejectHashAndDeclaredLengthBeforeLocalPublication() =
        runBlocking {
            for (hash in listOf(false, true)) {
                val f = Fixture(this)
                try {
                    f.signIn()
                    f.server.corruptHash = hash
                    f.server.wrongLength = !hash
                    f.phone.host.requestExport()
                    f.phone.await(PhoneExportPhase.READY)
                    f.phone.host.download()
                    f.phone.await(PhoneExportPhase.FAILED)
                    assertEquals(
                        if (hash) PhoneExportError.INTEGRITY else PhoneExportError.INVALID_RESPONSE,
                        f.phone.host.state.value.error,
                    )
                    assertTrue(f.phone.files().isEmpty())
                    assertNotNull(f.api.context.value)
                } finally {
                    f.close()
                }
            }
        }
}
