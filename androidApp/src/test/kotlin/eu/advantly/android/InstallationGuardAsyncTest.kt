package eu.advantly.android

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.view.View
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.match.session.AndroidMatchSessions
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Wartet auf die Hintergrundprüfung des Guards und führt die dadurch geposteten Main-Thread-Ergebnisse aus. */
internal fun settleInstallationGuard() {
    val worker =
        AndroidInstallationGuard::class.java
            .getDeclaredField("worker")
            .apply { isAccessible = true }
            .get(null) as ExecutorService
    // Ergebnis-Callbacks dürfen weitere Prüfungen anstoßen (Activity nach Application): zweimal abwarten.
    repeat(2) {
        worker.submit {}.get(30, TimeUnit.SECONDS)
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
}
