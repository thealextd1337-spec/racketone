package eu.advantly.ui.phone.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import eu.advantly.presentation.live.HapticCue
import eu.advantly.ui.phone.latency.percentileOf

@Composable
actual fun rememberHaptics(): Haptics {
    val context = LocalContext.current
    return remember(context) { AndroidHaptics(context.applicationContext) }
}

@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
actual fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** `System.nanoTime()` = CLOCK_MONOTONIC – dieselbe Uhr wie `SystemClock.uptimeMillis()` und Choreographer. */
actual fun monotonicNanos(): Long = System.nanoTime()

/** Compose übernimmt `MotionEvent.eventTime` (Basis `SystemClock.uptimeMillis()`). */
actual fun inputEventAgeMillis(eventUptimeMillis: Long): Long? = (SystemClock.uptimeMillis() - eventUptimeMillis).takeIf { it in 0..2_000 }

@Composable
actual fun displayRefreshRateHz(): Float? {
    val view = LocalView.current
    @Suppress("DEPRECATION")
    return view.display?.refreshRate
}

@Composable
actual fun rememberFrameStats(): FrameStats? {
    val context = LocalContext.current
    val window = remember(context) { context.findActivity()?.window } ?: return null
    val stats = remember { AndroidFrameStats() }
    DisposableEffect(window) {
        val thread = HandlerThread("advantly-frame-metrics").apply { start() }
        val listener =
            Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                val deadline =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) metrics.getMetric(FrameMetrics.DEADLINE) else 16_666_667L
                stats.add(total, deadline)
            }
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        onDispose {
            window.removeOnFrameMetricsAvailableListener(listener)
            thread.quitSafely()
        }
    }
    return stats
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** Frame-Dauern (`FrameMetrics.TOTAL_DURATION`) der letzten 600 Frames; Jank = Dauer über der Frame-Deadline. */
private class AndroidFrameStats : FrameStats {
    private val lock = Any()
    private val durations = ArrayDeque<Double>()
    private var jank = 0
    private var frames = 0

    fun add(
        totalNanos: Long,
        deadlineNanos: Long,
    ) = synchronized(lock) {
        if (durations.size == CAPACITY) durations.removeFirst()
        durations.addLast(totalNanos / 1_000_000.0)
        frames++
        if (totalNanos > deadlineNanos) jank++
    }

    override fun snapshot(): FrameStatsSnapshot =
        synchronized(lock) {
            FrameStatsSnapshot(frames = frames, p95Millis = percentileOf(durations.toList(), 95.0), jankFrames = jank)
        }

    override fun reset() =
        synchronized(lock) {
            durations.clear()
            jank = 0
            frames = 0
        }

    private companion object {
        const val CAPACITY = 600
    }
}

/**
 * Haptik-Muster 04 §5.8 über `VibrationEffect.Composition` (Primitives, API 30+, auf Pixel unterstützt);
 * Fallback: vordefinierte Effekte (API 29) bzw. kurze One-Shots.
 */
private class AndroidHaptics(
    context: Context,
) : Haptics {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val primitives: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator?.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
            ) == true

    override fun perform(cue: HapticCue) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (primitives) composition(cue) else fallback(cue)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            v.vibrate(effect)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun composition(cue: HapticCue): VibrationEffect {
        val c = VibrationEffect.startComposition()
        val click = VibrationEffect.Composition.PRIMITIVE_CLICK
        val tick = VibrationEffect.Composition.PRIMITIVE_TICK
        val fall = VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
        val rise = VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
        val slowRise = VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
        when (cue) {
            // Wir = 1 Klick, Gegner = 2 Klicks (120 ms) – unterscheidbar ohne Hinsehen
            HapticCue.PointUs -> c.addPrimitive(click, 0.8f)

            HapticCue.PointThem -> c.addPrimitive(click, 0.8f).addPrimitive(click, 0.8f, 120)

            HapticCue.Undo -> c.addPrimitive(fall, 1f)

            HapticCue.GameWon -> c.addPrimitive(rise, 0.7f).addPrimitive(click, 1f, 40)

            HapticCue.GameLost -> c.addPrimitive(tick, 1f).addPrimitive(tick, 0.6f, 100)

            HapticCue.SetWon -> c.addPrimitive(slowRise, 1f).addPrimitive(click, 1f, 60)

            HapticCue.SetLost -> c.addPrimitive(slowRise, 0.5f).addPrimitive(tick, 1f, 60)

            HapticCue.ChangeEnds -> c.addPrimitive(tick, 1f).addPrimitive(tick, 1f, 100).addPrimitive(tick, 1f, 100)

            HapticCue.Rejected -> c.addPrimitive(fall, 1f).addPrimitive(fall, 1f, 50)
        }
        return c.compose()
    }

    private fun fallback(cue: HapticCue): VibrationEffect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val id =
                when (cue) {
                    HapticCue.PointUs -> VibrationEffect.EFFECT_CLICK
                    HapticCue.PointThem -> VibrationEffect.EFFECT_DOUBLE_CLICK
                    HapticCue.Undo, HapticCue.Rejected -> VibrationEffect.EFFECT_HEAVY_CLICK
                    else -> VibrationEffect.EFFECT_TICK
                }
            return VibrationEffect.createPredefined(id)
        }
        return when (cue) {
            HapticCue.PointThem -> VibrationEffect.createWaveform(longArrayOf(0, 30, 120, 30), -1)
            else -> VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }
}
