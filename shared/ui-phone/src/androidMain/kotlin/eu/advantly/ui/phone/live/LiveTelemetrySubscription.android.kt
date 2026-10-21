package eu.advantly.ui.phone.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import eu.advantly.core.model.MatchId
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.presentation.live.LiveTelemetryGate
import eu.advantly.sync.protocol.LiveTelemetrySubscription
import eu.advantly.sync.protocol.PeerKind
import kotlinx.coroutines.delay

@Composable
internal actual fun LiveTelemetrySubscription(
    matchId: MatchId,
    enabled: Boolean,
): Boolean {
    val session = AndroidMatchSessions.current() ?: return false
    val state by session.state.collectAsState()
    val watch = state.sync.peers.singleOrNull { LiveTelemetryGate.compatiblePeer(state.sync.peers, it.peer, PeerKind.WATCH) }
    val context = LocalContext.current.applicationContext
    val subscription =
        remember(session, context, matchId, watch?.peer) {
            watch?.let { AndroidTelemetrySubscription(context, session, matchId, it.peer) }
        }
    DisposableEffect(subscription) { onDispose { subscription?.close() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(matchId, enabled, subscription, lifecycle) {
        if (!enabled || subscription == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (!subscription.keepAlive()) return@repeatOnLifecycle
                delay(LiveTelemetrySubscription.KEEPALIVE_MS)
            }
        }
    }
    return watch != null && AndroidMatchSessions.current() === session
}
