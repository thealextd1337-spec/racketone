package eu.advantly.android.auth

import android.app.Application
import android.database.sqlite.SQLiteDiskIOException
import android.os.Looper
import app.cash.sqldelight.db.QueryResult
import eu.advantly.android.UnsupportedDirectorySyncLinux
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudInstallation
import eu.advantly.cloud.sync.CloudJson
import eu.advantly.cloud.sync.ItemResult
import eu.advantly.cloud.sync.PushBatch
import eu.advantly.cloud.sync.PushResponse
import eu.advantly.cloud.sync.SupabasePhoneAuth
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.Presets
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.data.createAndroidDriver
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.match.session.AndroidSessionAccess
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.io.encoding.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class PhoneQueuedCloudRevocationTest {
    @org.junit.Before
    fun reset() =
        eu.advantly.android
            .InstallationHostFailureTest()
            .reset()

    private class QueueDispatcher : CoroutineDispatcher() {
        val owner = Thread.currentThread()
        val pending = ConcurrentLinkedQueue<Runnable>()

        override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== owner

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            pending += block
        }

        fun drain() {
            while (true) (pending.poll() ?: break).run()
        }

        fun await(condition: () -> Boolean) {
            val until = System.nanoTime() + 15_000_000_000
            while (!condition()) {
                check(System.nanoTime() < until) { "Timed out waiting for controlled HTTP/host continuation" }
                Thread.sleep(2)
            }
        }

        fun pump(condition: () -> Boolean) {
            await {
                drain()
                condition()
            }
        }
    }

    private class Fixture(
        cancelImmediately: Boolean = true,
    ) {
        val app: Application = RuntimeEnvironment.getApplication()
        val dispatcher = QueueDispatcher()
        val parentScope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = AndroidMatchSessions.get(app, PeerKind.PHONE)
        val runtime = AndroidAccountRuntime(app, session, parentScope)
        val scope = if (cancelImmediately) runtime.scope else parentScope
        val access = AndroidSessionAccess(app, session)
        val pushes = AtomicInteger()
        val requests = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val audit = createAndroidDriver(app)
        val sdk =
            createSupabaseClient("https://cloud.test", "public") {
                defaultLogLevel = LogLevel.NONE
                install(Auth) { minimalConfig() }
                httpEngine =
                    MockEngine { request ->
                        requests.incrementAndGet()
                        val name = request.url.encodedPath.substringAfterLast('/')
                        val text =
                            when (name) {
                                "logout" -> {
                                    "{}"
                                }

                                "issue_age_ticket" -> {
                                    """{"ticket":"ticket"}"""
                                }

                                "my_auth_profile" -> {
                                    """{"profileId":"alice","onboarded":true,"deletionRequested":false,"consentStatus":"CURRENT"}"""
                                }

                                "bind_cloud_session_v1" -> {
                                    val device =
                                        CloudJson
                                            .parseToJsonElement(
                                                (request.body as TextContent).text,
                                            ).jsonObject["p_device_id"]!!
                                            .jsonPrimitive.content
                                    """{"contractVersion":1,"profileId":"alice","sessionId":"session-a","installationId":"installation","deviceId":"$device"}"""
                                }

                                "cloud_sync_push_v1" -> {
                                    val batch =
                                        CloudJson.decodeFromJsonElement(
                                            PushBatch.serializer(),
                                            CloudJson.parseToJsonElement((request.body as TextContent).text).jsonObject["p_batch"]!!,
                                        )
                                    if (pushes.incrementAndGet() == 1) {
                                        entered.complete(Unit)
                                        response.await()
                                    }
                                    CloudJson.encodeToString(
                                        PushResponse.serializer(),
                                        PushResponse(
                                            devices = batch.devices.map { ItemResult(it.id, "ok") },
                                            players = batch.players.map { ItemResult(it.id, "ok") },
                                            matches =
                                                batch.matches.map {
                                                    ItemResult(
                                                        it.id,
                                                        "updated",
                                                        projection =
                                                            if (it.projection !=
                                                                null
                                                            ) {
                                                                "applied"
                                                            } else {
                                                                null
                                                            },
                                                    )
                                                },
                                            events = batch.events.map { ItemResult(it.id, "accepted") },
                                        ),
                                    )
                                }

                                else -> {
                                    error("Unexpected request: $name")
                                }
                            }
                        respond(text, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                    }
            }
        val auth =
            SupabasePhoneAuth(
                sdk,
                "secret",
                session.data.cloudUploads,
                scope,
                threadGuard = { Thread.currentThread() === dispatcher.owner },
                ownership = session,
                hostAccessAllowed = { runtime.allowed },
            )
        val backend = auth.cloudBackend(CloudInstallation("installation", "a".repeat(64)))
        val host =
            PhoneUploadHost(
                auth,
                session,
                backend,
                backend::bindDevice,
                scope,
                threadGuard = { Thread.currentThread() === dispatcher.owner },
                hostAccessAllowed = { runtime.allowed },
            )

        init {
            audit.execute(null, "CREATE TABLE test_ack_trace (event_id TEXT)", 0)
            audit.execute(
                null,
                "CREATE TRIGGER test_ack AFTER UPDATE OF cloud_state ON match_event WHEN new.cloud_state=1 AND old.cloud_state!=1 BEGIN INSERT INTO test_ack_trace VALUES(new.id); END",
                0,
            )
            var ready = false
            scope.launch {
                sdk.auth.awaitInitialization()
                val token =
                    "h." +
                        Base64.UrlSafe
                            .withPadding(Base64.PaddingOption.ABSENT)
                            .encode("""{"sub":"alice","session_id":"session-a"}""".encodeToByteArray()) + ".s"
                sdk.auth.importAuthToken(token, autoRefresh = false)
                auth.refreshProfile()
                ready = true
            }
            dispatcher.pump { ready }
        }

        fun match() {
            val self =
                session.data.players
                    .self()!!
                    .id
            session.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(self), listOf(PlayerId("opponent")), self))
        }

        fun ackWrites(): Long =
            audit
                .executeQuery(null, "SELECT count(*) FROM test_ack_trace", {
                    it.next()
                    QueryResult.Value(it.getLong(0)!!)
                }, 0)
                .value

        fun begin() {
            match()
            host.uploadNow()
            dispatcher.pump { entered.isCompleted }
            match() // Neuer lokaler Batch entsteht erst nach der ersten Auswahl.
            dispatcher.drain()
        }

        fun queueResponse() {
            assertTrue(dispatcher.pending.isEmpty())
            response.complete(Unit)
            dispatcher.await { dispatcher.pending.isNotEmpty() }
        }

        fun revoke() {
            val thread = Thread { AndroidInstallationGuard.storageFailed(app, SQLiteDiskIOException("background storage revoked")) }
            thread.start()
            thread.join()
            assertFalse(access.privateTransportAllowed)
        }

        fun finish() {
            response.complete(Unit)
            dispatcher.drain()
            host.close { runCatching { auth.close() } }
            dispatcher.drain()
            scope.cancel()
            parentScope.cancel()
            runtime.close()
            access.close()
            audit.close()
            runBlocking { sdk.close() }
        }
    }

    @Test
    fun queuedReplyAfterRevocationCannotAckOrSend() {
        val f = Fixture()
        try {
            f.begin()
            f.queueResponse()
            f.revoke()
            // Nur die bereits zuvor eingereihte HTTP-Fortsetzung ausführen, nicht den Android-UI-Abbau.
            f.dispatcher.pump { !f.host.state.value.busy }
            assertEquals(0, f.ackWrites())
            assertEquals(1, f.pushes.get())
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            f.finish()
        }
    }

    @Test
    fun capabilityFenceRejectsReplyWithoutJobCancellation() {
        val f = Fixture(cancelImmediately = false)
        try {
            f.begin()
            f.queueResponse()
            f.revoke()
            f.dispatcher.pump { !f.host.state.value.busy }
            assertEquals(0, f.ackWrites())
            assertEquals(1, f.pushes.get())
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            f.finish()
        }
    }

    @Test
    fun revokedAuthStaysFencedAfterRetry() {
        val f = Fixture(cancelImmediately = false)
        try {
            val original = AndroidInstallationGuard.get(f.app)
            val source = f.session.deviceId
            val export = f.auth.dataExports()
            val exportContext = requireNotNull(export.context.value)
            f.revoke()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(AndroidInstallationGuard.retry(f.app) is eu.advantly.data.AndroidInstallationAvailability.Ready)
            val next = AndroidMatchSessions.get(f.app, PeerKind.PHONE)
            assertEquals(source, next.deviceId)
            assertEquals(original, AndroidInstallationGuard.get(f.app))
            assertNotSame(f.session, next)
            assertFalse(f.runtime.allowed)
            val requestsBefore = f.requests.get()
            val authError = runBlocking { runCatching { f.auth.issueAgeTicket("DE", "2000-01-01") }.exceptionOrNull() }
            assertTrue(authError is CloudException)
            val exportError = runBlocking { runCatching { export.request(exportContext, "request") }.exceptionOrNull() }
            assertTrue(exportError is CloudException)
            f.host.uploadNow()
            assertEquals(requestsBefore, f.requests.get())
            assertEquals(0, f.ackWrites())
        } finally {
            f.finish()
        }
    }

    @Test
    fun healthyLogoutPersistsAndClearsAccount() {
        val f = Fixture()
        try {
            val queries =
                eu.advantly.data.db
                    .AdvantlyDatabase(f.audit)
                    .cloudUploadQueries
            val before = queries.generation().executeAsOne()
            var complete = false
            f.scope.launch {
                f.auth.signOut()
                complete = true
            }
            f.dispatcher.pump { complete }
            assertTrue(queries.generation().executeAsOne() > before)
            assertNull(f.auth.session.value)
            assertTrue(f.runtime.allowed)
        } finally {
            f.finish()
        }
    }

    @Test
    fun healthyReplyAcksAndSendsNextBatch() {
        val f = Fixture()
        try {
            f.begin()
            f.queueResponse()
            f.dispatcher.pump { !f.host.state.value.busy }
            println("Healthy state=${f.host.state.value}, pushes=${f.pushes.get()}, acks=${f.ackWrites()}")
            assertTrue(f.ackWrites() > 0)
            assertTrue(f.pushes.get() >= 2)
        } finally {
            f.finish()
        }
    }

    @Test
    fun closedHostRejectsQueuedReply() {
        val f = Fixture()
        try {
            f.begin()
            f.queueResponse()
            f.host.close { f.auth.close() }
            f.dispatcher.pump { !f.host.state.value.busy }
            assertEquals(0, f.ackWrites())
            assertEquals(1, f.pushes.get())
        } finally {
            f.finish()
        }
    }
}
