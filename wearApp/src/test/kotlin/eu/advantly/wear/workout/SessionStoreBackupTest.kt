package eu.advantly.wear.workout

import android.app.Application
import android.content.Context
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.wear.sync.UnsupportedDirectorySyncLinux
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Plan 0.5.1 §7: Der Workout-Zustand liegt in einer Datei mit festem Namen, die beide Backup-Regeln ausschließen – ein
 * wiederhergestelltes `sessionActive` nähme sonst auf einem neuen Gerät ein altes Workout wieder auf. Ein laufendes
 * Workout aus der alten Datei je Namensraum (bis 0.5.0) überlebt das Update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class SessionStoreBackupTest {
    @Before
    fun reset() =
        eu.advantly.wear
            .InstallationHostFailureTest()
            .reset()

    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun runningWorkoutSurvivesTheUpdate() {
        val namespace = AndroidInstallationGuard.get(app).cacheNamespace
        val legacy = SessionStore.legacyName(namespace)
        app
            .getSharedPreferences(legacy, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("session_active", true)
            .putString("session_id", "running")
            .putString("sport", WorkoutSport.BADMINTON.name)
            .putLong("last_exit_info_ts", 42L)
            .commit()
        val store = SessionStore(app)
        assertTrue(store.sessionActive)
        assertFalse(store.endRequested)
        assertEquals("running", store.sessionId)
        assertEquals(WorkoutSport.BADMINTON, store.sport)
        assertEquals(42L, store.lastExitInfoTimestamp)
        // alte Datei geleert; ein zweiter Zugriff überschreibt nichts mit alten Werten
        assertTrue(app.getSharedPreferences(legacy, Context.MODE_PRIVATE).all.isEmpty())
        store.sessionActive = false
        assertFalse(SessionStore(app).sessionActive)
        assertTrue(app.getSharedPreferences(SessionStore.FILE_NAME, Context.MODE_PRIVATE).all.isNotEmpty())
    }

    @Test
    fun existingValuesWinOverAnInterruptedMigration() {
        val namespace = AndroidInstallationGuard.get(app).cacheNamespace
        SessionStore(app).sessionId = "new"
        // Abbruch nach dem Kopieren, vor dem Löschen: die alte Datei ist noch da
        app
            .getSharedPreferences(SessionStore.legacyName(namespace), Context.MODE_PRIVATE)
            .edit()
            .putString("session_id", "old")
            .commit()
        assertEquals("new", SessionStore(app).sessionId)
    }

    @Test
    fun bothBackupRulesExcludeTheWorkoutState() {
        for ((rules, sections) in listOf("backup_rules.xml" to 1, "data_extraction_rules.xml" to 2)) {
            val text = File("src/main/res/xml/$rules").readText()
            val exclude = """<exclude domain="sharedpref" path="${SessionStore.FILE_NAME}.xml" />"""
            assertEquals(rules, sections, text.split(exclude).size - 1)
        }
    }
}
