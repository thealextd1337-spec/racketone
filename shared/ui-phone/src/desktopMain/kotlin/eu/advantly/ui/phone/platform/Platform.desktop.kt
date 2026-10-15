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
