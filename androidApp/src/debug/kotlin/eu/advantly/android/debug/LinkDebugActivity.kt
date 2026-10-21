package eu.advantly.android.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.advantly.android.R
import eu.advantly.android.showInstallationError
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.device.link.LinkState
import eu.advantly.device.link.LinkStats
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.match.session.AndroidSessionAccess
import eu.advantly.match.session.MatchSession
import eu.advantly.sync.protocol.PeerKind
import kotlinx.coroutines.delay

/**
 * Debug-Screen (nur Debug-APK, eigener Launcher-Eintrag): **nur Anzeige** von Zustand und Zählern des Links der
 * prozessweiten Match-Session (W1-I). Er öffnet keinen eigenen Link – ein zweiter Link würde Nachrichten und Acks der
 * Session stören. Der frühere Ping-Pong-Feldtest (Spike S3) ist damit entfallen.
 */
class LinkDebugActivity : ComponentActivity() {
    private var access: AndroidSessionAccess? = null

    override fun onDestroy() {
        access?.close()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session =
            AndroidInstallationGuard.storage(this) { AndroidMatchSessions.get(applicationContext, PeerKind.PHONE) }
                ?: run {
                    showInstallationError()
                    return
                }
        access =
            AndroidSessionAccess(this, session) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { if (!isDestroyed) showInstallationError() }
            }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { SessionLinkScreen(session) }
            }
        }
    }
}

@Composable
private fun SessionLinkScreen(session: MatchSession) {
    val state by session.state.collectAsState()
    // Link-Zähler sind kein Flow: jede Sekunde neu lesen
    var stats by remember { mutableStateOf<LinkStats?>(null) }
    LaunchedEffect(session) {
        while (true) {
            stats = session.linkStats
            delay(STATS_REFRESH_MS)
        }
    }
    val sync = state.sync
    val none = stringResource(R.string.link_none)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.link_debug_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(
                when (sync.link) {
                    LinkState.CONNECTED -> R.string.link_state_connected
                    LinkState.DISCONNECTED -> R.string.link_state_disconnected
                    LinkState.UNAVAILABLE -> R.string.link_state_unavailable
                },
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        if (sync.peers.isEmpty()) Text(stringResource(R.string.link_peer, stringResource(R.string.link_peer_none)))
        sync.peers.forEach { peer ->
            Text(
                stringResource(R.string.link_peer, listOfNotNull(peer.peer, peer.kind?.name, peer.compatibility?.name).joinToString(" · ")),
            )
        }
        Line(R.string.link_pending, sync.pendingToPeers.toString())
        Line(R.string.link_last_event, sync.lastEventReceivedAtMs?.toString() ?: none)
        Line(R.string.link_last_sync, sync.lastSyncAtMs?.toString() ?: none)
        Line(R.string.link_scorer_connected, sync.scorerConnected.toString())
        stats?.let { s ->
            Line(R.string.link_frames, "${s.framesSent} / ${s.acked} / ${s.retries}")
            Line(R.string.link_queue, "${s.queued} / ${s.failed}")
            Line(R.string.link_delivered, "${s.delivered} / ${s.duplicatesDropped}")
        }
    }
}

@Composable
private fun Line(
    label: Int,
    value: String,
) {
    Text(stringResource(label, value))
}

private const val STATS_REFRESH_MS = 1_000L
