package eu.advantly.cloud.sync

import eu.advantly.data.AdoptionSource
import eu.advantly.data.CloudUploadSession
import eu.advantly.data.LocalAccountOwnership
import eu.advantly.data.LocalData
import eu.advantly.data.StoredEvent
import eu.advantly.data.createInMemoryJvmDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OwnedSourceUploadPortTest {
    private class Fixture : AutoCloseable {
        val driver = InventoryRecordingDriver(createInMemoryJvmDriver())
        val data = LocalData(driver)
        lateinit var active: CloudUploadSession
        private val ownership =
            object : LocalAccountOwnership {
                override fun leaveAccount() = Unit

                override fun activateAccount(
                    profileId: String,
                    session: CloudUploadSession,
                ) {
                    active = session
                    data.cloudUploads.bindOwnedDevices(session, setOf("phone", "owner-only"))
                }

                override fun adoptionSources(): List<AdoptionSource> = emptyList()

                override fun adoptSources(
                    session: CloudUploadSession,
                    deviceIds: Set<String>,
                ) = data.cloudUploads.bindOwnedDevices(session, deviceIds)
            }
        val gate = CloudSessionGate({ CloudCredentials("alice", "session", "token") }, data.cloudUploads, ownership = ownership)
        var fail = true
        val bodies = mutableListOf<String>()
        val http =
            CloudHttp(
                "https://cloud.test",
                "key",
                HttpClient(
                    MockEngine { request ->
                        val raw = (request.body as TextContent).text
                        val result =
                            if (request.url.encodedPath.endsWith("bind_cloud_session_v1")) {
                                """{"contractVersion":1,"profileId":"alice","sessionId":"session","installationId":"installation","deviceId":"phone"}"""
                            } else {
                                bodies += raw
                                if (fail) throw IOException("lost reply")
                                val batch =
                                    CloudJson.decodeFromJsonElement(
                                        PushBatch.serializer(),
                                        CloudJson.parseToJsonElement(raw).jsonObject.getValue("p_batch"),
                                    )
                                CloudJson.encodeToString(
                                    PushResponse.serializer(),
                                    PushResponse(
                                        devices = batch.devices.map { ItemResult(it.id, "ok") },
                                        events = batch.events.map { ItemResult(it.id, "accepted") },
                                    ),
                                )
                            }
                        respond(result, headers = headersOf("Content-Type", "application/json"))
                    },
                ),
            )
        val backend = SupabaseCloudBackend(gate, http, CloudInstallation("installation", "a".repeat(64)))
        val port = OwnedSourceUploadPort(backend, data.accountIdentities)

        init {
            data.accountIdentities.initialize("phone", "self", "PHONE")
            data.accountIdentities.recordSource("foreign", "foreign-self", "WATCH", "same-scope", false)
            val bob = data.cloudUploads.activate("bob")
            data.cloudUploads.bindOwnedDevices(bob, setOf("foreign"))
            data.cloudUploads.deactivate()
            gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
        }

        fun event(
            id: String,
            source: String,
            seq: Long = 1,
        ) {
            data.events.append(StoredEvent(id, "match", source, seq, seq, """{"type":"PointWon","side":"A"}""", type = "PointWon"))
        }

        fun batch(index: Int): PushBatch =
            CloudJson.decodeFromJsonElement(
                PushBatch.serializer(),
                CloudJson.parseToJsonElement(bodies[index]).jsonObject.getValue("p_batch"),
            )

        override fun close() {
            gate.stop()
            http.close()
            driver.close()
        }
    }

    @Test fun retryFreezesDevicesAcrossLateAdoptionAndReadOnlyStatusWhileNextSelectionSeesWatch() =
        runBlocking {
            Fixture().use { f ->
                f.backend.bindDevice("phone")
                f.event("phone-event", "phone")
                f.event("foreign-event", "foreign")
                val runner = CloudUploadRunner(f.port, { 1L }, maxBatchesPerRun = 1)
                assertEquals(CloudUploadOutcome.FAILED, runner.uploadNow().outcome)
                f.data.accountIdentities.recordSource("late-watch", "watch-self", "WATCH", "new-scope", false)
                f.data.cloudUploads.bindOwnedDevices(f.active, setOf("late-watch"))
                f.event("watch-event", "late-watch")
                val before = f.driver.persistedState()
                f.driver.recording = true
                runner.refresh()
                assertEquals(before, f.driver.persistedState())
                assertTrue(f.driver.writes.isEmpty())
                f.fail = false
                assertEquals(CloudUploadOutcome.LIMIT_REACHED, runner.uploadNow().outcome)
                assertEquals(f.bodies[0], f.bodies[1])
                assertEquals(listOf(DeviceDto("phone", "phone", "android")), f.batch(1).devices)
                assertEquals(CloudUploadOutcome.DRAINED, runner.uploadNow().outcome)
                assertEquals(listOf(DeviceDto("late-watch", "watch", "wearos")), f.batch(2).devices)
                assertEquals(listOf("watch-event"), f.batch(2).events.map { it.id })
                assertEquals(1L, f.data.cloud.pendingCount())
                runner.close()
            }
        }

    @Test fun ownerWithoutProvenanceFailsClosedBeforeAnyPushAndForeignOrUnboundEventsNeverBecomeDevices() =
        runBlocking {
            Fixture().use { f ->
                f.backend.bindDevice("phone")
                f.event("owner-only-event", "owner-only")
                f.event("foreign-event", "foreign")
                f.event("unbound-event", "unbound")
                val error = assertFailsWith<CloudException> { f.port.captureUpload(500) }
                assertEquals("device_not_authorized", error.reason)
                assertTrue(f.bodies.isEmpty())
                assertEquals(
                    listOf("phone"),
                    f.data.accountIdentities
                        .ownedSources("alice")
                        .map { it.deviceId },
                )
            }
        }
}
