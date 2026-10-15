package eu.advantly.device.link.testing

import eu.advantly.device.link.Cancellable
import eu.advantly.device.link.LinkScheduler

/**
 * Deterministischer [LinkScheduler] mit virtueller Zeit für Tests und Simulationen.
 * Aufgaben laufen erst bei [runUntilIdle] bzw. [advanceBy], in Zeit- und Einfügereihenfolge.
 */
class ManualScheduler(
    startMs: Long = 0,
) : LinkScheduler {
    private class Task(
        val dueMs: Long,
        val order: Long,
        val action: () -> Unit,
    ) {
        var cancelled = false
    }

    private var now = startMs
    private var nextOrder = 0L
    private val tasks = mutableListOf<Task>()

    override fun nowMs(): Long = now

    override fun post(action: () -> Unit) {
        postDelayed(0, action)
    }

    override fun postDelayed(
        delayMs: Long,
        action: () -> Unit,
    ): Cancellable {
        val task = Task(now + delayMs.coerceAtLeast(0), nextOrder++, action)
        tasks += task
        return Cancellable { task.cancelled = true }
    }

    /** Führt alles aus, was bis jetzt fällig ist (auch neu entstehende Aufgaben ohne Verzögerung). */
    fun runUntilIdle() {
        advanceTo(now)
    }

    /** Lässt die virtuelle Zeit um [ms] laufen und führt fällige Aufgaben in Reihenfolge aus. */
    fun advanceBy(ms: Long) {
        advanceTo(now + ms)
    }

    fun advanceTo(targetMs: Long) {
        while (true) {
            tasks.removeAll { it.cancelled }
            val next = tasks.filter { it.dueMs <= targetMs }.minWithOrNull(compareBy<Task>({ it.dueMs }, { it.order })) ?: break
            tasks.remove(next)
            now = maxOf(now, next.dueMs)
            next.action()
        }
        now = maxOf(now, targetMs)
    }

    /** Anzahl noch geplanter (nicht abgebrochener) Aufgaben. */
    val pendingTasks: Int get() = tasks.count { !it.cancelled }
}
