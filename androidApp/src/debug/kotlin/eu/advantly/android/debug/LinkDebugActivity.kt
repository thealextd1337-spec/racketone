package eu.advantly.android.debug

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.advantly.android.R
import eu.advantly.device.link.LinkState
import eu.advantly.device.link.MainLooperScheduler
import eu.advantly.device.link.createWearDeviceLink
import eu.advantly.device.link.debug.PingPongSession
import eu.advantly.device.link.debug.PingPongStats

/**
 * Debug-Screen (nur Debug-APK, eigener Launcher-Eintrag): Ping-Pong Phone ↔ Wear über `device-link`
 * (Spike S3). Anleitung: docs/spikes/s1-s3-watch-sync.md.
 */
class LinkDebugActivity : ComponentActivity() {
    private lateinit var session: PingPongSession
    private var stats by mutableStateOf<PingPongStats?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Feldtest: Display bleibt an, sonst pausiert die Activity und die Listener sind weg
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val scheduler = MainLooperScheduler()
        session = PingPongSession(createWearDeviceLink(this, scheduler), scheduler) { stats = it }
        session.start()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LinkDebugScreen(
                        stats = stats,
                        onPings = ::startPings,
                        onStop = session::stopPings,
                        onReset = session::reset,
                    )
                }
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
private fun LinkDebugScreen(
    stats: PingPongStats?,
    onPings: (Int) -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.link_debug_title), style = MaterialTheme.typography.titleLarge)
        if (stats == null) return@Column
        Text(stateText(stats.state), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.link_peer, stats.target ?: stringResource(R.string.link_peer_none)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onPings(10) }) { Text(stringResource(R.string.link_send_10)) }
            Button(onClick = { onPings(100) }) { Text(stringResource(R.string.link_send_100)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onStop) { Text(stringResource(R.string.link_stop)) }
            OutlinedButton(onClick = onReset) { Text(stringResource(R.string.link_reset)) }
        }
        if (stats.running) Text(stringResource(R.string.link_running))
        Text(stringResource(R.string.link_sent, stats.pingsSent, stats.pingsPlanned))
        Text(stringResource(R.string.link_acked, stats.pingsAcked, stats.inFlight))
        Text(stringResource(R.string.link_pongs, stats.pongsReceived))
        Text(stringResource(R.string.link_answered, stats.pingsAnswered))
        Text(stringResource(R.string.link_queued, stats.pingsQueued))
        Text(stringResource(R.string.link_lost, stats.lost))
        Text(stringResource(R.string.link_retries, stats.link.retries))
        Text(stringResource(R.string.link_duplicates, stats.link.duplicatesDropped))
        Text(stringResource(R.string.link_rtt, ms(stats.rttP50Ms), ms(stats.rttP95Ms), ms(stats.rttMaxMs)))
        Text(stringResource(R.string.link_ack_latency, ms(stats.ackP50Ms), ms(stats.ackP95Ms)))
        Text(stringResource(R.string.link_go_rtt, goText(stats.rttP95Ms?.let { it < GO_RTT_P95_MS })))
        Text(stringResource(R.string.link_go_loss, goText(if (stats.pingsSent == 0) null else stats.lost == 0)))
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

@Composable
private fun goText(met: Boolean?): String =
    stringResource(
        when (met) {
            null -> R.string.link_go_open
            true -> R.string.link_go_met
            false -> R.string.link_go_not_met
        },
    )

/** Go-Kriterium Spike S3 (docs/spikes/s1-s3-watch-sync.md). */
private const val GO_RTT_P95_MS = 500L
