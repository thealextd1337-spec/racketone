package eu.advantly.android

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.Presets
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.data.LocalData
import eu.advantly.data.createAndroidDriver
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.sync.protocol.PeerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.security.MessageDigest

/**
 * Recovery-Paket am Android-Host: ein vom Betriebssystem übertragenes Datenbankabbild vor jedem Login. Ein neuer
 * Prozess wird durch Zurücksetzen der Prozess-Singletons nachgestellt (der Host startet nach der Entscheidung neu).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class AndroidRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val profileA = "11111111-1111-4111-8111-111111111111"
    private val profileB = "22222222-2222-4222-8222-222222222222"
    private val profileC = "33333333-3333-4333-8333-333333333333"
    private val format = Presets.TENNIS_SINGLES_BO3.format()

    @Before
    fun newProcess() {
        AndroidMatchSessions.current()?.close()
        AndroidMatchSessions::class.java
            .getDeclaredField("instance")
            .apply { isAccessible = true }
            .set(AndroidMatchSessions, null)
        val states = AndroidInstallationGuard::class.java.getDeclaredField("states").apply { isAccessible = true }
        (states.get(AndroidInstallationGuard) as MutableMap<*, *>).clear()
        val unavailable = AndroidInstallationGuard::class.java.getDeclaredField("unavailable").apply { isAccessible = true }
        (unavailable.get(AndroidInstallationGuard) as MutableSet<*>).clear()
    }

    /** Altgerät: Abbild mit [block], danach als `advantly.db` abgelegt, wie es ein Restore oder Altupdate hinterlässt. */
    private fun <T> oldDevice(block: (MatchSession) -> T): T {
        val driver = createAndroidDriver(app, "seed.db")
        val session = MatchSessions.create(LocalData(driver), null, ManualScheduler(), PeerKind.PHONE)
        session.start()
        val result = block(session)
        session.close()
        driver.close()
        assertTrue(app.getDatabasePath("seed.db").renameTo(app.getDatabasePath("advantly.db")))
        return result
    }

    private fun login(
        session: MatchSession,
        profile: String,
    ) {
        session.activateAccount(profile, session.data.cloudUploads.activate(profile))
        session.data.replicas.activate(session.data.replicas.captureContext()) {}
    }

    private fun MatchSession.match(name: String): MatchId {
        val self = data.players.ensureSelf(name, 0) { PlayerId("self-$name-${deviceId.value}") }.id
        if (data.players.self()?.displayName != name) data.players.rename(self, name)
        return create(format, Lineup(listOf(self), listOf(PlayerId("opponent-${deviceId.value}")), self))
    }

    private fun MatchSession.sees(id: MatchId) = data.matches.get(id) != null

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

    private var resets = 0
    private val restarts = mutableListOf<Boolean>()
    private val effects = RecoveryEffects(resetAuth = { resets++ }, restart = { restarts += it })

    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun dialog() = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog

    private fun AlertDialog.offers(button: Int) = getButton(button).visibility == View.VISIBLE

    private fun AlertDialog.message() = shadowOf(this).message.toString()

    private fun AlertDialog.tap(button: Int) {
        getButton(button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun restoredImageBeforeLoginIsReleasedOnlyForTheSameUidOnANewDevice() {
        val (a, b) =
            oldDevice { old ->
                login(old, profileA)
                val a = old.deviceId.value to old.match("Alex")
                login(old, profileB)
                a to (old.deviceId.value to old.match("Alex"))
            }
        val (deviceA, matchA) = a
        val (deviceB, matchB) = b
        val original = app.getDatabasePath("advantly.db")
        val originalHash = hash(original)

        val held = AndroidInstallationGuard.get(app)
        assertFalse(held.privateTransportAllowed)
        assertEquals("advantly.db", held.restorable)
        val interim = AndroidMatchSessions.get(app, PeerKind.PHONE)
        assertTrue(
            interim.data.matches
                .recent(10)
                .isEmpty(),
        )
        AndroidInstallationGuard.requestRestore(app)

        newProcess()
        val restored = AndroidInstallationGuard.get(app)
        assertTrue(restored.privateTransportAllowed)
        assertTrue(restored.activeDatabaseName !in setOf("advantly.db", held.activeDatabaseName))
        val session = AndroidMatchSessions.get(app, PeerKind.PHONE)
        val installationDevice = session.deviceId.value
        assertTrue(installationDevice !in setOf(deviceA, deviceB, interim.deviceId.value))
        assertEquals("installation:${restored.cacheNamespace}", session.data.accountIdentities.scope)
        assertEquals(restored.cacheNamespace, session.data.settings.get("identity.authority"))
        assertNull(session.data.settings.get(AndroidInstallationGuard.RESTORED_KEY))
        assertFalse(session.sees(matchA))
        assertFalse(session.sees(matchB))
        assertTrue(
            session.data.players
                .all()
                .none { it.displayName == "Alex" },
        )
        assertEquals(originalHash, hash(original))

        // Verspäteter Neustart vor dem Login ändert nichts: keine zweite Umstellung, keine Freigabe.
        newProcess()
        val restarted = AndroidMatchSessions.get(app, PeerKind.PHONE)
        assertEquals(installationDevice, restarted.deviceId.value)
        assertFalse(restarted.sees(matchA))

        login(restarted, profileA)
        assertTrue(restarted.deviceId.value !in setOf(deviceA, deviceB, installationDevice))
        assertTrue(restarted.sees(matchA))
        assertFalse(restarted.sees(matchB))
        login(restarted, profileC)
        assertFalse(restarted.sees(matchA))
        assertFalse(restarted.sees(matchB))
        assertEquals(originalHash, hash(original))
    }

    @Test
    fun restoredAccountlessImageIsReleasedLocallyUnderANewDevice() {
        val (legacyDevice, legacyMatch) = oldDevice { old -> old.deviceId.value to old.match("Alex") }
        AndroidInstallationGuard.get(app)
        AndroidMatchSessions.get(app, PeerKind.PHONE)
        AndroidInstallationGuard.requestRestore(app)

        newProcess()
        val session = AndroidMatchSessions.get(app, PeerKind.PHONE)
        assertNotEquals(legacyDevice, session.deviceId.value)
        assertTrue(session.sees(legacyMatch))
        assertTrue(
            session.data.accountIdentities
                .ownedSources(profileA)
                .isEmpty(),
        )
    }

    @Test
    fun unusableImageAbortsTheRestoreWithoutSwitchingOrTouchingAnyFamily() {
        oldDevice { old -> old.match("Alex") }
        val original = app.getDatabasePath("advantly.db")
        // Abbild einer neueren App-Version: Downgrade-Migration ist unmöglich.
        val jdbc = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver
        jdbc.connect("jdbc:sqlite:${original.absolutePath}", java.util.Properties()).use { database ->
            database.createStatement().use { it.execute("PRAGMA user_version = 999") }
        }
        val originalHash = hash(original)
        val held = AndroidInstallationGuard.get(app)
        AndroidMatchSessions.get(app, PeerKind.PHONE)
        AndroidInstallationGuard.requestRestore(app)

        newProcess()
        val failed = AndroidInstallationGuard.get(app)
        assertTrue(failed.restoreFailed)
        assertFalse(failed.privateTransportAllowed)
        assertEquals(held.activeDatabaseName, failed.activeDatabaseName)
        assertEquals("advantly.db", failed.restorable)
        assertEquals(originalHash, hash(original))
        assertTrue(
            AndroidMatchSessions
                .get(app, PeerKind.PHONE)
                .data.matches
                .recent(10)
                .isEmpty(),
        )
    }

    @Test
    fun keepingWithoutRestoreLeavesTheImageUnopenedAndLiftsTheHold() {
        val legacyMatch = oldDevice { old -> old.match("Alex") }
        val original = app.getDatabasePath("advantly.db")
        val originalHash = hash(original)
        val held = AndroidInstallationGuard.get(app)
        AndroidMatchSessions.get(app, PeerKind.PHONE)
        AndroidInstallationGuard.keepWithoutRestore(app)

        newProcess()
        val kept = AndroidInstallationGuard.get(app)
        assertTrue(kept.privateTransportAllowed)
        assertEquals(held.activeDatabaseName, kept.activeDatabaseName)
        assertFalse(AndroidMatchSessions.get(app, PeerKind.PHONE).sees(legacyMatch))
        assertEquals(originalHash, hash(original))
    }

    // --- Recovery-Paket, Runde 2: Dialoge am Host ------------------------------------------------------------------

    @Test
    fun restoreIsTheEmphasizedDefaultAndDropsTheOldSessionBeforeAnythingIsSwitched() {
        val legacyMatch = oldDevice { old -> old.match("Alex") }
        val held = AndroidInstallationGuard.get(app)
        val interim = AndroidMatchSessions.get(app, PeerKind.PHONE)
        val reservation = File(app.noBackupFilesDir, "installation-v1.reservation")
        val ordered =
            RecoveryEffects(
                resetAuth = {
                    check(!reservation.exists()) { "Sitzung zuerst verwerfen" }
                    resets++
                },
                restart = { restarts += it },
            )
        activity().showRecoveryDecision(held, interim, ordered)
        assertSame("Übernehmen ist hervorgehoben", Typeface.DEFAULT_BOLD, dialog().getButton(AlertDialog.BUTTON_POSITIVE).typeface)
        dialog().tap(AlertDialog.BUTTON_POSITIVE)
        assertEquals(1, resets)
        assertEquals(listOf(true), restarts)

        newProcess()
        assertTrue(AndroidMatchSessions.get(app, PeerKind.PHONE).sees(legacyMatch))
    }

    @Test
    fun matchesCountedDuringTheHoldHideTheRestoreChoice() {
        oldDevice { old -> old.match("Alex") }
        val held = AndroidInstallationGuard.get(app)
        val interim = AndroidMatchSessions.get(app, PeerKind.PHONE)
        interim.match("Sam")
        activity().showRecoveryDecision(held, interim, effects)
        assertFalse(dialog().offers(AlertDialog.BUTTON_POSITIVE))
        assertTrue(dialog().offers(AlertDialog.BUTTON_NEUTRAL))
        assertEquals(app.getString(R.string.recovery_blocked_body), dialog().message())
    }

    @Test
    fun startingFreshNeedsConfirmationAndCanStillBeRestoredLaterUnderTheSameRule() {
        val legacyMatch = oldDevice { old -> old.match("Alex") }
        val held = AndroidInstallationGuard.get(app)
        val interim = AndroidMatchSessions.get(app, PeerKind.PHONE)
        val activity = activity()
        activity.showRecoveryDecision(held, interim, effects)
        dialog().tap(AlertDialog.BUTTON_NEUTRAL)
        assertTrue("Erst nach der Rückfrage", restarts.isEmpty())
        assertEquals(app.getString(R.string.recovery_fresh_confirm_body), dialog().message())
        dialog().tap(AlertDialog.BUTTON_NEGATIVE)
        assertEquals("Abbrechen führt zur Entscheidung zurück", app.getString(R.string.recovery_decision_body), dialog().message())
        dialog().tap(AlertDialog.BUTTON_NEUTRAL)
        dialog().tap(AlertDialog.BUTTON_POSITIVE)
        assertEquals(listOf(false), restarts)
        assertEquals(1, resets)

        newProcess()
        val kept = AndroidInstallationGuard.get(app)
        assertTrue(kept.privateTransportAllowed)
        assertEquals("advantly.db", kept.restorable)
        val session = AndroidMatchSessions.get(app, PeerKind.PHONE)
        assertFalse(session.sees(legacyMatch))
        // Später im Profil: dieselbe Sperre wie im Hold, die Übernahme geht erst nach dem Löschen.
        val counted = session.match("Sam")
        activity.showLaterRestore(session, effects)
        assertFalse(dialog().offers(AlertDialog.BUTTON_POSITIVE))
        assertEquals(app.getString(R.string.recovery_blocked_body), dialog().message())
        dialog().dismiss()
        session.deleteMatch(counted)
        activity.showLaterRestore(session, effects)
        assertSame(Typeface.DEFAULT_BOLD, dialog().getButton(AlertDialog.BUTTON_POSITIVE).typeface)
        dialog().tap(AlertDialog.BUTTON_POSITIVE)
        assertEquals(listOf(false, true), restarts)
        assertEquals(2, resets)

        newProcess()
        val restored = AndroidInstallationGuard.get(app)
        assertTrue(restored.privateTransportAllowed)
        assertNull(restored.restorable)
        assertTrue(AndroidMatchSessions.get(app, PeerKind.PHONE).sees(legacyMatch))
    }

    @Test
    fun failingSessionResetAbortsTheDecisionAndEverythingStaysInTheHold() {
        oldDevice { old -> old.match("Alex") }
        val held = AndroidInstallationGuard.get(app)
        val interim = AndroidMatchSessions.get(app, PeerKind.PHONE)
        val activity = activity()
        // Ohne Keystore (Robolectric) scheitert auch der echte Reset; beides darf nicht fortsetzen.
        val failing =
            listOf(
                RecoveryEffects(resetAuth = { error("commit failed") }, restart = { restarts += it }),
                RecoveryEffects(resetAuth = activity.recoveryEffects().resetAuth, restart = { restarts += it }),
            )
        failing.forEach { broken ->
            activity.showRecoveryDecision(held, interim, broken)
            dialog().tap(AlertDialog.BUTTON_POSITIVE)
            assertTrue(restarts.isEmpty())
            assertEquals(app.getString(R.string.recovery_save_failed), dialog().message())
            activity.showRecoveryDecision(held, interim, broken)
            dialog().tap(AlertDialog.BUTTON_NEUTRAL)
            dialog().tap(AlertDialog.BUTTON_POSITIVE)
            assertTrue(restarts.isEmpty())
            assertEquals(app.getString(R.string.recovery_save_failed), dialog().message())
        }

        newProcess()
        val after = AndroidInstallationGuard.get(app)
        assertFalse(after.privateTransportAllowed)
        assertEquals(held.activeDatabaseName, after.activeDatabaseName)
        assertFalse(after.restoreFailed)
        assertEquals("advantly.db", after.restorable)
    }
}
