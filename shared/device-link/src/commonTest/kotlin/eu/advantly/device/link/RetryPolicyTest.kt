package eu.advantly.device.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetryPolicyTest {
    @Test
    fun exponentialBackoffIsCapped() {
        val policy = RetryPolicy(ackTimeoutMs = 1_000, backoffFactor = 2, maxAckTimeoutMs = 5_000)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L), (1..5).map { policy.ackTimeoutFor(it) })
    }

    @Test
    fun invalidPolicyIsRejected() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(ackTimeoutMs = 2_000, maxAckTimeoutMs = 1_000) }
    }
}
