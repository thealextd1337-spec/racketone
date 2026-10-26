package eu.advantly.cloud.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudReadinessBoundaryTest {
    private val match = "10000000-0000-0000-0000-000000000001"
    private val phone = "30000000-0000-0000-0000-000000000001"
    private val watch = "30000000-0000-0000-0000-000000000002"
    private val request = CloudReadinessRequest(match, generation = 7, redactionEpoch = 0, highWatermark = 12)

    private fun readiness() = CloudMatchReadiness(match, 7, 0, 12, 41, listOf(EventWatermark(phone, 8, 8), EventWatermark(watch, 4, 4)))

    private fun encoded(value: CloudMatchReadiness) = CloudJson.encodeToString(CloudMatchReadiness.serializer(), value)

    @Test
    fun requestCarriesGenerationEpochAndFixedWatermark() =
        runBlocking<Unit> {
            PullFixture().use { f ->
                f.bind()
                val attempt = f.backend.capturePull()
                f.readiness = { encoded(readiness()) }
                assertEquals(readiness(), f.backend.pullReadiness(attempt, request))
                val body = f.calls.last().second
                assertEquals(7L, body.long("p_generation"))
                assertEquals(0L, body.long("p_redaction_epoch"))
                assertEquals(12L, body.long("p_high_watermark"))
                f.readiness = { encoded(readiness().copy(highWatermark = 0, serverVersion = 3, watermarks = emptyList())) }
                assertTrue(
                    f.backend
                        .pullReadiness(attempt, request.copy(highWatermark = 0))
                        .watermarks
                        .isEmpty(),
                )
                // E = 0 ohne Transfer: Server hält H fest und liefert es zurück.
                f.readiness = { encoded(readiness().copy(highWatermark = 30)) }
                assertEquals(30L, f.backend.pullReadiness(attempt, request.copy(highWatermark = null)).highWatermark)
                assertTrue("p_high_watermark" !in f.calls.last().second)
                f.readiness = { encoded(readiness().copy(highWatermark = -1, watermarks = emptyList())) }
                assertFailsWith<CloudException> { f.backend.pullReadiness(attempt, request.copy(highWatermark = null)) }
            }
        }

    @Test
    fun responsesOutsideTheRequestedContextOrBoundsAreRejected() =
        runBlocking<Unit> {
            val original = readiness()
            val tooMany =
                (0..CloudMatchReadiness.MAX_READINESS_DEVICES).map {
                    EventWatermark("30000000-0000-0000-0000-" + it.toString().padStart(12, '0'), 0, 1)
                }
            val invalid =
                listOf(
                    original.copy(matchId = phone),
                    original.copy(generation = 8),
                    original.copy(redactionEpoch = 1),
                    original.copy(highWatermark = 13),
                    original.copy(serverVersion = -1),
                    original.copy(watermarks = original.watermarks.reversed()),
                    original.copy(watermarks = listOf(original.watermarks[0], original.watermarks[0])),
                    original.copy(watermarks = listOf(EventWatermark(phone.uppercase().replace('3', 'C'), 1, 1))),
                    original.copy(watermarks = listOf(EventWatermark(phone, 1, 0))),
                    original.copy(watermarks = listOf(EventWatermark(phone, -1, 1))),
                    original.copy(watermarks = listOf(EventWatermark(phone, 20, 13))),
                )
            PullFixture().use { f ->
                f.bind()
                val attempt = f.backend.capturePull()
                for (bad in invalid) {
                    f.readiness = { encoded(bad) }
                    val error = assertFailsWith<CloudException> { f.backend.pullReadiness(attempt, request) }
                    assertEquals("cloud_response_invalid", error.reason, bad.toString())
                }
                val wide = request.copy(highWatermark = 10_000)
                f.readiness = { encoded(original.copy(highWatermark = 10_000, watermarks = tooMany)) }
                assertEquals("cloud_response_invalid", assertFailsWith<CloudException> { f.backend.pullReadiness(attempt, wide) }.reason)
                f.readiness = { encoded(original.copy(highWatermark = 10_000, watermarks = tooMany.dropLast(1))) }
                assertEquals(
                    CloudMatchReadiness.MAX_READINESS_DEVICES,
                    f.backend
                        .pullReadiness(attempt, wide)
                        .watermarks.size,
                )
                f.readiness = { """{"matchId":"$match"}""" }
                assertEquals("cloud_response_invalid", assertFailsWith<CloudException> { f.backend.pullReadiness(attempt, request) }.reason)
            }
        }

    @Test
    fun staleGenerationOrEpochIsRestartNotPermissionLoss() =
        runBlocking<Unit> {
            PullFixture().use { f ->
                f.bind()
                val attempt = f.backend.capturePull()
                val restart = """{"code":"40001","message":"restart_required","details":null,"hint":null}"""
                f.readiness = { restart }
                val readinessError = assertFailsWith<CloudException> { f.backend.pullReadiness(attempt, request) }
                assertEquals("restart_required", readinessError.reason)
                assertFalse(readinessError.permission)
                f.redaction = { restart }
                val redactionError =
                    assertFailsWith<CloudException> {
                        f.backend.pullRedactionPage(attempt, CloudRedactionRequest(match, generation = 7, redactionEpoch = 0))
                    }
                assertEquals("restart_required", redactionError.reason)
                assertFalse(redactionError.permission)
                // Bekannte Rechtefehler behalten ihre bisherige Einstufung.
                f.readiness = { """{"code":"P0001","message":"device_revoked"}""" }
                val revoked = assertFailsWith<CloudException> { f.backend.pullReadiness(attempt, request) }
                assertEquals("device_revoked", revoked.reason)
                assertTrue(revoked.permission)
            }
        }

    @Test
    fun staleNonCooperativeResponsesFailAfterEveryAuthorityTransition() =
        runBlocking<Unit> {
            for (transition in listOf("account", "same-account-login", "logout", "consent", "deletion", "rebind")) {
                PullFixture().use { f ->
                    f.bind()
                    val attempt = f.backend.capturePull()
                    f.readiness = {
                        withContext(NonCancellable) {
                            when (transition) {
                                "account" -> {
                                    f.credentials = CloudCredentials("bob", "bob-session", "bob-token")
                                }

                                "same-account-login" -> {
                                    f.credentials = CloudCredentials("alice", "new-session", "new-token")
                                }

                                "logout" -> {
                                    f.gate.stop()
                                    f.credentials = null
                                }

                                "consent" -> {
                                    f.publish(ConsentStatus.REQUIRED)
                                }

                                "deletion" -> {
                                    f.publish(deleting = true)
                                }

                                "rebind" -> {
                                    f.backend.bindDevice("phone")
                                }
                            }
                            encoded(readiness())
                        }
                    }
                    var rejected = false
                    try {
                        f.backend.pullReadiness(attempt, request)
                    } catch (_: CloudException) {
                        rejected = true
                    } catch (_: CancellationException) {
                        rejected = true
                    }
                    assertTrue(rejected, transition)
                }
            }
        }

    @Test
    fun requestRejectsNegativeOrMalformedContext() {
        assertFailsWith<IllegalArgumentException> { request.copy(matchId = "x") }
        assertFailsWith<IllegalArgumentException> { request.copy(generation = -1) }
        assertFailsWith<IllegalArgumentException> { request.copy(redactionEpoch = -1) }
        assertFailsWith<IllegalArgumentException> { request.copy(highWatermark = -1) }
    }
}
