package eu.advantly.android

import android.app.Application
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import android.os.Looper
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = Application::class,
    shadows = [UiActionQueryDatabase::class, eu.advantly.android.UnsupportedDirectorySyncLinux::class],
)
class DirectUiBoundaryTest {
    @Before fun reset() {
        UiActionQueryDatabase.failure = null
        UiActionQueryDatabase.late = false
        UiActionQueryDatabase.hits = 0
        InstallationHostFailureTest().reset()
    }

    @org.junit.After
    fun finishNativeRuntime() {
        if (AndroidMatchSessions.current() != null) {
            AndroidInstallationGuard.storageFailed(RuntimeEnvironment.getApplication(), java.io.IOException("test teardown"))
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun actionFailure(
        undoFirst: Boolean = false,
        late: Boolean = false,
        programming: Boolean = false,
        healthy: Boolean = false,
    ) {
        val app = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        settleInstallationGuard()
        val session = requireNotNull(AndroidMatchSessions.current())

        val self =
            session.data.players
                .ensureSelf("Owner", 0) { PlayerId("owner") }
                .id
        val match = session.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(self), listOf(PlayerId("opponent")), self))
        val store = AndroidPhoneMatchStore.create(app, session)
        val controller =
            eu.advantly.ui.phone.live
                .LiveScoreController(store)

        fun point() {
            controller.onTap(Side.A, null)
        }

        fun undo() {
            controller.undo()
        }

        shadowOf(Looper.getMainLooper()).idle()
        val original = AndroidInstallationGuard.get(app)
        val source = session.deviceId
        val marker = File(app.noBackupFilesDir, "installation-v1").readBytes().toList()
        if (undoFirst) point()
        val before = session.data.events.count(match.value)
        assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
        assertEquals(0, UiActionQueryDatabase.hits)
        UiActionQueryDatabase.late = late
        UiActionQueryDatabase.failure =
            if (healthy) {
                null
            } else if (programming) {
                SQLiteException("no such column: UI programming error")
            } else {
                SQLiteDiskIOException("first native UI query failed")
            }
        if (programming) {
            assertThrows(SQLiteException::class.java) { point() }
            UiActionQueryDatabase.failure = null
            assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
        } else {
            val escaped = runCatching { if (undoFirst) undo() else point() }.exceptionOrNull()
            UiActionQueryDatabase.failure = null
            assertNull(escaped)
            if (healthy) {
                assertEquals(before + 1, session.data.events.count(match.value))
                assertTrue(AndroidMatchSessions.current() === session)
            } else {
                assertEquals(1, UiActionQueryDatabase.hits)
                assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
                assertNull(AndroidMatchSessions.current())
                shadowOf(Looper.getMainLooper()).idle()
                assertNotNull(
                    activity
                        .get()
                        .window.decorView
                        .findViewWithTag<android.view.View>("installation_error"),
                )
                assertEquals(marker, File(app.noBackupFilesDir, "installation-v1").readBytes().toList())
                assertEquals(original, (AndroidInstallationGuard.retry(app) as AndroidInstallationAvailability.Ready).installation)
                val retried = AndroidMatchSessions.get(app, PeerKind.PHONE)
                assertEquals(source, retried.deviceId)
                assertEquals(before + (if (late) 1 else 0), retried.data.events.count(match.value))
                // Kein automatisches DoubleApply und kein Wiederaufleben der alten UI-Generation nach Retry.
                point()
                assertEquals(before + (if (late) 1 else 0), retried.data.events.count(match.value))
            }
        }
        store.close()
        activity.pause().stop().destroy()
    }

    @Test fun firstPointSqlFailureIsHandled() = actionFailure()

    @Test fun firstUndoSqlFailureIsHandled() = actionFailure(undoFirst = true)

    @Test fun committedPointIsNotReappliedAfterLateFault() = actionFailure(late = true)

    @Test fun healthyPointStillCommitsOnce() = actionFailure(healthy = true)

    @Test fun programmingSqlErrorStillPropagates() = actionFailure(programming = true)

    @Test
    fun retainedAndroidModelSurvivesRotationAndClosesItsStore() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        settleInstallationGuard()
        val model = androidx.lifecycle.ViewModelProvider(activity.get())[AndroidPhoneAppViewModel::class.java]
        val guest = requireNotNull(model.model.players.tryAddGuest("Before rotation"))
        activity.recreate()
        val retained = androidx.lifecycle.ViewModelProvider(activity.get())[AndroidPhoneAppViewModel::class.java]
        org.junit.Assert.assertSame(model, retained)
        assertNotNull(retained.model.players.tryAddGuest("After rotation"))
        assertTrue(
            retained.model.players.guests.value
                .any { it.id == guest.id },
        )
        activity.pause().stop().destroy()
        assertNull(model.model.players.tryAddGuest("Closed owner"))
        assertTrue(AndroidInstallationGuard.privateTransportAllowed(RuntimeEnvironment.getApplication()))
    }

    @Test
    fun closedDecoratorRejectsLegacyCallsAndLateCompanionCallback() {
        val app = RuntimeEnvironment.getApplication()
        val session = AndroidMatchSessions.get(app, PeerKind.PHONE)
        var callback: ((eu.advantly.device.link.CompanionState) -> Unit)? = null
        var stops = 0
        val companion =
            object : eu.advantly.device.link.CompanionDetector {
                override fun start(listener: (eu.advantly.device.link.CompanionState) -> Unit) {
                    callback = listener
                }

                override fun refresh() {}

                override fun stop() {
                    stops++
                }
            }
        val store = AndroidPhoneMatchStore.create(app, session, companion)
        assertThrows(IllegalStateException::class.java) { store.addGuest("Legacy bypass") }
        assertTrue(store.guests.value.isEmpty())
        store.close()
        assertEquals(1, stops)
        // Ein spaeter SDK-Callback darf nach Close weder SQL lesen noch den geschlossenen Store aktualisieren.
        callback?.invoke(eu.advantly.device.link.CompanionState.APP_INSTALLED)
        assertNull(store.tryAddGuest("Closed"))
        assertEquals(eu.advantly.presentation.match.WatchStatus.UNKNOWN, store.watchStatus.value)
    }
}

/** Erst SQL im realen Controller-Actionpfad stoeren; keine vorab entzogene Capability. */
@Implements(SQLiteDatabase::class)
class UiActionQueryDatabase {
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
            if (late) {
                Thread.currentThread().stackTrace.any {
                    it.className == "eu.advantly.match.session.MatchSession" &&
                        it.methodName == "publish"
                }
            } else {
                sql.contains("max(lamport)", ignoreCase = true)
            }
        if (failure != null && target) {
            hits++
            throw requireNotNull(failure)
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
        var failure: Exception? = null
        var late = false
        var hits = 0
    }
}
