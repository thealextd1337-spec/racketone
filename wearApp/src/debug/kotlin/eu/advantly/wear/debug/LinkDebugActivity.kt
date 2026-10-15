package eu.advantly.wear.debug

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import eu.advantly.device.link.LinkState
import eu.advantly.device.link.MainLooperScheduler
import eu.advantly.device.link.createWearDeviceLink
import eu.advantly.device.link.debug.PingPongSession
import eu.advantly.device.link.debug.PingPongStats
import eu.advantly.wear.R

/**
 * Debug-Screen der Uhr (nur Debug-APK, eigener Launcher-Eintrag): Ping-Pong Wear ↔ Phone über `device-link`
 * (Spike S3). Bewusst getrennt vom Scoring-Screen. Anleitung: docs/spikes/s1-s3-watch-sync.md.
 */
class LinkDebugActivity : ComponentActivity() {
    private lateinit var session: PingPongSession
    private var stats by mutableStateOf<PingPongStats?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Feldtest: Display bleibt an, sonst geht die Uhr in den Ambient-Modus und die Activity pausiert
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val scheduler = MainLooperScheduler()
        session = PingPongSession(createWearDeviceLink(this, scheduler), scheduler) { stats = it }
        session.start()
        setContent {
            MaterialTheme {
                WearLinkDebugScreen(stats, onPings = ::startPings, onStop = session::stopPings, onReset = session::reset)
            }
        }
    }

    override fun onDestroy() {
        session.stop()
        super.onDestroy()
    }

    private fun startPings(count: Int) {
        if (!session.startPings(count, PING_INTERVAL_MS)) {
            Toast.makeText(this, R.string.link_no_peer, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val PING_INTERVAL_MS = 250L
    }
}

@Composable
private fun WearLinkDebugScreen(
    stats: PingPongStats?,
    onPings: (Int) -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (stats == null) return@Column
        Text(stateText(stats.state), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { onPings(10) }) { Text(stringResource(R.string.link_send_10)) }
            Button(onClick = { onPings(100) }) { Text(stringResource(R.string.link_send_100)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onStop) { Text(stringResource(R.string.link_stop)) }
            Button(onClick = onReset) { Text(stringResource(R.string.link_reset)) }
        }
        val small = MaterialTheme.typography.bodySmall
        Text(stringResource(R.string.link_sent, stats.pingsSent, stats.pingsPlanned), style = small)
        Text(stringResource(R.string.link_pongs, stats.pongsReceived), style = small)
        Text(stringResource(R.string.link_answered, stats.pingsAnswered), style = small)
        Text(stringResource(R.string.link_queued, stats.pingsQueued), style = small)
        Text(stringResource(R.string.link_lost, stats.lost), style = small)
        Text(stringResource(R.string.link_retries, stats.pingRetries), style = small)
        Text(
            stringResource(R.string.link_rtt, ms(stats.rttP50Ms), ms(stats.rttP95Ms), ms(stats.rttMaxMs)),
            style = small,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.link_peer, stats.target ?: stringResource(R.string.link_peer_none)),
            style = small,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun stateText(state: LinkState): String =
    stringResource(
        when (state) {
            LinkState.CONNECTED -> R.string.link_state_connected
            LinkState.DISCONNECTED -> R.string.link_state_disconnected
            LinkState.UNAVAILABLE -> R.string.link_state_unavailable
        },
    )

@Composable
private fun ms(value: Long?): String = if (value == null) stringResource(R.string.link_none) else stringResource(R.string.link_ms, value)
