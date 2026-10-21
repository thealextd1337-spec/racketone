package eu.advantly.android

import android.app.Application
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import android.os.Looper
import eu.advantly.android.auth.AndroidAccountRuntime
import eu.advantly.cloud.sync.SupabasePhoneAuth
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.device.link.WearDataLayerInbound
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.sync.protocol.PeerKind
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.io.encoding.Base64

/** Aus den sechs unveraendert ausgefuehrten Review-Probes erweiterte Hostregressionen; Baseline separat gesichert. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = Application::class,
    shadows = [FreshReviewQueryDatabase::class, UnsupportedDirectorySyncLinux::class],
)
class FreshReviewStorageProbeTest {
    @Before
    fun reset() {
        FreshReviewQueryDatabase.mode = ""
        FreshReviewQueryDatabase.failure = null
        FreshReviewQueryDatabase.hits = 0
        if (!AndroidInstallationGuard.privateTransportAllowed(RuntimeEnvironment.getApplication())) {
            AndroidMatchSessions::class.java
                .getDeclaredField("instance")
                .apply { isAccessible = true }
                .set(AndroidMatchSessions, null)
        }
        InstallationHostFailureTest().reset()
    }

    @After
    fun finishRuntime() {
        FreshReviewQueryDatabase.mode = ""
        FreshReviewQueryDatabase.failure = null
        if (AndroidMatchSessions.current() != null) {
            AndroidInstallationGuard.storageFailed(RuntimeEnvironment.getApplication(), SQLiteDiskIOException("test teardown"))
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun activation(error: SQLiteException?) {
        val app = RuntimeEnvironment.getApplication()
        FreshReviewQueryDatabase.mode = "activation"
        FreshReviewQueryDatabase.failure = error
        var result: Any? = null
        val escaped =
            runCatching {
                result = AndroidInstallationGuard.storage(app) { AndroidMatchSessions.get(app, PeerKind.PHONE) }
            }.exceptionOrNull()
        FreshReviewQueryDatabase.mode = ""
        println("ACTIVATION requested=$error escaped=$escaped hits=${FreshReviewQueryDatabase.hits} result=$result")
        try {
            if (error is SQLiteDiskIOException) {
                assertEquals(1, FreshReviewQueryDatabase.hits)
                assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
                assertNull("Storagefehler darf Bootstrap nicht als Programmierfehler verlassen", escaped)
                assertNull(AndroidMatchSessions.current())
                assertNull(
                    AndroidMatchSessions::class.java
                        .getDeclaredField("resources")
                        .apply { isAccessible = true }
                        .get(AndroidMatchSessions),
                )
                assertNull(result)
            } else if (error != null) {
                assertSame(error, escaped)
            } else {
                assertNull(escaped)
                assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
                assertTrue(result === AndroidMatchSessions.current())
            }
        } finally {
            if (AndroidMatchSessions.current() != null) {
                AndroidInstallationGuard.storageFailed(app, SQLiteDiskIOException("review teardown"))
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun activationStorageFailureIsHandled() = activation(SQLiteDiskIOException("review activation storage"))

    @Test fun activationHealthyControl() = activation(null)

    @Test fun activationProgrammingControl() = activation(SQLiteException("review activation programming"))

    private class QueueDispatcher : CoroutineDispatcher() {
        private val owner = Thread.currentThread()
        private val pending = LinkedBlockingQueue<Runnable>()

        override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== owner

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            pending += block
        }

        fun pump(until: () -> Boolean) {
            while (true) {
                while (true) (pending.poll() ?: break).run()
                if (until()) return
                checkNotNull(pending.poll(10, TimeUnit.SECONDS)) { "SDK-Collector wurde nicht erreicht" }.run()
            }
        }
    }

    @Test
    fun firstNativeBackgroundFailureBetweenCollectorAccessAndGateShowsErrorUi() {
        val app = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        val session = requireNotNull(AndroidMatchSessions.current())
        val installation = AndroidInstallationGuard.get(app)
        val marker = File(app.noBackupFilesDir, "installation-v1").readBytes().toList()
        val source = session.deviceId
        val errors = mutableListOf<Throwable>()
        val dispatcher = QueueDispatcher()
        val parent = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> errors += error })
        val runtime = AndroidAccountRuntime(app, session, parent)
        var armed = false
        var insideStorage = false
        var raceHits = 0
        var completions = 0
        val sdk =
            createSupabaseClient("https://cloud.test", "public") {
                defaultLogLevel = LogLevel.NONE
                install(Auth) { minimalConfig() }
            }
        val auth =
            SupabasePhoneAuth(
                sdk,
                "test",
                session.data.cloudUploads,
                runtime.scope,
                ownership = session,
                hostAccessAllowed = {
                    val captured = runtime.allowed
                    if (armed && insideStorage && captured && raceHits == 0) {
                        raceHits++
                        val done = CountDownLatch(1)
                        FreshReviewQueryDatabase.mode = "collector-race"
                        FreshReviewQueryDatabase.failure = SQLiteDiskIOException("first background setting read failed")
                        val background =
                            Thread({
                                try {
                                    AndroidInstallationGuard.storage(app) {
                                        session.data.settings.get("collector.race")
                                        true
                                    }
                                } finally {
                                    done.countDown()
                                }
                            }, "collector-storage-revoke")
                        background.start()
                        assertTrue(done.await(5, TimeUnit.SECONDS))
                        background.join(5_000)
                        assertFalse(background.isAlive)
                    }
                    captured
                },
                hostStorageAction = { action ->
                    insideStorage = true
                    try {
                        runtime.storageAction(action).also { completions++ }
                    } finally {
                        insideStorage = false
                    }
                },
            )
        try {
            runBlocking { sdk.auth.awaitInitialization() }
            dispatcher.pump { true }
            val before = completions
            armed = true
            runBlocking { sdk.auth.importAuthToken("header.eyJzdWIiOiJyYWNlIiwic2Vzc2lvbl9pZCI6Im9uZSJ9.signature", autoRefresh = false) }
            dispatcher.pump { errors.isNotEmpty() || completions > before }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, raceHits)
            assertEquals(1, FreshReviewQueryDatabase.hits)
            assertTrue("Collector escaped after native BG revoke: $errors", errors.isEmpty())
            assertFalse(runtime.allowed)
            assertNull(AndroidMatchSessions.current())
            assertFalse(WearDataLayerInbound.isAttached)
            assertNotNull(
                activity
                    .get()
                    .window.decorView
                    .findViewWithTag<android.view.View>("installation_error"),
            )
            assertEquals(marker, File(app.noBackupFilesDir, "installation-v1").readBytes().toList())
            assertEquals(installation, (AndroidInstallationGuard.retry(app) as AndroidInstallationAvailability.Ready).installation)
            val replacement = AndroidMatchSessions.get(app, PeerKind.PHONE)
            assertEquals(source, replacement.deviceId)
            assertFalse(runtime.allowed)
            auth.close()
            assertSame(replacement, AndroidMatchSessions.current())
        } finally {
            FreshReviewQueryDatabase.mode = ""
            FreshReviewQueryDatabase.failure = null
            auth.close()
            runtime.close()
            parent.cancel()
            runBlocking { sdk.close() }
            activity.pause().stop().destroy()
        }
    }

    private fun authCollector(
        error: SQLiteException?,
        revokeAt: String? = null,
        retry: Boolean = false,
    ) {
        val app = RuntimeEnvironment.getApplication()
        val session = AndroidMatchSessions.get(app, PeerKind.PHONE)
        val installation = AndroidInstallationGuard.get(app)
        val source = session.deviceId
        val marker = File(app.noBackupFilesDir, "installation-v1").readBytes().toList()
        shadowOf(Looper.getMainLooper()).idle()
        val uncaught = mutableListOf<Throwable>()
        val dispatcher = QueueDispatcher()
        var reads = 0
        var writesAfterRevoke = 0
        var armed = false
        var rejected = false
        val ownership =
            object : eu.advantly.data.LocalAccountOwnership by session {
                override val retainedProfileId: String? get() {
                    reads++
                    return session.retainedProfileId
                }

                override fun detachAccountSession() {
                    if (AndroidMatchSessions.current() !== session) writesAfterRevoke++
                    session.detachAccountSession()
                }

                override fun leaveAccount() {
                    if (AndroidMatchSessions.current() !== session) writesAfterRevoke++
                    session.leaveAccount()
                }
            }
        // Nur Beobachtung: Produktion hat keinen Handler und wuerde den Android-Uncaught-Pfad verwenden.
        val parent =
            CoroutineScope(
                SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, cause -> uncaught += cause },
            )
        val runtime = AndroidAccountRuntime(app, session, parent)
        val sdk =
            createSupabaseClient("https://cloud.test", "public") {
                defaultLogLevel = LogLevel.NONE
                install(Auth) { minimalConfig() }
            }
        val auth =
            SupabasePhoneAuth(
                sdk,
                "review-secret",
                session.data.cloudUploads,
                runtime.scope,
                ownership = ownership,
                hostAccessAllowed = { runtime.allowed },
                hostStorageAction = { action ->
                    if (armed &&
                        revokeAt == "before"
                    ) {
                        AndroidInstallationGuard.storageFailed(app, SQLiteDiskIOException("before collector operation"))
                    }
                    runtime
                        .storageAction {
                            action()
                            if (armed &&
                                revokeAt == "after"
                            ) {
                                AndroidInstallationGuard.storageFailed(app, SQLiteDiskIOException("after collector operation"))
                            }
                        }.also { if (!it) rejected = true }
                },
            )
        try {
            runBlocking { sdk.auth.awaitInitialization() }
            shadowOf(Looper.getMainLooper()).idle()
            FreshReviewQueryDatabase.mode = "auth"
            FreshReviewQueryDatabase.failure = error
            armed = true
            val token =
                "h." +
                    Base64.UrlSafe
                        .withPadding(Base64.PaddingOption.ABSENT)
                        .encode("""{"sub":"review-account","session_id":"review-session"}""".encodeToByteArray()) + ".s"
            runBlocking { sdk.auth.importAuthToken(token, autoRefresh = false) }
            dispatcher.pump { reads > 0 || rejected }
            shadowOf(Looper.getMainLooper()).idle()
            FreshReviewQueryDatabase.mode = ""
            println("AUTH requested=$error uncaught=$uncaught hits=${FreshReviewQueryDatabase.hits}")
            if (error is SQLiteDiskIOException || revokeAt != null) {
                assertEquals(if (error != null) 1 else 0, FreshReviewQueryDatabase.hits)
                assertFalse(runtime.allowed)
                assertNull(AndroidMatchSessions.current())
                assertFalse(WearDataLayerInbound.isAttached)
                var consumed = 0
                assertFalse(
                    WearDataLayerInbound.deliverQueuedItem(
                        "local",
                        "peer",
                        listOf("advantly", "link", "q", "local", "queued"),
                        byteArrayOf(1),
                    ) {
                        consumed++
                    },
                )
                assertEquals(0, consumed)
                assertTrue("Erster Storagefehler entweicht aus SDK-Collector: $uncaught", uncaught.isEmpty())
                assertTrue(rejected)
                assertNull(auth.session.value)
                assertEquals(0, writesAfterRevoke)
                if (revokeAt == "before") assertEquals(0, reads)
                if (retry) {
                    assertEquals(installation, (AndroidInstallationGuard.retry(app) as AndroidInstallationAvailability.Ready).installation)
                    val replacement = AndroidMatchSessions.get(app, PeerKind.PHONE)
                    assertEquals(source, replacement.deviceId)
                    assertFalse(runtime.allowed)
                    val previousReads = reads
                    val retryToken =
                        "h." +
                            Base64.UrlSafe
                                .withPadding(Base64.PaddingOption.ABSENT)
                                .encode("""{"sub":"review-account","session_id":"review-session-retry"}""".encodeToByteArray()) + ".s"
                    runBlocking { sdk.auth.importAuthToken(retryToken, autoRefresh = false) }
                    dispatcher.pump { true }
                    assertEquals(previousReads, reads)
                    assertSame(replacement, AndroidMatchSessions.current())
                }
                assertEquals(marker, File(app.noBackupFilesDir, "installation-v1").readBytes().toList())
            } else if (error != null) {
                assertEquals(1, FreshReviewQueryDatabase.hits)
                assertSame(error, uncaught.single())
                assertTrue(runtime.allowed)
            } else {
                assertTrue(uncaught.isEmpty())
                assertTrue(runtime.allowed)
            }
        } finally {
            FreshReviewQueryDatabase.mode = ""
            FreshReviewQueryDatabase.failure = null
            auth.close()
            assertEquals(0, writesAfterRevoke)
            runtime.close()
            parent.cancel()
            runBlocking { sdk.close() }
            if (AndroidMatchSessions.current() != null) {
                AndroidInstallationGuard.storageFailed(app, SQLiteDiskIOException("review teardown"))
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun authCollectorStorageFailureIsHandled() = authCollector(SQLiteDiskIOException("review auth storage"))

    @Test fun authCollectorHealthyControl() = authCollector(null)

    @Test fun authCollectorProgrammingControl() = authCollector(SQLiteException("review auth programming"))

    @Test fun authCollectorRejectsRevocationBeforeActualOperation() = authCollector(null, revokeAt = "before")

    @Test fun authCollectorRejectsRevocationAfterActualOperation() = authCollector(null, revokeAt = "after")

    @Test fun failedAuthHostCannotRebindAfterSameSourceRetry() = authCollector(SQLiteDiskIOException("collector retry probe"), retry = true)

    private fun protectedBytes(app: Application): Map<String, List<Byte>> {
        val database = app.getDatabasePath("advantly.db")
        return listOf("", "-wal", "-shm", "-journal")
            .filter {
                File(database.path + it).exists()
            }.associateWith { File(database.path + it).readBytes().toList() } +
            ("marker" to File(app.noBackupFilesDir, "installation-v1").readBytes().toList())
    }

    @Test
    fun activationRepeatedFailureKeepsProofIdentityAndOldCleanupCannotStopRetry() {
        val app = RuntimeEnvironment.getApplication()
        val installation = AndroidInstallationGuard.get(app)
        val original = protectedBytes(app)
        var source: eu.advantly.core.model.DeviceId? = null
        var identity: List<String?>? = null
        val abortedResources = mutableListOf<Any>()
        repeat(3) { attempt ->
            if (attempt > 0) {
                val retry = AndroidInstallationGuard.retry(app) as AndroidInstallationAvailability.Ready
                assertEquals(installation, retry.installation)
            }
            val failure = SQLiteDiskIOException("activate attempt $attempt")
            FreshReviewQueryDatabase.mode = "activation"
            FreshReviewQueryDatabase.failure = failure
            val result =
                AndroidInstallationGuard.storage(app) {
                    AndroidMatchSessions.prepare(app, PeerKind.PHONE) { candidate, _ ->
                        // Nur Testbeobachtung der alten Ressourcen; kein Produktionsadapter via Reflection.
                        abortedResources +=
                            requireNotNull(
                                AndroidMatchSessions::class.java
                                    .getDeclaredField(
                                        "resources",
                                    ).apply { isAccessible = true }
                                    .get(AndroidMatchSessions),
                            )
                        val actual = listOf("identity.authority", "identity.epoch", "identity.wireScope").map(candidate.data.settings::get)
                        if (attempt == 0) {
                            source = candidate.deviceId
                            identity = actual
                        }
                        assertEquals(source, candidate.deviceId)
                        assertEquals(identity, actual)
                    }
                }
            assertNull(result)
            assertNull(AndroidMatchSessions.current())
            assertFalse(WearDataLayerInbound.isAttached)
            assertSame(
                failure,
                org.junit.Assert.assertThrows(SQLiteDiskIOException::class.java) { AndroidInstallationGuard.requireReady(app) },
            )
            var consumed = 0
            assertFalse(
                WearDataLayerInbound.deliverQueuedItem(
                    "local",
                    "peer",
                    listOf("advantly", "link", "q", "local", "queued"),
                    byteArrayOf(1),
                ) {
                    consumed++
                },
            )
            assertEquals(0, consumed)
            assertEquals(original, protectedBytes(app))
        }
        FreshReviewQueryDatabase.mode = ""
        assertEquals(installation, (AndroidInstallationGuard.retry(app) as AndroidInstallationAvailability.Ready).installation)
        val replacement = AndroidMatchSessions.get(app, PeerKind.PHONE)
        assertEquals(source, replacement.deviceId)
        assertEquals(identity, listOf("identity.authority", "identity.epoch", "identity.wireScope").map(replacement.data.settings::get))
        abortedResources.forEach { old ->
            old.javaClass
                .getDeclaredMethod("invalidate", Exception::class.java)
                .apply { isAccessible = true }
                .invoke(old, SQLiteDiskIOException("late old generation cleanup"))
            assertSame(replacement, AndroidMatchSessions.current())
            assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
        }
        // Alte finish-Callbacks sind noch im Main-Handler. Sie duerfen die neue Runtime nicht abbauen.
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(replacement, AndroidMatchSessions.current())
        assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
        assertEquals(original, protectedBytes(app))
    }

    @Test
    fun repeatedReentrantFailurePreservesRetainedWalFamilyAndRecoveryHold() {
        val app = RuntimeEnvironment.getApplication()
        val originalFile = app.getDatabasePath("advantly.db")
        originalFile.parentFile!!.mkdirs()
        listOf("", "-wal", "-shm", "-journal").forEach { File(originalFile.path + it).writeText("retained original $it") }
        val installation = AndroidInstallationGuard.get(app)
        val original = protectedBytes(app)
        var source: eu.advantly.core.model.DeviceId? = null
        var authority: String? = null
        repeat(3) { attempt ->
            if (attempt > 0) {
                val retry = AndroidInstallationGuard.retry(app) as AndroidInstallationAvailability.Ready
                assertEquals(installation, retry.installation)
            }
            assertNull(
                AndroidInstallationGuard.storage(app) {
                    AndroidMatchSessions.prepare(app, PeerKind.PHONE) { candidate, stage ->
                        if (attempt == 0) {
                            source = candidate.deviceId
                            authority = candidate.data.settings.get("identity.authority")
                        }
                        assertEquals(source, candidate.deviceId)
                        assertEquals(authority, candidate.data.settings.get("identity.authority"))
                        stage.afterCommit { AndroidInstallationGuard.storage(app) { throw SQLiteDiskIOException("held publish $attempt") } }
                    }
                },
            )
            assertEquals(original, protectedBytes(app))
            assertNull(AndroidMatchSessions.current())
            assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
        }
        assertEquals(installation, (AndroidInstallationGuard.retry(app) as AndroidInstallationAvailability.Ready).installation)
        val replacement = AndroidMatchSessions.get(app, PeerKind.PHONE)
        assertEquals(source, replacement.deviceId)
        assertEquals(authority, replacement.data.settings.get("identity.authority"))
        assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
        val self =
            eu.advantly.core.model
                .PlayerId("local-owner")
        replacement.create(
            eu.advantly.core.scoring.Presets.TENNIS_SINGLES_BO3
                .format(),
            eu.advantly.core.model
                .Lineup(
                    listOf(self),
                    listOf(
                        eu.advantly.core.model
                            .PlayerId("opponent"),
                    ),
                    self,
                ),
        )
        assertTrue(replacement.recordPoint(eu.advantly.core.model.Side.A))
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(replacement, AndroidMatchSessions.current())
        assertEquals(original, protectedBytes(app))
    }

    @Test
    fun activationFailureShowsActualPhoneErrorView() {
        FreshReviewQueryDatabase.mode = "activation"
        FreshReviewQueryDatabase.failure = SQLiteDiskIOException("activity activation failed")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(
            activity
                .get()
                .window.decorView
                .findViewWithTag<android.view.View>("installation_error"),
        )
        assertNull(AndroidMatchSessions.current())
        assertFalse(WearDataLayerInbound.isAttached)
        activity.pause().stop().destroy()
    }

    @Test
    fun reentrantDiscardSkipsRemainingHostPublications() {
        val app = RuntimeEnvironment.getApplication()
        val failure = SQLiteDiskIOException("reentrant publication storage failed")
        var publications = 0
        var cleanups = 0
        val result =
            AndroidInstallationGuard.storage(app) {
                AndroidMatchSessions.prepare(app, PeerKind.PHONE) { _, stage ->
                    stage.onDiscard { cleanups++ }
                    stage.afterCommit {
                        publications++
                        // Wie ein innerer Allowed-/Storagepfad: Fehler wurde schon behandelt, Callback kehrt normal zurueck.
                        AndroidInstallationGuard.storage(app) { throw failure }
                    }
                    stage.afterCommit { publications++ }
                }
            }
        assertNull(result)
        assertEquals(1, publications)
        assertEquals(1, cleanups)
        assertNull(AndroidMatchSessions.current())
        assertFalse(WearDataLayerInbound.isAttached)
        assertSame(failure, org.junit.Assert.assertThrows(SQLiteDiskIOException::class.java) { AndroidInstallationGuard.requireReady(app) })
    }
}

@Implements(SQLiteDatabase::class)
class FreshReviewQueryDatabase {
    @RealObject private lateinit var database: SQLiteDatabase

    @Implementation
    fun rawQueryWithFactory(
        factory: SQLiteDatabase.CursorFactory?,
        sql: String,
        args: Array<String>?,
        editTable: String?,
        signal: CancellationSignal?,
    ): Cursor {
        val target =
            Thread.currentThread().stackTrace.any {
                when (mode) {
                    "activation" -> it.className == "eu.advantly.device.link.WearDataLayerTransport" && it.methodName == "start"
                    "auth" -> it.className == "eu.advantly.cloud.sync.CloudSessionGate" && it.methodName.startsWith("synchronize")
                    "collector-race" -> Thread.currentThread().name == "collector-storage-revoke"
                    else -> false
                }
            }
        if (target && failure != null && sql.contains("setting", ignoreCase = true)) {
            val cause = requireNotNull(failure)
            failure = null
            hits++
            println("NATIVE_PROBE mode=$mode sql=$sql")
            throw cause
        }
        return Shadow.directlyOn<Cursor, SQLiteDatabase>(
            database,
            SQLiteDatabase::class.java,
            "rawQueryWithFactory",
            ClassParameter.from(SQLiteDatabase.CursorFactory::class.java, factory),
            ClassParameter.from(String::class.java, sql),
            ClassParameter.from(Array<String>::class.java, args),
            ClassParameter.from(String::class.java, editTable),
            ClassParameter.from(CancellationSignal::class.java, signal),
        )
    }

    companion object {
        var mode = ""
        var failure: SQLiteException? = null
        var hits = 0
    }
}
