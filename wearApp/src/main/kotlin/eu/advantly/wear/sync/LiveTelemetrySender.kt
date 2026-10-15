package eu.advantly.wear.sync

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.wearable.Wearable
import eu.advantly.match.session.MatchSession
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.LiveTelemetry
import eu.advantly.sync.protocol.LiveTelemetryCodec
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.wear.diagnostics.DiagnosticLog
import eu.advantly.wear.diagnostics.PeriodicTrigger
import eu.advantly.wear.live.LiveTelemetryPublisher
import eu.advantly.wear.live.telemetryActive
import eu.advantly.wear.workout.WorkoutStateHolder
import eu.advantly.wear.workout.WorkoutUiState

/** Nur direkte Nachrichten an das eigene erreichbare Handy; niemals DataItems, Ack/Retry oder Offline-Queue. */
class LiveTelemetrySender(
    context: Context,
    private val session: MatchSession,
) {
    private val nodeClient = Wearable.getNodeClient(context.applicationContext)
    private val messageClient = Wearable.getMessageClient(context.applicationContext)
    private val publisher = LiveTelemetryPublisher()
    private val discoveryThrottle = PeriodicTrigger(LiveTelemetryPublisher.INTERVAL_MS)
    private var exerciseActive = false

    fun onUpdate(
        workout: WorkoutUiState,
        exerciseActive: Boolean,
    ) {
        this.exerciseActive = exerciseActive
        val state = session.state.value
        val match = state.match ?: return
        if (!exerciseActive || !workout.telemetryActive || !match.meta.watchWorkout) return
        val phone =
            state.sync.peers.singleOrNull { it.kind == PeerKind.PHONE && it.compatibility == CompatibilityStatus.COMPATIBLE } ?: return
        val requestedAtMs = SystemClock.elapsedRealtime()
        if (!discoveryThrottle.shouldFire(requestedAtMs)) return
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            val nowMs = SystemClock.elapsedRealtime()
            // Veraltete Abfragen verwerfen; nach der Abfrage nur die jetzt aktuellen Werte lesen, nichts puffern.
            if (nowMs - requestedAtMs >= LiveTelemetryPublisher.INTERVAL_MS) return@addOnSuccessListener
            val current = session.state.value
            if (current.match?.id != match.id || current.sync.peers.none { it == phone }) return@addOnSuccessListener
            val payload =
                publisher.next(
                    current.match?.id,
                    current.match?.meta?.watchWorkout == true,
                    this.exerciseActive,
                    nodes.any { it.id == phone.peer && it.isNearby },
                    WorkoutStateHolder.state.value,
                    nowMs,
                ) ?: return@addOnSuccessListener
            messageClient
                .sendMessage(phone.peer, LiveTelemetry.MESSAGE_PATH, LiveTelemetryCodec.encode(payload))
                .addOnSuccessListener { DiagnosticLog.log("TELEMETRY_SENT") }
            // Fehlschlag absichtlich verwerfen; die nächste frische Exercise-Aktualisierung entscheidet neu.
        }
    }
}
