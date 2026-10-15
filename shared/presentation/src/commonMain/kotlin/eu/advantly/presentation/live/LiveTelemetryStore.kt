package eu.advantly.presentation.live

import eu.advantly.core.model.MatchId
import eu.advantly.presentation.match.MonotonicClock
import eu.advantly.sync.protocol.LiveTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ein einzelner RAM-Wert mit Empfangszeit der lokalen monotonen Uhr (keine geräteübergreifende Wanduhr). */
data class ReceivedLiveTelemetry(
    val value: LiveTelemetry,
    val receivedAtMs: Long,
) {
    fun forMatch(
        matchId: MatchId,
        workoutEnabled: Boolean,
        nowMs: Long,
    ): LiveTelemetry? =
        value.takeIf {
            workoutEnabled && it.matchId == matchId && nowMs - receivedAtMs in 0 until LiveTelemetryStore.STALE_AFTER_MS
        }
}

/** Flüchtiger Phone-Eingang, ausschließlich im Prozessspeicher. Alle Aufrufe auf dem UI-Thread. */
class LiveTelemetryStore(
    private val clock: MonotonicClock = MonotonicClock.System,
) {
    private val mutable = MutableStateFlow<ReceivedLiveTelemetry?>(null)
    val state: StateFlow<ReceivedLiveTelemetry?> = mutable.asStateFlow()

    fun receive(value: LiveTelemetry) {
        mutable.value = ReceivedLiveTelemetry(value, clock.nowMs())
    }

    fun forMatch(
        matchId: MatchId,
        workoutEnabled: Boolean,
    ): LiveTelemetry? = mutable.value?.forMatch(matchId, workoutEnabled, clock.nowMs())

    /** Restzeit für einen UI-Timer, auch wenn der Screen erst nach dem Empfang geöffnet wird. */
    fun remainingLifetimeMs(): Long =
        mutable.value?.let { (STALE_AFTER_MS - (clock.nowMs() - it.receivedAtMs)).coerceIn(0, STALE_AFTER_MS) } ?: 0

    /** Auch ohne offenen Live-Screen nach Ablauf wirklich aus dem RAM-Halter entfernen. */
    fun expire() {
        val current = mutable.value ?: return
        if (current.forMatch(current.value.matchId, true, clock.nowMs()) == null) mutable.value = null
    }

    companion object {
        const val STALE_AFTER_MS: Long = 30_000
        val Phone: LiveTelemetryStore = LiveTelemetryStore(liveTelemetryClock())
    }
}

/** Plattformuhr für die Phone-Instanz; Android schließt Tiefschlaf in die Ablaufzeit ein. */
internal expect fun liveTelemetryClock(): MonotonicClock
