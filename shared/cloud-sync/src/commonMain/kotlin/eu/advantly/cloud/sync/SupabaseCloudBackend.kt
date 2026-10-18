package eu.advantly.cloud.sync

import eu.advantly.data.CloudUploadSnapshot
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Auswahl und unveränderlicher Auth-Kontext eines begrenzten Versuchs; keine Credentials in toString. */
class CloudUploadAttempt internal constructor(
    internal val lease: CloudLease,
    internal val snapshot: CloudUploadSnapshot,
    internal val deviceBinding: CloudDeviceBinding,
) {
    val events: List<EventRow> get() = snapshot.events.map(EventRows::toRow)
    val batch: PushBatch get() = snapshot.toPushBatch()
    val inventory get() = snapshot.inventory
    val profileId: String get() = snapshot.profileId
    val generation: Long get() = lease.generation
}

internal class CloudDeviceBinding(
    val generation: Long,
    val sessionId: String,
)

/**
 * Ausführbare Upload-Grenze und versionierte Lese-RPCs, ohne lokale Pull-Anwendung.
 * Ohne ausdrücklich bestätigte Gerätebindung bleibt jeder Sync-Aufruf gesperrt.
 */
class SupabaseCloudBackend internal constructor(
    private val sessions: CloudSessionGate,
    private val http: CloudHttp,
    private val installation: CloudInstallation,
) : CloudBackend,
    CloudUploadPort {
    private var binding: CloudDeviceBinding? = null
    private var bindingAttempt = 0L

    /** Expliziter, später vom U4/K4-Host ausgelöster Aufruf. Kein Fallback auf register_device. */
    suspend fun bindDevice(deviceId: String) {
        require(deviceId.isNotBlank())
        binding = null
        check(bindingAttempt < Long.MAX_VALUE)
        val attempt = ++bindingAttempt
        val lease = sessions.capture()
        val result =
            sessions.request(lease) {
                CloudJson
                    .parseToJsonElement(
                        http.rpc(
                            "bind_cloud_session_v1",
                            buildJsonObject {
                                put("p_installation_id", installation.id)
                                put("p_device_id", deviceId)
                            },
                            lease.credentials.accessToken,
                            installation,
                        ),
                    ).jsonObject
            }

        fun matches(
            key: String,
            expected: String,
        ) = result[key]?.jsonPrimitive?.content == expected
        if (!matches("contractVersion", "1") || !matches("profileId", lease.credentials.profileId) ||
            !matches("sessionId", lease.credentials.sessionId) || !matches("installationId", installation.id) ||
            !matches("deviceId", deviceId)
        ) {
            throw CloudException("device_not_authorized", true)
        }
        sessions.requireCurrent(lease)
        if (attempt != bindingAttempt) throw CloudException("device_not_authorized", true)
        binding = CloudDeviceBinding(lease.generation, lease.credentials.sessionId)
    }

    private fun requireBinding(lease: CloudLease): CloudDeviceBinding {
        sessions.requireCurrent(lease)
        val current = binding
        if (current == null || current.generation != lease.generation || current.sessionId != lease.credentials.sessionId) {
            throw CloudException("device_not_authorized", true)
        }
        return current
    }

    private suspend fun rpc(
        lease: CloudLease,
        name: String,
        body: JsonObject,
    ): String {
        val current = requireBinding(lease)
        val response = sessions.request(lease) { http.rpc(name, body, lease.credentials.accessToken, installation) }
        if (binding !== current) throw CloudException("device_not_authorized", true)
        return response
    }

    override suspend fun push(batch: PushBatch): PushResponse = push(sessions.capture(), batch)

    private suspend fun push(
        lease: CloudLease,
        batch: PushBatch,
    ): PushResponse {
        require(batch.events.size <= PushBatch.MAX_EVENTS_PER_PUSH)
        require(batch.devices.size <= 500 && batch.players.size <= 500 && batch.matches.size <= 500)
        return CloudJson.decodeFromString(
            rpc(
                lease,
                "cloud_sync_push_v1",
                buildJsonObject {
                    put("p_batch", CloudJson.encodeToJsonElement(PushBatch.serializer(), batch))
                },
            ),
        )
    }

    override fun captureUpload(limit: Int): CloudUploadAttempt {
        require(limit in 1..PushBatch.MAX_EVENTS_PER_PUSH)
        val lease = sessions.capture()
        val current = requireBinding(lease)
        return CloudUploadAttempt(lease, sessions.captureUpload(lease, limit), current).also(::requireCurrentUpload)
    }

    override fun requireCurrentUpload(attempt: CloudUploadAttempt) {
        sessions.requireUpload(attempt.lease, attempt.snapshot)
        if (binding !== attempt.deviceBinding) throw CloudException("device_not_authorized", true)
    }

    override fun nextUpload(
        attempt: CloudUploadAttempt,
        limit: Int,
    ): CloudUploadAttempt {
        requireCurrentUpload(attempt)
        return captureUpload(limit)
    }

    override fun uploadInventory(attempt: CloudUploadAttempt) = readUploadStatus(attempt).inventory

    override fun readUploadStatus(attempt: CloudUploadAttempt?): CloudUploadStatus {
        val lease = attempt?.lease ?: sessions.capture()
        val current = requireBinding(lease)
        if (attempt != null) requireCurrentUpload(attempt)
        val inventory = sessions.uploadInventory(lease)
        if (requireBinding(lease) !== current) throw CloudException("device_not_authorized", true)
        if (attempt != null) requireCurrentUpload(attempt)
        return CloudUploadStatus(lease.credentials.profileId, lease.generation, inventory)
    }

    /** Genau den eingefrorenen Stand senden; jüngere lokale Metadaten bleiben nach dem Ack ausstehend. */
    override suspend fun pushSelected(
        attempt: CloudUploadAttempt,
        batch: PushBatch,
    ): PushResponse {
        requireCurrentUpload(attempt)
        require(batch.events == attempt.events)
        val selected = attempt.batch
        require(batch.players == selected.players && batch.matches == selected.matches)
        val sent = selected.copy(devices = batch.devices.toList())
        val response =
            try {
                push(attempt.lease, sent)
            } catch (_: SerializationException) {
                throw CloudException("cloud_response_invalid", false)
            }
        val metadata =
            try {
                val ids = response.events.map { it.id }
                require(ids.size == ids.toSet().size && ids.all { id -> sent.events.any { it.id == id } })
                val deviceIds = response.devices.map { it.id }
                require(deviceIds.size == deviceIds.toSet().size && deviceIds.all { id -> sent.devices.any { it.id == id } })
                attempt.snapshot.acknowledgedMetadata(sent, response)
            } catch (_: IllegalArgumentException) {
                throw CloudException("cloud_response_invalid", false)
            }
        val update = OutboxPlanner.evaluate(sent, response)
        sessions.acknowledge(attempt.lease, attempt.snapshot, update.uploaded, update.rejected, metadata)
        return response
    }

    override suspend fun pull(
        cursor: Long,
        limit: Int,
    ): PullResponse {
        require(cursor >= 0 && limit in 1..200)
        return CloudJson.decodeFromString(
            rpc(
                sessions.capture(),
                "cloud_sync_pull_v1",
                buildJsonObject {
                    put("p_cursor", cursor)
                    put("p_limit", limit)
                },
            ),
        )
    }

    override suspend fun pullEvents(
        matchId: String,
        afterServerSeq: Long,
        limit: Int,
    ): List<PulledEvent> {
        require(matchId.isNotBlank() && afterServerSeq >= 0 && limit in 1..500)
        return CloudJson.decodeFromString(
            rpc(
                sessions.capture(),
                "cloud_pull_match_events_v1",
                buildJsonObject {
                    put("p_match_id", matchId)
                    put("p_after_server_seq", afterServerSeq)
                    put("p_limit", limit)
                },
            ),
        )
    }

    override suspend fun watermarks(matchId: String): List<EventWatermark> {
        require(matchId.isNotBlank())
        return CloudJson.decodeFromString(
            rpc(
                sessions.capture(),
                "cloud_match_event_watermarks_v1",
                buildJsonObject {
                    put("p_match_id", matchId)
                },
            ),
        )
    }

    override suspend fun visitorView(matchId: String): VisitorView {
        require(matchId.isNotBlank())
        return CloudJson.decodeFromString(
            rpc(sessions.capture(), "cloud_get_visitor_match_v1", buildJsonObject { put("p_match_id", matchId) }),
        )
    }
}
