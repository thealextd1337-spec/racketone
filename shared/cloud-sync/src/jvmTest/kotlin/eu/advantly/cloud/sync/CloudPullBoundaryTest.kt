package eu.advantly.cloud.sync

import eu.advantly.data.CloudPullEntityKind
import eu.advantly.data.LocalData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Die Änderung geschieht erst nach dem vollständig geprüften HTTP-Rückweg des Backends. */
internal class AfterPullPort(
    private val backend: CloudPullPort,
    private val afterMetadata: suspend () -> Unit = {},
    private val afterEvents: suspend () -> Unit = {},
) : CloudPullPort by backend {
    override suspend fun pullPage(
        attempt: CloudPullAttempt,
        cursor: Long,
        limit: Int,
    ): PullResponse = backend.pullPage(attempt, cursor, limit).also { afterMetadata() }

    override suspend fun pullEventPage(
        attempt: CloudPullAttempt,
        matchId: String,
        afterServerSeq: Long,
        limit: Int,
    ): List<PulledEvent> = backend.pullEventPage(attempt, matchId, afterServerSeq, limit).also { afterEvents() }
}

class CloudPullBoundaryTest {
    private val transitions: List<Pair<String, suspend (PullFixture) -> Unit>> =
        listOf(
            "account" to { f -> f.credentials = CloudCredentials("bob", "session-bob", "token-bob") },
            "same-account-login" to { f -> f.credentials = CloudCredentials("alice", "session-alice-new", "token-new") },
            "logout" to { f ->
                f.gate.stop()
                f.credentials = null
            },
            "consent" to { f -> f.publish(ConsentStatus.REQUIRED) },
            "consent-regain" to { f ->
                f.publish(ConsentStatus.REQUIRED)
                f.publish()
            },
            "deletion" to { f -> f.publish(deleting = true) },
            "rebind" to { f -> f.backend.bindDevice("phone") },
        )

    @Test
    fun allSessionConsentAndBindingTransitionsAfterMetadataResponsePreventCommitAndOldSuccess() =
        runBlocking {
            for ((name, transition) in transitions) {
                PullFixture().use { f ->
                    f.bind()
                    f.metadata = { _, _ -> PullResponse(listOf(pullMatch()), cursor = 1, visibilityEpoch = 1) }
                    val port = AfterPullPort(f.backend, afterMetadata = { transition(f) })
                    val result = CloudPullRunner(port, f.data).pullNow()
                    assertFalse(result.outcome == CloudPullOutcome.COMPLETE, name)
                    assertNull(result.profileId, name)
                    assertEquals(
                        0,
                        f.data.cloudPulls
                            .progress("alice")
                            .cursor,
                        name,
                    )
                    assertNull(f.data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "match"), name)
                    assertNull(f.data.cloudPulls.eventWork("alice", "match"), name)
                }
            }
        }

    @Test
    fun allTransitionsAfterEventResponsePreserveMetadataButPreventEventsReceiptsAndEventCursor() =
        runBlocking {
            for ((name, transition) in transitions) {
                PullFixture().use { f ->
                    f.bind()
                    f.metadata = { _, _ -> PullResponse(listOf(pullMatch()), cursor = 1, visibilityEpoch = 1) }
                    f.events = { _, _, _ -> listOf(pullEvent(1)) }
                    val port = AfterPullPort(f.backend, afterEvents = { transition(f) })
                    val result = CloudPullRunner(port, f.data).pullNow()
                    assertFalse(result.outcome == CloudPullOutcome.COMPLETE, name)
                    assertNull(result.profileId, name)
                    assertEquals(
                        1,
                        f.data.cloudPulls
                            .progress("alice")
                            .cursor,
                        name,
                    )
                    assertEquals(0, f.data.events.count("match"), name)
                    val work = assertNotNull(f.data.cloudPulls.eventWork("alice", "match"))
                    assertEquals(0, work.cursor, name)
                    assertTrue(
                        f.data.cloudPulls
                            .receivedWatermarks(work)
                            .isEmpty(),
                        name,
                    )
                }
            }
        }

    @Test
    fun sameSessionRefreshRetainsAttemptAndUsesOnlyItsFrozenBearer() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                f.metadata = { _, _ -> PullResponse(listOf(pullMatch()), cursor = 1, visibilityEpoch = 1) }
                val port =
                    AfterPullPort(f.backend, afterMetadata = {
                        f.credentials = CloudCredentials("alice", "session-alice", "refreshed-token")
                    })
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(port, f.data).pullNow().outcome)
                assertTrue(f.bearers.all { it == "Bearer token-alice" })
            }
        }

    @Test
    fun deadlineAndCloseCancelOnlyRunnerAndNeverMarkSuccess() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                f.metadata = { _, _ ->
                    delay(300)
                    PullResponse(cursor = 1, visibilityEpoch = 1)
                }
                val deadline = CloudPullRunner(f.backend, f.data, deadlineMillis = 30)
                assertEquals(CloudPullOutcome.DEADLINE_REACHED, deadline.pullNow().outcome)
                assertEquals(
                    0,
                    f.data.cloudPulls
                        .progress("alice")
                        .cursor,
                )
                val runner = CloudPullRunner(f.backend, f.data)
                val child = async { runner.pullNow() }
                delay(30)
                runner.close()
                try {
                    child.await()
                } catch (_: CancellationException) {
                }
                assertEquals(CloudPullOutcome.CLOSED, runner.state.value.outcome)
                assertTrue(coroutineContext[kotlinx.coroutines.Job]!!.isActive)
                f.data.settings.put("still-open", "yes")
                assertEquals("yes", f.data.settings.get("still-open"))
                assertNotNull(f.gate.capture())
                Unit
            }
        }

    @Test
    fun noBindingOrNonCurrentConsentIsBlockedBeforeAnyReadRpc() =
        runBlocking {
            PullFixture().use { f ->
                f.publish()
                assertEquals(CloudPullOutcome.BLOCKED, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                assertTrue(f.calls.isEmpty())
                f.backend.bindDevice("phone")
                f.publish(ConsentStatus.UNAVAILABLE)
                assertEquals(CloudPullOutcome.BLOCKED, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                assertEquals(listOf("bind_cloud_session_v1"), f.calls.map { it.first })
            }
        }
}
