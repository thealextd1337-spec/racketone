package eu.advantly.cloud.sync

import eu.advantly.data.LocalData
import eu.advantly.data.StoredEvent
import eu.advantly.data.createInMemoryJvmDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudUploadBoundaryTest {
    private fun event(
        id: String,
        device: String,
    ) = StoredEvent(
        id,
        "match",
        device,
        1,
        1,
        """{"type":"PointWon","side":"A"}""",
        type = "PointWon",
    )

    @Test fun hostActivationNeverAdoptsUnknownSourcesAndAckOnlyAppliesSelectedEvents(): Unit =
        runBlocking {
            val driver = createInMemoryJvmDriver()
            val data = LocalData(driver)
            data.events.appendAll(listOf(event("own", "phone"), event("foreign", "watch")))
            val previous = data.cloudUploads.activate("alice")
            data.cloudUploads.bindOwnedDevices(previous, setOf("phone"))
            data.cloudUploads.deactivate()
            val gate = CloudSessionGate({ CloudCredentials("alice", "session-a", "token-a") }, data.cloudUploads)
            gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
            val http = http()
            try {
                val backend = SupabaseCloudBackend(gate, http, CloudInstallation("installation", "a".repeat(64)))
                backend.bindDevice("transport-phone")
                val selected = backend.captureUpload()
                assertEquals(listOf("own"), selected.events.map { it.id })
                backend.pushSelected(selected, PushBatch(events = selected.events))
                assertEquals(1, data.cloud.pendingCount())
                assertTrue(backend.captureUpload().events.isEmpty())
            } finally {
                gate.stop()
                http.close()
                driver.close()
            }
        }

    @Test fun logoutDuringPushLeavesOutboxPendingEvenWhenHttpAlreadySent(): Unit =
        runBlocking {
            val driver = createInMemoryJvmDriver()
            val data = LocalData(driver)
            data.events.append(event("own", "phone"))
            val previous = data.cloudUploads.activate("alice")
            data.cloudUploads.bindOwnedDevices(previous, setOf("phone"))
            val gate = CloudSessionGate({ CloudCredentials("alice", "session-a", "token-a") }, data.cloudUploads)
            gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
            val sent = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val http =
                http {
                    sent.complete(Unit)
                    release.await()
                }
            try {
                val backend = SupabaseCloudBackend(gate, http, CloudInstallation("installation", "a".repeat(64)))
                backend.bindDevice("transport-phone")
                val selected = backend.captureUpload()
                val response = async { runCatching { backend.pushSelected(selected, PushBatch(events = selected.events)) } }
                withTimeout(5_000) { sent.await() }
                gate.stop()
                release.complete(Unit)
                assertTrue(response.await().isFailure)
                assertEquals(1, data.cloud.pendingCount())
                assertFailsWith<CloudException> { backend.pushSelected(selected, PushBatch(events = selected.events)) }
            } finally {
                gate.stop()
                http.close()
                driver.close()
            }
        }

    @Test fun tokenRefreshPreservesSelectedAttemptButSameAccountReloginInvalidatesIt(): Unit =
        runBlocking {
            val driver = createInMemoryJvmDriver()
            val data = LocalData(driver)
            data.events.append(event("own", "phone"))
            val previous = data.cloudUploads.activate("alice")
            data.cloudUploads.bindOwnedDevices(previous, setOf("phone"))
            var credentials = CloudCredentials("alice", "session-a", "token-a")
            val gate = CloudSessionGate({ credentials }, data.cloudUploads)
            gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
            val http = http()
            try {
                val backend = SupabaseCloudBackend(gate, http, CloudInstallation("installation", "a".repeat(64)))
                backend.bindDevice("transport-phone")
                val selected = backend.captureUpload()
                credentials = CloudCredentials("alice", "session-a", "refreshed-token")
                gate.requireUpload(selected.lease, selected.snapshot)
                val transition = gate.stop()
                gate.completeTransition(transition)
                gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
                assertFailsWith<CloudException> { backend.pushSelected(selected, PushBatch(events = selected.events)) }
                assertEquals(1, data.cloud.pendingCount())
            } finally {
                gate.stop()
                http.close()
                driver.close()
            }
        }

    @Test fun successfulRebindInvalidatesSelectionWithoutChangingAuthGeneration(): Unit =
        runBlocking {
            val driver = createInMemoryJvmDriver()
            val data = LocalData(driver)
            data.events.append(event("own", "phone"))
            val previous = data.cloudUploads.activate("alice")
            data.cloudUploads.bindOwnedDevices(previous, setOf("phone"))
            val gate = CloudSessionGate({ CloudCredentials("alice", "session-a", "token-a") }, data.cloudUploads)
            gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
            var pushes = 0
            val http = http { pushes++ }
            try {
                val backend = SupabaseCloudBackend(gate, http, CloudInstallation("installation", "a".repeat(64)))
                backend.bindDevice("transport-phone")
                val selected = backend.captureUpload()
                val generation = gate.session.value!!.generation
                backend.bindDevice("transport-phone")
                assertEquals(generation, gate.session.value!!.generation)
                val error =
                    assertFailsWith<CloudException> {
                        backend.pushSelected(selected, PushBatch(events = selected.events))
                    }
                assertEquals("device_not_authorized", error.reason)
                assertEquals(0, pushes)
                assertEquals(1, data.cloud.pendingCount())

                val currentSelection = backend.captureUpload()
                backend.pushSelected(currentSelection, PushBatch(events = currentSelection.events))
                assertEquals(1, pushes)
                assertEquals(0, data.cloud.pendingCount())
            } finally {
                gate.stop()
                http.close()
                driver.close()
            }
        }

    private fun http(beforePush: suspend () -> Unit = {}): CloudHttp {
        val engine =
            MockEngine {
                val text =
                    if (it.url.encodedPath.endsWith("bind_cloud_session_v1")) {
                        """{"contractVersion":1,"profileId":"alice","sessionId":"session-a","installationId":"installation","deviceId":"transport-phone"}"""
                    } else {
                        assertEquals("Bearer token-a", it.headers["Authorization"])
                        beforePush()
                        """{"events":[{"id":"own","result":"accepted"}]}"""
                    }
                respond(text, headers = headersOf("Content-Type", "application/json"))
            }
        return CloudHttp("https://cloud.test", "public-key", HttpClient(engine) { followRedirects = false })
    }
}
