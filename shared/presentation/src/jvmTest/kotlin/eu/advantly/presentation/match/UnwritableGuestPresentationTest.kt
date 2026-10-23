package eu.advantly.presentation.match

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Sport
import eu.advantly.data.CloudMetadataKind
import eu.advantly.data.createInMemoryJvmLocalData
import eu.advantly.data.metadataState
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionConfig
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnwritableGuestPresentationTest {
    @Test
    fun startWithGuestFromForeignScopeSkipsDirectoryWriteAndKeepsPlayerId() {
        val data = createInMemoryJvmLocalData()
        val session = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE))
        val store = SessionMatchStore(session)
        val wizard = MatchWizard(store)
        session.activateAccount("alice", data.cloudUploads.activate("alice"))
        data.replicas.activate(data.replicas.captureContext()) {}
        val bea = store.addGuest("Bea")
        val revision = assertNotNull(data.cloud.metadataState(CloudMetadataKind.PLAYER, bea.id.value))
        // Kontowechsel: Die Verzeichniszeile bleibt, ist im neuen Scope aber weder lesbar noch schreibbar.
        session.activateAccount("bob", data.cloudUploads.activate("bob"))
        assertNull(data.players.get(bea.id))
        wizard.selectSport(Sport.TENNIS)
        wizard.selectSurface(null)
        // Veralteter Entwurf (z. B. Access-Entzug bei offenem Wizard) hält den Gast noch mit Namen.
        wizard.pickPlayer(bea)
        val setup = assertNotNull(wizard.finish())
        val match = store.start(setup, StartOptions())
        assertTrue(bea.id in assertNotNull(data.matches.get(match)).lineup.b)
        store.saveFavorite(setup)
        assertEquals(revision, data.cloud.metadataState(CloudMetadataKind.PLAYER, bea.id.value))
        session.activateAccount("alice", data.cloudUploads.activate("alice"))
        assertEquals("Bea", data.players.get(bea.id)?.displayName)
        assertEquals(revision, data.cloud.metadataState(CloudMetadataKind.PLAYER, bea.id.value))
        wizard.close()
        store.close()
    }
}
