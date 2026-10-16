// NSUInteger/NSInteger sind auf watchosArm64 (arm64_32) 32 Bit, sonst 64 Bit → Umrechnung über convert()
@file:OptIn(kotlinx.cinterop.UnsafeNumber::class)

package eu.advantly.device.link

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.clock_gettime_nsec_np
import platform.posix.memcpy

/** [LinkScheduler] auf der Main-Queue (WCSession-Delegates kommen auf einer Hintergrund-Queue an). */
@OptIn(ExperimentalForeignApi::class)
class MainQueueScheduler : LinkScheduler {
    private val origin = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.convert())

    // Darwin bildet MONOTONIC_RAW auf mach_continuous_time ab: zählt auch im Geräteschlaf weiter.
    override fun nowMs(): Long = ((clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.convert()) - origin) / NANOS_PER_MS.toULong()).toLong()

    override fun post(action: () -> Unit) {
        dispatch_async(dispatch_get_main_queue()) { action() }
    }

    override fun postDelayed(
        delayMs: Long,
        action: () -> Unit,
    ): Cancellable {
        // dispatch_after lässt sich nicht abbrechen → der Block hält nur den Halter; cancel() gibt die Action
        // (und alles, was sie referenziert) sofort frei
        val holder = DelayedAction(action)
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, delayMs * NANOS_PER_MS), dispatch_get_main_queue()) {
            val pending = holder.action
            holder.action = null
            pending?.invoke()
        }
        return Cancellable { holder.action = null }
    }

    private class DelayedAction(
        var action: (() -> Unit)?,
    )

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

/** Zuverlässiger Kanal iPhone ↔ Apple Watch über WatchConnectivity. */
fun createWatchConnectivityLink(
    scheduler: LinkScheduler = MainQueueScheduler(),
    policy: RetryPolicy = RetryPolicy(),
): ReliableDeviceLink = ReliableDeviceLink(WatchConnectivityTransport(counterpartPeerId()), scheduler, policy)

/** iOS: Gegenüber ist die Uhr ([WATCH_PEER]); watchOS: das Handy ([PHONE_PEER]). */
internal expect fun counterpartPeerId(): String

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.convert<Int>()
    val out = ByteArray(size)
    if (size > 0) {
        out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
    return out
}
