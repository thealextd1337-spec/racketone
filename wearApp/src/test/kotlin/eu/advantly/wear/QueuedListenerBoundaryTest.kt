package eu.advantly.wear

import android.app.Application
import android.database.sqlite.SQLiteDiskIOException
import android.os.Looper
import com.google.android.gms.wearable.MessageEvent
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.device.link.WearDataLayerInbound
import eu.advantly.device.link.WearDataLayerTransport
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.SyncCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [eu.advantly.wear.sync.UnsupportedDirectorySyncLinux::class])
class QueuedListenerBoundaryTest {
    @Before fun reset() = InstallationHostFailureTest().reset()

    @org.junit.After
    fun finishNativeRuntime() {
        if (AndroidMatchSessions.current() != null) {
            AndroidInstallationGuard.storageFailed(RuntimeEnvironment.getApplication(), java.io.IOException("test teardown"))
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun message(): MessageEvent =
        object : MessageEvent {
            override fun getRequestId() = 1

            override fun getPath() = WearDataLayerTransport.MESSAGE_PATH

            override fun getData() =
                byteArrayOf(0xad.toByte(), 1, 1, 0, 1, 109) +
                    SyncCodec.encode(
                        Hello(
                            eu.advantly.core.model
                                .DeviceId("counterpart"),
                            PeerKind.PHONE,
                        ),
                    )

            override fun getSourceNodeId() = "counterpart-node"
        }

    private fun awaitHandoff(worker: Thread) {
        val until = System.nanoTime() + 5_000_000_000
        while (worker.isAlive) {
            val waiting = worker.stackTrace.any { it.className == "java.util.concurrent.CountDownLatch" && it.methodName == "await" }
            if (waiting &&
                worker.stackTrace.any {
                    it.className == "eu.advantly.wear.sync.SyncListenerService" && it.methodName == "runOnMain"
                }
            ) {
                return
            }
            check(System.nanoTime() < until) { "Worker did not queue real Main handoff" }
            Thread.sleep(2)
        }
        error("Worker returned before Main handoff")
    }

    private fun handoff(revoke: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        val session = AndroidMatchSessions.get(app, PeerKind.WATCH)
        eu.advantly.wear.sync.WearSessions
            .get(app)
        eu.advantly.wear.sync.WearSessions
            .consumeColdStart()
        shadowOf(Looper.getMainLooper()).idle()
        val marker = File(app.noBackupFilesDir, "installation-v1").readBytes().toList()
        val service = Robolectric.buildService(eu.advantly.wear.sync.SyncListenerService::class.java).create()
        var workerFailure: Exception? = null
        val worker =
            Thread {
                try {
                    service.get().onMessageReceived(message())
                } catch (error: Exception) {
                    workerFailure = error
                }
            }
        assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
        worker.start()
        awaitHandoff(worker)
        if (revoke) {
            val failure = Thread { AndroidInstallationGuard.storageFailed(app, SQLiteDiskIOException("revoked between worker and main")) }
            failure.start()
            failure.join()
        }
        val escaped = runCatching { shadowOf(Looper.getMainLooper()).idle() }.exceptionOrNull()
        // Auch bei rotem Main-Callback den echten Worker sauber aus seinem zweiten Barrieredurchlauf loesen.
        val until = System.nanoTime() + 5_000_000_000
        while (worker.isAlive && System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        worker.join(1_000)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(worker.isAlive)
        assertNull(workerFailure)
        assertNull(escaped)
        assertEquals(marker, File(app.noBackupFilesDir, "installation-v1").readBytes().toList())
        if (revoke) {
            assertNull(AndroidMatchSessions.current())
            assertFalse(WearDataLayerInbound.isAttached)
            assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
        } else {
            assertTrue(
                session.state.value.devices
                    .any { it.peer == "counterpart-node" },
            )
            assertTrue(AndroidMatchSessions.current() === session)
        }
        service.destroy()
    }

    @Test fun queuedMessageRejectsRevokeBeforeMain() = handoff(revoke = true)

    @Test fun healthyQueuedMessageStillProcesses() = handoff(revoke = false)

    @Test
    fun coldListenerWithStorageFailureStaysInert() {
        val app = RuntimeEnvironment.getApplication()
        AndroidInstallationGuard.storageFailed(app, SQLiteDiskIOException("cold storage unavailable"))
        val service = Robolectric.buildService(eu.advantly.wear.sync.SyncListenerService::class.java).create()
        service.get().onMessageReceived(message())
        assertNull(AndroidMatchSessions.current())
        assertFalse(WearDataLayerInbound.isAttached)
        assertFalse(File(app.noBackupFilesDir, "installation-v1").exists())
        service.destroy()
    }
}
