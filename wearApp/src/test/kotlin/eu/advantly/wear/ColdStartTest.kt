package eu.advantly.wear

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import com.google.android.gms.wearable.MessageEvent
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.device.link.WearDataLayerTransport
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.SyncCodec
import eu.advantly.wear.glance.GlanceAction
import eu.advantly.wear.glance.GlanceLaunch
import eu.advantly.wear.sync.SyncListenerService
import eu.advantly.wear.sync.UiVisibility
import eu.advantly.wear.sync.UnsupportedDirectorySyncLinux
import org.junit.After
import org.junit.Assert.assertFalse
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch

/** Kaltstart mit noch laufender Installationsprüfung: nachgeholte Schritte statt still verlorener Arbeit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class ColdStartTest {
    private val busy = CountDownLatch(1)

    @Before
    fun reset() {
        InstallationHostFailureTest().reset()
        UiVisibility.resumed = false
        // Der Hintergrundthread hängt wie bei einer großen Familie, bis der Test ihn freigibt.
        AndroidInstallationGuard.executorForTesting().execute { busy.await() }
    }

    @After
    fun finish() {
        busy.countDown()
        settleInstallationGuard()
        if (AndroidMatchSessions.current() != null) {
            AndroidInstallationGuard.storageFailed(RuntimeEnvironment.getApplication(), java.io.IOException("test teardown"))
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun activity(intent: Intent? = null): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java, intent).also { activity ->
            // Derselbe reine Robolectric-Ambientstub wie in den bestehenden Hosttests.
            MainActivity::class.java.getDeclaredField("ambientObserver\$delegate").apply {
                isAccessible = true
                set(
                    activity.get(),
                    lazy {
                        object : androidx.wear.ambient.AmbientLifecycleObserver {
                            override val isAmbient = false
                        }
                    },
                )
            }
        }

    @Test
    fun activityCatchesUpStartAndResumeAfterLateCheck() {
        val activity = activity().setup()
        assertNotNull(
            activity
                .get()
                .window.decorView
                .findViewWithTag<View>("installation_loading"),
        )
        assertFalse(UiVisibility.resumed)
        busy.countDown()
        settleInstallationGuard()
        assertTrue(UiVisibility.resumed)
        assertTrue(
            MainActivity::class.java
                .getDeclaredField("powerReceiverRegistered")
                .apply { isAccessible = true }
                .getBoolean(activity.get()),
        )
        activity.pause().stop().destroy()
    }

    @Test
    fun tileIntentDuringCheckAfterProcessDeathIsConsumed() {
        val app = RuntimeEnvironment.getApplication()
        // Gespeicherter Zustand einer früher gestarteten Instanz (singleTask, Prozess inzwischen beendet).
        val activity = activity().create(Bundle().apply { putBoolean("installation_started", true) }).start()
        activity.newIntent(Intent(app, MainActivity::class.java).putExtra(GlanceLaunch.EXTRA_ACTION, GlanceAction.NEW.name))
        activity.resume()
        assertNotNull(activity.get().intent.getStringExtra(GlanceLaunch.EXTRA_ACTION))
        busy.countDown()
        settleInstallationGuard()
        assertNull(activity.get().intent.getStringExtra(GlanceLaunch.EXTRA_ACTION))
        activity.pause().stop().destroy()
    }

    private fun tile(): Intent =
        Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)
            .putExtra(GlanceLaunch.EXTRA_ACTION, GlanceAction.NEW.name)

    /** Neuerstellung im Ladezustand: Zustand der ersten Instanz sichern und an eine neue Instanz geben. */
    private fun recreateWhilePending(
        first: ActivityController<MainActivity>,
        intent: Intent,
    ): ActivityController<MainActivity> {
        val saved = Bundle()
        first.saveInstanceState(saved)
        first.pause().stop().destroy()
        return activity(intent).create(saved).start().resume()
    }

    @Test
    fun restoredGlanceStaysConsumedAcrossRecreationDuringCheck() {
        // Echter Restore (alte Task mit verbrauchter Tile-Aktion), dann Konfigurationswechsel während der Prüfung.
        val first = activity(tile()).create(Bundle().apply { putBoolean("installation_started", true) }).start().resume()
        val second = recreateWhilePending(first, tile())
        busy.countDown()
        settleInstallationGuard()
        assertNotNull(second.get().intent.getStringExtra(GlanceLaunch.EXTRA_ACTION))
        second.pause().stop().destroy()
    }

    @Test
    fun tileIntentDuringCheckSurvivesRecreation() {
        val first = activity().create(Bundle().apply { putBoolean("installation_started", true) }).start()
        first.newIntent(tile())
        val second = recreateWhilePending(first, tile())
        busy.countDown()
        settleInstallationGuard()
        assertNull(second.get().intent.getStringExtra(GlanceLaunch.EXTRA_ACTION))
        second.pause().stop().destroy()
    }

    @Test
    fun permissionResultDuringCheckIsAppliedAfterwards() {
        val app = RuntimeEnvironment.getApplication()
        // Ausstehender Workout-Auftrag aus dem früheren Prozess; danach wieder kalt (Ergebnis nicht zwischengespeichert).
        eu.advantly.wear.sync.WearPrefs(app).apply {
            workoutPending = eu.advantly.core.model
                .MatchId("pending-match") to eu.advantly.wear.workout.WorkoutSport.TENNIS
            notificationsAsked = true
        }
        (
            AndroidInstallationGuard::class.java
                .getDeclaredField("states")
                .apply { isAccessible = true }
                .get(AndroidInstallationGuard) as MutableMap<*, *>
        ).clear()
        val saved =
            Bundle().apply {
                putBoolean("installation_started", true)
                putBoolean("permissionRequestInFlight", true)
            }
        val activity = activity().create(saved)
        // Das Dialogergebnis wird mit ON_START zugestellt, also vor dem Prüfergebnis.
        val registry = activity.get().activityResultRegistry
        val requestCode =
            (
                androidx.activity.result.ActivityResultRegistry::class.java.declaredFields
                    .first { it.name == "keyToRc" }
                    .apply { isAccessible = true }
                    .get(registry) as Map<*, *>
            ).values.single() as Int
        registry.dispatchResult(requestCode, android.app.Activity.RESULT_CANCELED, null)
        activity.start()
        busy.countDown()
        settleInstallationGuard()
        assertFalse(
            MainActivity::class.java
                .getDeclaredField("permissionRequestInFlight")
                .apply { isAccessible = true }
                .getBoolean(activity.get()),
        )
        val prefs =
            eu.advantly.wear.sync
                .WearPrefs(app)
        assertNull(prefs.workoutPending)
        assertTrue(prefs.workoutHandledMatchId?.value == "pending-match")
        activity.pause().stop().destroy()
    }

    @Test
    @Config(application = AdvantlyWearApplication::class)
    fun coldListenerWaitsForAsyncBootstrapAndDeliversFrame() {
        val app = RuntimeEnvironment.getApplication() as AdvantlyWearApplication
        // Kaltstart nachstellen: Die Session aus dem App-onCreate ist geschlossen, beide Bootstrap-Merker zurück.
        AdvantlyWearApplication::class.java
            .getDeclaredField("bootstrapped")
            .apply { isAccessible = true }
            .setBoolean(app, false)
        (
            AndroidInstallationGuard::class.java
                .getDeclaredField("bootstrapped")
                .apply { isAccessible = true }
                .get(AndroidInstallationGuard) as MutableSet<*>
        ).clear()
        val service = Robolectric.buildService(SyncListenerService::class.java).create()
        var failure: Throwable? = null
        val listener =
            Thread {
                try {
                    service.get().onMessageReceived(hello())
                } catch (error: Throwable) {
                    failure = error
                }
            }
        listener.start()
        awaitIn(listener, "awaitBootstrap")
        assertNull(AndroidMatchSessions.current())
        busy.countDown()
        val until = System.nanoTime() + 30_000_000_000
        while (listener.isAlive) {
            assertTrue(System.nanoTime() < until)
            // Robolectric-Uhr vorstellen: Der Kaltstart-Abgleich wartet begrenzt auf elapsedRealtime.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
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
}

internal fun hello(peer: PeerKind = PeerKind.PHONE): MessageEvent =
    object : MessageEvent {
        override fun getRequestId() = 1

        override fun getPath() = WearDataLayerTransport.MESSAGE_PATH

        override fun getData() =
            byteArrayOf(0xad.toByte(), 1, 1, 0, 1, 109) +
                SyncCodec.encode(
                    Hello(
                        eu.advantly.core.model
                            .DeviceId("counterpart"),
                        peer,
                    ),
                )

        override fun getSourceNodeId() = "counterpart-node"
    }

/** Wartet, bis [thread] in [method] an einem CountDownLatch steht. */
internal fun awaitIn(
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
