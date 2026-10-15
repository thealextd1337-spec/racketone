package eu.advantly.ui.phone.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import eu.advantly.presentation.live.HapticCue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification
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

/** Liest „Bewegung reduzieren“ und beobachtet `UIAccessibilityReduceMotionStatusDidChangeNotification`. */
@Composable
actual fun rememberReduceMotion(): Boolean {
    val reduce = remember { mutableStateOf(UIAccessibilityIsReduceMotionEnabled()) }
    DisposableEffect(Unit) {
        val token =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = UIAccessibilityReduceMotionStatusDidChangeNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ -> reduce.value = UIAccessibilityIsReduceMotionEnabled() }
        reduce.value = UIAccessibilityIsReduceMotionEnabled()
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(token) }
    }
    return reduce.value
}

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

actual fun formatDateTime(epochMs: Long): String {
    val formatter = platform.Foundation.NSDateFormatter()
    formatter.dateStyle = platform.Foundation.NSDateFormatterShortStyle
    formatter.timeStyle = platform.Foundation.NSDateFormatterShortStyle
    // Designierter Initialisierer: Sekunden seit 2001-01-01 (Referenzdatum) = Unix-Sekunden − 978 307 200
    return formatter.stringFromDate(platform.Foundation.NSDate(timeIntervalSinceReferenceDate = epochMs / 1000.0 - 978_307_200.0))
}

actual fun formatMonth(
    year: Int,
    month: Int,
): String {
    val parser = platform.Foundation.NSDateFormatter()
    parser.locale = platform.Foundation.NSLocale(localeIdentifier = "en_US_POSIX")
    parser.calendar = platform.Foundation.NSCalendar(calendarIdentifier = platform.Foundation.NSCalendarIdentifierGregorian)
    parser.dateFormat = "yyyy-MM-dd"
    val date = parser.dateFromString("$year-${month.toString().padStart(2, '0')}-01") ?: return ""
    val formatter = platform.Foundation.NSDateFormatter()
    formatter.setLocalizedDateFormatFromTemplate("MMMM yyyy")
    return formatter.stringFromDate(date)
}
