package eu.advantly.wear.sync

import android.app.Application
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.Presets
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.data.LocalData
import eu.advantly.data.createAndroidDriver
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.match.session.MatchSessions
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.wear.workout.WorkoutStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest

/** Recovery-Paket auf der Uhr: Entscheidung im Hold; ein neuer Prozess wird über die Prozess-Singletons nachgestellt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class WearRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun newProcess() {
        AndroidMatchSessions.current()?.close()
        AndroidMatchSessions::class.java
            .getDeclaredField("instance")
            .apply { isAccessible = true }
            .set(AndroidMatchSessions, null)
        WearSessions::class.java
            .getDeclaredField("holder")
            .apply { isAccessible = true }
            .set(WearSessions, null)
        val states = AndroidInstallationGuard::class.java.getDeclaredField("states").apply { isAccessible = true }
        (states.get(AndroidInstallationGuard) as MutableMap<*, *>).clear()
        val unavailable = AndroidInstallationGuard::class.java.getDeclaredField("unavailable").apply { isAccessible = true }
        (unavailable.get(AndroidInstallationGuard) as MutableSet<*>).clear()
        WorkoutStateHolder.reset()
    }

    /** Altuhr, gekoppelt mit einem Handy (Autorität, Scope), mit einem eigenen Match; als `advantly.db` abgelegt. */
    private fun oldWatch(): Pair<String, String> {
        val driver = createAndroidDriver(app, "seed.db")
        val data = LocalData(driver)
        data.players.ensureSelf("Alex", 0) { PlayerId("old-watch-self") }
        val device = data.accountIdentities.select("phone-scope", null, 0, "WATCH") { "old-watch-${++counter}" }
        data.settings.put("identity.authority", "old-phone")
        data.settings.put("identity.epoch", "2")
        data.settings.put("identity.wireScope", "phone-scope")
        val session = MatchSessions.create(data, null, ManualScheduler(), PeerKind.WATCH)
        session.start()
        val self = data.players.self()!!.id
        val match = session.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(self), listOf(PlayerId("opponent")), self))
        session.close()
        driver.close()
        assertTrue(app.getDatabasePath("seed.db").renameTo(app.getDatabasePath("advantly.db")))
        return device to match.value
    }

    private var counter = 0

    private fun hash() = MessageDigest.getInstance("SHA-256").digest(app.getDatabasePath("advantly.db").readBytes()).toList()

    @Test
    fun heldWatchKeepsNoPairingAuthoritySoItCanPairOnceTheHoldEnds() {
        oldWatch()
        val holder = WearSessions.get(app)
        assertFalse(AndroidInstallationGuard.get(app).privateTransportAllowed)
        assertNull(holder.data.settings.get("identity.authority"))
        assertNull(holder.data.settings.get("identity.wireScope"))
    }

    @Test
    fun restoredWatchImageGetsANewIdentityAndWaitsForItsPhone() {
        val (oldDevice, oldMatch) = oldWatch()
        val original = hash()
        WearSessions.get(app)
        AndroidInstallationGuard.requestRestore(app)

        newProcess()
        val holder = WearSessions.get(app)
        assertTrue(AndroidInstallationGuard.get(app).privateTransportAllowed)
        assertNotEquals(oldDevice, holder.session.deviceId.value)
        assertNull("Uhr übernimmt keine alte Handy-Autorität", holder.data.settings.get("identity.authority"))
        assertNull("Alter Bestand erst über das gekoppelte Handy", holder.data.matches.get(MatchId(oldMatch)))
        assertEquals(original, hash())
    }

    @Test
    fun startingFreshLiftsTheHoldWithoutOpeningTheImage() {
        val (_, oldMatch) = oldWatch()
        val original = hash()
        val held = AndroidInstallationGuard.get(app)
        WearSessions.get(app)
        AndroidInstallationGuard.keepWithoutRestore(app)

        newProcess()
        val holder = WearSessions.get(app)
        val kept = AndroidInstallationGuard.get(app)
        assertTrue(kept.privateTransportAllowed)
        assertEquals(held.activeDatabaseName, kept.activeDatabaseName)
        assertNull(holder.data.settings.get("identity.authority"))
        assertNull(holder.data.matches.get(MatchId(oldMatch)))
        assertEquals(original, hash())
    }
}
