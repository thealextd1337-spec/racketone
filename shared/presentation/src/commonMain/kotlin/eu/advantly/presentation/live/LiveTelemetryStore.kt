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
    override fun toString(): String = "ReceivedLiveTelemetry(<redacted>)"

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
    private val mutableRecent = MutableStateFlow<RecentLiveTelemetry?>(null)
    val recent: StateFlow<RecentLiveTelemetry?> = mutableRecent.asStateFlow()

    fun receive(value: LiveTelemetry) {
        val now = clock.nowMs()
        mutable.value = if (value.heartRateBpm != null || value.steps != null) ReceivedLiveTelemetry(value, now) else null
        if (mutable.value != null) mutableRecent.value = RecentLiveTelemetry(value.matchId, now)
    }

    fun forMatch(
        matchId: MatchId,
        workoutEnabled: Boolean,
    ): LiveTelemetry? = mutable.value?.forMatch(matchId, workoutEnabled, clock.nowMs())

    /** Restzeit für einen UI-Timer, auch wenn der Screen erst nach dem Empfang geöffnet wird. */
    fun remainingLifetimeMs(): Long =
        mutable.value?.let { (STALE_AFTER_MS - (clock.nowMs() - it.receivedAtMs)).coerceIn(0, STALE_AFTER_MS) } ?: 0

    fun recentlyReceived(matchId: MatchId): Boolean =
        mutableRecent.value?.let { it.matchId == matchId && clock.nowMs() - it.receivedAtMs in 0 until RECENT_FOR_MS } == true

    fun remainingVisibilityMs(): Long =
        mutableRecent.value?.let { (RECENT_FOR_MS - (clock.nowMs() - it.receivedAtMs)).coerceIn(0, RECENT_FOR_MS) } ?: 0

    /** „Gar nicht“ (P2-J): Wert und Empfangsmerker sofort verwerfen, nicht erst nach Ablauf. */
    fun clear() {
        mutable.value = null
        mutableRecent.value = null
    }

    /** Auch ohne offenen Live-Screen nach Ablauf wirklich aus dem RAM-Halter entfernen. */
    fun expire() {
        val current = mutable.value
        if (current != null && current.forMatch(current.value.matchId, true, clock.nowMs()) == null) mutable.value = null
        if (remainingVisibilityMs() == 0L) mutableRecent.value = null
    }

    companion object {
        const val STALE_AFTER_MS: Long = 30_000
        const val RECENT_FOR_MS: Long = 120_000
        val Phone: LiveTelemetryStore = LiveTelemetryStore(liveTelemetryClock())
    }
}

/** Für den Stale-Hinweis bleiben nur Match-ID und Empfangszeit, keine Gesundheitswerte. */
data class RecentLiveTelemetry(
    val matchId: MatchId,
    val receivedAtMs: Long,
)

/** Plattformuhr für die Phone-Instanz; Android schließt Tiefschlaf in die Ablaufzeit ein. */
internal expect fun liveTelemetryClock(): MonotonicClock
