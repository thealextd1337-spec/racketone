package eu.advantly.ui.phone.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException

/** Desktop-Preview: keine Haptik. */
@Composable
actual fun rememberHaptics(): Haptics = Haptics.None

@Composable
actual fun KeepScreenOn(enabled: Boolean) = Unit

@Composable
actual fun rememberReduceMotion(): Boolean = false

actual fun monotonicNanos(): Long = System.nanoTime()

/** CMP Desktop übernimmt `MouseEvent.getWhen()` (Basis `System.currentTimeMillis()`). */
actual fun inputEventAgeMillis(eventUptimeMillis: Long): Long? = (System.currentTimeMillis() - eventUptimeMillis).takeIf { it in 0..2_000 }

@Composable
actual fun displayRefreshRateHz(): Float? =
    remember {
        try {
            GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .defaultScreenDevice.displayMode.refreshRate
                .takeIf { it > 0 }
                ?.toFloat()
        } catch (_: HeadlessException) {
            null
        }
    }

@Composable
actual fun rememberFrameStats(): FrameStats? = null

actual fun formatDateTime(epochMs: Long): String =
    java.time.format.DateTimeFormatter
        .ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
        .withLocale(java.util.Locale.getDefault())
        .format(
            java.time.Instant
                .ofEpochMilli(epochMs)
                .atZone(java.time.ZoneId.systemDefault()),
        )

actual fun formatMonth(
    year: Int,
    month: Int,
): String =
    java.time.format.DateTimeFormatter
        .ofPattern("LLLL yyyy", java.util.Locale.getDefault())
        .format(java.time.YearMonth.of(year, month))

actual fun formatBuildDate(epochMs: Long): String =
    java.time.format.DateTimeFormatter
        .ofPattern(
            if (java.util.Locale
                    .getDefault()
                    .language == "de"
            ) {
                "dd.MM. HH:mm"
            } else {
                "MMM d, HH:mm"
            },
            java.util.Locale.getDefault(),
        ).format(
            java.time.Instant
                .ofEpochMilli(epochMs)
                .atZone(java.time.ZoneId.systemDefault()),
        )
