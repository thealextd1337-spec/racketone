package eu.advantly.android

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.view.View
import com.google.android.gms.wearable.MessageEvent
import eu.advantly.android.sync.PhoneSyncListenerService
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.device.link.WearDataLayerTransport
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.SyncCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Wartet auf die Hintergrundprüfung des Guards und führt die dadurch geposteten Main-Thread-Ergebnisse aus. */
internal fun settleInstallationGuard() {
    val worker = AndroidInstallationGuard.executorForTesting()
    // Ergebnis-Callbacks dürfen weitere Prüfungen anstoßen (Activity nach Application): zweimal abwarten.
    repeat(2) {
        java.util.concurrent
            .FutureTask {}
            .also(worker::execute)
            .get(30, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class InstallationGuardAsyncTest {
    @Before
    fun reset() = InstallationHostFailureTest().reset()

    @Test
    fun concurrentCallersShareOneOffMainCheckAndNothingOpensBeforeIt() {
        val app = RuntimeEnvironment.getApplication()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val selections = AtomicInteger()
        // Jede Dateiauswahl liest genau einmal den no-backup-Stamm: zählen und wie eine große Familie aufhalten.
        val slow =
            object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this

                override fun getNoBackupFilesDir(): File {
                    selections.incrementAndGet()
                    entered.countDown()
                    assertTrue(release.await(30, TimeUnit.SECONDS))
                    return super.getNoBackupFilesDir()
                }
            }
        val results = Collections.synchronizedList(mutableListOf<AndroidInstallationAvailability>())
        // Main-Thread-Aufrufer kehren sofort zurück, während die Prüfung im Hintergrund hängt.
        repeat(2) { AndroidInstallationGuard.checkAsync(slow) { results += it } }
        assertTrue(entered.await(30, TimeUnit.SECONDS))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(
            activity
                .get()
                .window.decorView
                .findViewWithTag<View>("installation_loading"),
        )

        val openedWhilePending = AtomicLong(-1)
        val storage =
            Thread {
                AndroidInstallationGuard.storage(slow) { openedWhilePending.set(release.count) }
            }
        var checked: AndroidInstallationAvailability? = null
        val check = Thread { checked = AndroidInstallationGuard.check(slow) }
        listOf(storage, check).forEach { thread ->
            thread.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (thread.state != Thread.State.BLOCKED) {
                assertTrue(System.nanoTime() < deadline)
                Thread.yield()
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(results.isEmpty())
        assertEquals(-1, openedWhilePending.get())
        assertNull(AndroidMatchSessions.current())

        release.countDown()
        storage.join()
        check.join()
        settleInstallationGuard()
        assertEquals(1, selections.get())
        assertEquals(0, openedWhilePending.get())
        assertTrue(checked is AndroidInstallationAvailability.Ready)
        assertEquals(listOf(checked, checked), results.toList())
        assertNull(
            activity
                .get()
                .window.decorView
                .findViewWithTag<View>("installation_loading"),
        )
        assertNotNull(AndroidMatchSessions.current())
        activity.pause().stop().destroy()
    }

    @Test
    fun foregroundSyncRunsOnceAfterLateCheck() {
        val busy = CountDownLatch(1)
        AndroidInstallationGuard.executorForTesting().execute { busy.await() }
        val calls = AtomicInteger()
        val original = MainActivity.foreground
        MainActivity.foreground = { calls.incrementAndGet() }
        try {
            val activity =
                try {
                    Robolectric.buildActivity(MainActivity::class.java).setup()
                } finally {
                    busy.countDown()
                }
            assertEquals(0, calls.get())
            settleInstallationGuard()
            assertEquals(1, calls.get())
            activity.pause().stop().destroy()
        } finally {
            MainActivity.foreground = original
        }
    }

    @Test
    fun recoveryDoneIsNotRepeatedAfterRecreationDuringCheck() {
        val busy = CountDownLatch(1)
        AndroidInstallationGuard.executorForTesting().execute { busy.await() }
        // Echter Restore einer Instanz, deren Hinweis „Übernahme gelungen“ schon gezeigt wurde; Rotation im Ladezustand.
        val intent = android.content.Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java).putExtra("recovery_done", true)
        val activity =
            try {
                Robolectric
                    .buildActivity(MainActivity::class.java, intent)
                    .create(android.os.Bundle().apply { putBoolean("installation_started", true) })
                    .start()
                    .resume()
                    .recreate()
            } finally {
                busy.countDown()
            }
        settleInstallationGuard()
        assertNull(
            org.robolectric.shadows.ShadowAlertDialog
                .getLatestAlertDialog(),
        )
        activity.pause().stop().destroy()
    }

    @Test
    @Config(application = AdvantlyPhoneApplication::class)
    fun coldListenerWaitsForAsyncBootstrapAndDeliversFrame() {
        val app = RuntimeEnvironment.getApplication() as AdvantlyPhoneApplication
        // Kaltstart nachstellen: Die Session aus dem App-onCreate ist geschlossen, beide Bootstrap-Merker zurück.
        AdvantlyPhoneApplication::class.java
            .getDeclaredField("bootstrapped")
            .apply { isAccessible = true }
            .setBoolean(app, false)
        (
            AndroidInstallationGuard::class.java
                .getDeclaredField("bootstrapped")
                .apply { isAccessible = true }
                .get(AndroidInstallationGuard) as MutableSet<*>
        ).clear()
        val busy = CountDownLatch(1)
        AndroidInstallationGuard.executorForTesting().execute { busy.await() }
        val service = Robolectric.buildService(PhoneSyncListenerService::class.java).create()
        var failure: Throwable? = null
        val listener =
            Thread {
                try {
                    service.get().onMessageReceived(hello())
                } catch (error: Throwable) {
                    failure = error
                }
            }
        try {
            listener.start()
            awaitIn(listener, "awaitBootstrap")
            assertNull(AndroidMatchSessions.current())
        } finally {
            busy.countDown()
        }
        val until = System.nanoTime() + 30_000_000_000
        while (listener.isAlive) {
            assertTrue(System.nanoTime() < until)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        assertNull(failure)
        assertTrue(
            AndroidMatchSessions
                .current()!!
                .state.value.devices
                .any { it.peer == "counterpart-node" },
        )
        service.destroy()
    }

    @After
    fun finish() {
        settleInstallationGuard()
        if (AndroidMatchSessions.current() != null) {
            AndroidInstallationGuard.storageFailed(RuntimeEnvironment.getApplication(), java.io.IOException("test teardown"))
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}

private fun hello(): MessageEvent =
    object : MessageEvent {
        override fun getRequestId() = 1

        override fun getPath() = WearDataLayerTransport.MESSAGE_PATH

        override fun getData() =
            byteArrayOf(0xad.toByte(), 1, 1, 0, 1, 109) +
                SyncCodec.encode(
                    Hello(
                        eu.advantly.core.model
                            .DeviceId("counterpart"),
                        PeerKind.WATCH,
                    ),
                )

        override fun getSourceNodeId() = "counterpart-node"
    }

/** Wartet, bis [thread] in [method] an einem CountDownLatch steht. */
private fun awaitIn(
    thread: Thread,
    method: String,
) {
    val until = System.nanoTime() + 10_000_000_000
    while (true) {
        val stack = thread.stackTrace
        if (stack.any { it.methodName == method } && stack.any { it.className == CountDownLatch::class.java.name }) return
        check(thread.isAlive) { "Thread returned before $method" }
        check(System.nanoTime() < until) { "Thread did not wait in $method" }
        Thread.sleep(2)
    }
}
