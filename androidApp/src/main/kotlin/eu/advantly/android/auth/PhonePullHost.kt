package eu.advantly.android.auth

import android.os.Looper
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudPullOutcome
import eu.advantly.cloud.sync.CloudPullPort
import eu.advantly.cloud.sync.CloudPullRunner
import eu.advantly.cloud.sync.CloudPullState
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.CloudTelemetryPort
import eu.advantly.core.model.TelemetryPreference
import eu.advantly.match.session.MatchSession
import eu.advantly.presentation.auth.PhonePullBlock
import eu.advantly.presentation.auth.PhonePullPort
import eu.advantly.presentation.auth.PhonePullResult
import eu.advantly.presentation.auth.PhonePullState
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

/**
 * Einziger Schalter für Pull und Einweg-Aktivierung des Phone-Replikats: Pull nur mit aktiviertem Replikat.
 * Ohne Aktivierung wären gepullte Cloud-Daten (auch geteilte Matches) ohne Lesespur voll lesbar.
 * Seit Welle 3 zusammen mit Protokoll 4 an: Mit Konto gelten Lese-/Schreibspuren statt P1-Vollzugriff. Die Uhr braucht
 * keinen eigenen Schalter, sie aktiviert ihr Replikat mit der ersten gültigen Access-Zustellung dieses Handys (E2).
 */
internal const val PHONE_REPLICA_ACTIVATION = true

/** Fehlercode des Runners für einen gescheiterten Request (BLOCKED bei Rechteverlust, sonst FAILED). */
private const val REQUEST_FAILED = "cloud_pull_request_failed"

/** Mit ausgeschaltetem [PHONE_REPLICA_ACTIVATION] entsteht kein Pull-Host: kein Button, kein Request, kein Bind. */
internal fun phonePullHost(
    replicaActivation: Boolean = PHONE_REPLICA_ACTIVATION,
    create: () -> PhonePullHost,
): PhonePullHost? = if (replicaActivation) create() else null

/**
 * Begrenzter D1-Abgleich je Konto, an dieselben Gates wie [PhoneUploadHost] gebunden; aktiviert vor dem ersten Lauf das
 * Replikat. Planung, Backoff und Auslöser liegen beim [PhoneSyncCoordinator].
 */
internal class PhonePullHost(
    private val auth: CloudAuthPort,
    private val session: MatchSession,
    private val pulls: CloudPullPort,
    private val bindDevice: suspend (String) -> Unit,
    private val lane: PhoneCloudLane,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate),
    private val threadGuard: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
    private val hostAccessAllowed: () -> Boolean = { true },
    private val storageAction: (() -> Unit) -> Boolean = {
        it()
        true
    },
    private val telemetry: CloudTelemetryPort? = null,
) : PhonePullPort {
    private val lifecycle = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + lifecycle)
    private val mutable = MutableStateFlow(PhonePullState())
    override val state = mutable.asStateFlow()
    private var context: PhoneCloudContext? = null
    private var runner: CloudPullRunner? = null
    private var action: Job? = null
    private var closed = false
    private val removeDirectoryListener: () -> Unit

    // Eigener Cleanup ohne geerbten Job: ViewModel-Cancellation darf das Join nicht überspringen.
    private val cleanup = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())

    init {
        confined()
        // Ownership und Identität ändern sich auch ohne neuen Sitzungswert: `available` sonst veraltet (H-Review P3-6).
        removeDirectoryListener = session.data.observeDirectoryChanges { synchronize() }
        work.launch(start = CoroutineStart.UNDISPATCHED) {
            auth.session.collect { synchronize() }
        }
    }

    private fun confined() = check(threadGuard()) { "Phone-Pull nur auf dem Main-Host-Kontext verwenden" }

    private fun current(): PhoneCloudContext? {
        var result: PhoneCloudContext? = null
        return if (storageAction { result = readPhoneCloudContext(auth, session, hostAccessAllowed) }) result else null
    }

    /** Logout, Kontowechsel, Löschantrag und Einwilligungsentzug beenden den laufenden Pull sofort. */
    private fun synchronize(): PhoneCloudContext? {
        confined()
        if (closed) return null
        val next = current()
        if (next != context) {
            context = next
            runner?.close()
            runner = null
            action?.cancel()
            mutable.value = PhonePullState(available = next != null, busy = action != null)
        }
        return next
    }

    override fun pullNow() {
        start()
    }

    /** Startet einen Pull oder liefert den laufenden; `null` ohne gültigen Kontext. */
    internal fun start(): Job? {
        confined()
        val expected = synchronize() ?: return null
        if (closed) return null
        action?.let { return it }
        mutable.value = mutable.value.copy(busy = true, result = null, blocked = null, metadataComplete = false)
        val job =
            work.launch(start = CoroutineStart.LAZY) {
                try {
                    // Host-Vertrag B4: wartet auf einen laufenden Upload desselben Kontos.
                    lane.mutex(expected.profile).withLock {
                        if (synchronize() != expected) return@launch
                        if (lane.bound != expected) {
                            lane.bound = null
                            bindDevice(expected.device)
                            currentCoroutineContext().ensureActive()
                            if (synchronize() != expected) return@launch
                            lane.bound = expected
                        }
                        syncTelemetry(expected)
                        if (synchronize() != expected) return@launch
                        activate(expected)
                        val active =
                            runner
                                ?: CloudPullRunner(
                                    pulls,
                                    session.data,
                                    threadGuard = threadGuard,
                                    eventsCommitted = session::eventsCommitted,
                                ).also { runner = it }
                        val result = active.pullNow()
                        currentCoroutineContext().ensureActive()
                        if (synchronize() == expected) publish(result)
                    }
                } catch (error: CancellationException) {
                    if (!closed && synchronize() == expected) mutable.value = mutable.value.copy(result = PhonePullResult.CANCELLED)
                    throw error
                } catch (error: Exception) {
                    if (!closed && synchronize() == expected) {
                        val permission = (error as? CloudException)?.permission == true
                        mutable.value =
                            mutable.value.copy(
                                result =
                                    when {
                                        permission -> PhonePullResult.BLOCKED
                                        error is CloudException -> PhonePullResult.CONNECTION_FAILED
                                        else -> PhonePullResult.FAILED
                                    },
                                blocked = if (permission) PhonePullBlock.PERMISSION else null,
                            )
                    }
                } finally {
                    action = null
                    if (!closed) mutable.value = mutable.value.copy(busy = false)
                }
            }
        action = job
        job.start()
        return job
    }

    /**
     * H0: Kontoweite Telemetrie-Wahl abgleichen – lokalen Stand senden, Kontostand nach derselben Regel übernehmen und
     * damit an die Uhr weitergeben. Nur für das gebundene Konto [expected]; die Antwort gilt nur, wenn danach noch derselbe
     * Kontext besteht. Kein Rechtefehler (etwa ein Server ohne die RPC) hält den Match-Abgleich auf.
     */
    private suspend fun syncTelemetry(expected: PhoneCloudContext) {
        val port = telemetry ?: return
        if (synchronize() != expected) return
        var local: TelemetryPreference? = null
        if (!storageAction { local = session.accountTelemetry() }) return
        val remote =
            try {
                port.syncTelemetry(expected.profile, local ?: return)
            } catch (error: CloudException) {
                if (error.permission) throw error
                return
            } ?: return
        currentCoroutineContext().ensureActive()
        if (synchronize() == expected) storageAction { session.mergeTelemetryPreference(remote) }
    }

    /**
     * Nach Restore-Gate und gültiger Kontositzung; der Fence verlangt beides bis zum Commit. Die Auth-Lease liest
     * die SDK-Identität synchron, damit ein noch nicht synchronisierter Sitzungswechsel nicht durchrutscht.
     */
    private fun activate(expected: PhoneCloudContext) {
        val replicas = session.data.replicas
        replicas.activate(replicas.captureContext()) {
            confined()
            if (readPhoneCloudContext(auth, session, hostAccessAllowed) != expected) {
                throw CloudException("cloud_session_changed", true)
            }
            auth.requireAccountSession(CloudSession(expected.profile, false, generation = expected.generation))
        }
    }

    private fun publish(result: CloudPullState) {
        // Nur Requestfehler sind Verbindungsfehler; lokale, Antwort- und Matchfehler bleiben neutral.
        val failed = if (result.failure == REQUEST_FAILED) PhonePullResult.CONNECTION_FAILED else PhonePullResult.FAILED
        // Nur wenn dieser Lauf Seiten übernahm und die letzte davon den Abgleich abschloss (hasMore = false).
        var metadataComplete = false
        val profile = result.profileId
        if (result.completedMetadataPages > 0 && profile != null) {
            storageAction {
                metadataComplete =
                    session.data.cloudPulls
                        .progress(profile)
                        .metadataComplete
            }
        }
        mutable.value =
            PhonePullState(
                available = true,
                busy = true,
                result =
                    when (result.outcome) {
                        CloudPullOutcome.COMPLETE -> PhonePullResult.COMPLETE
                        CloudPullOutcome.LIMIT_REACHED, CloudPullOutcome.DEADLINE_REACHED -> PhonePullResult.LIMIT_REACHED
                        CloudPullOutcome.BLOCKED -> PhonePullResult.BLOCKED
                        CloudPullOutcome.FAILED -> failed
                        CloudPullOutcome.CANCELLED -> PhonePullResult.CANCELLED
                        else -> null
                    },
                blocked =
                    when {
                        result.outcome != CloudPullOutcome.BLOCKED -> null

                        // Der Runner meldet Rechteverlust als BLOCKED mit Requestfehler.
                        result.failure == REQUEST_FAILED -> PhonePullBlock.PERMISSION

                        result.pendingRemovals > 0 -> PhonePullBlock.REMOVALS

                        result.pendingRedactions > 0 -> PhonePullBlock.REDACTIONS

                        result.pendingMaterializations > 0 -> PhonePullBlock.MATERIALIZATION

                        else -> PhonePullBlock.READINESS
                    },
                metadataComplete = metadataComplete,
            )
    }

    override fun close(onDrained: () -> Unit) {
        confined()
        if (closed) return
        closed = true
        mutable.value = PhonePullState()
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
