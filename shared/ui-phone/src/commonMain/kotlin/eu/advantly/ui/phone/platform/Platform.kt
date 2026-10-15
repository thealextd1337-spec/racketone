package eu.advantly.ui.phone.platform

import androidx.compose.runtime.Composable
import eu.advantly.presentation.live.HapticCue

/** Haptik pro Ereignis (04 §5.8). Android: Vibrator-Primitives, iOS: UIFeedbackGenerator, Desktop: still. */
fun interface Haptics {
    fun perform(cue: HapticCue)

    companion object {
        val None = Haptics { }
    }
}

@Composable
expect fun rememberHaptics(): Haptics

/** Bildschirm wach halten, solange [enabled] (04 §5.3). */
@Composable
expect fun KeepScreenOn(enabled: Boolean)

/** System-Einstellung „Bewegung reduzieren“ (iOS) bzw. „Animationen entfernen“ (Android). */
@Composable
expect fun rememberReduceMotion(): Boolean

/** Monotone Uhr in Nanosekunden – dieselbe Basis wie [inputEventAgeMillis]. */
expect fun monotonicNanos(): Long

/**
 * Alter eines Pointer-Ereignisses in ms, berechnet aus `PointerInputChange.uptimeMillis`.
 * Die Zeitbasis dieses Werts ist plattformabhängig (Android: `SystemClock.uptimeMillis`,
 * iOS: `UIEvent.timestamp`, Desktop: `MouseEvent.getWhen`). `null`, wenn unplausibel.
 */
expect fun inputEventAgeMillis(eventUptimeMillis: Long): Long?

/** Bildwiederholrate des Displays (Hz), falls ermittelbar. */
@Composable
expect fun displayRefreshRateHz(): Float?

/** Frame-Dauer-Statistik der Plattform (Android: `FrameMetrics`); `null`, wo nicht verfügbar. */
@Composable
expect fun rememberFrameStats(): FrameStats?

interface FrameStats {
    fun snapshot(): FrameStatsSnapshot

    fun reset()
}

data class FrameStatsSnapshot(
    val frames: Int,
    val p95Millis: Double?,
    val jankFrames: Int,
)
