package eu.advantly.ui.phone.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import eu.advantly.presentation.live.HapticCue
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIApplication
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UIScreen
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

@Composable
actual fun rememberHaptics(): Haptics = remember { IosHaptics() }

@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    DisposableEffect(enabled) {
        UIApplication.sharedApplication.idleTimerDisabled = enabled
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}

@Composable
actual fun rememberReduceMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }

/** `systemUptime` – dieselbe Basis wie `UIEvent.timestamp` und `CACurrentMediaTime()` (CADisplayLink). */
actual fun monotonicNanos(): Long = (NSProcessInfo.processInfo.systemUptime * 1_000_000_000.0).toLong()

/**
 * Annahme: CMP füllt `uptimeMillis` aus `UIEvent.timestamp` (Sekunden seit Systemstart). Stimmt das nicht,
 * ist das Alter unplausibel → `null` → das Overlay zeigt „Uhr: Handler (Fallback)“.
 */
actual fun inputEventAgeMillis(eventUptimeMillis: Long): Long? {
    val now = (NSProcessInfo.processInfo.systemUptime * 1_000.0).toLong()
    return (now - eventUptimeMillis).takeIf { it in 0..2_000 }
}

@Composable
actual fun displayRefreshRateHz(): Float? = remember { UIScreen.mainScreen.maximumFramesPerSecond.toFloat() }

/** iOS: keine FrameMetrics-Entsprechung über CMP; Frame-Zeitpunkte kommen aus der CADisplayLink-getriebenen Frame-Clock. */
@Composable
actual fun rememberFrameStats(): FrameStats? = null

/** Haptik-Muster 04 §5.8 (Spalte Handy) über UIFeedbackGenerator. */
private class IosHaptics : Haptics {
    private val light = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    private val medium = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
    private val heavy = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy)
    private val notification = UINotificationFeedbackGenerator()

    init {
        light.prepare()
        notification.prepare()
    }

    override fun perform(cue: HapticCue) {
        when (cue) {
            HapticCue.PointUs -> {
                light.impactOccurred()
            }

            HapticCue.PointThem -> {
                light.impactOccurred()
                after(120) { light.impactOccurred() }
            }

            HapticCue.Undo -> {
                notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
            }

            HapticCue.GameWon, HapticCue.GameLost -> {
                notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
            }

            HapticCue.SetWon, HapticCue.SetLost -> {
                notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
                after(180) { heavy.impactOccurred() }
            }

            HapticCue.ChangeEnds -> {
                medium.impactOccurred()
                after(100) { medium.impactOccurred() }
                after(200) { medium.impactOccurred() }
            }

            HapticCue.Rejected -> {
                notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
            }
        }
        light.prepare()
    }

    private fun after(
        millis: Long,
        block: () -> Unit,
    ) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, millis * 1_000_000), dispatch_get_main_queue(), block)
    }
}
