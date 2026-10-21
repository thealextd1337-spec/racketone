package eu.advantly.cloud.sync

import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmDriver
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reale HTTP-Sockets; auch abgeschnittene Bodies durchlaufen den produktiven CloudHttp-Lesepfad. */
internal class PullSocketServer(
    private val respond: (String, JsonObject) -> SocketReply,
) : AutoCloseable {
    private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    val url = "http://127.0.0.1:${socket.localPort}"

    init {
        executor.submit {
            while (!socket.isClosed) {
                val connection =
                    try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                executor.submit {
                    connection.use { client ->
                        val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
                        val path = reader.readLine().split(' ')[1].substringAfterLast('/')
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: return@submit
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        val body = CharArray(length)
                        var offset = 0
                        while (offset < length) {
                            val count = reader.read(body, offset, length - offset)
                            if (count < 0) return@submit
                            offset += count
                        }
                        val response = respond(path, CloudJson.parseToJsonElement(String(body)).jsonObject)
                        if (response.delayMillis > 0) Thread.sleep(response.delayMillis)
                        val bytes = response.body.toByteArray(Charsets.UTF_8)
                        val output = client.getOutputStream()
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${bytes.size + response.extraLength}\r\nConnection: close\r\n\r\n"
                            ).toByteArray(),
                        )
                        output.write(bytes)
                        output.flush()
                    }
                }
            }
        }
    }

    override fun close() {
        socket.close()
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
        executor.shutdownNow()
    }
}

internal data class SocketReply(
    val body: String,
    val delayMillis: Long = 0,
    val extraLength: Int = 0,
)

internal fun socketBinding(body: JsonObject): SocketReply =
    SocketReply(
        """{"contractVersion":1,"profileId":"alice","sessionId":"session-alice","installationId":"installation",
        "deviceId":"${body.getValue("p_device_id").jsonPrimitive.content}"}""",
    )

class CloudPullSocketTest {
    @Test
    fun truncatedAndDelayedHttpResponsesNeverMoveMetadataCursor() =
        runBlocking {
            for (delayed in listOf(false, true)) {
                PullSocketServer { path, body ->
                    if (path == "bind_cloud_session_v1") {
                        socketBinding(body)
                    } else {
                        SocketReply(
                            if (delayed) """{"cursor":1,"visibilityEpoch":1}""" else """{"cursor":1,"visibilityEpoch":""",
                            if (delayed) 250 else 0,
                            if (delayed) 0 else 100,
                        )
                    }
                }.use { server ->
                    createInMemoryJvmDriver().use { driver ->
                        val data = LocalData(driver).selectPullAccount()
                        val credentials = CloudCredentials("alice", "session-alice", "token")
                        val gate = CloudSessionGate({ credentials }, data.cloudUploads)
                        gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
                        HttpClient { followRedirects = false }.use { client ->
                            val backend =
                                SupabaseCloudBackend(
                                    gate,
                                    CloudHttp(server.url, "public", client),
                                    CloudInstallation("installation", "p".repeat(64)),
                                )
                            backend.bindDevice("phone")
                            val outcome = CloudPullRunner(backend, data, deadlineMillis = if (delayed) 50 else 2000).pullNow().outcome
                            assertEquals(if (delayed) CloudPullOutcome.DEADLINE_REACHED else CloudPullOutcome.FAILED, outcome)
                            assertEquals(0, data.cloudPulls.progress("alice").cursor)
                        }
                    }
                }
            }
        }

    @Test
    fun accountSwitchAfterRealSocketResponseBeforeCommitRejectsOldPage() =
        runBlocking {
            PullSocketServer { path, body ->
                if (path == "bind_cloud_session_v1") {
                    socketBinding(body)
                } else {
                    SocketReply(
                        CloudJson.encodeToString(
                            PullResponse.serializer(),
                            PullResponse(listOf(pullMatch()), cursor = 1, visibilityEpoch = 1),
                        ),
                    )
                }
            }.use { server ->
                createInMemoryJvmDriver().use { driver ->
                    val data = LocalData(driver).selectPullAccount()
                    var credentials: CloudCredentials? = CloudCredentials("alice", "session-alice", "token")
                    val gate = CloudSessionGate({ credentials }, data.cloudUploads)
                    gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
                    HttpClient { followRedirects = false }.use { client ->
                        val backend =
                            SupabaseCloudBackend(
                                gate,
                                CloudHttp(server.url, "public", client),
                                CloudInstallation("installation", "p".repeat(64)),
                            )
                        backend.bindDevice("phone")
                        val port =
                            AfterPullPort(backend, afterMetadata = {
                                credentials =
                                    CloudCredentials("bob", "session-bob", "bob-token")
                            })
                        val result = CloudPullRunner(port, data).pullNow()
                        assertTrue(result.outcome != CloudPullOutcome.COMPLETE)
                        assertNull(result.profileId)
                        assertEquals(0, data.cloudPulls.progress("alice").cursor)
                        assertEquals(0, data.cloudPulls.progress("bob").cursor)
                    }
                }
            }
        }
}
