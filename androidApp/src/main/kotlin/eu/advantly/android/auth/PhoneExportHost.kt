package eu.advantly.android.auth

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import eu.advantly.cloud.sync.CloudDataExportContext
import eu.advantly.cloud.sync.CloudDataExportPort
import eu.advantly.cloud.sync.CloudDataExportStatus
import eu.advantly.cloud.sync.CloudException
import eu.advantly.presentation.auth.PhoneExportAction
import eu.advantly.presentation.auth.PhoneExportError
import eu.advantly.presentation.auth.PhoneExportPhase
import eu.advantly.presentation.auth.PhoneExportPort
import eu.advantly.presentation.auth.PhoneExportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.coroutines.ContinuationInterceptor

/** Ein expliziter Export je Konto-Host; besitzt weder Auth noch Upload und hält keine Activity. */
internal class PhoneExportHost(
    private val exports: CloudDataExportPort,
    applicationContext: Context,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val threadGuard: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
    private val cache: PrivateExportCache = PrivateExportCache(applicationContext),
    private val waitMs: suspend (Long) -> Unit = { delay(it) },
) : PhoneExportPort {
    private val lifecycle = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + lifecycle)

    // Unabhängig vom Caller; auch Dateiabbruch und tatsächlicher HTTP-Drain dürfen zu Ende laufen.
    private val cleanup = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())
    private val cleanupJobs = mutableSetOf<Job>()
    private val share = ExportShare(applicationContext)
    private val mutable = MutableStateFlow(PhoneExportState())
    override val state = mutable.asStateFlow()
    private var context: CloudDataExportContext? = null
    private var revision = 0L
    private var requestId: String? = null
    private var ready: CloudDataExportStatus.Ready? = null
    private var expiry: ExportDeadline? = null
    private var waiting: ExportDeadline? = null
    private var local: PrivateExportCache.Slot? = null
    private var phase = PhoneExportPhase.IDLE
    private var error: PhoneExportError? = null
    private var action: Job? = null
    private var timer: Job? = null
    private var cancelledLocally = false
    private var initialized = false
    private var closed = false
    private var drained = false
    private var cleanupFailed = false
    private val closeCallbacks = mutableListOf<() -> Unit>()

    init {
        confined()
        share.revokeAll()
        work.launch {
            try {
                cache.clear(share::revokeFile)
                initialized = true
                publish()
            } catch (_: IOException) {
                phase = PhoneExportPhase.FAILED
                error = PhoneExportError.LOCAL_IO
                publish()
            }
        }
        work.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                exports.context.collect { publish() }
            } finally {
                if (!closed) close {}
            }
        }
    }

    private fun confined() = check(threadGuard()) { "Phone-Export nur auf dem seriellen Main-Host-Kontext verwenden" }

    private fun synchronize(): CloudDataExportContext? {
        confined()
        if (closed) return null
        if (!lifecycle.isActive) {
            close {}
            return null
        }
        val next = exports.context.value?.takeIf { runCatching { exports.requireCurrent(it) }.isSuccess }
        if (next != context) {
            context = next
            reset()
        }
        return next
    }

    private fun reset() {
        val running = action
        revision++
        timer?.cancel()
        timer = null
        invalidateFile()
        requestId = null
        ready = null
        expiry = null
        waiting = null
        cancelledLocally = false
        error = null
        phase = PhoneExportPhase.IDLE
        // Ein bereits abgeschlossener LAZY-Job darf erst den vollständig zurückgesetzten Stand publizieren.
        running?.cancel()
    }

    private fun invalidateFile() {
        val obsolete = local ?: return
        local = null
        share.revoke(obsolete)
        val running = action
        var job: Job? = null
        job =
            cleanup.launch(start = CoroutineStart.LAZY) {
                try {
                    running?.join()
                    cache.delete(obsolete)
                } catch (_: IOException) {
                    cleanupFailed = true
                    if (!closed) error = PhoneExportError.LOCAL_IO
                } finally {
                    cleanupJobs.remove(job)
                    if (!closed) publish()
                }
            }
        cleanupJobs += job
        job.start()
    }

    private fun expire() {
        if (expiry?.valid() == false) {
            revision++
            cancelledLocally = false
            action?.cancel()
            invalidateFile()
            ready = null
            expiry = null
            waiting = null
            phase = PhoneExportPhase.FAILED
            error = PhoneExportError.EXPIRED
        }
        if (waiting?.valid() == false) waiting = null
    }

    private fun publish() {
        if (closed) return
        val current = synchronize()
        if (closed) return
        expire()
        val actions =
            when {
                current == null || !initialized -> emptySet()

                action != null -> setOf(PhoneExportAction.CANCEL, PhoneExportAction.DISCARD)

                cleanupJobs.isNotEmpty() -> emptySet()

                requestId == null -> setOf(PhoneExportAction.REQUEST)

                waiting != null -> setOf(PhoneExportAction.DISCARD)

                error == PhoneExportError.EXPIRED || error == PhoneExportError.SESSION_REJECTED ||
                    error == PhoneExportError.TOO_LARGE -> setOf(PhoneExportAction.DISCARD)

                local != null -> setOf(PhoneExportAction.SHARE, PhoneExportAction.DISCARD)

                ready != null -> setOf(PhoneExportAction.DOWNLOAD, PhoneExportAction.REFRESH_STATUS, PhoneExportAction.DISCARD)

                phase == PhoneExportPhase.SERVER_BUSY -> setOf(PhoneExportAction.RETRY, PhoneExportAction.DISCARD)

                else -> setOf(PhoneExportAction.RETRY, PhoneExportAction.REFRESH_STATUS, PhoneExportAction.DISCARD)
            }
        mutable.value =
            PhoneExportState(
                phase = if (current == null) PhoneExportPhase.UNAVAILABLE else phase,
                allowedActions = actions,
                error = error,
                exportedAtMs = ready?.exportedAtMs,
                expiresAtMs = ready?.expiresAtMs,
                byteLength = ready?.byteLength,
                retryAtMs = waiting?.wallAtMs,
            )
        scheduleTimer()
    }

    private fun scheduleTimer() {
        timer?.cancel()
        timer = null
        if (waiting == null && expiry == null) return
        timer =
            work.launch {
                val remaining = listOfNotNull(waiting?.remainingMs(), expiry?.remainingMs()).minOrNull() ?: return@launch
                if (remaining > 0) waitMs(remaining)
                timer = null
                publish()
            }
    }

    private fun allowed(required: PhoneExportAction): Boolean {
        confined()
        publish()
        return !closed && required in state.value.allowedActions
    }

    override fun requestExport() {
        if (!allowed(PhoneExportAction.REQUEST)) return
        requestId = UUID.randomUUID().toString()
        metadata(false)
    }

    override fun retry() {
        if (allowed(PhoneExportAction.RETRY)) metadata(false)
    }

    override fun refreshStatus() {
        if (allowed(PhoneExportAction.REFRESH_STATUS)) metadata(true)
    }

    private fun metadata(status: Boolean) {
        val id = requireNotNull(requestId)
        phase = if (status) PhoneExportPhase.CHECKING else PhoneExportPhase.REQUESTING
        start(requesting = !status) { expected, fence ->
            val result = if (status) exports.status(expected, id) else exports.request(expected, id)
            fence()
            when (result) {
                is CloudDataExportStatus.Busy -> {
                    ready = null
                    phase = PhoneExportPhase.SERVER_BUSY
                    waitFor(result.retryAfterSeconds)
                }

                is CloudDataExportStatus.Pending -> {
                    if (result.requestId != id) throw ExportFileFailure(PhoneExportError.INVALID_RESPONSE)
                    ready = null
                    phase = PhoneExportPhase.PENDING
                    waitFor(result.retryAfterSeconds)
                }

                is CloudDataExportStatus.Ready -> {
                    if (result.requestId != id || result.exportedAtMs < 0 || result.expiresAtMs <= result.exportedAtMs ||
                        result.expiresAtMs - result.exportedAtMs > 86_400_000
                    ) {
                        throw ExportFileFailure(PhoneExportError.INVALID_RESPONSE)
                    }
                    if (result.byteLength > PrivateExportCache.MAX_BYTES) throw ExportFileFailure(PhoneExportError.TOO_LARGE)
                    if (result.byteLength < 1 || !Regex("[0-9a-fA-F]{64}").matches(result.sha256)) {
                        throw ExportFileFailure(PhoneExportError.INVALID_RESPONSE)
                    }
                    val deadline = PrivateExportCache.deadline(result, nowMs, elapsedMs)
                    // Ein erneuter Status darf den bereits verankerten Ablauf nicht verlängern.
                    if (expiry == null || deadline.remainingMs() < expiry!!.remainingMs()) expiry = deadline
                    ready = result
                    phase = PhoneExportPhase.READY
                }
            }
        }
    }

    private fun waitFor(seconds: Long) {
        if (seconds !in 1..86_400) throw ExportFileFailure(PhoneExportError.INVALID_RESPONSE)
        val duration = seconds * 1000
        waiting = ExportDeadline(nowMs() + duration, duration, nowMs, elapsedMs)
    }

    override fun download() {
        if (!allowed(PhoneExportAction.DOWNLOAD)) return
        val metadata = requireNotNull(ready)
        phase = PhoneExportPhase.DOWNLOADING
        start { expected, fence ->
            val slot = cache.slot()
            var retained = false
            try {
                cache.receive(slot, metadata, fence) { sink -> exports.download(expected, metadata, sink) }
                fence()
                local = slot
                phase = PhoneExportPhase.LOCAL_FILE
                retained = true
            } finally {
                if (!retained) cache.delete(slot)
            }
        }
    }

    fun share(activity: ComponentActivity) {
        if (!allowed(PhoneExportAction.SHARE)) return
        val target = WeakReference(activity)
        val slot = requireNotNull(local)
        val metadata = requireNotNull(ready)
        val deadline = requireNotNull(expiry)
        start { expected, fence ->
            cache.verify(slot, metadata)
            fence()
            val access =
                ExportAccessGateway(expected, cleanup.coroutineContext, threadGuard) { captured ->
                    check(!closed && lifecycle.isActive && context == captured && local === slot)
                    exports.requireCurrent(captured)
                }
            target.get()?.let { share.send(it, slot, metadata.byteLength, deadline, access) }
        }
    }

    private fun start(
        requesting: Boolean = false,
        block: suspend (CloudDataExportContext, () -> Unit) -> Unit,
    ) {
        val expected = context ?: return
        if (synchronize() != expected || requestId == null) return
        val attempt = revision
        val id = requestId
        error = null
        cancelledLocally = false
        val running =
            work.launch(start = CoroutineStart.LAZY) {
                val job = currentCoroutineContext()[Job]!!
                val fence = {
                    job.ensureActive()
                    if (synchronize() != expected || revision != attempt) throw CancellationException()
                    if (expiry?.valid() == false) throw ExportFileFailure(PhoneExportError.EXPIRED)
                }
                try {
                    fence()
                    block(expected, fence)
                    fence()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    if (!closed && synchronize() == expected && revision == attempt) {
                        error = classify(failure)
                        phase =
                            if (requesting && error in setOf(PhoneExportError.CONNECTION, PhoneExportError.TIMEOUT)) {
                                PhoneExportPhase.OUTCOME_UNKNOWN
                            } else {
                                PhoneExportPhase.FAILED
                            }
                        if (local != null) invalidateFile()
                    }
                }
            }
        action = running
        val finished = CompletableDeferred<Unit>()
        cleanupJobs += finished

        fun completeAction() {
            confined()
            var changed = false
            try {
                if (action === running) {
                    action = null
                    if (!closed && synchronize() == expected && requestId == id && cancelledLocally) {
                        phase =
                            when {
                                local != null -> PhoneExportPhase.LOCAL_FILE
                                ready != null -> PhoneExportPhase.READY
                                else -> PhoneExportPhase.OUTCOME_UNKNOWN
                            }
                        cancelledLocally = false
                    }
                    changed = true
                }
            } finally {
                cleanupJobs.remove(finished)
                finished.complete(Unit)
            }
            if (changed) publish()
        }
        // Gilt auch ohne Body-Eintritt und erst nach sämtlichen Kindern sowie deren tatsächlichem Abbau.
        running.invokeOnCompletion {
            if (threadGuard()) {
                completeAction()
            } else {
                // Kein Caller-Job: Close wartet im vorhandenen Register auf diesen seriellen Abschluss.
                val hostContext = cleanup.coroutineContext
                (hostContext[ContinuationInterceptor] as CoroutineDispatcher).dispatch(hostContext, Runnable { completeAction() })
            }
        }
        publish()
        running.start()
    }

    override fun cancel() {
        confined()
        if (synchronize() == null || closed || action == null) return
        revision++
        cancelledLocally = true
        action?.cancel()
        publish()
    }

    override fun discard() {
        confined()
        if (closed) return
        synchronize()
        reset()
        publish()
    }

    override fun close(onDrained: () -> Unit) {
        confined()
        if (drained) {
            onDrained()
            return
        }
        closeCallbacks += onDrained
        if (closed) return
        closed = true
        reset()
        share.revokeAll()
        mutable.value = PhoneExportState(phase = PhoneExportPhase.CLOSED)
        lifecycle.cancel()
        cleanup.launch {
            lifecycle.join()
            while (cleanupJobs.isNotEmpty()) cleanupJobs.toList().joinAll()
            try {
                cache.clear(share::revokeFile)
            } catch (_: IOException) {
                cleanupFailed = true
            } finally {
                if (cleanupFailed) mutable.value = PhoneExportState(phase = PhoneExportPhase.CLOSED, error = PhoneExportError.LOCAL_IO)
                drained = true
                closeCallbacks.toList().also { closeCallbacks.clear() }.forEach { runCatching(it) }
                cleanup.coroutineContext[Job]!!.cancel()
            }
        }
    }

    private fun classify(failure: Exception): PhoneExportError =
        when (failure) {
            is ExportFileFailure -> {
                failure.error
            }

            is CloudException -> {
                when (failure.reason) {
                    "export_timeout" -> PhoneExportError.TIMEOUT
                    "export_not_authenticated", "cloud_session_changed", "cloud_closed" -> PhoneExportError.SESSION_REJECTED
                    "export_not_found" -> PhoneExportError.NOT_FOUND
                    "export_expired" -> PhoneExportError.EXPIRED
                    "export_too_large" -> PhoneExportError.TOO_LARGE
                    "export_response_invalid", "export_not_ready" -> PhoneExportError.INVALID_RESPONSE
                    else -> PhoneExportError.CONNECTION
                }
            }

            is IOException -> {
                PhoneExportError.LOCAL_IO
            }

            else -> {
                PhoneExportError.LOCAL_IO
            }
        }
}
