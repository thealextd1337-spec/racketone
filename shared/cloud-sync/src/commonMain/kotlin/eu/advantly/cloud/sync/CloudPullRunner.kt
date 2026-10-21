package eu.advantly.cloud.sync

import eu.advantly.data.CloudPullEventWork
import eu.advantly.data.CloudPullReadinessResult
import eu.advantly.data.CloudPullStore
import eu.advantly.data.CloudPullWatermark
import eu.advantly.data.LocalData
import eu.advantly.data.ReplicaRedactionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Expliziter D1-Lauf auf dem seriellen LocalData-Kontext; keine Anmeldung, Bindung oder Hintergrundplanung. */
class CloudPullRunner(
    private val pulls: CloudPullPort,
    private val data: LocalData,
    private val maxRpcsPerRun: Int = 8,
    private val maxPagesPerRun: Int = 4,
    private val maxEventJobsPerRun: Int = 4,
    private val deadlineMillis: Long = 15_000,
    private val matchPageSize: Int = 200,
    private val eventPageSize: Int = 500,
    private val threadGuard: () -> Boolean = { true },
    private val eventsCommitted: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(CloudPullState())
    val state = mutable.asStateFlow()
    private var active: Job? = null
    private var closed = false

    init {
        require(maxRpcsPerRun in 1..100 && maxPagesPerRun in 1..100 && maxEventJobsPerRun in 1..100)
        require(deadlineMillis in 1..300_000 && matchPageSize in 1..200 && eventPageSize in 1..500)
    }

    private fun confined() = check(threadGuard()) { "Pull-Runner nur auf dem seriellen Host-Kontext verwenden" }

    suspend fun pullNow(): CloudPullState =
        coroutineScope {
            confined()
            mutex.withLock {
                check(!closed) { "Pull-Runner geschlossen" }
                active = currentCoroutineContext().job
                var attempt: CloudPullAttempt? = null
                var runSerial: Long? = null
                var processingMatch: String? = null
                val context = currentCoroutineContext()

                fun recordDeadlineFailure() {
                    val current = attempt ?: return
                    val serial = runSerial ?: return
                    val match = processingMatch ?: return
                    // Nur den Fehlerfortschritt nach Ablauf vermerken, niemals eine alte Seite quittieren.
                    runCatching {
                        data.cloudPulls.recordEventFailure(current.profileId, match, serial, false) {
                            confined()
                            context.ensureActive()
                            pulls.requireCurrentPull(current)
                        }
                    }
                }
                try {
                    val started = TimeSource.Monotonic.markNow()

                    fun fenceFor(selected: CloudPullAttempt): () -> Unit =
                        {
                            confined()
                            context.ensureActive()
                            if (started.elapsedNow() >= deadlineMillis.milliseconds) throw StopPull(CloudPullOutcome.DEADLINE_REACHED)
                            pulls.requireCurrentPull(selected)
                        }
                    val first = pulls.capturePull()
                    attempt = first
                    mutable.value = CloudPullState(CloudPullOutcome.RUNNING, first.profileId, first.generation)
                    val budget = Budget(fenceFor(first))
                    withTimeout(deadlineMillis) {
                        var jobs = 0
                        var restarts = 0
                        while (true) {
                            // restart_required verwirft Handle und Kontext: neue Quellenautorisierung, neuer Lauf, frischer Katalog.
                            val selected = if (restarts == 0) first else pulls.capturePull()
                            if (selected.profileId != first.profileId) throw CloudException("cloud_pull_account_changed", true)
                            attempt = selected
                            val fence = fenceFor(selected)
                            budget.fence = fence
                            mutable.value = mutable.value.copy(generation = selected.generation, restarts = restarts)
                            try {
                                val run = data.cloudPulls.beginRun(selected.profileId, fence).runSerial
                                runSerial = run
                                if (restarts > 0) data.cloudPulls.restartCatalog(selected.profileId, run, fence)
                                while (true) {
                                    fence()
                                    // ABSENT erst nach vollständigem Katalog, begrenzt je Transaktion und ohne Netzwerkanfrage.
                                    if (data.cloudPulls.applyMissing(selected.profileId, run, fence) > 0) continue
                                    val phase = data.cloudPulls.progress(selected.profileId)
                                    val work = data.cloudPulls.nextEventWork(selected.profileId, run)
                                    // Nach zwölf Eventversuchen eine Katalogseite; danach wieder Events, auch bei hasMore.
                                    // Ohne ersten vollständigen Rundenscan bleibt Eventarbeit gesperrt.
                                    if ((!phase.metadataComplete && (!phase.roundCatalogReady || work == null)) ||
                                        (
                                            work != null &&
                                                phase.eventRequestsSinceMetadata >= CloudPullStore.EVENT_REQUESTS_PER_METADATA_TURN
                                        )
                                    ) {
                                        pullMetadataPage(selected, run, budget)
                                        continue
                                    }
                                    if (work == null) break
                                    if (jobs >= maxEventJobsPerRun) throw StopPull(CloudPullOutcome.LIMIT_REACHED)
                                    budget.requireRequest(page = !work.needsVerification)
                                    val claimed = data.cloudPulls.claimEventWork(work, run, fence)
                                    processingMatch = work.matchId
                                    jobs++
                                    try {
                                        val complete = processMatch(selected, run, claimed, budget)
                                        processingMatch = null
                                        if (complete) {
                                            mutable.value = mutable.value.copy(completedEventJobs = mutable.value.completedEventJobs + 1)
                                        }
                                    } catch (stop: StopPull) {
                                        throw stop
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        // Ein Neustart ist weder Rechteverlust noch Dauerfehler dieses Matches.
                                        if (error.isRestart()) throw error
                                        data.cloudPulls.recordEventFailure(
                                            selected.profileId,
                                            work.matchId,
                                            run,
                                            error is PullNotificationFailure,
                                            fence,
                                        )
                                        throw error
                                    }
                                }
                                fence()
                                data.cloudPulls.finishRound(selected.profileId, run, fence)
                            } catch (error: CloudException) {
                                if (!error.isRestart() || restarts >= MAX_RESTARTS) throw error
                                restarts++
                                processingMatch = null
                                continue
                            }
                            val removals = data.cloudPulls.pendingRemovals(selected.profileId)
                            val redactions = data.cloudPulls.pendingRedactions(selected.profileId)
                            val failures = data.cloudPulls.failedEventJobs(selected.profileId)
                            val materializations = data.cloudPulls.pendingMaterializations(selected.profileId)
                            val readiness = data.cloudPulls.pendingReadiness(selected.profileId)
                            val blocked = removals > 0 || redactions > 0 || materializations > 0 || readiness > 0
                            fence()
                            mutable.value =
                                mutable.value.copy(
                                    outcome =
                                        when {
                                            failures > 0 -> CloudPullOutcome.FAILED
                                            blocked -> CloudPullOutcome.BLOCKED
                                            else -> CloudPullOutcome.COMPLETE
                                        },
                                    pendingRemovals = removals,
                                    pendingRedactions = redactions,
                                    failedEventJobs = failures,
                                    pendingMaterializations = materializations,
                                    pendingReadiness = readiness,
                                    failure =
                                        when {
                                            failures > 0 -> "cloud_pull_event_work_failed"
                                            materializations > 0 -> "cloud_pull_materialization_pending"
                                            else -> null
                                        },
                                )
                            break
                        }
                    }
                } catch (stop: StopPull) {
                    if (stop.outcome == CloudPullOutcome.DEADLINE_REACHED) recordDeadlineFailure()
                    finish(attempt, stop.outcome)
                } catch (_: TimeoutCancellationException) {
                    recordDeadlineFailure()
                    finish(attempt, CloudPullOutcome.DEADLINE_REACHED)
                } catch (error: CancellationException) {
                    finish(attempt, CloudPullOutcome.CANCELLED)
                    throw error
                } catch (error: CloudException) {
                    when {
                        error.isRestart() -> {
                            finish(attempt, CloudPullOutcome.LIMIT_REACHED, "cloud_pull_restart_required")
                        }

                        else -> {
                            finish(
                                attempt,
                                if (error.permission) CloudPullOutcome.BLOCKED else CloudPullOutcome.FAILED,
                                "cloud_pull_request_failed",
                            )
                        }
                    }
                } catch (_: IllegalArgumentException) {
                    finish(attempt, CloudPullOutcome.FAILED, "cloud_pull_invalid_response")
                } catch (_: Exception) {
                    finish(attempt, CloudPullOutcome.FAILED, "cloud_pull_local_failed")
                } finally {
                    active = null
                }
                mutable.value
            }
        }

    private suspend fun pullMetadataPage(
        attempt: CloudPullAttempt,
        run: Long,
        budget: Budget,
    ) {
        val cursor = data.cloudPulls.progress(attempt.profileId).cursor
        budget.page()
        val response = pulls.pullPage(attempt, cursor, matchPageSize)
        budget.fence()
        val page = PullMapping.metadata(response, cursor, matchPageSize)
        val applied = data.cloudPulls.commitMetadata(attempt.profileId, run, cursor, page, budget.fence)
        if (applied) mutable.value = mutable.value.copy(completedMetadataPages = mutable.value.completedMetadataPages + 1)
    }

    private fun turnExhausted(profileId: String) =
        data.cloudPulls.progress(profileId).eventRequestsSinceMetadata >= CloudPullStore.EVENT_REQUESTS_PER_METADATA_TURN

    private fun current(work: CloudPullEventWork) = requireNotNull(data.cloudPulls.eventWork(work.profileId, work.matchId))

    /** Je Match: Redaktion vor jedem Eventimport, danach Events bis zur Prüfung (Readiness bzw. Wasserstand). */
    private suspend fun processMatch(
        attempt: CloudPullAttempt,
        run: Long,
        initial: CloudPullEventWork,
        budget: Budget,
    ): Boolean = pullRedaction(attempt, run, initial, budget) && pullEvents(attempt, run, current(initial), budget)

    /** Persistenter, fortsetzbarer Transfer (G, E, H); IDs und Events behalten getrennte Cursor. */
    private suspend fun pullRedaction(
        attempt: CloudPullAttempt,
        run: Long,
        initial: CloudPullEventWork,
        budget: Budget,
    ): Boolean {
        var work = initial
        while (true) {
            val target = data.cloudPulls.redactionWork(work, run) ?: return true
            if (turnExhausted(attempt.profileId)) return false
            budget.page()
            data.cloudPulls.recordEventRequest(work, run, budget.fence)
            require(target.redactionEpoch <= Int.MAX_VALUE)
            val page =
                pulls.pullRedactionPage(
                    attempt,
                    CloudRedactionRequest(
                        work.matchId,
                        target.generation,
                        target.redactionEpoch.toInt(),
                        target.cursor.afterPlayerId,
                        target.cursor.afterServerSeq,
                        target.highWatermark,
                    ),
                )
            budget.fence()
            data.cloudPulls.stageRedaction(
                work,
                run,
                target,
                page.highWatermark,
                page.redactedPlayerIds,
                page.events.map { ReplicaRedactionEvent(EventRows.toStored(it.toRow()), it.serverSeq) },
                idsDone = !page.hasMorePlayers,
                eventsDone = !page.hasMoreEvents,
                fence = budget.fence,
            )
            mutable.value = mutable.value.copy(completedRedactionPages = mutable.value.completedRedactionPages + 1)
            work = current(work)
        }
    }

    private suspend fun pullEvents(
        attempt: CloudPullAttempt,
        run: Long,
        initial: CloudPullEventWork,
        budget: Budget,
    ): Boolean {
        var work = initial
        while (true) {
            if (data.cloudPulls.progress(attempt.profileId).eventRequestsSinceMetadata >= CloudPullStore.EVENT_REQUESTS_PER_METADATA_TURN) {
                return false
            }
            if (!work.needsVerification) {
                budget.page()
                data.cloudPulls.recordEventRequest(work, run, budget.fence)
                val response = pulls.pullEventPage(attempt, work.matchId, work.cursor, eventPageSize)
                budget.fence()
                val received = PullMapping.events(response, work.matchId, work.cursor, eventPageSize)
                data.cloudPulls.commitEvents(work, run, received, budget.fence, endOfStream = response.size < eventPageSize) {
                    try {
                        eventsCommitted(work.matchId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        throw PullNotificationFailure(error)
                    }
                }
                mutable.value = mutable.value.copy(completedEventPages = mutable.value.completedEventPages + 1)
                work = requireNotNull(data.cloudPulls.eventWork(attempt.profileId, work.matchId))
                if (!work.needsVerification) continue
            }
            if (data.cloudPulls.progress(attempt.profileId).eventRequestsSinceMetadata >= CloudPullStore.EVENT_REQUESTS_PER_METADATA_TURN) {
                return false
            }
            budget.rpc()
            data.cloudPulls.recordEventRequest(work, run, budget.fence)
            // Der an G/E und das Runden-H gebundene Vektor ersetzt den Wasserstandsvergleich, auch für ready-Matches;
            // Wasserstände nur, solange kein abgeschlossener Katalog bei G vorliegt (Neuscan).
            val target = data.cloudPulls.readinessWork(work, run)
            if (target != null) {
                require(target.redactionEpoch <= Int.MAX_VALUE)
                val readiness =
                    pulls.pullReadiness(
                        attempt,
                        CloudReadinessRequest(work.matchId, target.generation, target.redactionEpoch.toInt(), target.highWatermark),
                    )
                budget.fence()
                val result =
                    data.cloudPulls.applyReadiness(
                        work,
                        run,
                        target,
                        readiness.highWatermark,
                        readiness.serverVersion,
                        readiness.watermarks.map { CloudPullWatermark(it.deviceId, it.maxSeq, it.eventCount) },
                        budget.fence,
                    )
                // Fehlende Events oder Lücken öffnen die Eventarbeit erneut; ein älterer Snapshot wartet auf den Katalog.
                if (result == CloudPullReadinessResult.READY || result == CloudPullReadinessResult.STALE_SNAPSHOT) return true
                work = current(work)
                continue
            }
            val watermarks = pulls.pullWatermarks(attempt, work.matchId)
            budget.fence()
            val complete =
                data.cloudPulls.verifyEvents(
                    work,
                    run,
                    watermarks.map { CloudPullWatermark(it.deviceId, it.maxSeq, it.eventCount) },
                    budget.fence,
                )
            if (complete) return true
            work = requireNotNull(data.cloudPulls.eventWork(attempt.profileId, work.matchId))
        }
    }

    private inner class Budget(
        var fence: () -> Unit,
    ) {
        private var pages = 0

        fun requireRequest(page: Boolean) {
            fence()
            if (mutable.value.rpcCount >= maxRpcsPerRun || (page && pages >= maxPagesPerRun)) {
                throw StopPull(CloudPullOutcome.LIMIT_REACHED)
            }
        }

        fun page() {
            requireRequest(page = true)
            rpc()
            pages++
        }

        fun rpc() {
            requireRequest(page = false)
            mutable.value = mutable.value.copy(rpcCount = mutable.value.rpcCount + 1)
        }
    }

    private fun finish(
        attempt: CloudPullAttempt?,
        outcome: CloudPullOutcome,
        failure: String? = null,
    ) {
        if (closed) return
        val current = attempt != null && runCatching { pulls.requireCurrentPull(attempt) }.isSuccess
        mutable.value =
            if (!current) {
                CloudPullState(outcome = outcome, failure = failure)
            } else {
                mutable.value.copy(
                    outcome = outcome,
                    failure = failure,
                    pendingRemovals = data.cloudPulls.pendingRemovals(attempt.profileId),
                    pendingRedactions = data.cloudPulls.pendingRedactions(attempt.profileId),
                    failedEventJobs = data.cloudPulls.failedEventJobs(attempt.profileId),
                    pendingMaterializations = data.cloudPulls.pendingMaterializations(attempt.profileId),
                    pendingReadiness = data.cloudPulls.pendingReadiness(attempt.profileId),
                )
            }
    }

    private fun Exception.isRestart() = this is CloudException && reason == RESTART_REQUIRED

    /** Beendet ausschließlich den Runner-Child; Auth, HTTP und gemeinsame Datenbank bleiben beim Host. */
    fun close() {
        confined()
        closed = true
        active?.cancel(CancellationException("cloud_pull_closed"))
        mutable.value = CloudPullState(CloudPullOutcome.CLOSED)
    }

    private class StopPull(
        val outcome: CloudPullOutcome,
    ) : Exception()

    private class PullNotificationFailure(
        cause: Exception,
    ) : Exception(cause)

    private companion object {
        const val RESTART_REQUIRED = "restart_required"
        const val MAX_RESTARTS = 2
    }
}
