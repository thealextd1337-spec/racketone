package eu.advantly.cloud.sync

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

/** Eigener Client ohne SDK-Auth-Plugin. Redirects dürfen keine Credentials an ein anderes Ziel weitergeben. */
internal class CloudHttp(
    private val url: String,
    private val publicKey: String,
    private val http: HttpClient,
    private val timeoutMillis: Long = 20_000,
    private val exportFactory: ExportHttpTransportFactory? = null,
) {
    init {
        val parsed = Url(url)
        require(
            parsed.protocol.name == "https" ||
                (parsed.protocol.name == "http" && parsed.host in setOf("localhost", "127.0.0.1", "10.0.2.2")),
        )
        require(publicKey.isNotBlank() && !publicKey.startsWith("sb_secret_"))
        if (publicKey.count { it == '.' } == 2) {
            val claims =
                CloudJson.parseToJsonElement(
                    Base64.UrlSafe
                        .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                        .decode(publicKey.split('.')[1])
                        .decodeToString(),
                ) as JsonObject
            require(claims["role"]?.jsonPrimitive?.content == "anon")
        }
        require(timeoutMillis > 0)
    }

    suspend fun rpc(
        name: String,
        body: JsonObject,
        accessToken: String?,
        installation: CloudInstallation? = null,
    ): String = request("rpc/$name", body.toString(), accessToken, installation)

    /** Lesender PostgREST-Tabellenzugriff (RLS + Cloud-Bindung), gleiche Header und Fehlerauswertung wie [rpc]. */
    suspend fun select(
        path: String,
        accessToken: String,
        installation: CloudInstallation,
    ): String = request(path, null, accessToken, installation)

    private suspend fun request(
        path: String,
        payload: String?,
        accessToken: String?,
        installation: CloudInstallation?,
    ): String =
        try {
            withTimeoutOrNull(timeoutMillis) {
                require(payload == null || payload.encodeToByteArray().size <= 2_000_000)
                http
                    .prepareRequest("${url.trimEnd('/')}/rest/v1/$path") {
                        method = if (payload == null) HttpMethod.Get else HttpMethod.Post
                        header("apikey", publicKey)
                        accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                        installation?.let {
                            header("X-Advantly-Installation-Id", it.id)
                            header("X-Advantly-Installation-Proof", it.proof)
                        }
                        if (payload != null) {
                            header(HttpHeaders.ContentType, ContentType.Application.Json)
                            setBody(payload)
                        }
                    }.execute { response ->
                        val channel = response.bodyAsChannel()
                        val chunks = mutableListOf<ByteArray>()
                        var size = 0
                        while (true) {
                            val bytes = ByteArray(8192)
                            val count = channel.readAvailable(bytes)
                            if (count == -1) break
                            size += count
                            if (size > 8_000_000) throw CloudException("cloud_response_invalid", false)
                            chunks += bytes.copyOf(count)
                        }
                        val bytes = ByteArray(size)
                        var offset = 0
                        chunks.forEach {
                            it.copyInto(bytes, offset)
                            offset += it.size
                        }
                        val text = bytes.decodeToString()
                        val result = runCatching { CloudJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
                        val error =
                            result?.get("error")?.jsonPrimitive?.content
                                ?: result
                                    ?.get("message")
                                    ?.jsonPrimitive
                                    ?.content
                                    ?.takeIf { it in ERRORS || it == RESTART_REQUIRED }
                        // Veraltetes G/E (SQLSTATE 40001): kein Rechteverlust, sondern Neustart nach frischem Pull.
                        if (error == RESTART_REQUIRED) throw CloudException(RESTART_REQUIRED, false)
                        if (error != null) throw CloudException(error.takeIf { it in ERRORS } ?: "cloud_connection_failed", true)
                        if (response.status.value !in 200..299) {
                            throw CloudException("cloud_connection_failed", response.status.value in setOf(401, 403))
                        }
                        text
                    }
            } ?: throw CloudException("cloud_connection_failed", false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudException) {
            throw e
        } catch (_: Exception) {
            throw CloudException("cloud_connection_failed", false)
        }

    /** Separater Edge-Zweig: RPC-Limits und Fehlerauswertung bleiben unveraendert. */
    internal suspend fun <T> export(
        suffix: String,
        method: HttpMethod,
        accessToken: String,
        body: String?,
        timeout: Long,
        checkCurrent: () -> Unit,
        receive: suspend (Int, String?, String?, String?, suspend (CloudDataExportSink, Long) -> Unit) -> T,
    ): T {
        val lease = exportFactory?.open() ?: throw CloudException("export_unavailable", false)
        val edge = lease.client

        suspend fun fence() {
            currentCoroutineContext().ensureActive()
            checkCurrent()
        }
        // Ktor haengt Lifecycle-/Call-Jobs darunter ein, auch bevor eine Response existiert.
        // execute-Cleanup kann bei Cancellation abbrechen; native Callbacks brauchen eine eigene Barriere.
        var requestLifetime: Job? = null
        try {
            return edge
                .prepareRequest("${url.trimEnd('/')}/functions/v1/export-my-data$suffix") {
                    requestLifetime = executionContext
                    this.method = method
                    header("apikey", publicKey)
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    timeout {
                        requestTimeoutMillis = timeout
                        connectTimeoutMillis = minOf(timeout, 20_000)
                        socketTimeoutMillis = minOf(timeout, 20_000)
                    }
                    if (body != null) {
                        header(HttpHeaders.ContentType, ContentType.Application.Json)
                        setBody(body)
                    }
                }.execute { response ->
                    fence()
                    receive(
                        response.status.value,
                        response.headers[HttpHeaders.ContentType],
                        response.headers[HttpHeaders.ContentLength],
                        response.headers[HttpHeaders.RetryAfter],
                    ) { sink, maximum ->
                        val channel = response.bodyAsChannel()
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            fence()
                            val count = channel.readAvailable(buffer)
                            fence()
                            if (count == -1) break
                            total += count
                            if (total > maximum) throw CloudException("export_response_invalid", false)
                            if (count > 0) {
                                sink.write(buffer, 0, count)
                                fence()
                            }
                        }
                    }.also { fence() }
                }
        } finally {
            withContext(NonCancellable) {
                requestLifetime?.cancel()
                lease.cancel()
                requestLifetime?.join()
                lease.awaitCompletion()
                lease.close()
            }
        }
    }

    fun close() {
        http.close()
    }

    private companion object {
        const val RESTART_REQUIRED = "restart_required"

        val ERRORS =
            setOf(
                "profile_not_active",
                "account_suspended",
                "device_not_authorized",
                "device_revoked",
                "device_unrevoke_self",
                "device_unrevoke_caller_too_new",
                "device_not_found",
                "device_not_revoked",
                "account_deletion_pending",
                "cloud_session_changed",
                "profile_missing",
                "age_ticket_expired",
                "age_ticket_bound",
                "age_retry_later",
                "age_ticket_required",
                "age_below_minimum",
                "rate_limit_exceeded",
                "country_not_supported",
                "invalid_birth_date",
                "username_taken",
                "username_reserved",
                "invalid_username",
                "invalid_alias",
                "already_onboarded",
                "otp_expired",
                "consent_required",
                "consent_unavailable",
                "consent_version_changed",
                "consent_documents_invalid",
                "consent_language_invalid",
                "no_deletion_pending",
                // Freunde (P2-D)
                "cannot_befriend_self",
                "profile_not_found",
                "request_cooldown",
                "no_pending_request",
            )
    }
}

/** Installations-Capability; getrennt von Altersgate, Quellgeräte-ID und Auth-Refresh-Token speichern. */
class CloudInstallation(
    val id: String,
    internal val proof: String,
) {
    init {
        require(id.isNotBlank())
        require(proof.length >= 64)
    }
}
