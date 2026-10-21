package eu.advantly.android

import android.app.Application
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import android.os.Looper
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Node
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.device.link.WearDataLayerInbound
import eu.advantly.match.session.AndroidMatchSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = Application::class,
    shadows = [PollQueryDatabase::class, PollCapabilityClient::class, UnsupportedDirectorySyncLinux::class],
)
class ScopedPollFailureTest {
    @Before
    fun reset() {
        PollQueryDatabase.failure = null
        PollQueryDatabase.onQuery = null
        PollQueryDatabase.hits = 0
        PollCapabilityClient.calls = 0
        InstallationHostFailureTest().reset()
    }

    private fun firstFailure(error: Exception) {
        val app = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val installation = AndroidInstallationGuard.get(app)
        val source = requireNotNull(AndroidMatchSessions.current()).deviceId
        val marker = File(app.noBackupFilesDir, "installation-v1").readBytes().toList()
        assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
        assertEquals(0, PollQueryDatabase.hits)
        PollQueryDatabase.failure = error
        val escaped = runCatching { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5)) }.exceptionOrNull()
        PollQueryDatabase.failure = null
        assertEquals(1, PollQueryDatabase.hits)
        assertNull(escaped)
        assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
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
        val retried = AndroidMatchSessions.get(app, eu.advantly.sync.protocol.PeerKind.PHONE)
        assertEquals(source, retried.deviceId)
        activity.pause().stop().destroy()
    }

    @Test fun firstPollDiskIoDoesNotEscapeHandler() = firstFailure(SQLiteDiskIOException("first native poll query failed"))

    @Test fun firstPollCorruptionDoesNotEscapeHandler() = firstFailure(SQLiteDatabaseCorruptException("first native poll query corrupt"))

    @Test
    fun pollProgrammingErrorPropagates() {
        val app = RuntimeEnvironment.getApplication()
        AndroidMatchSessions.get(app, eu.advantly.sync.protocol.PeerKind.PHONE)
        shadowOf(Looper.getMainLooper()).idle()
        val error = SQLiteException("no such column: programming_error")
        PollQueryDatabase.failure = error
        try {
            assertSame(error, assertThrows(SQLiteException::class.java) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5)) })
            assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
            assertEquals(1, PollQueryDatabase.hits)
        } finally {
            PollQueryDatabase.failure = null
        }
    }

    @Test
    fun reentrantRevokeCannotAuthorizeCapabilityRead() {
        val app = RuntimeEnvironment.getApplication()
        AndroidMatchSessions.get(app, eu.advantly.sync.protocol.PeerKind.PHONE)
        shadowOf(Looper.getMainLooper()).idle()
        val calls = PollCapabilityClient.calls
        PollQueryDatabase.onQuery = {
            PollQueryDatabase.onQuery = null
            AndroidInstallationGuard.storageFailed(app, SQLiteDiskIOException("reentrant native callback"))
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(1, PollQueryDatabase.hits)
        assertEquals(calls, PollCapabilityClient.calls)
        assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
    }

    @Test
    fun healthyPollStillRefreshesPeers() {
        val app = RuntimeEnvironment.getApplication()
        AndroidMatchSessions.get(app, eu.advantly.sync.protocol.PeerKind.PHONE)
        shadowOf(Looper.getMainLooper()).idle()
        val calls = PollCapabilityClient.calls
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(PollCapabilityClient.calls > calls)
        assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
    }
}

/** Nur die erste echte SQLite-Abfrage im produktiven Handler-Peerpoll wird gestoert. Kein Vorabwiderruf. */
@Implements(SQLiteDatabase::class)
class PollQueryDatabase {
    @RealObject private lateinit var database: SQLiteDatabase

    @Implementation
    fun rawQueryWithFactory(
        factory: SQLiteDatabase.CursorFactory?,
        sql: String,
        args: Array<String>?,
        editTable: String?,
        signal: CancellationSignal?,
    ): Cursor {
        val poll =
            Thread.currentThread().stackTrace.any {
                it.className == "eu.advantly.device.link.WearDataLayerTransport" && it.methodName == "refreshPeers"
            }
        if (poll && (failure != null || onQuery != null) && sql.contains("setting", ignoreCase = true)) {
            hits++
            onQuery?.invoke()
            failure?.let { throw it }
        }
        return Shadow.directlyOn<Cursor, SQLiteDatabase>(
            database,
            SQLiteDatabase::class.java,
            "rawQueryWithFactory",
            org.robolectric.util.ReflectionHelpers.ClassParameter
                .from(SQLiteDatabase.CursorFactory::class.java, factory),
            org.robolectric.util.ReflectionHelpers.ClassParameter
                .from(String::class.java, sql),
            org.robolectric.util.ReflectionHelpers.ClassParameter
                .from(Array<String>::class.java, args),
            org.robolectric.util.ReflectionHelpers.ClassParameter
                .from(String::class.java, editTable),
            org.robolectric.util.ReflectionHelpers.ClassParameter
                .from(CancellationSignal::class.java, signal),
        )
    }

    companion object {
        var failure: Exception? = null
        var onQuery: (() -> Unit)? = null
        var hits = 0
    }
}

/** Kontrollierte Capability-Antwort statt realer GMS; zaehlt den Seiteneffekt nach Allowed. */
@Implements(className = "com.google.android.gms.wearable.internal.zzan", isInAndroidSdk = false)
class PollCapabilityClient {
    @Implementation
    fun getCapability(
        name: String,
        filter: Int,
    ): Task<CapabilityInfo> {
        calls++
        return Tasks.forResult(
            object : CapabilityInfo {
                override fun getName() = name

                override fun getNodes(): Set<Node> = emptySet()
            },
        )
    }

    companion object {
        var calls = 0
    }
}
