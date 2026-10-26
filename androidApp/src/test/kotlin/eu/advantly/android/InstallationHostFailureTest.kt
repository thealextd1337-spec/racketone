package eu.advantly.android

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.widget.Button
import com.google.android.gms.wearable.MessageEvent
import eu.advantly.android.auth.createPhoneAccount
import eu.advantly.android.sync.PhoneSyncListenerService
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.device.link.WearDataLayerInbound
import eu.advantly.device.link.WearDataLayerTransport
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.sync.protocol.PeerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.DirectoryIteratorException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class InstallationHostFailureTest {
    @Test
    fun runningPhoneActivityObservesRuntimeRevocation() {
        val app = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        settleInstallationGuard()
        val accountLifetime = androidx.lifecycle.ViewModelProvider(activity.get())[RuntimeViewModel::class.java]
        assertNotNull(AndroidMatchSessions.current())
        AndroidInstallationGuard.storageFailed(app, SQLiteDiskIOException("runtime revoked"))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull(
            activity
                .get()
                .window.decorView
                .findViewWithTag<android.view.View>("installation_error"),
        )
        assertTrue(accountLifetime.cleared)
        activity.pause().stop().destroy()
    }

    @Test
    @Config(shadows = [CommitFailureDatabase::class, UnsupportedDirectorySyncLinux::class])
    fun directLocalDataNativeCommitFailureRevokesTransportOutsideSessionWork() {
        val app = RuntimeEnvironment.getApplication()
        val session = AndroidMatchSessions.get(app, PeerKind.PHONE)
        assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
        CommitFailureDatabase.failures = 0
        CommitFailureDatabase.fail = true
        try {
            assertThrows(SQLiteDiskIOException::class.java) {
                session.data.transaction { session.data.settings.put("commit-only", "value") }
            }
        } finally {
            CommitFailureDatabase.fail = false
        }
        assertEquals(1, CommitFailureDatabase.failures)
        assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
        assertNull(AndroidMatchSessions.current())
        assertFalse(WearDataLayerInbound.isAttached)
    }

    @Test
    @Config(shadows = [CommitFailureDatabase::class, UnsupportedDirectorySyncLinux::class])
    fun nativeRollbackFailureAlsoRevokesAndRetryKeepsIdentity() {
        val app = RuntimeEnvironment.getApplication()
        val session = AndroidMatchSessions.get(app, PeerKind.PHONE)
        val first = AndroidInstallationGuard.get(app)
        val source = session.deviceId
        val marker = File(app.noBackupFilesDir, "installation-v1").readBytes().toList()
        val body = IllegalArgumentException("body failed before rollback")
        CommitFailureDatabase.failures = 0
        CommitFailureDatabase.fail = true
        try {
            val native =
                assertThrows(SQLiteDiskIOException::class.java) {
                    session.data.transaction {
                        session.data.settings.put("rollback-only", "never committed")
                        throw body
                    }
                }
            // SQLDelight 2.4.1 maskiert den Bodyfehler, wenn sein nativer Rollback zusätzlich wirft.
            assertEquals("native COMMIT failed", native.message)
        } finally {
            CommitFailureDatabase.fail = false
        }
        assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
        assertNull(AndroidMatchSessions.current())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(marker, File(app.noBackupFilesDir, "installation-v1").readBytes().toList())
        assertEquals(first, (AndroidInstallationGuard.retry(app) as AndroidInstallationAvailability.Ready).installation)
        val retried = AndroidMatchSessions.get(app, PeerKind.PHONE)
        assertEquals(source, retried.deviceId)
        assertNull(retried.data.settings.get("rollback-only"))
    }

    @Test
    fun warmSessionAccessRevokesBeforeMainUiCallbackAndNeverRebindsAfterRetry() {
        val app = RuntimeEnvironment.getApplication()
        val first = AndroidMatchSessions.get(app, PeerKind.PHONE)
        val installation = AndroidInstallationGuard.get(app)
        var revoked = 0
        val access =
            eu.advantly.match.session
                .AndroidSessionAccess(app, first) { revoked++ }
        assertTrue(access.privateTransportAllowed)
        val thread = Thread { AndroidInstallationGuard.storageFailed(app, SQLiteDiskIOException("background failure")) }
        thread.start()
        thread.join()
        assertEquals(1, revoked)
        // Keine Mainlooper-Ausführung nötig, um einen weiteren Keepalive bereits zu verhindern.
        assertFalse(access.ready)
        assertFalse(access.privateTransportAllowed)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(installation, (AndroidInstallationGuard.retry(app) as AndroidInstallationAvailability.Ready).installation)
        val second = AndroidMatchSessions.get(app, PeerKind.PHONE)
        assertEquals(first.deviceId, second.deviceId)
        assertFalse(access.ready)
        eu.advantly.match.session
            .AndroidSessionAccess(app, second)
            .use { assertTrue(it.privateTransportAllowed) }
        access.close()
    }

    @Test
    @Config(shadows = [CommitFailureDatabase::class, UnsupportedDirectorySyncLinux::class])
    fun nativePrimarySurvivesAdditionalAvailableCleanupFailure() {
        val app = RuntimeEnvironment.getApplication()
        val session = AndroidMatchSessions.get(app, PeerKind.PHONE)
        AndroidMatchSessions.prepare(app, PeerKind.PHONE) { _, stage ->
            stage.onDiscard { throw IOException("available cleanup failed") }
        }
        CommitFailureDatabase.failures = 0
        CommitFailureDatabase.fail = true
        val primary =
            try {
                assertThrows(SQLiteDiskIOException::class.java) {
                    session.data.transaction { session.data.settings.put("commit-with-close-failure", "committed") }
                }
            } finally {
                CommitFailureDatabase.fail = false
            }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(primary.suppressed.any { it.message == "available cleanup failed" })
        assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
        val caught = assertThrows(SQLiteDiskIOException::class.java) { AndroidInstallationGuard.requireReady(app) }
        org.junit.Assert.assertSame(primary, caught)
    }

    @Test
    fun programmingBodyFailureWithSuccessfulRollbackPropagatesWithoutStorageLatch() {
        val app = RuntimeEnvironment.getApplication()
        val session = AndroidMatchSessions.get(app, PeerKind.PHONE)
        val original = IllegalStateException("programming body failure")
        val caught =
            assertThrows(IllegalStateException::class.java) {
                session.data.transaction {
                    session.data.settings.put("programming-failure", "must roll back")
                    throw original
                }
            }
        org.junit.Assert.assertSame(original, caught)
        assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
        assertNull(session.data.settings.get("programming-failure"))
    }

    @Test
    fun openHelperCloseFailureRevokesRuntimeWithoutOpeningOrWritingDatabase() {
        val app = RuntimeEnvironment.getApplication()
        AndroidMatchSessions.get(app, PeerKind.PHONE)
        val native = SQLiteDiskIOException("native close failed")
        val helperType = Class.forName("androidx.sqlite.db.SupportSQLiteOpenHelper")
        val factoryType = Class.forName("androidx.sqlite.db.SupportSQLiteOpenHelper\$Factory")
        val helper =
            java.lang.reflect.Proxy.newProxyInstance(helperType.classLoader, arrayOf(helperType)) { _, method, _ ->
                when (method.name) {
                    "close" -> throw native
                    "getDatabaseName" -> "controlled-close-port"
                    else -> error("must not open or mutate: ${method.name}")
                }
            }
        val port =
            java.lang.reflect.Proxy.newProxyInstance(
                factoryType.classLoader,
                arrayOf(factoryType),
            ) { _, _, _ -> error("must not open") }
        // Nur die Testnaht verwendet das Kotlin-interne Modul, ohne weiteren Compileexport der Android-App.
        val type = Class.forName("eu.advantly.data.AndroidStorageOpenHelperFactory")
        val factory = type.getDeclaredConstructor(Context::class.java, factoryType).newInstance(app, port)
        val guarded = type.getDeclaredMethod("protect", helperType).invoke(factory, helper)
        val caught = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { helperType.getMethod("close").invoke(guarded) }
        org.junit.Assert.assertSame(native, caught.cause)
        assertNull(AndroidMatchSessions.current())
        assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
        assertFalse(WearDataLayerInbound.isAttached)
    }

    @Before
    fun reset() = resetProcess()

    private fun resetProcess() {
        settleInstallationGuard()
        AndroidMatchSessions.current()?.close()
        AndroidMatchSessions::class.java
            .getDeclaredField("instance")
            .apply { isAccessible = true }
            .set(AndroidMatchSessions, null)

        clearGuard()
    }

    private fun clearGuard() {
        AndroidInstallationGuard::class.java.declaredFields.first { it.name == "states" }.apply {
            isAccessible = true
            (get(AndroidInstallationGuard) as MutableMap<*, *>).clear()
        }
        AndroidInstallationGuard::class.java.declaredFields.firstOrNull { it.name == "unavailable" }?.apply {
            isAccessible = true
            (get(AndroidInstallationGuard) as MutableSet<*>).clear()
        }
    }

    @Test
    fun postReadyBootstrapIoDoesNotEscapeApplication() {
        val app = RuntimeEnvironment.getApplication()
        seedLegacy(app)
        val first = AndroidInstallationGuard.get(app)
        val before = protectedBytes(app)
        var fail = true
        val broken =
            object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this

                override fun getDatabasePath(name: String): File {
                    if (fail && name == first.activeDatabaseName) throw SQLiteDiskIOException("post-Ready productive open failed")
                    return super.getDatabasePath(name)
                }
            }
        val product = AdvantlyPhoneApplication()
        shadowOf(product).callAttach(broken)
        product.onCreate()
        assertNull(AndroidMatchSessions.current())
        assertEquals(AndroidInstallationAvailability.Unavailable, AndroidInstallationGuard.check(app))
        assertThrows(SQLiteDiskIOException::class.java) { AndroidInstallationGuard.get(app) }
        assertFalse(WearDataLayerInbound.isAttached)
        assertEquals(before, protectedBytes(app))
        repeat(2) {
            assertTrue(AndroidInstallationGuard.retry(broken) is AndroidInstallationAvailability.Ready)
            assertFalse(product.bootstrap())
            assertNull(AndroidMatchSessions.current())
            assertEquals(before, protectedBytes(app))
        }
        fail = false
        assertEquals(first, (AndroidInstallationGuard.retry(broken) as AndroidInstallationAvailability.Ready).installation)
        assertTrue(product.bootstrap())
        assertEquals(first, AndroidInstallationGuard.get(app))
        assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
    }

    @Test
    fun sqlProgrammingErrorMustPropagate() {
        val app = RuntimeEnvironment.getApplication()
        val broken =
            object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this

                override fun getNoBackupFilesDir(): File = throw SQLiteException("no such column: programming_error")
            }
        assertThrows(SQLiteException::class.java) { AndroidInstallationGuard.check(broken) }
    }

    @Test
    fun prepareAndHostPublishFailureCannotLeaveSingleton() {
        val app = RuntimeEnvironment.getApplication()
        seedLegacy(app)
        val first = AndroidInstallationGuard.get(app)
        val source = AndroidMatchSessions.get(app, PeerKind.PHONE).deviceId
        for (afterCommit in listOf(false, true)) {
            val before = protectedBytes(app)
            var discarded = 0
            val error = SQLiteDiskIOException("host bootstrap failed")
            val result =
                AndroidInstallationGuard.storage(app) {
                    AndroidMatchSessions.prepare(app, PeerKind.PHONE) { session, stage ->
                        assertNull(AndroidMatchSessions.current())
                        assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
                        stage.onDiscard { discarded++ }
                        if (afterCommit) {
                            stage.afterCommit { throw error }
                        } else {
                            session.data.settings.put("must.rollback", "value")
                            throw error
                        }
                    }
                }
            assertNull(result)
            assertEquals(1, discarded)
            assertEquals(before, protectedBytes(app))
            assertNull(AndroidMatchSessions.current())
            assertFalse(WearDataLayerInbound.isAttached)
            assertEquals(AndroidInstallationAvailability.Unavailable, AndroidInstallationGuard.check(app))
            assertEquals(first, (AndroidInstallationGuard.retry(app) as AndroidInstallationAvailability.Ready).installation)
            val recovered = AndroidMatchSessions.get(app, PeerKind.PHONE)
            assertEquals(source, recovered.deviceId)
            assertNull(recovered.data.settings.get("must.rollback"))
            assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
        }
    }

    @Test
    fun expectedWrappersAreStorageFailuresAndSqlErrorsAreNot() {
        val app = RuntimeEnvironment.getApplication()
        val first = AndroidInstallationGuard.get(app)
        val bytes = protectedBytes(app)
        val failures =
            listOf(
                DirectoryIteratorException(IOException("readdir")),
                UncheckedIOException(IOException("readdir")),
                SQLiteDiskIOException("disk"),
                android.database.sqlite.SQLiteFullException("full"),
                android.database.sqlite.SQLiteDatabaseLockedException("locked"),
                android.database.sqlite.SQLiteCantOpenDatabaseException("open"),
            )
        for (failure in failures) {
            assertNull(AndroidInstallationGuard.storage(app) { throw failure })
            assertThrows(failure.javaClass) { AndroidInstallationGuard.requireReady(app) }
            assertEquals(bytes, protectedBytes(app))
            assertEquals(first, (AndroidInstallationGuard.retry(app) as AndroidInstallationAvailability.Ready).installation)
        }
        for (failure in listOf(SQLiteException("no such column"), android.database.sqlite.SQLiteConstraintException("constraint"))) {
            assertThrows(failure.javaClass) { AndroidInstallationGuard.storage(app) { throw failure } }
            assertTrue(AndroidInstallationGuard.check(app) is AndroidInstallationAvailability.Ready)
        }
    }

    @Test
    fun emptyInstallFailureDoesNotInventInstallation() {
        val app = RuntimeEnvironment.getApplication()
        File(app.noBackupFilesDir, "installation-check-123").writeText("blocked cleanup")
        assertEquals(AndroidInstallationAvailability.Unavailable, AndroidInstallationGuard.check(app))
        repeat(2) { assertEquals(AndroidInstallationAvailability.Unavailable, AndroidInstallationGuard.retry(app)) }
        assertTrue(
            app
                .getDatabasePath("advantly.db")
                .parentFile!!
                .listFiles()!!
                .isEmpty(),
        )
        assertFalse(File(app.noBackupFilesDir, "installation-v1").exists())
        assertFalse(File(app.noBackupFilesDir, "installation-v1.reservation").exists())
        assertNull(AndroidMatchSessions.current())
        assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
    }

    @Test
    fun programmingErrorsAreNotClassifiedAsStorageFailure() {
        val app = RuntimeEnvironment.getApplication()
        val broken =
            object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this

                override fun getNoBackupFilesDir(): File = throw IllegalStateException("programming failure")
            }
        assertThrows(IllegalStateException::class.java) { AndroidInstallationGuard.check(broken) }
        assertThrows(IllegalStateException::class.java) { AndroidInstallationGuard.retry(broken) }
        assertNull(AndroidMatchSessions.current())
    }

    @Test
    fun coldApplicationAndListenerStayInertOnConcreteCleanupIoFailure() {
        val app = RuntimeEnvironment.getApplication()
        seedLegacy(app)
        AndroidInstallationGuard.get(app)
        clearGuard()
        val before = protectedBytes(app)
        File(app.noBackupFilesDir, "installation-check-123").writeText("cleanup cannot treat a file as a directory")
        val product = AdvantlyPhoneApplication()
        shadowOf(product).callAttach(app)
        product.onCreate()
        settleInstallationGuard()
        assertInert()

        assertEquals(before, protectedBytes(app))
    }

    @Test
    fun retryKeepsDataAndGeneration() {
        val app = RuntimeEnvironment.getApplication()
        seedLegacy(app)
        val first = AndroidInstallationGuard.get(app)
        val source = AndroidMatchSessions.get(app, PeerKind.PHONE).deviceId
        resetProcess()
        val before = protectedBytes(app)
        val blocker = File(app.noBackupFilesDir, "installation-check-123").apply { writeText("blocked cleanup") }
        val activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
        settleInstallationGuard()
        val activity = activityController.get()
        assertNotNull(activity.window.decorView.findViewWithTag<android.view.View>("installation_error"))
        val retry = activity.window.decorView.findViewWithTag<Button>("installation_retry")
        assertEquals(activity.getString(R.string.installation_error_retry), retry.text.toString())
        repeat(2) {
            retry.performClick()
            settleInstallationGuard()
            assertTrue(retry.isEnabled)
            assertInert()
            assertEquals(before, protectedBytes(app))
        }
        assertNull(activity.createPhoneAccount("https://must-not-open.invalid", "key"))
        assertNull(activity.createPhoneAccount("", ""))
        assertTrue(blocker.delete())
        // Ein Dienst darf auch nach Wegfall des Fehlers keine implizite Wiederholung/Entsperrung auslösen.
        assertInert()
        assertEquals(before, protectedBytes(app))
        retry.performClick()
        settleInstallationGuard()
        assertEquals(first, AndroidInstallationGuard.get(app))
        assertEquals(before, protectedBytes(app))
        assertFalse(first.privateTransportAllowed)
        val product = AdvantlyPhoneApplication()
        shadowOf(product).callAttach(app)
        product.onCreate()
        val session = AndroidMatchSessions.current()!!
        assertEquals(source, session.deviceId)
        assertFalse(AndroidInstallationGuard.get(app).privateTransportAllowed)
        assertFalse(WearDataLayerInbound.isAttached)
        assertNull(session.linkStats)
        val self =
            session.data.players
                .self()!!
                .id
        session.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(self), listOf(PlayerId("fresh-opponent")), self))
        assertTrue(session.recordPoint(Side.A))
        assertEquals(legacyBytes, legacyBytes(app))
        activityController.pause().stop().destroy()
    }

    private fun assertInert() {
        Robolectric.buildService(PhoneSyncListenerService::class.java).create().get().onMessageReceived(
            object : MessageEvent {
                override fun getRequestId() = 1

                override fun getPath() = WearDataLayerTransport.MESSAGE_PATH

                override fun getData() = byteArrayOf(1, 2, 3)

                override fun getSourceNodeId() = "old-private-peer"
            },
        )
        assertNull(AndroidMatchSessions.current())
        assertFalse(WearDataLayerInbound.isAttached)
        var deleted = 0
        assertFalse(
            WearDataLayerInbound.deliverQueuedItem(
                "fresh",
                "old",
                listOf("advantly", "link", "q", "fresh", "private"),
                byteArrayOf(1),
            ) {
                deleted++
            },
        )
        assertEquals(0, deleted)
    }

    private val legacyBytes =
        mapOf(
            "" to "retained database",
            "-wal" to "retained pending WAL",
            "-shm" to "retained SHM",
            "-journal" to "retained journal",
        )

    private fun seedLegacy(app: Context) {
        legacyBytes.forEach { (suffix, bytes) ->
            app.getDatabasePath("advantly.db$suffix").apply {
                parentFile!!.mkdirs()
                writeText(bytes)
            }
        }
    }

    private fun legacyBytes(app: Context) = legacyBytes.mapValues { (suffix, _) -> app.getDatabasePath("advantly.db$suffix").readText() }

    private fun protectedBytes(app: Context): Map<String, List<Byte>> {
        val databases =
            app
                .getDatabasePath("advantly.db")
                .parentFile!!
                .listFiles()!!
                .filter { it.isFile }
        val metadata =
            app.noBackupFilesDir.listFiles()!!.filter {
                it.name.startsWith("installation-v1") &&
                    it.name != "installation-v1.lock"
            }
        return (databases + metadata).associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
    }
}

/** Nur der native Transaktionsabschluss scheitert; BEGIN und alle Statements sind erfolgreich. */
@org.robolectric.annotation.Implements(android.database.sqlite.SQLiteDatabase::class)
class CommitFailureDatabase {
    @org.robolectric.annotation.RealObject
    private lateinit var database: android.database.sqlite.SQLiteDatabase

    @org.robolectric.annotation.Implementation
    fun endTransaction() {
        org.robolectric.shadow.api.Shadow.directlyOn<Any, android.database.sqlite.SQLiteDatabase>(
            database,
            android.database.sqlite.SQLiteDatabase::class.java,
            "endTransaction",
        )
        if (fail) {
            failures++
            throw SQLiteDiskIOException("native COMMIT failed")
        }
    }

    companion object {
        var fail = false
        var failures = 0
    }
}

class RuntimeViewModel : androidx.lifecycle.ViewModel() {
    var cleared = false

    override fun onCleared() {
        cleared = true
    }
}
