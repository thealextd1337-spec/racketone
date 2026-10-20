package eu.advantly.wear.sync

import android.app.Application
import android.content.Context
import android.content.Intent
import android.system.ErrnoException
import android.system.OsConstants
import com.google.android.gms.wearable.MessageEvent
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.device.link.WearDataLayerInbound
import eu.advantly.device.link.WearDataLayerTransport
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.wear.glance.GlancePublisher
import eu.advantly.wear.glance.GlanceState
import eu.advantly.wear.workout.SessionStore
import eu.advantly.wear.workout.WorkoutService
import eu.advantly.wear.workout.WorkoutStateHolder
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLinux
import java.io.File
import java.io.FileDescriptor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class WearRestoreGuardTest {
    @Before
    fun resetProcessSingletons() {
        AndroidMatchSessions.current()?.close()
        AndroidMatchSessions::class.java
            .getDeclaredField("instance")
            .apply { isAccessible = true }
            .set(AndroidMatchSessions, null)
        WearSessions::class.java
            .getDeclaredField("holder")
            .apply { isAccessible = true }
            .set(WearSessions, null)
        val field = AndroidInstallationGuard::class.java.getDeclaredField("states").apply { isAccessible = true }
        (field.get(AndroidInstallationGuard) as MutableMap<*, *>).clear()
        val failures = AndroidInstallationGuard::class.java.getDeclaredField("unavailable").apply { isAccessible = true }
        (failures.get(AndroidInstallationGuard) as MutableSet<*>).clear()
        WorkoutStateHolder.reset()
    }

    @Test
    fun restoredCachesStayPreservedAndCannotResumeOrDisplayPrivateOldMatch() {
        val app = RuntimeEnvironment.getApplication()
        val original =
            app.getDatabasePath("advantly.db").apply {
                parentFile!!.mkdirs()
                writeText("original-database-never-open")
            }
        val oldPrefs = app.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        oldPrefs
            .edit()
            .putString("open_match", "old-private-match")
            .putString("glance_sent_state", "old-private-snapshot")
            .putString("workout_pending_match", "old-private-match")
            .putString("workout_pending_sport", "TENNIS")
            .putString("workout_handled_match", "old-private-match")
            .commit()
        val oldWorkout = app.getSharedPreferences("workout_session", Context.MODE_PRIVATE)
        oldWorkout
            .edit()
            .putBoolean("session_active", true)
            .putString("session_id", "old-private-workout")
            .commit()
        val holder = WearSessions.get(app)
        assertFalse(AndroidInstallationGuard.get(app).privateTransportAllowed)
        assertNull(holder.prefs.openMatchId)
        assertNull(holder.prefs.workoutPending)
        assertNull(holder.prefs.workoutHandledMatchId)
        assertNull(holder.prefs.glanceSentState)
        assertFalse(SessionStore(app).sessionActive)
        assertNull(SessionStore(app).sessionId)
        assertNull(holder.session.state.value.match)
        assertTrue(
            holder.data.matches
                .recent(100)
                .isEmpty(),
        )
        holder.session.addListener(GlancePublisher(app, holder))
        assertEquals(GlanceState(), GlancePublisher.snapshot)
        assertEquals("old-private-match", oldPrefs.getString("open_match", null))
        assertEquals("old-private-snapshot", oldPrefs.getString("glance_sent_state", null))
        assertTrue(oldWorkout.getBoolean("session_active", false))
        assertEquals("old-private-workout", oldWorkout.getString("session_id", null))
        assertEquals("original-database-never-open", original.readText())
        var resets = 0
        holder.prefs.initializeOperationalCache { resets++ }
        holder.prefs.initializeOperationalCache { resets++ }
        assertEquals(1, resets)
        val service = Robolectric.buildService(WorkoutService::class.java).create().get()
        service.onStartCommand(Intent(), 0, 1)
        assertFalse(WorkoutStateHolder.state.value.countOnly)
        assertFalse(SessionStore(app).sessionActive)
        val self =
            holder.data.players
                .self()!!
                .id
        holder.session.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(self), listOf(PlayerId("fresh-opponent")), self))
        assertTrue(holder.session.recordPoint(Side.A))
        val freshId = holder.prefs.openMatchId
        assertTrue(freshId != null && freshId != MatchId("old-private-match"))
        assertEquals(freshId, WearPrefs(app).openMatchId)
    }

    @Test
    fun listenerCannotAcknowledgeOrConsumeOldPrivateQueueWhileRecoveryIsHeld() {
        val app = RuntimeEnvironment.getApplication()
        app.getDatabasePath("advantly.db").apply {
            parentFile!!.mkdirs()
            writeText("retained")
        }
        val service = Robolectric.buildService(SyncListenerService::class.java).create().get()
        service.onMessageReceived(
            object : MessageEvent {
                override fun getRequestId() = 1

                override fun getPath() = WearDataLayerTransport.MESSAGE_PATH

                override fun getData() = byteArrayOf(1, 2, 3)

                override fun getSourceNodeId() = "old-phone-node"
            },
        )
        val holder = WearSessions.get(app)
        assertTrue(
            holder.session.state.value.sync.peers
                .isEmpty(),
        )
        assertFalse(WearDataLayerInbound.isAttached)
        var consumed = 0
        assertFalse(
            WearDataLayerInbound.deliverQueuedItem(
                "fresh-watch",
                "old-phone",
                listOf("advantly", "link", "q", "fresh-watch", "private-item"),
                byteArrayOf(4, 5),
            ) { consumed++ },
        )
        assertEquals(0, consumed)
        assertNull(holder.session.linkStats) // Kein produktiver Transport: weder Sends noch ACK-/Queue-Erfolg möglich.
    }
}

/** Robolectric/Windows kann Verzeichnisse nicht öffnen; modelliert gezielt einen nicht unterstützten fsync. */
@Implements(className = "libcore.io.Linux", isInAndroidSdk = false)
class UnsupportedDirectorySyncLinux : ShadowLinux() {
    @Implementation
    override fun open(
        path: String,
        flags: Int,
        mode: Int,
    ): FileDescriptor {
        if (File(path).isDirectory) throw ErrnoException("open", OsConstants.EINVAL)
        return super.open(path, flags, mode)
    }
}
