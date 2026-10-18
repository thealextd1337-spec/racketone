package eu.advantly.cloud.sync

import eu.advantly.data.AdoptionSource
import eu.advantly.data.CloudUploadSession
import eu.advantly.data.LocalAccountOwnership
import eu.advantly.data.LocalData
import eu.advantly.data.StoredEvent
import eu.advantly.data.createInMemoryJvmDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConsentUploadGateTest {
    private class Owner : LocalAccountOwnership {
        var profile: String? = null
        var leaves = 0

        override fun leaveAccount() {
            leaves++
            profile = null
        }

        override fun activateAccount(
            profileId: String,
            session: CloudUploadSession,
        ) {
            profile = profileId
        }

        override fun adoptionSources(): List<AdoptionSource> = emptyList()

        override fun adoptSources(
            session: CloudUploadSession,
            deviceIds: Set<String>,
        ) = Unit
    }

    private fun prepare(data: LocalData) {
        data.events.append(StoredEvent("event", "match", "phone", 1, 1, """{"type":"PointWon","side":"A"}""", type = "PointWon"))
        val previous = data.cloudUploads.activate("alice")
        data.cloudUploads.bindOwnedDevices(previous, setOf("phone"))
        data.cloudUploads.deactivate()
    }

    @Test
    fun everyNonCurrentConsentBlocksCloudButLeavesIndependentAccountRequestsAvailable(): Unit =
        runBlocking {
            ConsentStatus.entries.filter { it != ConsentStatus.CURRENT }.forEach { status ->
                val driver = createInMemoryJvmDriver()
                try {
                    val data = LocalData(driver)
                    prepare(data)
                    val gate = CloudSessionGate({ CloudCredentials("alice", "session", "token") }, data.cloudUploads)
                    gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = status))
                    assertFailsWith<CloudException> { gate.capture() }
                    assertFailsWith<CloudException> { gate.captureUpload(gate.capture(false), 10) }
                    assertEquals("account-rpc", gate.request(gate.capture(false)) { "account-rpc" })
                    assertEquals(1, data.cloud.pendingCount())
                    gate.stop()
                } finally {
                    driver.close()
                }
            }
        }

    @Test
    fun consentChangeInvalidatesOldAcksPermanentlyWithoutLeavingAccountOrLosingPendingData() {
        val driver = createInMemoryJvmDriver()
        try {
            val data = LocalData(driver)
            prepare(data)
            val owner = Owner()
            val gate = CloudSessionGate({ CloudCredentials("alice", "session", "token") }, data.cloudUploads, ownership = owner)
            gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
            val lease = gate.capture()
            val selected = gate.captureUpload(lease, 10)
            val staleProfile = gate.capture(false)
            gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.REQUIRED))
            assertFailsWith<CloudException> {
                gate.publish(staleProfile, CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
            }
            assertFailsWith<CloudException> { gate.acknowledge(lease, selected, setOf("event"), emptyMap()) }
            assertEquals("alice", owner.profile)
            assertEquals(0, owner.leaves)
            assertEquals(1, data.cloud.pendingCount())
            gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
            assertFailsWith<CloudException> { gate.acknowledge(lease, selected, setOf("event"), emptyMap()) }
            val renewed = gate.capture()
            gate.acknowledge(renewed, gate.captureUpload(renewed, 10), setOf("event"), emptyMap())
            assertEquals(0, data.cloud.pendingCount())
            assertEquals(1, data.events.unconfirmedCount())
            gate.stop()
        } finally {
            driver.close()
        }
    }

    @Test
    fun consentRevocationCancelsOnlyCloudRequestsAndPreventsTheirLateAck(): Unit =
        runBlocking {
            val driver = createInMemoryJvmDriver()
            try {
                val data = LocalData(driver)
                prepare(data)
                val gate = CloudSessionGate({ CloudCredentials("alice", "session", "token") }, data.cloudUploads)
                gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
                val lease = gate.capture()
                val selected = gate.captureUpload(lease, 10)
                val cloudSent = CompletableDeferred<Unit>()
                val accountSent = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val cloud =
                    async {
                        runCatching {
                            gate.request(lease) {
                                cloudSent.complete(Unit)
                                release.await()
                            }
                            gate.acknowledge(lease, selected, setOf("event"), emptyMap())
                        }
                    }
                val account =
                    async {
                        gate.request(gate.capture(false)) {
                            accountSent.complete(Unit)
                            release.await()
                            "export-or-consent"
                        }
                    }
                withTimeout(5_000) {
                    cloudSent.await()
                    accountSent.await()
                }
                gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.DECLINED))
                release.complete(Unit)
                assertTrue(cloud.await().isFailure)
                assertEquals("export-or-consent", account.await())
                assertEquals(1, data.cloud.pendingCount())
                gate.stop()
            } finally {
                driver.close()
            }
        }
}
