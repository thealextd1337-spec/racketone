package eu.advantly.cloud.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
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
    ): String =
        try {
            withTimeoutOrNull(timeoutMillis) {
                val payload = body.toString()
                require(payload.encodeToByteArray().size <= 2_000_000)
                http
                    .preparePost("${url.trimEnd('/')}/rest/v1/rpc/$name") {
                        header("apikey", publicKey)
                        accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                        installation?.let {
                            header("X-Advantly-Installation-Id", it.id)
                            header("X-Advantly-Installation-Proof", it.proof)
                        }
                        header(HttpHeaders.ContentType, ContentType.Application.Json)
                        setBody(payload)
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
                                    ?.takeIf { it in ERRORS }
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

    fun close() = http.close()

    private companion object {
        val ERRORS =
            setOf(
                "profile_not_active",
                "device_not_authorized",
                "device_revoked",
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
