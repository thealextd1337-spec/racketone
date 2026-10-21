package eu.advantly.match.session

import eu.advantly.device.link.Cancellable
import eu.advantly.device.link.DeliveryResult
import eu.advantly.device.link.LinkScheduler
import eu.advantly.device.link.LinkTransport

/**
 * Eine Bootstrap-/Laufzeitgeneration. Nur erfolgreiche Tasks geben Ausgaben frei.
 * Bestehende lokale Transaktionen committen samt afterCommit an ihrer bisherigen Grenze.
 * Bereits committed Arbeit bleibt bei späterem Taskfehler erhalten; nur Ausgaben werden verworfen.
 * Kurze Puffer sind begrenzt; sie sind keine Zustellqueue und melden verworfene Arbeit niemals als Erfolg.
 * Alle Aktionen laufen seriell; post/cancel dürfen wie beim Android-Handler von anderen Threads kommen.
 */
internal class AndroidSessionWork(
    private val scheduler: LinkScheduler,
    private val storage: (() -> Unit) -> Boolean,
    private val available: () -> Boolean,
    private val failed: (Exception) -> Unit,
) : LinkScheduler {
    private inner class Job(
        val due: Long,
        val action: () -> Unit,
    ) : Cancellable {
        var registration: Cancellable? = null

        @Volatile var cancelled = false

        override fun cancel() =
            synchronized(this@AndroidSessionWork) {
                cancelled = true
                registration?.cancel()
                jobs -= this
                Unit
            }
    }

    private val jobs = mutableSetOf<Job>()
    private val outputs = mutableListOf<() -> Unit>()
    private var depth = 0

    @Volatile private var committed = false

    @Volatile private var discarded = false

    @Volatile private var failure: Exception? = null

    override fun nowMs(): Long = scheduler.nowMs()

    override fun post(action: () -> Unit) {
        postDelayed(0, action)
    }

    @Synchronized
    override fun postDelayed(
        delayMs: Long,
        action: () -> Unit,
    ): Cancellable {
        if (discarded) return Cancellable { }
        if (jobs.size >= LIMIT) {
            val error = IllegalStateException("Android session task buffer exceeded")
            abort(error)
            throw error
        }
        val job = Job(nowMs() + delayMs.coerceAtLeast(0), action)
        jobs += job
        if (committed) schedule(job)
        return job
    }

    @Synchronized
    fun release() {
        throwIfFailed()
        check(!discarded)
        committed = true
        jobs.toList().forEach(::schedule)
    }

    @Synchronized
    fun hold() {
        throwIfFailed()
        check(!discarded)
        committed = false
        jobs.forEach {
            it.registration?.cancel()
            it.registration = null
        }
    }

    @Synchronized
    fun discard(error: Exception? = null) {
        if (failure == null) failure = error
        discarded = true
        committed = false
        jobs.toList().forEach { it.cancel() }
        outputs.clear()
    }

    fun throwIfFailed() {
        failure?.let { throw it }
    }

    /** ACKs, Sends und onConsumed erst nach Taskende einschließlich seiner lokalen SQL-Commits ausführen. */
    fun output(action: () -> Unit) {
        if (discarded || !committed || !available()) return
        if (depth > 0) {
            synchronized(this) {
                if (discarded) return
                check(outputs.size < LIMIT) { "Android session output buffer exceeded" }
                outputs += action
            }
        } else {
            action()
        }
    }

    private fun abort(error: Exception) {
        discard(error)
        try {
            failed(error)
        } catch (cleanup: Exception) {
            if (cleanup !== error) error.addSuppressed(cleanup)
        }
    }

    private fun schedule(job: Job) {
        if (job.cancelled || job.registration != null) return
        job.registration = scheduler.postDelayed((job.due - nowMs()).coerceAtLeast(0)) { run(job) }
    }

    private fun run(job: Job) {
        synchronized(this) { jobs -= job }
        if (job.cancelled || discarded || !committed || !available()) return
        if (depth > 0) {
            // Reentranz gehört zum äußeren Task: weder früh committen noch einen Fehler hier verschlucken.
            job.action()
            return
        }
        synchronized(this) { check(outputs.isEmpty()) }
        depth = 1
        try {
            val succeeded =
                storage {
                    job.action()
                    throwIfFailed()
                    depth = 0
                    val ready = synchronized(this) { outputs.toList().also { outputs.clear() } }
                    ready.forEach { if (!discarded && committed && available()) it() }
                }
            if (!succeeded) discard()
        } catch (error: Exception) {
            abort(error)
            throw error
        } finally {
            depth = 0
            // Insbesondere kein FailedTask-Puffer beim nächsten Task oder Retry.
            synchronized(this) { outputs.clear() }
        }
    }

    private companion object {
        const val LIMIT = 512
    }
}

/** Android-Ausgabebarriere unter ReliableDeviceLink, ohne Kenntnis oder Änderung des Wireformats. */
internal class AndroidProtectedTransport(
    private val delegate: LinkTransport,
    private val work: AndroidSessionWork,
    private val allowed: () -> Boolean,
) : LinkTransport {
    @Volatile private var generation = 0L

    @Volatile private var running = false

    override fun isTrustedCounterpart(peer: String): Boolean = running && allowed() && delegate.isTrustedCounterpart(peer)

    override fun start(listener: LinkTransport.Listener) {
        val epoch = ++generation
        running = true
        delegate.start(
            object : LinkTransport.Listener {
                private fun current() = running && epoch == generation && allowed()

                override fun onFrame(
                    peer: String,
                    frame: ByteArray,
                    onConsumed: () -> Unit,
                ) {
                    if (!current()) return
                    listener.onFrame(peer, frame) { work.output { if (current()) onConsumed() } }
                }

                override fun onPeersChanged(
                    peers: List<String>,
                    available: Boolean,
                ) {
                    if (current()) listener.onPeersChanged(peers, available)
                }
            },
        )
    }

    override fun stop() {
        running = false
        generation++
        delegate.stop()
    }

    override fun send(
        peer: String,
        frame: ByteArray,
        onResult: (DeliveryResult) -> Unit,
    ) {
        val epoch = generation
        work.output {
            if (running && epoch == generation && allowed()) {
                delegate.send(peer, frame) { if (running && epoch == generation && allowed()) onResult(it) }
            }
        }
    }
}
