package eu.advantly.cloud.sync

import eu.advantly.data.CloudUploadLifecycleChangedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Expliziter, begrenzter Upload auf dem seriellen LocalData-/Auth-Host-Kontext (Android: Main).
 * Ein Runner je Backend/Host; weder Dispatcherwechsel noch Scheduler, Bindung, Reauth oder Pull.
 * Der Host aktualisiert die Momentaufnahme nach lokalen Änderungen über [refresh] und schließt vor Hostabbau.
 * Ein verlorener Request bleibt bis zum nächsten expliziten Aufruf als unveränderlicher Versuch erhalten.
 * [sourceDevices] beschreibt ausdrücklich bekannte eigene Quellen (z. B. übernommene Wear-OS-Uhr).
 * Nur Quellen ausgewählter Events werden mitgesendet; daraus entsteht niemals eine Caller-Bindung.
 */
class CloudUploadRunner(
    private val uploads: CloudUploadPort,
    private val nowMs: () -> Long,
    private val maxBatchesPerRun: Int = 4,
    private val maxEventsPerBatch: Int = PushBatch.MAX_EVENTS_PER_PUSH,
    private val threadGuard: () -> Boolean = { true },
    sourceDevices: List<DeviceDto> = emptyList(),
) {
    private val sourceDevices = sourceDevices.toList()
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(CloudUploadState())
    val state = mutable.asStateFlow()
    private var retry: CloudUploadAttempt? = null
    private var active: Job? = null
    private var closed = false

    init {
        require(maxBatchesPerRun in 1..100)
        require(maxEventsPerBatch in 1..PushBatch.MAX_EVENTS_PER_PUSH)
        require(sourceDevices.map { it.id }.distinct().size == sourceDevices.size)
    }

    private fun confined() = check(threadGuard()) { "Upload-Runner nur auf dem seriellen Host-Kontext verwenden" }

    /** Gleichzeitig aufgerufene Läufe warten seriell; jeder führt höchstens sein konfiguriertes Budget aus. */
    suspend fun uploadNow(): CloudUploadState =
        coroutineScope {
            confined()
            mutex.withLock {
                check(!closed) { "Upload-Runner geschlossen" }
                active = currentCoroutineContext().job
                var attempt: CloudUploadAttempt? = retry
                try {
                    val selected = attempt ?: uploads.captureUpload(maxEventsPerBatch)
                    attempt = selected
                    uploads.requireCurrentUpload(selected)
                    val previous = mutable.value
                    mutable.value =
                        CloudUploadState(
                            outcome = CloudUploadOutcome.RUNNING,
                            profileId = selected.profileId,
                            generation = selected.generation,
                            inventory = if (retry == null) selected.inventory else uploads.uploadInventory(selected),
                            observedAtMs = nowMs(),
                            lastSuccessAtMs =
                                previous.lastSuccessAtMs.takeIf {
                                    previous.profileId == selected.profileId && previous.generation == selected.generation
                                },
                        )
                    for (index in 0 until maxBatchesPerRun) {
                        currentCoroutineContext().ensureActive()
                        val current = requireNotNull(attempt)
                        val selected = current.batch
                        val sources = selected.events.mapTo(mutableSetOf()) { it.deviceId }
                        val batch = selected.copy(devices = sourceDevices.filter { it.id in sources })
                        if (batch.events.isEmpty() && batch.matches.isEmpty() && batch.players.isEmpty()) {
                            // Blockierte Matches bleiben ausstehend: niemals DRAINED.
                            mutable.value =
                                mutable.value.copy(
                                    outcome =
                                        if (current.inventory.pendingEvents > 0 || current.inventory.pendingMetadata > 0 ||
                                            current.inventory.rejectedEvents > 0
                                        ) {
                                            CloudUploadOutcome.BLOCKED
                                        } else {
                                            CloudUploadOutcome.DRAINED
                                        },
                                )
                            break
                        }
                        // Auch externe Cancellation kann nach einem Server-Commit eintreten: Auswahl aufbewahren.
                        retry = current
                        val response = uploads.pushSelected(current, batch)
                        retry = null
                        mutable.value =
                            mutable.value.copy(lastSuccessAtMs = nowMs(), completedBatches = index + 1)
                        val update = OutboxPlanner.evaluate(batch, response)
                        val acknowledged = current.snapshot.acknowledgedMetadata(batch, response)
                        val incomplete = update.retry.isNotEmpty() || acknowledged != current.snapshot.metadataRevisions
                        val next = uploads.nextUpload(current, maxEventsPerBatch)
                        attempt = next
                        mutable.value =
                            mutable.value.copy(
                                inventory = next.inventory,
                                observedAtMs = nowMs(),
                                missingEventResponseIds = batch.events.map { it.id }.toSet() - response.events.mapNotNull { it.id }.toSet(),
                                outcome =
                                    when {
                                        incomplete -> {
                                            CloudUploadOutcome.PARTIAL
                                        }

                                        next.inventory.pendingEvents == 0L && next.inventory.pendingMetadata == 0L -> {
                                            if (next.inventory.rejectedEvents ==
                                                0L
                                            ) {
                                                CloudUploadOutcome.DRAINED
                                            } else {
                                                CloudUploadOutcome.BLOCKED
                                            }
                                        }

                                        next.batch.let { it.events.isEmpty() && it.matches.isEmpty() && it.players.isEmpty() } -> {
                                            // Nur blockierte Reste: ein sofortiger neuer Lauf brächte nichts.
                                            CloudUploadOutcome.BLOCKED
                                        }

                                        else -> {
                                            CloudUploadOutcome.LIMIT_REACHED
                                        }
                                    },
                            )
                        if (incomplete || mutable.value.outcome in setOf(CloudUploadOutcome.DRAINED, CloudUploadOutcome.BLOCKED)) break
                        if (index + 1 < maxBatchesPerRun) {
                            mutable.value = mutable.value.copy(outcome = CloudUploadOutcome.RUNNING)
                        }
                    }
                } catch (error: CancellationException) {
                    finishFailure(attempt, CloudUploadOutcome.CANCELLED, null)
                    throw error
                } catch (error: CloudException) {
                    val kind =
                        when {
                            error.reason == "cloud_response_invalid" -> CloudUploadFailureKind.RESPONSE
                            error.reason == "rate_limit_exceeded" -> CloudUploadFailureKind.TRANSIENT
                            error.permission -> CloudUploadFailureKind.PERMISSION
                            else -> CloudUploadFailureKind.TRANSIENT
                        }
                    val restart = error.reason == RESTART_REQUIRED
                    // 40001 ohne Teilwrites: Snapshot verwerfen, kein Rechteverlust, Profil erst nach Pull erneut senden.
                    if (kind == CloudUploadFailureKind.PERMISSION || restart) retry = null
                    val reason = error.reason.takeIf { it in FAILURE_REASONS } ?: "cloud_upload_failed"
                    finishFailure(attempt, CloudUploadOutcome.FAILED, CloudUploadFailure(kind, reason))
                    // Scheitert die Sperre, liefert der nächste Versuch höchstens erneut 40001.
                    if (restart) runCatching { attempt?.snapshot?.deferUntilPull() }
                } catch (_: CloudUploadLifecycleChangedException) {
                    // Kontext oder G/E zwischen Auswahl, Versand und Ack geändert: keine Acks, neue Auswahl im nächsten Lauf.
                    retry = null
                    finishFailure(
                        attempt,
                        CloudUploadOutcome.FAILED,
                        CloudUploadFailure(CloudUploadFailureKind.TRANSIENT, LIFECYCLE_CHANGED),
                        staleSnapshot = true,
                    )
                } catch (error: IllegalStateException) {
                    finishFailure(
                        attempt,
                        CloudUploadOutcome.FAILED,
                        CloudUploadFailure(CloudUploadFailureKind.LOCAL, "cloud_upload_local_failed"),
                    )
                    throw error
                } catch (_: Exception) {
                    finishFailure(
                        attempt,
                        CloudUploadOutcome.FAILED,
                        CloudUploadFailure(CloudUploadFailureKind.LOCAL, "cloud_upload_local_failed"),
                    )
                } finally {
                    active = null
                    if (mutable.value.running) mutable.value = mutable.value.copy(outcome = CloudUploadOutcome.CANCELLED)
                }
                mutable.value
            }
        }

    /** Nur lokal lesen; keine Antwort und damit auch kein neuer letzter Erfolg. */
    suspend fun refresh(): CloudUploadState {
        confined()
        return mutex.withLock {
            check(!closed) { "Upload-Runner geschlossen" }
            try {
                val status = uploads.readUploadStatus(retry)
                val previous = mutable.value
                mutable.value =
                    CloudUploadState(
                        profileId = status.profileId,
                        generation = status.generation,
                        inventory = status.inventory,
                        observedAtMs = nowMs(),
                        lastSuccessAtMs =
                            previous.lastSuccessAtMs.takeIf {
                                previous.profileId == status.profileId && previous.generation == status.generation
                            },
                    )
            } catch (_: CloudException) {
                retry = null
                mutable.value = CloudUploadState()
            }
            mutable.value
        }
    }

    private fun finishFailure(
        attempt: CloudUploadAttempt?,
        outcome: CloudUploadOutcome,
        failure: CloudUploadFailure?,
        staleSnapshot: Boolean = false,
    ) {
        if (closed) return
        val inventory =
            attempt?.let { failed ->
                runCatching { uploads.uploadInventory(failed) }
                    .recoverCatching { error ->
                        // Nur ein veralteter Snapshot einer aktiven Sitzung behält seinen Status; sonst wie bisher leeren.
                        if (!staleSnapshot) throw error
                        val status = uploads.readUploadStatus()
                        check(status.profileId == failed.profileId && status.generation == failed.generation)
                        status.inventory
                    }.getOrNull()
            }
        mutable.value =
            if (inventory == null) {
                // Ein Kontowechsel darf weder den vorherigen Erfolg noch dessen Restbestand übernehmen.
                retry = null
                CloudUploadState(outcome = outcome, failure = failure)
            } else {
                mutable.value.copy(outcome = outcome, inventory = inventory, observedAtMs = nowMs(), failure = failure)
            }
    }

    /** Cancelt nur den laufenden Runner-Child, nicht Auth-Host, HTTP-Client oder LocalData. */
    fun close() {
        confined()
        closed = true
        retry = null
        active?.cancel(CancellationException("cloud_upload_closed"))
        mutable.value = CloudUploadState(outcome = CloudUploadOutcome.CLOSED)
    }

    private companion object {
        const val RESTART_REQUIRED = "restart_required"
        const val LIFECYCLE_CHANGED = "cloud_lifecycle_changed"

        val FAILURE_REASONS =
            setOf(
                RESTART_REQUIRED,
                LIFECYCLE_CHANGED,
                "cloud_connection_failed",
                "cloud_response_invalid",
                "cloud_session_changed",
                "device_not_authorized",
                "device_revoked",
                "profile_not_active",
                "profile_missing",
                "consent_required",
                "consent_unavailable",
                "consent_version_changed",
                "rate_limit_exceeded",
            )
    }
}
