package eu.advantly.match.session

import app.cash.sqldelight.db.SqlDriver
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.Presets
import eu.advantly.data.CloudUploadSession
import eu.advantly.data.LocalData
import eu.advantly.data.ReplicaAccessLevel
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.PeerKind
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recovery-Paket auf Daten-/Sessionebene: Ein übernommenes Abbild (hier dieselbe Datenbank, die der Guard als private
 * Kopie veröffentlicht) wird umgestellt und danach nur über UID, ausdrückliche Adoption oder lokale Freigabe sichtbar.
 */
class RestoredInstallationTest {
    private val profileA = "11111111-1111-4111-8111-111111111111"
    private val profileB = "22222222-2222-4222-8222-222222222222"
    private val profileC = "33333333-3333-4333-8333-333333333333"
    private val format = Presets.TENNIS_SINGLES_BO3.format()
    private val scheduler = ManualScheduler()
    private val driver: SqlDriver = createInMemoryJvmDriver()
    private var seed = 0
    private var data = LocalData(driver)
    private var session = open()

    @AfterTest
    fun close() {
        session.close()
        driver.close()
    }

    private fun open(): MatchSession =
        MatchSession(
            data,
            null,
            scheduler,
            SessionConfig(MatchSessions.deviceId(data), PeerKind.PHONE),
            WallClock { 1_760_000_000_000 + scheduler.nowMs() },
            RandomIdGenerator(Random(++seed)),
        ).also { it.start() }

    /** Prozessneustart auf demselben Abbild, wahlweise mit der einmaligen Umstellung des Bootstraps davor. */
    private fun restart(rekey: String? = null) {
        session.close()
        data = LocalData(driver)
        if (rekey != null) {
            rekeyRestoredInstallation(data, PeerKind.PHONE, rekey, releaseUnbound = true, ids = RandomIdGenerator(Random(++seed)))
            data = LocalData(driver)
        }
        session = open()
    }

    private fun login(profile: String): CloudUploadSession {
        val upload = data.cloudUploads.activate(profile)
        session.activateAccount(profile, upload)
        data.replicas.activate(data.replicas.captureContext()) {}
        return upload
    }

    private fun createMatch(name: String): MatchId {
        val self = data.players.ensureSelf(name, 0) { PlayerId("self-${++seed}") }.id
        if (data.players.self()?.displayName != name) data.players.rename(self, name)
        return session.create(format, Lineup(listOf(self), listOf(PlayerId("opponent-${++seed}")), self))
    }

    private fun visible(id: MatchId) = data.matches.get(id) != null

    @Test
    fun sameUidRestoreGetsNewDeviceAndOnlyItsOwnBoundHistory() {
        login(profileA)
        val deviceA = session.deviceId.value
        val matchA = createMatch("Alex")
        login(profileB)
        val deviceB = session.deviceId.value
        val matchB = createMatch("Alex")
        val oldAuthority = data.settings.get("identity.authority")

        restart(rekey = "restored-token")
        val installation = session.deviceId.value
        assertTrue(installation !in setOf(deviceA, deviceB))
        assertEquals("installation:restored-token", data.accountIdentities.scope)
        assertNull(data.accountIdentities.profile)
        assertNotEquals(oldAuthority, data.settings.get("identity.authority"))
        assertTrue(
            data.settings
                .all()
                .keys
                .none { it.startsWith("identity.account.") || it == "identity.guest" },
        )
        // Vor dem Login: weder eigener noch fremder Bestand, auch nicht über einen gleichen Namen.
        assertFalse(visible(matchA))
        assertFalse(visible(matchB))
        assertTrue(data.matches.recent(100).isEmpty())
        assertTrue(data.players.all().none { it.displayName == "Alex" })
        assertTrue(data.accountIdentities.adoptionSources().isEmpty())

        login(profileA)
        val replacement = session.deviceId.value
        assertTrue(replacement !in setOf(deviceA, deviceB, installation), "Neue aktive Gerätequelle für A")
        assertTrue(visible(matchA))
        assertFalse(visible(matchB))
        assertEquals(
            setOf(deviceA, replacement),
            data.accountIdentities
                .ownedSources(profileA)
                .map { it.deviceId }
                .toSet(),
        )

        // A → B → A nach Neustart: B sieht nur B, A bekommt seine neue (nicht die alte) Quelle zurück.
        login(profileB)
        assertTrue(visible(matchB))
        assertFalse(visible(matchA))
        assertTrue(session.deviceId.value !in setOf(deviceB, replacement))
        restart()
        login(profileA)
        assertEquals(replacement, session.deviceId.value)
        assertTrue(visible(matchA))
        assertFalse(visible(matchB))
    }

    @Test
    fun newUidWithIdenticalNameInheritsNothingFromRestoredImage() {
        login(profileA)
        val deviceA = session.deviceId.value
        val matchA = createMatch("Alex")
        restart(rekey = "restored-token")

        val other = login(profileC)
        createMatch("Alex")
        assertFalse(visible(matchA))
        assertTrue(data.accountIdentities.adoptionSources().none { it.deviceId == deviceA })
        assertFails { data.accountIdentities.adopt(other, setOf(deviceA)) }
        assertFails { data.cloudUploads.bindOwnedDevices(other, setOf(deviceA)) }
        assertTrue(
            data.cloudUploads
                .capture(other, 50)
                .events
                .none { it.deviceId == deviceA },
        )
        assertTrue(data.accountIdentities.ownedSources(profileC).none { it.deviceId == deviceA })
    }

    @Test
    fun unboundLegacyImageIsReleasedOnlyLocallyAndStaysUnboundUntilExplicitAdoption() {
        val legacyDevice = session.deviceId.value
        val legacyMatch = createMatch("Alex")
        assertNull(data.accountIdentities.scope, "P1-Altabbild ohne Quellenbeleg")

        restart(rekey = "restored-token")
        assertNotEquals(legacyDevice, session.deviceId.value)
        assertTrue(visible(legacyMatch), "Ausdrückliche lokale Freigabe eines kontolosen Abbilds")
        assertTrue(data.accountIdentities.ownedSources(profileA).isEmpty())

        val account = login(profileA)
        assertFalse(visible(legacyMatch), "Nach dem Login nur noch über ausdrückliche Adoption")
        assertTrue(data.accountIdentities.adoptionSources().any { it.deviceId == legacyDevice })
        session.adoptSources(account, setOf(legacyDevice))
        assertTrue(visible(legacyMatch))
    }

    @Test
    fun boundImageReleasesNothingLocallyAndUnboundGuestSourcesNeedAdoption() {
        login(profileA)
        val matchA = createMatch("Alex")
        session.leaveAccount()
        val guestDevice = session.deviceId.value
        val guestMatch = createMatch("Alex")

        restart(rekey = "restored-token")
        assertFalse(visible(matchA))
        assertFalse(visible(guestMatch), "Kontogebundenes Abbild: keine lokale Freigabe")

        val account = login(profileA)
        assertTrue(visible(matchA))
        assertFalse(visible(guestMatch))
        session.adoptSources(account, setOf(guestDevice))
        assertTrue(visible(guestMatch))
    }

    @Test
    fun lateContextFromBeforeTheRekeyIsRejected() {
        login(profileA)
        val matchA = createMatch("Alex")
        val stale = data.replicas.captureContext()
        rekeyRestoredInstallation(data, PeerKind.PHONE, "restored-token", releaseUnbound = true)
        assertFails { data.replicas.applyAccess(stale, matchA.value, 1, ReplicaAccessLevel.MEMBER) {} }
        assertFails { data.replicas.registerPeer(stale, "old-watch") {} }
    }

    @Test
    fun repeatedRestoreNeverReturnsToAnEarlierDevice() {
        login(profileA)
        val first = session.deviceId.value
        restart(rekey = "first")
        login(profileA)
        val second = session.deviceId.value
        restart(rekey = "second")
        login(profileA)
        val third = session.deviceId.value
        assertEquals(3, setOf(first, second, third).size)
        assertEquals(
            setOf(first, second, third),
            data.accountIdentities
                .ownedSources(profileA)
                .map { it.deviceId }
                .toSet(),
        )
    }

    @Test
    fun restoredWatchImageNeverClonesItsOldSequenceIdentityForTheSameScope() {
        val watch = LocalData(createInMemoryJvmDriver())
        watch.players.ensureSelf("Alex", 0) { PlayerId("watch-self") }
        val old = watch.accountIdentities.select("phone-scope", null, 0, "WATCH") { "old-${++seed}" }
        watch.settings.put("identity.authority", "old-phone")
        watch.settings.put("identity.epoch", "3")
        watch.settings.put("identity.wireScope", "phone-scope")

        rekeyRestoredInstallation(watch, PeerKind.WATCH, "restored-watch", releaseUnbound = true)
        assertNull(watch.settings.get("identity.authority"), "Uhr wartet ohne Autorität auf ihre Kopplung")
        // Dasselbe Handy (gleicher Scope) koppelt erneut: neue Geräte-ID, alte Quelle bleibt nur Historie.
        val again = watch.accountIdentities.select("phone-scope", null, 1, "WATCH") { "new-${++seed}" }
        assertNotEquals(old, again)
        assertNotNull(watch.accountIdentities.localSources().firstOrNull { it.deviceId == old })
    }
}
