package eu.advantly.cloud.sync

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** Kontorecht ohne Cloud-Lease; Revision und Jobs gehoeren ausschliesslich zum Export. */
internal class SupabaseDataExport(
    private val http: CloudHttp,
    private val capture: (CloudDataExportContext) -> CloudCredentials,
    private val checkAccount: (CloudDataExportContext) -> Unit,
    private val metadataTimeoutMillis: Long = 20_000,
    private val downloadTimeoutMillis: Long = 60_000,
) : CloudDataExportPort {
    private val mutable = MutableStateFlow<CloudDataExportContext?>(null)
    override val context = mutable.asStateFlow()
    private var revision = 0L
    private val jobs = mutableSetOf<Job>()

    internal fun publish(account: CloudSession) {
        val verified = CloudDataExportContext(account.profileId, account.generation, revision)
        checkAccount(verified)
        mutable.value = verified
    }

    internal fun invalidate(account: CloudSession? = null) {
        check(revision < Long.MAX_VALUE)
        val obsolete = jobs.toList()
        revision++
        mutable.value = account?.let { CloudDataExportContext(it.profileId, it.generation, revision) }
        obsolete.forEach { it.cancel(CancellationException("cloud_session_changed")) }
    }

    override fun requireCurrent(context: CloudDataExportContext) {
        checkAccount(context)
        if (mutable.value != context) throw CloudException("cloud_session_changed", true)
    }

    override suspend fun request(
        context: CloudDataExportContext,
        requestId: String,
    ): CloudDataExportStatus = metadata(context, requestId, HttpMethod.Post)

    override suspend fun status(
        context: CloudDataExportContext,
        requestId: String,
    ): CloudDataExportStatus = metadata(context, requestId, HttpMethod.Get)

    private suspend fun metadata(
        context: CloudDataExportContext,
        requestId: String,
        method: HttpMethod,
    ): CloudDataExportStatus =
        operation(context, metadataTimeoutMillis) { credentials ->
            validateId(requestId)
            http.export(
                if (method == HttpMethod.Post) "" else "/$requestId",
                method,
                credentials.accessToken,
                if (method == HttpMethod.Post) buildJsonObject { put("requestId", requestId) }.toString() else null,
                metadataTimeoutMillis,
                { requireCurrent(context) },
            ) { code, mime, length, retry, read ->
                if (code !in setOf(200, 202, 409)) failStatus(code)
                requireValid(mime?.substringBefore(';')?.trim()?.lowercase() == "application/json")
                contentLength(length, 65_536)
                val bytes = ByteArray(65_536)
                var size = 0
                read(
                    CloudDataExportSink { chunk, offset, count ->
                        chunk.copyInto(bytes, size, offset, offset + count)
                        size += count
                    },
                    65_536,
                )
                if (length != null) requireValid(length.toLongOrNull() == size.toLong())
                val json =
                    runCatching { CloudJson.parseToJsonElement(bytes.decodeToString(0, size)) as? JsonObject }.getOrNull() ?: invalid()
                requireValid(number(json, "contractVersion") == 1L)
                if (code == 409) {
                    requireValid(string(json, "error") == "export_busy")
                    CloudDataExportStatus.Busy(retryAfter(retry))
                } else {
                    requireValid(string(json, "requestId") == requestId)
                    if (code == 202) {
                        requireValid(string(json, "status") == "pending")
                        val seconds = number(json, "retryAfterSeconds")
                        requireValid(seconds == retryAfter(retry))
                        CloudDataExportStatus.Pending(requestId, seconds)
                    } else {
                        requireValid(string(json, "status") == "ready")
                        requireValid(string(json, "downloadUrl") == "/functions/v1/export-my-data/$requestId/download")
                        val ready =
                            CloudDataExportStatus.Ready(
                                requestId,
                                timestamp(json, "exportedAt"),
                                timestamp(json, "expiresAt"),
                                number(json, "byteLength"),
                                string(json, "sha256"),
                            )
                        validateReady(ready)
                        ready
                    }
                }
            }
        }

    override suspend fun download(
        context: CloudDataExportContext,
        ready: CloudDataExportStatus.Ready,
        sink: CloudDataExportSink,
    ): Unit =
        operation(context, downloadTimeoutMillis) { credentials ->
            validateReady(ready)
            http.export(
                "/${ready.requestId}/download",
                HttpMethod.Get,
                credentials.accessToken,
                null,
                downloadTimeoutMillis,
                { requireCurrent(context) },
            ) { code, mime, length, _, read ->
                if (code != 200) {
                    if (code == 202 || code == 409) throw CloudException("export_not_ready", false)
                    failStatus(code)
                }
                requireValid(mime?.substringBefore(';')?.trim()?.lowercase() == "application/zip")
                contentLength(length, ready.byteLength)
                if (length != null) requireValid(length.toLongOrNull() == ready.byteLength)
                var received = 0L
                read(
                    CloudDataExportSink { bytes, offset, count ->
                        requireCurrent(context)
                        sink.write(bytes, offset, count)
                        currentCoroutineContext().ensureActive()
                        requireCurrent(context)
                        received += count
                    },
                    ready.byteLength,
                )
                requireValid(received == ready.byteLength)
            }
        }

    private suspend fun <T> operation(
        context: CloudDataExportContext,
        timeout: Long,
        block: suspend (CloudCredentials) -> T,
    ): T =
        coroutineScope {
            requireCurrent(context)
            val credentials = capture(context)
            requireCurrent(context)
            val job = currentCoroutineContext().job
            jobs += job
            try {
                withTimeoutOrNull(timeout) {
                    val result = block(credentials)
                    currentCoroutineContext().ensureActive()
                    requireCurrent(context)
                    result
                } ?: throw CloudException("export_timeout", false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: CloudException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                requireCurrent(context)
                val timeoutFailure =
                    error is HttpRequestTimeoutException || error is ConnectTimeoutException || error is SocketTimeoutException
                throw CloudException(if (timeoutFailure) "export_timeout" else "export_unavailable", false)
            } finally {
                jobs -= job
            }
        }

    private fun failStatus(code: Int): Nothing =
        throw CloudException(
            when (code) {
                401 -> "export_not_authenticated"
                404 -> "export_not_found"
                410 -> "export_expired"
                413 -> "export_too_large"
                else -> "export_unavailable"
            },
            code == 401,
        )

    private fun retryAfter(value: String?): Long = value?.toLongOrNull()?.takeIf { it == 3L } ?: invalid()

    private fun contentLength(
        value: String?,
        maximum: Long,
    ) {
        if (value != null) requireValid(value.toLongOrNull()?.let { it in 0..maximum } == true)
    }

    private fun timestamp(
        json: JsonObject,
        key: String,
    ): Long = runCatching { Instant.parse(string(json, key)).toEpochMilliseconds() }.getOrElse { invalid() }

    private fun string(
        json: JsonObject,
        key: String,
    ): String {
        val value = json[key] as? JsonPrimitive ?: invalid()
        requireValid(value.isString)
        return value.content
    }

    private fun number(
        json: JsonObject,
        key: String,
    ): Long {
        val value = json[key] as? JsonPrimitive ?: invalid()
        requireValid(!value.isString)
        return value.longOrNull ?: invalid()
    }

    private fun validateId(id: String) = requireValid(UUID.matches(id))

    private fun validateReady(ready: CloudDataExportStatus.Ready) {
        validateId(ready.requestId)
        requireValid(ready.exportedAtMs >= 0 && ready.expiresAtMs > ready.exportedAtMs)
        requireValid(ready.expiresAtMs - ready.exportedAtMs <= 86_400_000)
        requireValid(ready.byteLength in 1..16_777_216)
        requireValid(SHA.matches(ready.sha256))
    }

    private fun requireValid(valid: Boolean) {
        if (!valid) invalid()
    }

    private fun invalid(): Nothing = throw CloudException("export_response_invalid", false)

    private companion object {
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val SHA = Regex("[0-9a-fA-F]{64}")
    }
}
