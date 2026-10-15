package eu.advantly.wear.sync

import eu.advantly.core.model.DeviceId
import eu.advantly.device.link.LinkState
import eu.advantly.device.link.LinkStats
import eu.advantly.match.session.PeerInfo
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.PeerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionDiagnosticsTest {
    private val phoneNoHello = PeerInfo("node-1", null, null, null)
    private val phone = PeerInfo("node-1", DeviceId("phone"), PeerKind.PHONE, CompatibilityStatus.COMPATIBLE)

    @Test
    fun logsLinkPeersHelloTrafficAndErrors() {
        val start = ConnectionSnapshot(LinkState.DISCONNECTED, emptyList(), LinkStats(), 0)
        val up = ConnectionSnapshot(LinkState.CONNECTED, listOf(phoneNoHello), LinkStats(framesSent = 2), 0)
        val hello = up.copy(peers = listOf(phone), stats = LinkStats(framesSent = 3, acked = 2, delivered = 1))
        val error = hello.copy(stats = hello.stats!!.copy(transportErrors = 1, failed = 1), pendingToPeers = 4)
        val down = error.copy(link = LinkState.DISCONNECTED, peers = emptyList())

        assertEquals(listOf("LINK_STATE", "SYNC_PENDING"), ConnectionDiagnostics.diff(null, start).map { it.first })
        assertEquals(listOf("LINK_STATE", "PEER_UP", "LINK_TRAFFIC"), ConnectionDiagnostics.diff(start, up).map { it.first })
        val helloLines = ConnectionDiagnostics.diff(up, hello)
        assertEquals(listOf("PEER_HELLO", "LINK_TRAFFIC"), helloLines.map { it.first })
        assertEquals("PHONE", helloLines.first().second["kind"])
        assertEquals(1, helloLines[1].second["received"])
        assertEquals(listOf("LINK_ERROR", "SYNC_PENDING"), ConnectionDiagnostics.diff(hello, error).map { it.first })
        assertEquals(listOf("LINK_STATE", "PEER_DOWN"), ConnectionDiagnostics.diff(error, down).map { it.first })
        assertTrue("keine Änderung, keine Zeile", ConnectionDiagnostics.diff(down, down).isEmpty())
    }
}
