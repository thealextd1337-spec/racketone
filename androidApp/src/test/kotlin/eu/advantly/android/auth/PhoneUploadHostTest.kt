package eu.advantly.android.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.cloud.sync.CloudInstallation
import eu.advantly.cloud.sync.CloudJson
import eu.advantly.cloud.sync.CloudUploadAttempt
import eu.advantly.cloud.sync.CloudUploadPort
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.ItemResult
import eu.advantly.cloud.sync.PushBatch
import eu.advantly.cloud.sync.PushResponse
import eu.advantly.cloud.sync.SupabasePhoneAuth
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.LocalData
import eu.advantly.data.StoredEvent
import eu.advantly.data.db.AdvantlyDatabase
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionConfig
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.PhoneUploadAction
import eu.advantly.presentation.auth.PhoneUploadPhase
import eu.advantly.presentation.auth.PhoneUploadResult
import eu.advantly.sync.protocol.PeerKind
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.encoding.Base64

class PhoneUploadHostTest {
    private class RecordingDriver(
        private val delegate: SqlDriver,
    ) : SqlDriver by delegate {
        var record = false
        val writes = mutableListOf<String>()

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            if (record) writes += sql
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    private class Hold {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()

        suspend fun waitForRelease() {
            entered.complete(Unit)
            try {
                release.await()
            } finally {
                if (!release.isCompleted) cancelled.complete(Unit)
                // Absichtlich nicht kooperativer Transport-Abbau als Drain-Nachweis.
                withContext(NonCancellable) { release.await() }
                ended.complete(Unit)
            }
        }
    }

    private class Fixture(
        scope: CoroutineScope,
    ) {
        val thread = Thread.currentThread()
        val driver = RecordingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), AdvantlyDatabase.Schema))
        val data = LocalData(driver)
        val phone = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("config-phone"), PeerKind.PHONE))
        val lifecycle = SupervisorJob(scope.coroutineContext[Job])
        val hostScope = CoroutineScope(scope.coroutineContext + lifecycle)
        val requests = CopyOnWriteArrayList<String>()
        val bindings = CopyOnWriteArrayList<String>()
        val batches = CopyOnWriteArrayList<PushBatch>()
        val bodies = CopyOnWriteArrayList<String>()
        val trace = CopyOnWriteArrayList<String>()
        val committed = mutableSetOf<String>()
        var owner = "alice"
        var epoch = 1
        var consent = ConsentStatus.CURRENT
        var deletion = false
        var loseReply = false
        var partial = false
        var reject = false
        var holdBinding: Hold? = null
        var holdPush: Hold? = null
        var bindingDrain: Hold? = null
        var uploadDrain: Hold? = null
        val sdk =
            createSupabaseClient("https://cloud.test", "public") {
                defaultLogLevel = LogLevel.NONE
                install(Auth) { minimalConfig() }
                httpEngine =
                    MockEngine { request ->
                        val path = request.url.encodedPath.substringAfterLast('/')
                        requests += path
                        val response =
                            when (path) {
                                "my_auth_profile" -> {
                                    """{"profileId":"$owner","onboarded":true,"deletionRequested":$deletion,"consentStatus":"$consent"}"""
                                }

                                "bind_cloud_session_v1" -> {
                                    val body = CloudJson.parseToJsonElement((request.body as TextContent).text).jsonObject
                                    val device = body.getValue("p_device_id").jsonPrimitive.content
                                    val token = request.headers["Authorization"]!!.removePrefix("Bearer ")
                                    val claims =
                                        CloudJson
                                            .parseToJsonElement(
                                                Base64.UrlSafe
                                                    .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                                                    .decode(token.split('.')[1])
                                                    .decodeToString(),
                                            ).jsonObject
                                    val profile = claims.getValue("sub").jsonPrimitive.content
                                    val session = claims.getValue("session_id").jsonPrimitive.content
                                    assertEquals("a".repeat(64), request.headers["X-Advantly-Installation-Proof"])
                                    bindings += device
                                    holdBinding?.waitForRelease()
                                    """{"contractVersion":1,"profileId":"$profile","sessionId":"$session","installationId":"installation","deviceId":"$device"}"""
                                }

                                "cloud_sync_push_v1" -> {
                                    val raw = (request.body as TextContent).text
                                    val batch =
                                        CloudJson.decodeFromJsonElement(
                                            PushBatch.serializer(),
                                            CloudJson.parseToJsonElement(raw).jsonObject.getValue("p_batch"),
                                        )
                                    bodies += raw
                                    batches += batch
                                    val result =
                                        PushResponse(
                                            devices = batch.devices.map { ItemResult(it.id, "ok") },
                                            players = batch.players.map { ItemResult(it.id, "ok") },
                                            matches =
                                                batch.matches.map {
                                                    ItemResult(
                                                        it.id,
                                                        "updated",
                                                        projection = it.projection?.let { "applied" },
                                                    )
                                                },
                                            events =
                                                batch.events.filterIndexed { index, _ -> !partial || index == 0 }.map {
                                                    ItemResult(
                                                        it.id,
                                                        if (reject) {
                                                            "rejected"
                                                        } else if (committed.add(it.id)) {
                                                            "accepted"
                                                        } else {
                                                            "duplicate"
                                                        },
                                                        reason = if (reject) "device_not_owned" else null,
                                                    )
                                                },
                                        )
                                    holdPush?.waitForRelease()
                                    if (loseReply) throw IOException("lost after commit")
                                    CloudJson.encodeToString(PushResponse.serializer(), result)
                                }

                                "export_my_data" -> {
                                    """{"exported":true}"""
                                }

                                "logout" -> {
                                    "{}"
                                }

                                else -> {
                                    error("Unexpected request: $path")
                                }
                            }
                        respond(response, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                    }
            }
        val auth =
            SupabasePhoneAuth(
                sdk,
                "secret",
                data.cloudUploads,
                hostScope,
                threadGuard = { Thread.currentThread() === thread },
                ownership = phone,
            )
        private val backend = auth.cloudBackend(CloudInstallation("installation", "a".repeat(64)))
        private val uploads =
            object : CloudUploadPort by backend {
                override suspend fun pushSelected(
                    attempt: CloudUploadAttempt,
                    batch: PushBatch,
                ): PushResponse =
                    try {
                        backend.pushSelected(attempt, batch)
                    } finally {
                        uploadDrain?.waitForRelease()
                    }
            }
        val host =
            PhoneUploadHost(auth, phone, uploads, {
                try {
                    backend.bindDevice(it)
                } finally {
                    bindingDrain?.waitForRelease()
                }
            }, hostScope, { 1000L }, { Thread.currentThread() === thread })

        init {
            phone.start()
        }

        suspend fun signIn() {
            sdk.auth.awaitInitialization()
            val token =
                "h." +
                    Base64.UrlSafe
                        .withPadding(
                            Base64.PaddingOption.ABSENT,
                        ).encode("""{"sub":"$owner","session_id":"session-$owner-$epoch"}""".encodeToByteArray()) + ".s"
            sdk.auth.importAuthToken(token, autoRefresh = false)
            auth.refreshProfile()
            yield()
        }

        fun match() =
            phone.create(
                Presets.TENNIS_SINGLES_BO3.format(),
                Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a")),
            )

        suspend fun idle() {
            withTimeout(15_000) { while (host.state.value.busy) yield() }
        }

        suspend fun close() {
            holdBinding?.release?.complete(Unit)
            holdPush?.release?.complete(Unit)
            bindingDrain?.release?.complete(Unit)
            uploadDrain?.release?.complete(Unit)
            host.close {
                trace += "auth-close"
                auth.close()
            }
            withTimeout(15_000) { while ("auth-close" !in trace) yield() }
            lifecycle.cancel()
            phone.close()
            driver.close()
        }
    }

    @Test fun createRotationAndStatusHaveNoBindPushOrWritesAndUseCurrentOwnedPhoneOnly() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.signIn()
                assertNotEquals(f.phone.config.deviceId, f.phone.deviceId)
                assertTrue(f.host.state.value.available)
                assertNull(f.host.state.value.pendingEvents)
                val before = f.requests.toList()
                f.driver.record = true
                repeat(3) { f.host.refreshStatus() }
                assertEquals(before, f.requests.toList())
                assertTrue(f.driver.writes.isEmpty())
                assertTrue(f.bindings.isEmpty())
                f.match()
                f.host.uploadNow()
                f.idle()
                assertEquals(listOf(f.phone.deviceId.value), f.bindings.toList())
                assertFalse(f.bindings.contains(f.phone.config.deviceId.value))
                assertEquals(PhoneUploadResult.DRAINED, f.host.state.value.result)
            } finally {
                f.close()
            }
        }

    @Test fun viewModelStoreRetainsHostAcrossActivityRecreationWithoutStartingBindOrPush() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.signIn()
                val store = ViewModelStore()
                var creations = 0
                val factory =
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            creations++
                            return AuthViewModel(f.auth, f.hostScope, upload = f.host) as T
                        }
                    }
                val first = ViewModelProvider(store, factory)[AuthViewModel::class.java]
                while (first.state.value.busy) yield()
                val before = f.requests.toList()
                val rotated = ViewModelProvider(store, factory)[AuthViewModel::class.java]
                assertSame(first, rotated)
                assertSame(f.host, rotated.upload)
                assertEquals(1, creations)
                assertEquals(before, f.requests.toList())
                assertTrue(f.bindings.isEmpty())
                assertTrue(f.batches.isEmpty())
            } finally {
                f.close()
            }
        }

    @Test fun boundedRunReportsRemainingEventsAndLastResponseWithoutContinuingAutomatically() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.signIn()
                val match = f.match()
                f.data.events.appendAll(
                    (2L..2002L).map { seq ->
                        StoredEvent(
                            "bulk-$seq",
                            match.value,
                            f.phone.deviceId.value,
                            seq,
                            seq,
                            """{"type":"PointWon","side":"A"}""",
                            type = "PointWon",
                        )
                    },
                )
                f.host.uploadNow()
                f.idle()
                assertEquals(4, f.batches.size)
                assertTrue(f.batches.all { it.events.size == 500 })
                assertEquals(PhoneUploadResult.LIMIT_REACHED, f.host.state.value.result)
                assertEquals(2L, f.host.state.value.pendingEvents)
                assertEquals(1000L, f.host.state.value.lastResponseAtMs)
                yield()
                assertEquals(4, f.batches.size)
                f.host.uploadNow()
                f.idle()
                assertEquals(PhoneUploadResult.DRAINED, f.host.state.value.result)
                assertEquals(1, f.bindings.size)
            } finally {
                f.close()
            }
        }

    @Test fun lostReplyRetryIsByteIdenticalKeepsBindingAndStatusDoesNotEraseFailureImplicitly() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.signIn()
                f.match()
                assertTrue(f.phone.recordPoint(Side.A))
                f.loseReply = true
                f.host.uploadNow()
                f.idle()
                assertEquals(PhoneUploadResult.FAILED, f.host.state.value.result)
                assertEquals(PhoneUploadAction.RETRY, f.host.state.value.action)
                assertNull(f.host.state.value.lastResponseAtMs)
                yield()
                assertEquals(PhoneUploadResult.FAILED, f.host.state.value.result)
                f.phone.recordPoint(Side.B)
                assertTrue(f.host.state.value.stale)
                f.loseReply = false
                f.host.uploadNow()
                f.idle()
                assertEquals(1, f.bindings.size)
                assertEquals(f.bodies[0], f.bodies[1])
                assertEquals(PhoneUploadResult.DRAINED, f.host.state.value.result)
                assertEquals(1000L, f.host.state.value.lastResponseAtMs)
                val before = f.bodies.size
                f.driver.writes.clear()
                f.driver.record = true
                f.host.refreshStatus()
                f.idle()
                assertEquals(before, f.bodies.size)
                assertTrue(f.driver.writes.isEmpty())
                assertEquals(1000L, f.host.state.value.lastResponseAtMs)
            } finally {
                f.close()
            }
        }

    @Test fun doubleClickDoesNotQueueSecondRunDuringBindingOrPush() =
        runBlocking {
            for (binding in listOf(true, false)) {
                val f = Fixture(this)
                try {
                    f.signIn()
                    f.match()
                    val hold = Hold()
                    if (binding) f.holdBinding = hold else f.holdPush = hold
                    f.host.uploadNow()
                    withTimeout(15_000) { hold.entered.await() }
                    repeat(4) {
                        f.host.uploadNow()
                        f.host.refreshStatus()
                    }
                    assertEquals(1, f.bindings.size)
                    assertEquals(if (binding) 0 else 1, f.batches.size)
                    hold.release.complete(Unit)
                    f.idle()
                    assertEquals(1, f.batches.size)
                } finally {
                    f.close()
                }
            }
        }

    @Test fun lateWatchAdoptionAddsOnlyProvenOwnedSourcesWithoutChangingCaller() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.signIn()
                val match = f.match()
                f.host.uploadNow()
                f.idle()
                f.data.accountIdentities.recordSource("late-watch", "watch-self", "WATCH", "watch-scope", false)
                f.data.accountIdentities.recordSource("unbound-watch", "other-self", "WATCH", "watch-scope", false)
                f.data.events.append(
                    StoredEvent(
                        "watch-event",
                        match.value,
                        "late-watch",
                        1,
                        10,
                        """{"type":"PointWon","side":"A"}""",
                        type = "PointWon",
                    ),
                )
                f.data.events.append(
                    StoredEvent(
                        "unbound-event",
                        match.value,
                        "unbound-watch",
                        1,
                        11,
                        """{"type":"PointWon","side":"B"}""",
                        type = "PointWon",
                    ),
                )
                f.auth.adoptSources(setOf("late-watch"))
                f.host.uploadNow()
                f.idle()
                assertEquals(1, f.bindings.size)
                assertEquals(f.phone.deviceId.value, f.bindings.single())
                assertEquals(
                    listOf("late-watch"),
                    f.batches
                        .last()
                        .devices
                        .map { it.id },
                )
                assertEquals(
                    "wearos",
                    f.batches
                        .last()
                        .devices
                        .single()
                        .platform,
                )
                assertEquals(
                    listOf("watch-event"),
                    f.batches
                        .last()
                        .events
                        .map { it.id },
                )
                assertEquals(1L, f.data.cloud.pendingCount())
            } finally {
                f.close()
            }
        }

    @Test fun consentDeletionAndAccountChangesFenceBindingAndPushAndNeverLeakOldUiOrAcks() =
        runBlocking {
            for (binding in listOf(true, false)) {
                for (transition in listOf("consent", "logout", "bob", "alice-again", "deletion")) {
                    val f = Fixture(this)
                    try {
                        f.signIn()
                        val match = f.match()
                        val oldEvents = f.data.events.eventsFor(match.value)
                        val oldDevice = f.phone.deviceId
                        val hold = Hold()
                        if (binding) f.holdBinding = hold else f.holdPush = hold
                        f.host.uploadNow()
                        withTimeout(15_000) { hold.entered.await() }
                        when (transition) {
                            "consent" -> {
                                f.consent = ConsentStatus.REQUIRED
                                f.auth.refreshProfile()
                            }

                            "deletion" -> {
                                f.deletion = true
                                f.auth.refreshProfile()
                            }

                            "logout" -> {
                                f.auth.signOut()
                            }

                            else -> {
                                f.owner = if (transition == "bob") "bob" else "alice"
                                f.epoch++
                                f.signIn()
                            }
                        }
                        withTimeout(15_000) { hold.cancelled.await() }
                        assertNull(f.host.state.value.pendingEvents)
                        assertNull(f.host.state.value.lastResponseAtMs)
                        hold.release.complete(Unit)
                        f.idle()
                        assertNull(f.host.state.value.lastResponseAtMs)
                        assertNull(f.host.state.value.result)
                        assertEquals(oldEvents, f.data.events.eventsFor(match.value))
                        if (binding) assertTrue(f.batches.isEmpty())
                        // Keine Cloud-Quittierung trotz ggf. bereits serverseitig bestätigter alter Events.
                        assertEquals(oldEvents.size.toLong(), f.data.cloud.pendingCount())
                        if (transition == "consent") assertEquals(oldDevice, f.phone.deviceId)
                    } finally {
                        f.close()
                    }
                }
            }
        }

    @Test fun partialAndPermanentRejectionRemainVisibleAndNeverStartAnotherRun() =
        runBlocking {
            for (rejected in listOf(true, false)) {
                val f = Fixture(this)
                try {
                    f.signIn()
                    f.match()
                    f.phone.recordPoint(Side.A)
                    f.reject = rejected
                    f.partial = !rejected
                    f.host.uploadNow()
                    f.idle()
                    assertEquals(if (rejected) PhoneUploadResult.BLOCKED else PhoneUploadResult.PARTIAL, f.host.state.value.result)
                    assertEquals(1000L, f.host.state.value.lastResponseAtMs)
                    assertTrue(if (rejected) f.host.state.value.rejectedEvents!! > 0 else f.host.state.value.pendingEvents!! > 0)
                    yield()
                    assertEquals(1, f.batches.size)
                } finally {
                    f.close()
                }
            }
        }

    @Test fun closeCancelsThenWaitsForBindingAndPushBeforeAuthCloseEvenWithCancelledParent() =
        runBlocking {
            for (binding in listOf(true, false)) {
                val f = Fixture(this)
                try {
                    f.signIn()
                    f.match()
                    val hold = Hold()
                    if (binding) f.holdBinding = hold else f.holdPush = hold
                    val drain = Hold()
                    if (binding) f.bindingDrain = drain else f.uploadDrain = drain
                    f.host.uploadNow()
                    withTimeout(15_000) { hold.entered.await() }
                    f.lifecycle.cancel()
                    f.host.close {
                        assertTrue(drain.ended.isCompleted)
                        f.trace += "auth-close"
                        f.auth.close()
                    }
                    withTimeout(15_000) { hold.cancelled.await() }
                    withTimeout(15_000) { drain.cancelled.await() }
                    assertEquals(PhoneUploadPhase.CLOSED, f.host.state.value.phase)
                    assertFalse(f.trace.contains("auth-close"))
                    val before = f.requests.toList()
                    f.host.uploadNow()
                    f.host.refreshStatus()
                    assertEquals(before, f.requests.toList())
                    hold.release.complete(Unit)
                    assertFalse(f.trace.contains("auth-close"))
                    drain.release.complete(Unit)
                    withTimeout(15_000) { while (!f.trace.contains("auth-close")) yield() }
                    f.driver.writes.clear()
                    f.driver.record = true
                    yield()
                    assertTrue(f.driver.writes.isEmpty())
                    assertTrue(f.phone.recordPoint(Side.B))
                    assertEquals(PhoneUploadPhase.CLOSED, f.host.state.value.phase)
                } finally {
                    f.close()
                }
            }
        }

    @Test fun offHostActionsAreRejectedBeforeDataOrNetworkAccess() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.signIn()
                val before = f.requests.toList()
                var failure: Throwable? = null
                val other =
                    Thread {
                        try {
                            assertThrows(IllegalStateException::class.java) { f.host.uploadNow() }
                            assertThrows(IllegalStateException::class.java) { f.host.refreshStatus() }
                            assertThrows(IllegalStateException::class.java) { f.host.close {} }
                        } catch (error: Throwable) {
                            failure = error
                        }
                    }
                other.start()
                other.join()
                assertNull(failure)
                assertEquals(before, f.requests.toList())
            } finally {
                f.close()
            }
        }
}
