package eu.advantly.android.auth

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.sun.net.httpserver.HttpServer
import eu.advantly.android.phoneExportShareCallback
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudDataExportContext
import eu.advantly.cloud.sync.CloudDataExportPort
import eu.advantly.cloud.sync.CloudDataExportSink
import eu.advantly.cloud.sync.CloudDataExportStatus
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudInstallation
import eu.advantly.cloud.sync.CloudJson
import eu.advantly.cloud.sync.CloudUploadAttempt
import eu.advantly.cloud.sync.CloudUploadPort
import eu.advantly.cloud.sync.ConsentSnapshot
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.ItemResult
import eu.advantly.cloud.sync.PushBatch
import eu.advantly.cloud.sync.PushResponse
import eu.advantly.cloud.sync.SupabasePhoneAuth
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.Presets
import eu.advantly.data.LocalData
import eu.advantly.data.createAndroidDriver
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionConfig
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.PhoneExportAction
import eu.advantly.presentation.auth.PhoneExportPhase
import eu.advantly.presentation.auth.PhoneUploadPhase
import eu.advantly.sync.protocol.PeerKind
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64

/** Echte SDK-/Session-/Host-Komposition mit nativem HTTP gegen einen lokalen Vertragsserver und kontrollierten Drain-Barrieren. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneAccountExportCompositionTest {
    /** Hält die echte HTTP-Antwort vor Headers und Body, nicht nur den späteren Host-Child. */
    private class UploadResponseHold {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val ended = CompletableDeferred<Unit>()
    }

    private class Fixture(
        scope: CoroutineScope,
    ) {
        private val owner = Thread.currentThread()
        private val lifecycle = SupervisorJob(scope.coroutineContext[Job])
        private val work = CoroutineScope(scope.coroutineContext + lifecycle)
        private val application: Application = RuntimeEnvironment.getApplication()
        private val databaseName = "account-export-composition.db"
        val trace = CopyOnWriteArrayList<String>()
        private val rawDriver = createAndroidDriver(application, databaseName)
        private val driver =
            object : SqlDriver by rawDriver {
                override fun execute(
                    identifier: Int?,
                    sql: String,
                    parameters: Int,
                    binders: (SqlPreparedStatement.() -> Unit)?,
                ): QueryResult<Long> {
                    val result = rawDriver.execute(identifier, sql, parameters, binders)
                    if (sql.startsWith("UPDATE match_event SET cloud_state = 1")) trace += "sql-ack"
                    return result
                }
            }
        val data = LocalData(driver)
        val session = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE))
        val requests = CopyOnWriteArrayList<String>()
        val bytes = ByteArray(20_000) { (it % 127).toByte() }

        @Volatile var consent = ConsentStatus.CURRENT

        @Volatile var deletion = false
        var exportHold: ExportHold? = null
        var uploadHold: ExportHold? = null

        @Volatile var uploadResponseHold: UploadResponseHold? = null
        val backendCompleted = CompletableDeferred<Unit>()
        val backendEnded = CompletableDeferred<Unit>()
        private lateinit var uploadAttempt: CloudUploadAttempt
        var closes = 0
        val closeReadiness = mutableListOf<List<Boolean>>()
        val closed = CompletableDeferred<Unit>()
        val store = ViewModelStore()
        private val executor = Executors.newCachedThreadPool()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                this.executor = this@Fixture.executor
                createContext("/") { exchange ->
                    var heldResponse: UploadResponseHold? = null
                    try {
                        val path = exchange.requestURI.path
                        requests += path
                        val body = exchange.requestBody.use { it.readBytes().decodeToString() }
                        if (path.endsWith("/download")) {
                            exchange.responseHeaders.add("Content-Type", "application/zip")
                            exchange.sendResponseHeaders(200, bytes.size.toLong())
                            exchange.responseBody.write(bytes)
                            return@createContext
                        }
                        val response =
                            when {
                                path.endsWith("my_auth_profile") -> {
                                    """{"profileId":"alice","onboarded":true,"deletionRequested":$deletion,"consentStatus":"$consent"}"""
                                }

                                path.endsWith("my_consent_v1") -> {
                                    consentJson()
                                }

                                path.endsWith("record_consent_v1") -> {
                                    consent = ConsentStatus.DECLINED
                                    consentJson()
                                }

                                path.endsWith("request_account_deletion") -> {
                                    deletion = true
                                    "{}"
                                }

                                path.endsWith("logout") -> {
                                    "{}"
                                }

                                path.endsWith("bind_cloud_session_v1") -> {
                                    val device =
                                        CloudJson
                                            .parseToJsonElement(requireNotNull(body))
                                            .jsonObject
                                            .getValue("p_device_id")
                                            .jsonPrimitive.content
                                    """{"contractVersion":1,"profileId":"alice","sessionId":"session-a","installationId":"installation","deviceId":"$device"}"""
                                }

                                path.endsWith("cloud_sync_push_v1") -> {
                                    val batch =
                                        CloudJson.decodeFromJsonElement(
                                            PushBatch.serializer(),
                                            CloudJson.parseToJsonElement(requireNotNull(body)).jsonObject.getValue("p_batch"),
                                        )
                                    CloudJson.encodeToString(
                                        PushResponse.serializer(),
                                        PushResponse(
                                            devices = batch.devices.map { ItemResult(it.id, "ok") },
                                            players = batch.players.map { ItemResult(it.id, "ok") },
                                            matches = batch.matches.map { ItemResult(it.id, "updated", projection = "applied") },
                                            events = batch.events.map { ItemResult(it.id, "accepted") },
                                        ),
                                    )
                                }

                                path == "/functions/v1/export-my-data" -> {
                                    val id =
                                        CloudJson
                                            .parseToJsonElement(requireNotNull(body))
                                            .jsonObject
                                            .getValue("requestId")
                                            .jsonPrimitive.content
                                    """{"contractVersion":1,"status":"ready","requestId":"$id",
                                        "exportedAt":"1970-01-01T00:00:01Z","expiresAt":"1970-01-01T00:01:01Z",
                                        "byteLength":${bytes.size},"sha256":"${digest(bytes)}",
                                        "downloadUrl":"/functions/v1/export-my-data/$id/download"}"""
                                }

                                else -> {
                                    error("Unexpected request: $path")
                                }
                            }
                        val output = response.encodeToByteArray()
                        if (path.endsWith("cloud_sync_push_v1")) {
                            trace += "http-push-entered"
                            heldResponse = uploadResponseHold
                            heldResponse?.let { hold ->
                                hold.entered.complete(Unit)
                                check(hold.release.await(10, TimeUnit.SECONDS))
                            }
                            trace += "http-push-response"
                        }
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, output.size.toLong())
                        exchange.responseBody.write(output)
                    } catch (failure: IOException) {
                        // Nach Rechteentzug kann die native Gegenstelle bereits geschlossen sein.
                        if (heldResponse == null) throw failure
                    } finally {
                        exchange.close()
                        heldResponse?.let { hold ->
                            trace += "http-push-ended"
                            hold.ended.complete(Unit)
                        }
                    }
                }
                start()
            }
        val sdk =
            createSupabaseClient("http://127.0.0.1:${server.address.port}", "public") {
                defaultLogLevel = LogLevel.NONE
                install(Auth) { minimalConfig() }
            }
        val auth =
            SupabasePhoneAuth(
                sdk,
                "secret",
                data.cloudUploads,
                work,
                threadGuard = { Thread.currentThread() === owner },
                ownership = session,
            )
        val api = auth.dataExports()
        private val observedExports =
            object : CloudDataExportPort by api {
                override suspend fun download(
                    context: CloudDataExportContext,
                    ready: CloudDataExportStatus.Ready,
                    sink: CloudDataExportSink,
                ) {
                    api.download(context, ready) { frame, offset, length ->
                        sink.write(frame, offset, length)
                        exportHold?.await()
                    }
                }
            }
        val export = ExportHostFixture(work, observedExports)
        private val backend = auth.cloudBackend(CloudInstallation("installation", "a".repeat(64)))
        private val observedUploads =
            object : CloudUploadPort by backend {
                override suspend fun pushSelected(
                    attempt: CloudUploadAttempt,
                    batch: PushBatch,
                ): PushResponse =
                    coroutineScope {
                        uploadAttempt = attempt
                        // Der Child gehört zum tatsächlichen Host-Lauf, unabhängig vom HTTP-Request-Job.
                        uploadHold?.let { hold -> launch { hold.await() } }
                        try {
                            backend.pushSelected(attempt, batch).also {
                                trace += "backend-completed"
                                backendCompleted.complete(Unit)
                            }
                        } finally {
                            trace += "backend-ended"
                            backendEnded.complete(Unit)
                        }
                    }
            }
        val upload =
            PhoneUploadHost(auth, session, observedUploads, backend::bindDevice, work, { 1_000L }, {
                Thread.currentThread() ===
                    owner
            }, lane = PhoneCloudLane())
        private val observedAuth =
            object : CloudAuthPort by auth {
                override suspend fun decideConsent(
                    snapshot: ConsentSnapshot,
                    accept: Boolean,
                ): ConsentSnapshot {
                    trace += "consent-port-start"
                    return auth.decideConsent(snapshot, accept).also { trace += "consent-port-end" }
                }

                override suspend fun requestDeletion() {
                    trace += "deletion-port-start"
                    auth.requestDeletion()
                    trace += "deletion-port-end"
                }

                override suspend fun signOut() {
                    trace += "logout-port-start"
                    auth.signOut()
                    trace += "logout-port-end"
                }

                override fun close() {
                    closeReadiness +=
                        listOf(
                            uploadHold?.ended?.isCompleted ?: true,
                            exportHold?.ended?.isCompleted ?: true,
                            export.files().isEmpty(),
                        )
                    closes++
                    auth.close()
                    closed.complete(Unit)
                }
            }
        lateinit var vm: AuthViewModel

        init {
            session.start()
        }

        private fun consentJson() = """{"profileId":"alice","status":"$consent","documents":[]}"""

        fun requireCurrentUpload() = backend.requireCurrentUpload(uploadAttempt)

        fun model(
            target: ViewModelStore = store,
            withHosts: Boolean = true,
        ): AuthViewModel =
            ViewModelProvider(
                target,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        if (withHosts) {
                            AuthViewModel(observedAuth, work, upload = upload, export = export.host) as T
                        } else {
                            AuthViewModel(observedAuth, work) as T
                        }
                },
            )[AuthViewModel::class.java]

        suspend fun start(target: ViewModelStore = store) {
            sdk.auth.awaitInitialization()
            val token =
                "h." +
                    Base64.UrlSafe
                        .withPadding(Base64.PaddingOption.ABSENT)
                        .encode("""{"sub":"alice","session_id":"session-a"}""".encodeToByteArray()) + ".s"
            sdk.auth.importAuthToken(token, autoRefresh = false)
            auth.refreshProfile()
            vm = model(target)
            idle()
            assertTrue(vm.state.value.authenticated)
            assertNull(vm.state.value.error)
            export.start()
            assertTrue(upload.state.value.available)
        }

        suspend fun idle() {
            withTimeout(10_000) { vm.state.first { !it.busy } }
        }

        suspend fun blockBoth() {
            session.create(
                Presets.TENNIS_SINGLES_BO3.format(),
                Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a")),
            )
            exportHold = ExportHold()
            uploadHold = ExportHold()
            export.host.requestExport()
            export.await(PhoneExportPhase.READY)
            export.host.download()
            upload.uploadNow()
            withTimeout(10_000) {
                exportHold!!.entered.await()
                uploadHold!!.entered.await()
            }
            assertEquals(PhoneExportPhase.DOWNLOADING, export.host.state.value.phase)
            assertTrue(upload.state.value.busy)
            assertFalse(vm.state.value.busy)
        }

        suspend fun close() {
            uploadResponseHold?.release?.countDown()
            exportHold?.release?.complete(Unit)
            uploadHold?.release?.complete(Unit)
            store.clear()
            export.close()
            val uploadDrained = CompletableDeferred<Unit>()
            if (upload.state.value.phase != PhoneUploadPhase.CLOSED) {
                upload.close { uploadDrained.complete(Unit) }
                withTimeout(10_000) { uploadDrained.await() }
            }
            if (::vm.isInitialized) withTimeout(10_000) { closed.await() }
            lifecycle.cancel()
            session.close()
            sdk.close()
            server.stop(0)
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            driver.close()
            application.deleteDatabase(databaseName)
        }
    }

    @Test fun completedUploadMayAcknowledgeBeforeTheAccountIntentStarts() =
        runBlocking {
            val f = Fixture(this)
            val response = UploadResponseHold()
            try {
                f.start()
                f.uploadResponseHold = response
                f.blockBoth()
                withTimeout(10_000) { response.entered.await() }
                val pendingBefore = f.data.cloud.pendingCount()
                assertEquals(1L, pendingBefore)
                assertFalse("sql-ack" in f.trace)
                f.trace += "pending-before"
                response.release.countDown()
                withTimeout(10_000) {
                    f.backendCompleted.await()
                    response.ended.await()
                }
                assertEquals(0L, f.data.cloud.pendingCount())
                assertTrue(f.upload.state.value.busy)
                f.trace += "consent-intent"
                f.vm.decideConsent(false)
                f.idle()
                withTimeout(10_000) { f.uploadHold!!.cancelled.await() }
                f.uploadHold!!.release.complete(Unit)
                f.exportHold!!.release.complete(Unit)
                withTimeout(10_000) { f.upload.state.first { !it.busy } }
                val ownerOrder =
                    listOf(
                        "pending-before",
                        "sql-ack",
                        "backend-completed",
                        "backend-ended",
                        "consent-intent",
                        "consent-port-start",
                        "consent-port-end",
                    )
                assertEquals(ownerOrder, f.trace.filter { it in ownerOrder })
                assertEquals(0L, f.data.cloud.pendingCount())
                assertNull(f.vm.state.value.error)
                println("ACK-before-intent: ${f.trace}; pendingBefore=$pendingBefore; pendingAfter=${f.data.cloud.pendingCount()}")
            } finally {
                f.close()
            }
        }

    @Test fun clearWaitsForBothActualHostDrainsInEitherOrderBeforeClosingAuthOnce() =
        runBlocking {
            for (uploadFirst in listOf(false, true)) {
                val f = Fixture(this)
                try {
                    f.start()
                    f.blockBoth()
                    f.store.clear()
                    f.store.clear()
                    var extraCallbacks = 0
                    val exportDrained = CompletableDeferred<Unit>()
                    repeat(2) {
                        f.export.host.close {
                            extraCallbacks++
                            exportDrained.complete(Unit)
                        }
                    }
                    withTimeout(10_000) {
                        f.exportHold!!.cancelled.await()
                        f.uploadHold!!.cancelled.await()
                    }
                    assertEquals(PhoneExportPhase.CLOSED, f.export.host.state.value.phase)
                    assertEquals(PhoneUploadPhase.CLOSED, f.upload.state.value.phase)
                    assertEquals(0, f.closes)
                    assertEquals(0, extraCallbacks)
                    val first = if (uploadFirst) f.uploadHold!! else f.exportHold!!
                    val second = if (uploadFirst) f.exportHold!! else f.uploadHold!!
                    first.release.complete(Unit)
                    withTimeout(10_000) { first.ended.await() }
                    if (!uploadFirst) withTimeout(10_000) { exportDrained.await() }
                    yield()
                    assertFalse(second.ended.isCompleted)
                    assertEquals(0, f.closes)
                    second.release.complete(Unit)
                    withTimeout(10_000) {
                        f.closed.await()
                        exportDrained.await()
                    }
                    assertTrue(f.uploadHold!!.ended.isCompleted)
                    assertTrue(f.exportHold!!.ended.isCompleted)
                    assertTrue(f.export.files().isEmpty())
                    assertEquals(1, f.closes)
                    assertEquals(listOf(listOf(true, true, true)), f.closeReadiness)
                    assertEquals(2, extraCallbacks)
                    f.export.host.close { extraCallbacks++ }
                    f.store.clear()
                    assertEquals(3, extraCallbacks)
                    assertEquals(1, f.closes)
                } finally {
                    f.close()
                }
            }
        }

    @Test fun accountActionsRemainUsableDuringBothRunsAndKeepExportAndUploadRightsSeparate() =
        runBlocking {
            for (transition in listOf("consent", "deletion", "logout")) {
                val f = Fixture(this)
                val response = UploadResponseHold()
                try {
                    f.start()
                    f.uploadResponseHold = response
                    f.blockBoth()
                    withTimeout(10_000) { response.entered.await() }
                    val pendingBefore = f.data.cloud.pendingCount()
                    assertEquals(1L, pendingBefore)
                    assertFalse("sql-ack" in f.trace)
                    f.requireCurrentUpload()
                    val exportContext = f.api.context.value
                    f.trace += "$transition-intent"
                    when (transition) {
                        "consent" -> f.vm.decideConsent(false)
                        "deletion" -> f.vm.requestDeletion()
                        else -> f.vm.signOut()
                    }
                    f.idle()
                    assertNull(f.vm.state.value.error)
                    assertEquals(0, f.closes)
                    withTimeout(10_000) { f.uploadHold!!.cancelled.await() }
                    assertFalse(f.upload.state.value.available)
                    assertNull(f.upload.state.value.pendingEvents)
                    assertNull(f.upload.state.value.observedAtMs)
                    assertTrue(assertThrows(CloudException::class.java) { f.requireCurrentUpload() }.permission)
                    assertTrue(f.upload.state.value.busy)
                    assertFalse(f.backendCompleted.isCompleted)
                    assertFalse("sql-ack" in f.trace)
                    assertEquals(pendingBefore, f.data.cloud.pendingCount())
                    f.trace += "$transition-revoked"
                    if (transition == "consent") {
                        assertEquals(
                            ConsentStatus.DECLINED,
                            f.vm.state.value.consent!!
                                .status,
                        )
                        assertEquals(exportContext, f.api.context.value)
                        assertFalse(f.exportHold!!.cancelled.isCompleted)
                    } else {
                        withTimeout(10_000) { f.exportHold!!.cancelled.await() }
                        assertFalse(PhoneExportAction.SHARE in f.export.host.state.value.allowedActions)
                        assertNull(f.export.host.state.value.byteLength)
                    }
                    response.release.countDown()
                    withTimeout(10_000) {
                        response.ended.await()
                        f.backendEnded.await()
                    }
                    assertFalse(f.backendCompleted.isCompleted)
                    assertTrue(f.upload.state.value.busy)
                    f.uploadHold!!.release.complete(Unit)
                    f.exportHold!!.release.complete(Unit)
                    withTimeout(10_000) {
                        f.upload.state.first { !it.busy }
                        f.export.await(
                            if (transition ==
                                "logout"
                            ) {
                                PhoneExportPhase.UNAVAILABLE
                            } else if (transition ==
                                "consent"
                            ) {
                                PhoneExportPhase.LOCAL_FILE
                            } else {
                                PhoneExportPhase.IDLE
                            },
                        )
                    }
                    assertNull(f.upload.state.value.result)
                    assertNull(f.upload.state.value.lastResponseAtMs)
                    assertEquals(pendingBefore, f.data.cloud.pendingCount())
                    assertFalse("sql-ack" in f.trace)
                    val boundaryOrder =
                        listOf(
                            "http-push-entered",
                            "$transition-intent",
                            "$transition-port-start",
                            "$transition-port-end",
                            "$transition-revoked",
                            "http-push-response",
                            "http-push-ended",
                        )
                    assertEquals(boundaryOrder, f.trace.filter { it in boundaryOrder })
                    println(
                        "revocation-before-response ($transition): ${f.trace}; pendingBefore=$pendingBefore; pendingAfter=${f.data.cloud.pendingCount()}",
                    )
                    if (transition != "consent") {
                        // UNAVAILABLE darf schon vor Ende des abgebrochenen Sinks erscheinen; Dateien erst nach Drain prüfen.
                        val drained = CompletableDeferred<Unit>()
                        f.export.host.close { drained.complete(Unit) }
                        withTimeout(10_000) { drained.await() }
                        assertTrue(f.export.files().isEmpty())
                        assertEquals(0, f.closes)
                    }
                    if (transition == "deletion") assertTrue(f.vm.state.value.deletionRequested)
                    if (transition == "logout") assertFalse(f.vm.state.value.authenticated)
                } finally {
                    f.close()
                }
            }
        }

    @Test fun retainedViewModelUsesANewActivityCallbackAndOnlyTheCurrentActivityLaunchesReadOnlyChooser() =
        runBlocking {
            val f = Fixture(this)
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                val old = activity.get()
                f.start(old.viewModelStore)
                val oldVm = f.vm
                withTimeout(10_000) { oldVm.state.first { !it.busy } }
                val oldCallback = requireNotNull(old.phoneExportShareCallback(oldVm))
                val requests = f.requests.toList()
                activity.recreate()
                val current = activity.get()
                assertTrue(old.isDestroyed)
                assertNotSame(old, current)
                val retained = f.model(current.viewModelStore)
                assertSame(oldVm, retained)
                assertSame(f.export.host, retained.export)
                val currentCallback = requireNotNull(current.phoneExportShareCallback(retained))
                assertNotSame(oldCallback, currentCallback)
                assertEquals(requests, f.requests.toList())
                assertTrue(requests.none { it.contains("bind_cloud") || it.contains("cloud_sync_push") || it.contains("/functions/") })
                f.export.local()
                oldCallback()
                f.export.await(PhoneExportPhase.LOCAL_FILE)
                assertNull(shadowOf(old).nextStartedActivity)
                assertNull(shadowOf(current).nextStartedActivity)
                currentCallback()
                f.export.await(PhoneExportPhase.LOCAL_FILE)
                val chooser = requireNotNull(shadowOf(current).nextStartedActivity)
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
                assertEquals(Intent.ACTION_SEND, send.action)
                assertEquals("application/zip", send.type)
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
                val uri = requireNotNull(send.clipData).getItemAt(0).uri
                val info = current.packageManager.resolveContentProvider(uri.authority!!, PackageManager.GET_META_DATA)!!
                val provider = Robolectric.buildContentProvider(AccountExportProvider::class.java).create(info).get()
                provider.openFile(uri, "r").close()
                assertThrows(SecurityException::class.java) { provider.openFile(uri, "rw").close() }
                assertEquals(0, f.closes)
                f.vm.signOut()
                f.idle()
                assertThrows(SecurityException::class.java) { provider.openFile(uri, "r").close() }
                Unit
            } finally {
                activity.pause().stop().destroy()
                f.close()
            }
        }

    @Test fun missingBackendAndTrailingNullExportRemainCompatibleWithoutStartingAccountWork() =
        runBlocking {
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val f = Fixture(this)
            try {
                assertNull(activity.get().createPhoneAccount(apiUrl = "", apiKey = ""))
                assertNull(activity.get().phoneExportShareCallback(null))
                val vm = f.model(withHosts = false)
                withTimeout(10_000) { vm.state.first { !it.busy } }
                assertNull(vm.export)
                assertNull(vm.upload)
                assertNull(activity.get().phoneExportShareCallback(vm))
                assertTrue(f.requests.isEmpty())
                f.store.clear()
                assertEquals(1, f.closes)
            } finally {
                activity.pause().stop().destroy()
                f.close()
            }
        }
}
