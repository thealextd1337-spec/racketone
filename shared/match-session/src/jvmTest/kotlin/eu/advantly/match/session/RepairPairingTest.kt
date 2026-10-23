package eu.advantly.match.session

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.Presets
import eu.advantly.data.CloudPullMatch
import eu.advantly.data.CloudPullMetadataPage
import eu.advantly.data.ReplicaAccessLevel
import eu.advantly.sync.protocol.AccessState
import eu.advantly.sync.protocol.SyncCodec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recovery-Paket Punkt 4: ausdrückliche Neukopplung unter aktiver V4-Zustellung. Alte Autorität, Nonces und
 * Uhr-Registrierungen werden ungültig; Quellen, Scopes und Konten bleiben, wie sie sind.
 */
class RepairPairingTest {
    private val pair = LifecyclePair(3..4)

    @AfterTest
    fun tearDown() = pair.close()

    private fun sharedMatch(): MatchId {
        pair.activate("alice")
        val self =
            pair.phoneData.players
                .self()!!
                .id
        val match = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(self), listOf(PlayerId("guest")), self))
        pair.settle()
        val upload = pair.phoneData.cloudUploads.capture(pair.token!!, 100)
        pair.phoneData.cloudUploads.acknowledge(upload, upload.events.mapTo(mutableSetOf()) { it.id }, metadata = upload.metadataRevisions)
        return match
    }

    /** Katalogseite des Pulls am Handy (Access bei [generation]); die Zustellung an die Uhr folgt daraus. */
    private fun catalog(
        match: MatchId,
        version: Long,
        generation: Long,
    ) {
        val data = pair.phoneData
        val profile = data.accountIdentities.profile!!
        val record = checkNotNull(data.matches.get(match))
        val run = data.cloudPulls.beginRun(profile) {}.runSerial
        val entry = CloudPullMatch(match.value, version, """{"version":$version}""", "member", generation, 0, false, record)
        val page = CloudPullMetadataPage(listOf(entry), emptyList(), emptyList(), version, false, generation)
        if (!data.cloudPulls.commitMetadata(profile, run, data.cloudPulls.progress(profile).cursor, page) {}) {
            assertTrue(data.cloudPulls.commitMetadata(profile, run, data.cloudPulls.progress(profile).cursor, page) {})
        }
    }

    private fun watchGeneration(match: MatchId): Long? =
        pair.watchData.replicas
            .access(pair.watchData.replicas.captureContext(), match.value)
            ?.takeIf { it.access == ReplicaAccessLevel.MEMBER }
            ?.generation

    private fun phoneDeliveries(): Int {
        val ctx = pair.phoneData.replicas.captureContext()
        return pair.phoneData.replicas
            .pendingDeliveries(ctx, pair.watch.deviceId.value, null, 50)
            .deliveries.size
    }

    @Test
    fun phoneRepairRetiresTheWatchUntilItRepairsItselfWithoutRehangingSources() {
        val match = sharedMatch()
        catalog(match, 1, 1)
        pair.settle()
        assertEquals(1L, watchGeneration(match))
        val watchDevice = pair.watch.deviceId.value
        val watchScope = pair.watchData.accountIdentities.scope
        val owned = pair.phoneData.accountIdentities.ownedSources("alice")
        val oldAuthority = pair.phoneData.settings.get("identity.authority")

        pair.phone.repairPairing()
        assertNotEquals(oldAuthority, pair.phoneData.settings.get("identity.authority"))
        pair.settle()
        assertTrue(pair.watch.state.value.pairingConflict, "Uhr lehnt die neue Autorität ab und meldet es")
        catalog(match, 2, 2)
        pair.settle()
        assertEquals(1L, watchGeneration(match), "Ohne Neukopplung der Uhr keine Zustellung")
        assertEquals(0, phoneDeliveries(), "Keine Uhr bleibt Empfänger: G 2 wird für die alte Kopplung nicht eingereiht")
        assertEquals(0, pair.phone.state.value.sync.lifecyclePendingToPeers)

        pair.watch.repairPairing()
        pair.settle()
        assertFalse(pair.watch.state.value.pairingConflict)
        assertEquals(2L, watchGeneration(match), "Nach bestätigtem Hello neu registriert und zugestellt")
        assertEquals(0, pair.phone.state.value.sync.lifecyclePendingToPeers)
        assertEquals(watchDevice, pair.watch.deviceId.value, "Dieselbe Uhr behält ihre eigene Sequenzidentität")
        assertEquals(watchScope, pair.watchData.accountIdentities.scope)
        assertEquals(owned, pair.phoneData.accountIdentities.ownedSources("alice"), "Keine Quelle umgehängt")
    }

    @Test
    fun watchRepairRejectsEnvelopesOfTheOldPairing() {
        val match = sharedMatch()
        catalog(match, 1, 1)
        pair.settle()
        pair.network.setConnected("watch", false)
        val before = pair.phoneLink.sent.size
        catalog(match, 2, 2)
        pair.settle()
        val stale =
            pair.phoneLink.sent
                .drop(before)
                .first { (SyncCodec.decode(it, 3..4) as? eu.advantly.sync.protocol.DecodeResult.Ok)?.message is AccessState }

        pair.watch.repairPairing()
        assertNull(pair.watchData.settings.get("identity.authority"))
        pair.watchLink.replay("phone", stale)
        assertEquals(1L, watchGeneration(match), "Alter Envelope nach der Neukopplung wirkungslos")

        pair.network.setConnected("watch", true)
        pair.settle()
        assertEquals(2L, watchGeneration(match), "Erst die neu bestätigte Kopplung stellt zu")
        assertEquals(pair.phoneData.settings.get("identity.authority"), pair.watchData.settings.get("identity.authority"))
    }

    @Test
    fun repairWithoutAnyPairingChangesNothing() {
        val solo = LifecyclePair(3..4)
        try {
            val settings = solo.phoneData.settings.all()
            solo.phone.repairPairing()
            assertEquals(settings, solo.phoneData.settings.all())
        } finally {
            solo.close()
        }
    }
}
