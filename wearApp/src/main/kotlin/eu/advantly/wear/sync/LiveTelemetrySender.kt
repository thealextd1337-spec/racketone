package eu.advantly.wear.sync

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.wearable.Wearable
import eu.advantly.core.model.MatchId
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionState
import eu.advantly.presentation.live.LiveTelemetryGate
import eu.advantly.presentation.live.ReceivedTelemetrySubscription
import eu.advantly.sync.protocol.LiveTelemetry
import eu.advantly.sync.protocol.LiveTelemetryCodec
import eu.advantly.sync.protocol.LiveTelemetrySubscription
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.wear.diagnostics.DiagnosticLog
import eu.advantly.wear.live.LiveTelemetryPublisher
import eu.advantly.wear.workout.WorkoutUiState

/** Port für direkte Messages; keine Queue und keine Wiederholung. */
fun interface TelemetryMessageClient {
    fun send(
        nodeId: String,
        path: String,
        payload: ByteArray,
    )
}

/** RAM-Abo des eigenen Handys; Annahme und Alter werden unabhängig vom Versand geprüft. Main-Thread. */
class LiveTelemetrySubscriptions {
    private var current: ReceivedTelemetrySubscription? = null

    fun receive(
        value: LiveTelemetrySubscription,
        nodeId: String,
        state: SessionState,
        nowMs: Long,
    ) {
        if (value.v !in LiveTelemetrySubscription.MIN_VERSION..LiveTelemetrySubscription.CURRENT_VERSION ||
            state.match?.id != value.matchId || state.match?.meta?.watchWorkout != true ||
            !LiveTelemetryGate.compatiblePeer(state.sync.peers, nodeId, PeerKind.PHONE)
        ) {
            return
        }
        current = ReceivedTelemetrySubscription(value.matchId, nodeId, nowMs)
    }

    fun isFresh(
        matchId: MatchId,
        phoneNodeId: String,
        nowMs: Long,
    ): Boolean = current?.isFresh(matchId, phoneNodeId, nowMs) == true

    companion object {
        val Watch = LiveTelemetrySubscriptions()
    }
}

/** Peers kommen aus dem Capability-gepflegten Session-State; kein NodeClient-IPC pro Sensorupdate. */
class LiveTelemetrySender(
    private val state: () -> SessionState,
    private val messages: TelemetryMessageClient,
    private val subscriptions: LiveTelemetrySubscriptions,
    private val nowMs: () -> Long,
) {
    constructor(context: Context, session: MatchSession) : this(
        state = { session.state.value },
        messages = androidMessages(context),
        subscriptions = LiveTelemetrySubscriptions.Watch,
        nowMs = SystemClock::elapsedRealtime,
    )

    private val publisher = LiveTelemetryPublisher()

    fun onUpdate(
        workout: WorkoutUiState,
        exerciseActive: Boolean,
    ) {
        val current = state()
        val match = current.match ?: return
        val phone =
            current.sync.peers.singleOrNull {
                LiveTelemetryGate.compatiblePeer(current.sync.peers, it.peer, PeerKind.PHONE)
            } ?: return
        val now = nowMs()
        if (!subscriptions.isFresh(match.id, phone.peer, now)) return
        val payload = publisher.next(match.id, match.meta.watchWorkout, exerciseActive, true, workout, now) ?: return
        messages.send(phone.peer, LiveTelemetry.MESSAGE_PATH, LiveTelemetryCodec.encode(payload))
    }
}

private fun androidMessages(context: Context): TelemetryMessageClient {
    val client = Wearable.getMessageClient(context.applicationContext)
    return TelemetryMessageClient { nodeId, path, payload ->
        client.sendMessage(nodeId, path, payload).addOnSuccessListener { DiagnosticLog.log("TELEMETRY_SENT") }
        // Fehlschlag verwerfen; ausschließlich das nächste frische Exercise-Update entscheidet neu.
    }
}
