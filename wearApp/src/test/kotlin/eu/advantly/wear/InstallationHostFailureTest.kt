package eu.advantly.wear

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDiskIOException
import android.widget.Button
import com.google.android.gms.wearable.MessageEvent
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.device.link.WearDataLayerInbound
import eu.advantly.device.link.WearDataLayerTransport
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.wear.glance.GlanceAction
import eu.advantly.wear.glance.GlanceCopy
import eu.advantly.wear.glance.GlanceMatch
import eu.advantly.wear.glance.GlancePublisher
import eu.advantly.wear.glance.GlanceState
import eu.advantly.wear.glance.installationGlance
import eu.advantly.wear.sync.SyncListenerService
import eu.advantly.wear.sync.UnsupportedDirectorySyncLinux
import eu.advantly.wear.sync.WearSessions
import eu.advantly.wear.workout.WorkoutService
import eu.advantly.wear.workout.WorkoutStateHolder
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
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class InstallationHostFailureTest {
    @Test
    fun runningWearActivityCannotResumeClosedGeneration() {
        val app = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(MainActivity::class.java)
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
        activity.setup()
        settleInstallationGuard()
        AndroidInstallationGuard.storageFailed(app, SQLiteDiskIOException("runtime revoked"))
        activity.pause().stop()
        org.robolectric.Shadows
            .shadowOf(android.os.Looper.getMainLooper())
            .idle()
        activity.start().resume()
        assertNotNull(
            activity
                .get()
                .window.decorView
                .findViewWithTag<android.view.View>("installation_error"),
        )
        activity.pause().stop().destroy()
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
        WearSessions::class.java
            .getDeclaredField("holder")
            .apply { isAccessible = true }
            .set(WearSessions, null)
        WorkoutStateHolder.reset()
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
    fun lateWearResumeAndGlanceIoAreUnavailableUntilRetry() {
        val app = RuntimeEnvironment.getApplication()
        seedLegacy(app)
        val first = AndroidInstallationGuard.get(app)
        val source = WearSessions.get(app).session.deviceId
        resetProcess()
        val before = protectedBytes(app)
        var failedKey: String? = "open_match"
        val broken =
            object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this

                override fun getSharedPreferences(
                    name: String,
                    mode: Int,
                ): SharedPreferences {
                    val prefs = super.getSharedPreferences(name, mode)
                    if (name != "wear_prefs_${first.cacheNamespace}") return prefs
                    return object : SharedPreferences by prefs {
                        override fun getString(
                            key: String?,
                            defValue: String?,
                        ): String? {
                            if (failedKey != null && key == failedKey) throw SQLiteDiskIOException("late Wear storage failed: $key")
                            return prefs.getString(key, defValue)
                        }
                    }
                }
            }
        val product = AdvantlyWearApplication()
        shadowOf(product).callAttach(broken)
        for (key in listOf("open_match", "glance_sent_state")) {
            failedKey = key
            assertTrue(AndroidInstallationGuard.retry(broken) is AndroidInstallationAvailability.Ready)
            repeat(2) {
                assertFalse(product.bootstrap())
                assertEquals(AndroidInstallationAvailability.Unavailable, AndroidInstallationGuard.check(app))
                assertNull(AndroidMatchSessions.current())
                assertFalse(WearDataLayerInbound.isAttached)
                assertEquals(before, protectedBytes(app))
            }
        }
        failedKey = null
        assertEquals(first, (AndroidInstallationGuard.retry(broken) as AndroidInstallationAvailability.Ready).installation)
        assertTrue(product.bootstrap())
        assertEquals(source, WearSessions.get(app).session.deviceId)
        assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
        assertEquals(legacyBytes, legacyBytes(app))
    }

    @Test
    fun coldWorkoutServiceDefersCommandUntilBackgroundCheck() {
        val app = RuntimeEnvironment.getApplication()
        File(app.noBackupFilesDir, "installation-check-123").writeText("blocked cleanup")
        val busy = java.util.concurrent.CountDownLatch(1)
        AndroidInstallationGuard.executorForTesting().execute { busy.await() }
        val workout =
            try {
                // Sticky-Neustart im Kaltstart: onCreate/onStartCommand warten nicht auf die Prüfung und lesen keinen Marker.
                Robolectric.buildService(WorkoutService::class.java).create().also { workout ->
                    assertEquals(
                        android.app.Service.START_STICKY,
                        workout.get().onStartCommand(Intent(WorkoutService.ACTION_RECOVER), 0, 1),
                    )
                    assertFalse(shadowOf(workout.get()).isStoppedBySelf)
                }
            } finally {
                busy.countDown()
            }
        settleInstallationGuard()
        assertTrue(shadowOf(workout.get()).isStoppedBySelf)
        assertNull(AndroidMatchSessions.current())
        workout.destroy()
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
        val product = AdvantlyWearApplication()
        shadowOf(product).callAttach(app)
        product.onCreate()
        settleInstallationGuard()
        assertInert()
        val workout = Robolectric.buildService(WorkoutService::class.java).create()
        assertEquals(android.app.Service.START_NOT_STICKY, workout.get().onStartCommand(Intent(WorkoutService.ACTION_RECOVER), 0, 1))
        workout.destroy()
        GlancePublisher::class.java.getDeclaredField("snapshot").apply {
            isAccessible = true
            set(null, GlanceState(live = GlanceMatch("old sets", "old games", "old points", null, true, MatchStatus.IN_PROGRESS)))
        }
        var glance: Pair<GlanceState, GlanceCopy>? = null
        installationGlance(app) { state, copy -> glance = state to copy }
        val (state, copy) = glance!!
        assertNull(state.live)
        assertEquals(GlanceAction.OPEN, copy.primary)
        assertEquals(app.getString(R.string.installation_error_title), copy.title)
        assertFalse(WorkoutStateHolder.state.value.countOnly)
        assertEquals(before, protectedBytes(app))
    }

    @Test
    fun retryKeepsDataAndGeneration() {
        val app = RuntimeEnvironment.getApplication()
        seedLegacy(app)
        val first = AndroidInstallationGuard.get(app)
        val source = AndroidMatchSessions.get(app, PeerKind.WATCH).deviceId
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

        assertTrue(blocker.delete())
        // Ein Dienst darf auch nach Wegfall des Fehlers keine implizite Wiederholung/Entsperrung auslösen.
        assertInert()
        assertEquals(before, protectedBytes(app))
        retry.performClick()
        // Erfolgszweig ausführen (Ergebnis-Callback bis recreate()), aber nicht das von recreate() gepostete Neuerstellen:
        // Robolectric fehlen die Wear-Ambient-Klassen der neuen Instanz.
        settleInstallationGuard(idleMain = false)
        val main = shadowOf(android.os.Looper.getMainLooper())
        repeat(20) { if (!activity.intent.getBooleanExtra(INSTALLATION_RETRY_COMPLETE, false) && !main.isIdle) main.runOneTask() }
        assertTrue(activity.intent.getBooleanExtra(INSTALLATION_RETRY_COMPLETE, false))
        assertFalse(retry.isEnabled)
        assertEquals(first, AndroidInstallationGuard.get(app))
        assertEquals(before, protectedBytes(app))
        assertFalse(first.privateTransportAllowed)
        val product = AdvantlyWearApplication()
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
        Robolectric.buildService(SyncListenerService::class.java).create().get().onMessageReceived(
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
