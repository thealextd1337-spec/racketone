package eu.advantly.device.link

/**
 * Serielle Ausführung + monotone Zeit für [ReliableDeviceLink]. Bewusst ohne Coroutines: hält das
 * watchOS-Framework klein und die Swift-Grenze frei von suspend/Flow (CLAUDE.md Regel 4).
 */
interface LinkScheduler {
    /** Monotone Zeit in Millisekunden (nur Differenzen sind aussagekräftig). */
    fun nowMs(): Long

    /** Führt [action] später auf dem Scheduler-Thread aus. */
    fun post(action: () -> Unit)

    fun postDelayed(
        delayMs: Long,
        action: () -> Unit,
    ): Cancellable
}

fun interface Cancellable {
    fun cancel()
}
