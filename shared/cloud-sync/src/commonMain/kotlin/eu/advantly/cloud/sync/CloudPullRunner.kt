package eu.advantly.cloud.sync

import eu.advantly.data.CloudPullEventWork
import eu.advantly.data.CloudPullWatermark
import eu.advantly.data.LocalData
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
                try {
                    val selected = pulls.capturePull()
                    attempt = selected
                    mutable.value = CloudPullState(CloudPullOutcome.RUNNING, selected.profileId, selected.generation)
                    val started = TimeSource.Monotonic.markNow()
                    val context = currentCoroutineContext()
                    val fence = {
                        confined()
                        context.ensureActive()
                        if (started.elapsedNow().inWholeMilliseconds >= deadlineMillis) throw StopPull(CloudPullOutcome.DEADLINE_REACHED)
                        pulls.requireCurrentPull(selected)
                    }
                    withTimeout(deadlineMillis) {
                        val run = data.cloudPulls.beginRun(selected.profileId, fence).runSerial
                        val budget = Budget(fence)
                        pullMetadata(selected, run, budget)
                        var jobs = 0
                        while (true) {
                            fence()
                            val work = data.cloudPulls.nextEventWork(selected.profileId, run) ?: break
                            if (jobs >= maxEventJobsPerRun) throw StopPull(CloudPullOutcome.LIMIT_REACHED)
                            pullEvents(selected, run, work, budget)
                            jobs++
                            mutable.value = mutable.value.copy(completedEventJobs = jobs)
                        }
                        fence()
                        val removals = data.cloudPulls.pendingRemovals(selected.profileId)
                        val redactions = data.cloudPulls.pendingRedactions(selected.profileId)
                        mutable.value =
                            mutable.value.copy(
                                outcome = if (removals > 0 || redactions > 0) CloudPullOutcome.BLOCKED else CloudPullOutcome.COMPLETE,
                                pendingRemovals = removals,
                                pendingRedactions = redactions,
                            )
                    }
                } catch (stop: StopPull) {
                    finish(attempt, stop.outcome)
                } catch (_: TimeoutCancellationException) {
                    finish(attempt, CloudPullOutcome.DEADLINE_REACHED)
                } catch (error: CancellationException) {
                    finish(attempt, CloudPullOutcome.CANCELLED)
                    throw error
                } catch (error: CloudException) {
                    finish(
                        attempt,
                        if (error.permission) CloudPullOutcome.BLOCKED else CloudPullOutcome.FAILED,
                        "cloud_pull_request_failed",
                    )
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

    private suspend fun pullMetadata(
        attempt: CloudPullAttempt,
        run: Long,
        budget: Budget,
    ) {
        while (true) {
            val cursor = data.cloudPulls.progress(attempt.profileId).cursor
            budget.page()
            val response = pulls.pullPage(attempt, cursor, matchPageSize)
            budget.fence()
            val page = PullMapping.metadata(response, cursor, matchPageSize)
            val applied = data.cloudPulls.commitMetadata(attempt.profileId, run, cursor, page, budget.fence)
            if (applied) mutable.value = mutable.value.copy(completedMetadataPages = mutable.value.completedMetadataPages + 1)
            if (applied && !page.hasMore) return
        }
    }

    private suspend fun pullEvents(
        attempt: CloudPullAttempt,
        run: Long,
        initial: CloudPullEventWork,
        budget: Budget,
    ) {
        var work = initial
        while (true) {
            budget.page()
            val response = pulls.pullEventPage(attempt, work.matchId, work.cursor, eventPageSize)
            budget.fence()
            val received = PullMapping.events(response, work.matchId, work.cursor, eventPageSize)
            data.cloudPulls.commitEvents(work, run, received, budget.fence) { eventsCommitted(work.matchId) }
            mutable.value = mutable.value.copy(completedEventPages = mutable.value.completedEventPages + 1)
            work = requireNotNull(data.cloudPulls.eventWork(attempt.profileId, work.matchId))
            if (response.size == eventPageSize) continue
            budget.rpc()
            val watermarks = pulls.pullWatermarks(attempt, work.matchId)
            budget.fence()
            val complete =
                data.cloudPulls.verifyEvents(
                    work,
                    run,
                    watermarks.map { CloudPullWatermark(it.deviceId, it.maxSeq, it.eventCount) },
                    budget.fence,
                )
            if (complete) return
            work = requireNotNull(data.cloudPulls.eventWork(attempt.profileId, work.matchId))
        }
    }

    private inner class Budget(
        val fence: () -> Unit,
    ) {
        private var pages = 0

        fun page() {
            fence()
            if (pages >= maxPagesPerRun) throw StopPull(CloudPullOutcome.LIMIT_REACHED)
            rpc()
            pages++
        }

        fun rpc() {
            fence()
            if (mutable.value.rpcCount >= maxRpcsPerRun) throw StopPull(CloudPullOutcome.LIMIT_REACHED)
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
                )
            }
    }

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
}
