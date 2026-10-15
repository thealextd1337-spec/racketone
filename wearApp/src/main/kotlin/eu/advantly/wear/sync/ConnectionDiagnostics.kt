package eu.advantly.wear.sync

import eu.advantly.device.link.LinkState
import eu.advantly.device.link.LinkStats
import eu.advantly.match.session.PeerInfo

/** Verbindungsrelevanter Ausschnitt des Session-Zustands (ohne Spielstand, ohne Gesundheitsdaten). */
data class ConnectionSnapshot(
    val link: LinkState,
    val peers: List<PeerInfo>,
    val stats: LinkStats?,
    val pendingToPeers: Int,
)

/**
 * Debug-Diagnose der Verbindung zum Handy (Feldtest Pixel Watch 3): leitet aus zwei aufeinanderfolgenden
 * [ConnectionSnapshot]s Log-Zeilen ab – Link-Zustand, Gegenstelle da/weg, `Hello` angekommen (Gerät, Art,
 * Kompatibilität), gesendete/bestätigte/empfangene Nachrichten und Fehler als Zuwachs, Rückstand. Rein, JVM-getestet;
 * geschrieben wird nur im Debug-Build ([eu.advantly.wear.diagnostics.DiagnosticLog]).
 */
object ConnectionDiagnostics {
    fun diff(
        before: ConnectionSnapshot?,
        after: ConnectionSnapshot,
    ): List<Pair<String, Map<String, Any?>>> =
        buildList {
            if (before?.link != after.link) add("LINK_STATE" to mapOf("state" to after.link.name))
            val beforePeers = before?.peers.orEmpty().associateBy { it.peer }
            val afterPeers = after.peers.associateBy { it.peer }
            (afterPeers.keys - beforePeers.keys).forEach { add("PEER_UP" to mapOf("node" to it)) }
            (beforePeers.keys - afterPeers.keys).forEach { add("PEER_DOWN" to mapOf("node" to it)) }
            afterPeers.values
                .filter { it.compatibility != null && beforePeers[it.peer]?.compatibility == null }
                .forEach {
                    add(
                        "PEER_HELLO" to
                            mapOf(
                                "node" to it.peer,
                                "device" to it.deviceId?.value,
                                "kind" to it.kind?.name,
                                "compat" to it.compatibility?.name,
                            ),
                    )
                }
            val s0 = before?.stats ?: LinkStats()
            val s1 = after.stats
            if (s1 != null) {
                val sent = s1.framesSent - s0.framesSent
                val acked = s1.acked - s0.acked
                val received = s1.delivered - s0.delivered
                if (sent > 0 || acked > 0 || received > 0) {
                    add(
                        "LINK_TRAFFIC" to
                            mapOf(
                                "sent" to sent,
                                "retries" to s1.retries - s0.retries,
                                "acked" to acked,
                                "queued" to s1.queued - s0.queued,
                                "received" to received,
                                "duplicates" to s1.duplicatesDropped - s0.duplicatesDropped,
                            ),
                    )
                }
                val failed = s1.failed - s0.failed
                val transport = s1.transportErrors - s0.transportErrors
                val malformed = s1.malformedFrames - s0.malformedFrames
                if (failed > 0 || transport > 0 || malformed > 0) {
                    add("LINK_ERROR" to mapOf("failed" to failed, "transportErrors" to transport, "malformed" to malformed))
                }
            }
            if (before?.pendingToPeers != after.pendingToPeers) add("SYNC_PENDING" to mapOf("pending" to after.pendingToPeers))
        }
}
