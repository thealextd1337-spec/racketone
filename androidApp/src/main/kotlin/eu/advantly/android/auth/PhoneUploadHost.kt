package eu.advantly.android.auth

import android.os.Looper
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudUploadFailureKind
import eu.advantly.cloud.sync.CloudUploadOutcome
import eu.advantly.cloud.sync.CloudUploadPort
import eu.advantly.cloud.sync.CloudUploadRunner
import eu.advantly.cloud.sync.CloudUploadState
import eu.advantly.cloud.sync.OwnedSourceUploadPort
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionListener
import eu.advantly.presentation.auth.PhoneUploadAction
import eu.advantly.presentation.auth.PhoneUploadError
import eu.advantly.presentation.auth.PhoneUploadPhase
import eu.advantly.presentation.auth.PhoneUploadPort
import eu.advantly.presentation.auth.PhoneUploadResult
import eu.advantly.presentation.auth.PhoneUploadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** Ein Host je prozessweitem Konto-Host ([PhoneAccountCore]); die MatchSession und ihre Datenbank gehören ihm nicht. */
internal class PhoneUploadHost(
    private val auth: CloudAuthPort,
    private val session: MatchSession,
    private val uploads: CloudUploadPort,
    private val bindDevice: suspend (String) -> Unit,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val threadGuard: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
    private val hostAccessAllowed: () -> Boolean = { true },
    private val storageAction: (() -> Unit) -> Boolean = {
        it()
        true
    },
    private val lane: PhoneCloudLane,
) : PhoneUploadPort {
    private val lifecycle = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + lifecycle)
    private val mutable = MutableStateFlow(PhoneUploadState())
    override val state = mutable.asStateFlow()
    private var context: PhoneCloudContext? = null
    private var runner: CloudUploadRunner? = null
    private var action: Job? = null
    private var closed = false
    private var localRevision = 0L
    private val sessionListener = SessionListener { invalidateSnapshot() }
    private val removeDirectoryListener: () -> Unit

    // Eigener Cleanup ohne geerbten Job: ViewModel-Cancellation darf das Join nicht überspringen.
    private val cleanup = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())

    init {
        confined()
        removeDirectoryListener = session.data.observeDirectoryChanges(::invalidateSnapshot)
        session.addListener(sessionListener)
        work.launch(start = CoroutineStart.UNDISPATCHED) {
            auth.session.collect { synchronize() }
        }
    }

    private fun confined() = check(threadGuard()) { "Phone-Upload nur auf dem Main-Host-Kontext verwenden" }

    private fun current(): PhoneCloudContext? {
        var result: PhoneCloudContext? = null
        return if (storageAction { result = readPhoneCloudContext(auth, session, hostAccessAllowed) }) result else null
    }

    private fun synchronize(): PhoneCloudContext? {
        confined()
        if (closed) return null
        val next = current()
        if (next != context) {
            context = next
            lane.bound = null
            runner?.close()
            runner = null
            action?.cancel()
            mutable.value =
                PhoneUploadState(
                    available = next != null,
                    busy = action != null,
                    action = if (next == null) PhoneUploadAction.NONE else PhoneUploadAction.UPLOAD,
                )
        }
        return next
    }

    private fun invalidateSnapshot() {
        confined()
        if (closed) return
        localRevision++
        if (mutable.value.observedAtMs != null) mutable.value = mutable.value.copy(stale = true)
        synchronize()
    }

    override fun uploadNow() {
        start()
    }

    /** Startet einen Upload oder liefert die laufende Aktion (auch [refreshStatus]); `null` ohne gültigen Kontext. */
    internal fun start(): Job? {
        confined()
        val expected = synchronize() ?: return null
        if (closed) return null
        action?.let { return it }
        val lock = lane.mutex(expected.profile)
        mutable.value =
            mutable.value.copy(
                busy = true,
                phase =
                    when {
                        lock.isLocked -> PhoneUploadPhase.WAITING
                        lane.bound == expected -> PhoneUploadPhase.UPLOADING
                        else -> PhoneUploadPhase.BINDING
                    },
                result = null,
                error = null,
            )
        // LAZY setzt die Referenz auch dann vor Ausführung, wenn Main.immediate sofort weiterläuft.
        val job =
            work.launch(start = CoroutineStart.LAZY) {
                try {
                    // Host-Vertrag B4: wartet auf einen laufenden Pull desselben Kontos.
                    lock.withLock {
                        if (synchronize() != expected) return@launch
                        if (lane.bound != expected) {
                            lane.bound = null
                            mutable.value = mutable.value.copy(phase = PhoneUploadPhase.BINDING)
                            bindDevice(expected.device)
                            currentCoroutineContext().ensureActive()
                            if (synchronize() != expected) return@launch
                            lane.bound = expected
                        }
                        val active =
                            runner ?: CloudUploadRunner(
                                OwnedSourceUploadPort(uploads, session.data.accountIdentities),
                                nowMs,
                                threadGuard = threadGuard,
                            ).also { runner = it }
                        mutable.value = mutable.value.copy(phase = PhoneUploadPhase.UPLOADING, canRefreshStatus = true)
                        val revision = localRevision
                        val result = active.uploadNow()
                        currentCoroutineContext().ensureActive()
                        if (synchronize() == expected) publish(result, revision != localRevision)
                    }
                } catch (error: CancellationException) {
                    if (!closed && synchronize() == expected) {
                        mutable.value = mutable.value.copy(result = PhoneUploadResult.CANCELLED)
                    }
                    throw error
                } catch (error: Exception) {
                    if (!closed && synchronize() == expected) {
                        val permission = (error as? CloudException)?.permission == true
                        mutable.value =
                            mutable.value.copy(
                                result = PhoneUploadResult.FAILED,
                                error =
                                    when {
                                        permission -> PhoneUploadError.PERMISSION
                                        error is IllegalStateException -> PhoneUploadError.LOCAL
                                        else -> PhoneUploadError.CONNECTION
                                    },
                                action = if (permission) PhoneUploadAction.NONE else PhoneUploadAction.RETRY,
                            )
                    }
                } finally {
                    action = null
                    if (!closed) mutable.value = mutable.value.copy(busy = false, phase = PhoneUploadPhase.IDLE)
                }
            }
        action = job
        job.start()
        return job
    }

    override fun refreshStatus() {
        confined()
        val expected = synchronize() ?: return
        if (action != null || closed || lane.bound != expected) return
        val active = runner ?: return
        action =
            work.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = active.refresh()
                    if (synchronize() == expected) publish(result, false)
                } finally {
                    action = null
                    if (!closed) mutable.value = mutable.value.copy(busy = false)
                }
            }
        mutable.value = mutable.value.copy(busy = true)
        action?.start()
    }

    private fun publish(
        result: CloudUploadState,
        stale: Boolean,
    ) {
        val failure = result.failure?.kind
        mutable.value =
            PhoneUploadState(
                available = true,
                busy = true,
                canRefreshStatus = context != null && lane.bound == context && runner != null,
                pendingEvents = result.inventory?.pendingEvents,
                rejectedEvents = result.inventory?.rejectedEvents,
                pendingMetadata = result.inventory?.pendingMetadata,
                observedAtMs = result.observedAtMs,
                lastResponseAtMs = result.lastSuccessAtMs,
                stale = stale,
                result =
                    when (result.outcome) {
                        CloudUploadOutcome.DRAINED -> PhoneUploadResult.DRAINED
                        CloudUploadOutcome.PARTIAL -> PhoneUploadResult.PARTIAL
                        CloudUploadOutcome.LIMIT_REACHED -> PhoneUploadResult.LIMIT_REACHED
                        CloudUploadOutcome.BLOCKED -> PhoneUploadResult.BLOCKED
                        CloudUploadOutcome.FAILED -> PhoneUploadResult.FAILED
                        CloudUploadOutcome.CANCELLED -> PhoneUploadResult.CANCELLED
                        else -> null
                    },
                error =
                    when (failure) {
                        CloudUploadFailureKind.PERMISSION -> PhoneUploadError.PERMISSION
                        CloudUploadFailureKind.TRANSIENT -> PhoneUploadError.CONNECTION
                        CloudUploadFailureKind.RESPONSE -> PhoneUploadError.RESPONSE
                        CloudUploadFailureKind.LOCAL -> PhoneUploadError.LOCAL
                        null -> null
                    },
                action =
                    when (failure) {
                        CloudUploadFailureKind.PERMISSION -> PhoneUploadAction.NONE
                        CloudUploadFailureKind.TRANSIENT, CloudUploadFailureKind.RESPONSE -> PhoneUploadAction.RETRY
                        else -> PhoneUploadAction.UPLOAD
                    },
            )
    }

    override fun close(onDrained: () -> Unit) {
        confined()
        if (closed) return
        closed = true
        mutable.value = PhoneUploadState(phase = PhoneUploadPhase.CLOSED)
        session.removeListener(sessionListener)
        removeDirectoryListener()
        runner?.close()
        lifecycle.cancel()
        cleanup.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                lifecycle.join()
                onDrained()
            } finally {
                cleanup.coroutineContext[Job]?.cancel()
            }
        }
    }
}
