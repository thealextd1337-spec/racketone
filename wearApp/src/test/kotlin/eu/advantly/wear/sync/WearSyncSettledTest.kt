package eu.advantly.wear.sync

import eu.advantly.core.model.DeviceId
import eu.advantly.device.link.LinkState
import eu.advantly.match.session.PeerInfo
import eu.advantly.match.session.SyncIndicator
import eu.advantly.match.session.SyncStatus
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.PeerKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearSyncSettledTest {
    private val sync =
        SyncStatus(
            link = LinkState.CONNECTED,
            peers = listOf(PeerInfo("node", DeviceId("phone"), PeerKind.PHONE, CompatibilityStatus.COMPATIBLE)),
            indicator = SyncIndicator.CONNECTED,
            scorerConnected = true,
            pendingToPeers = 0,
            lastEventReceivedAtMs = null,
            lastSyncAtMs = 1,
        )

    @Test
    fun lifecycleBacklogKeepsSyncUnsettled() {
        assertTrue(WearSessions.isSettled(sync))
        assertFalse(WearSessions.isSettled(sync.copy(pendingToPeers = 1)))
        assertFalse(WearSessions.isSettled(sync.copy(lifecyclePendingToPeers = 1)))
        assertFalse(WearSessions.isSettled(sync.copy(lifecycleBlocked = true)))
        assertFalse(WearSessions.isSettled(sync.copy(lastSyncAtMs = null)))
        assertFalse(WearSessions.isSettled(sync.copy(peers = emptyList())))
    }
}
