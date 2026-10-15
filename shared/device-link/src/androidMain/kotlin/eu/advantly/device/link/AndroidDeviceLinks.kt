package eu.advantly.device.link

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** [LinkScheduler] auf dem Main-Looper (Play-Services-Callbacks laufen dort ohnehin). */
class MainLooperScheduler : LinkScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun post(action: () -> Unit) {
        handler.post(action)
    }

    override fun postDelayed(
        delayMs: Long,
        action: () -> Unit,
    ): Cancellable {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}

/** Zuverlässiger Kanal Phone ↔ Wear über die Data Layer API (gleicher Code in `androidApp` und `wearApp`). */
fun createWearDeviceLink(
    context: Context,
    scheduler: LinkScheduler = MainLooperScheduler(),
    policy: RetryPolicy = RetryPolicy(),
): ReliableDeviceLink = ReliableDeviceLink(WearDataLayerTransport(context), scheduler, policy)
