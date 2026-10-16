package eu.advantly.cloud.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/** Eigener HTTP-Aufruf hält die Credentials eines Versands fest; spätere SDK-Sessionwechsel ändern sie nicht. */
class SupabaseSupport(
    private val url: String,
    private val publicKey: String,
    private val authClient: SupabaseClient? = null,
    private val http: HttpClient = HttpClient { install(HttpTimeout) { requestTimeoutMillis = 20_000 } },
) : SupportPort {
    private val json = Json { ignoreUnknownKeys = true }
    override val owners =
        authClient
            ?.auth
            ?.sessionStatus
            ?.map { currentOwner() }
            ?.distinctUntilChanged() ?: flowOf(null)

    init {
        val parsed = Url(url)
        require(
            parsed.protocol.name == "https" ||
                (parsed.protocol.name == "http" && parsed.host in setOf("localhost", "127.0.0.1", "10.0.2.2")),
        )
        require(publicKey.isNotBlank() && !publicKey.startsWith("sb_secret_"))
        if (publicKey.count { it == '.' } == 2) {
            val claims =
                json.parseToJsonElement(
                    Base64.UrlSafe
                        .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                        .decode(publicKey.split('.')[1])
                        .decodeToString(),
                ) as JsonObject
            require(claims["role"]?.jsonPrimitive?.content == "anon")
        }
    }

    private fun currentOwner() =
        authClient
            ?.auth
            ?.currentSessionOrNull()
            ?.user
            ?.id

    override fun close() = http.close()

    override suspend fun identity(): SupportIdentity {
        authClient?.auth?.awaitInitialization()
        val session = authClient?.auth?.currentSessionOrNull()
        return SupportIdentity(session?.user?.id, session?.accessToken)
    }

    override suspend fun submit(
        draft: SupportDraft,
        identity: SupportIdentity,
    ): SupportReceipt {
        checkOwner(identity)
        if (draft.ownerId != identity.ownerId || !draft.submitted) throw CloudException("support_identity_changed", false)
        val response =
            rpc(
                "submit_support",
                identity,
                buildJsonObject {
                    put("p_id", draft.id)
                    put("p_receipt_secret", draft.receiptSecret)
                    put("p_category", draft.category)
                    put("p_title", draft.title)
                    put("p_body", draft.body)
                    put("p_contact_email", draft.contactEmail.ifBlank { null })
                    put("p_diagnostics", JsonObject(draft.diagnostics.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
                },
            )
        return json.decodeFromString(response)
    }

    override suspend fun tickets(identity: SupportIdentity): List<SupportTicket> {
        checkOwner(identity)
        if (identity.ownerId == null) return emptyList()
        return json.decodeFromString(rpc("my_support_tickets", identity, buildJsonObject {}))
    }

    private fun checkOwner(identity: SupportIdentity) {
        if (currentOwner() != identity.ownerId) throw CloudException("support_identity_changed", false)
    }

    private suspend fun rpc(
        name: String,
        identity: SupportIdentity,
        payload: JsonObject,
    ): String =
        try {
            val response =
                http.post("${url.trimEnd('/')}/rest/v1/rpc/$name") {
                    header("apikey", publicKey)
                    // Publishable Keys sind keine JWTs. Ohne Sitzung reicht der apikey-Header.
                    identity.accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody(payload.toString())
                }
            val text = response.bodyAsText()
            if (response.status.value !in 200..299) throw CloudException("support_connection_failed", false)
            val error = (json.parseToJsonElement(text) as? JsonObject)?.get("error")?.jsonPrimitive?.content
            if (error != null) throw CloudException(error.takeIf { it in ERRORS } ?: "support_connection_failed", false)
            text
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudException) {
            throw e
        } catch (_: Exception) {
            throw CloudException("support_connection_failed", false)
        }

    private companion object {
        val ERRORS = setOf("support_invalid", "support_identity_changed", "support_conflict", "support_rate_limited", "support_unavailable")
    }
}
