package eu.advantly.cloud.sync

import eu.advantly.data.CloudUploadStore
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/** supabase-kt-Adapter. Der Host injiziert Client samt sicherem SessionManager und Installationsgeheimnis. */
class SupabasePhoneAuth(
    private val client: SupabaseClient,
    private val deviceSecret: String,
    uploads: CloudUploadStore? = null,
    private val scope: CoroutineScope,
    threadGuard: () -> Boolean = { true },
    private val requestTimeoutMillis: Long = 20_000,
) : CloudAuthPort {
    private val transport =
        CloudHttp(
            client.supabaseHttpUrl,
            client.supabaseKey,
            client.config.networkConfig.httpEngine
                ?.let { HttpClient(it) { followRedirects = false } }
                ?: HttpClient { followRedirects = false },
            requestTimeoutMillis,
        )
    val sessions = CloudSessionGate(::credentials, uploads, threadGuard)
    override val session = sessions.session
    private val mutations = Mutex()
    private val lifecycle = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private var closed = false

    init {
        lifecycle.launch(start = CoroutineStart.UNDISPATCHED) {
            client.auth.sessionStatus.collect { sessions.synchronize() }
        }
    }

    private fun credentials(): CloudCredentials? {
        val raw = client.auth.currentSessionOrNull() ?: return null
        return try {
            val claims =
                Json.parseToJsonElement(
                    Base64.UrlSafe
                        .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                        .decode(raw.accessToken.split('.')[1])
                        .decodeToString(),
                ) as JsonObject
            val profile = claims.getValue("sub").jsonPrimitive.content
            val sessionId = claims.getValue("session_id").jsonPrimitive.content
            if (profile.isBlank() || sessionId.isBlank() || (raw.user != null && raw.user?.id != profile)) return null
            CloudCredentials(profile, sessionId, raw.accessToken)
        } catch (_: Exception) {
            null
        }
    }

    /** Keine automatische Registrierung/Übertragung; der versionierte Serververtrag ist noch ein Release-Gate. */
    fun cloudBackend(installation: CloudInstallation): SupabaseCloudBackend = SupabaseCloudBackend(sessions, transport, installation)

    /** Vor einem späteren Lösch-RPC aufrufen; Netzwerkfehler dürfen die lokale Sperre nicht aufheben. */
    fun stopForDeletion() = sessions.stop()

    override fun close() {
        if (closed) return
        closed = true
        sessions.stop()
        lifecycle.cancel()
        client.auth.stopAutoRefreshForCurrentSession()
        transport.close()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) { client.close() } }
    }

    override suspend fun issueAgeTicket(
        country: String,
        birthDate: String,
    ): String =
        guarded {
            rpc(
                "issue_age_ticket",
                buildJsonObject {
                    put("p_country", country)
                    put("p_birth_date", birthDate)
                    put("p_device_secret", deviceSecret)
                },
            ).getValue("ticket").jsonPrimitive.content
        }

    override suspend fun cancelAgeTicket(ticket: String) =
        guarded {
            rpc("cancel_age_ticket", buildJsonObject { put("p_ticket", ticket) })
            Unit
        }

    override suspend fun signInWithIdToken(
        provider: IdTokenProvider,
        idToken: String,
        rawNonce: String?,
        ticket: String,
    ) = newLogin {
        if (provider != IdTokenProvider.GOOGLE) throw CloudException("provider_unavailable", false)
        // Nur Routing, kein Vertrauensbeweis: Supabase verifiziert Signatur, aud, exp und Nonce des ganzen Tokens.
        val subject =
            Json.parseToJsonElement(
                Base64.UrlSafe
                    .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                    .decode(idToken.split('.')[1])
                    .decodeToString(),
            ) as JsonObject
        bind(ticket, "google", subject.getValue("sub").jsonPrimitive.content)
        client.auth.signInWith(IDToken) {
            this.provider = Google
            this.idToken = idToken
            nonce = rawNonce
        }
    }

    override suspend fun linkGoogle(
        idToken: String,
        rawNonce: String?,
    ) = guarded {
        val lease = sessions.capture(requireProfile = false)
        mutations.withLock {
            sessions.request(lease) { boundedAuth { client.auth.linkIdentityWithIdToken(Google, idToken) { nonce = rawNonce } } }
            refreshProfile()
        }
    }

    override suspend fun sendEmailCode(
        email: String,
        ticket: String,
    ) {
        val transition = sessions.stop()
        guarded {
            mutations.withLock {
                sessions.requireTransition(transition)
                bind(ticket, "email", email)
                boundedAuth {
                    client.auth.signInWith(OTP) {
                        this.email = email.trim()
                        data = buildJsonObject { put("age_ticket", ticket) }
                    }
                }
            }
        }
    }

    override suspend fun verifyEmailCode(
        email: String,
        code: String,
    ) = newLogin {
        client.auth.verifyEmailOtp(OtpType.Email.EMAIL, email.trim(), code.trim())
    }

    override suspend fun completeOnboarding(
        alias: String,
        username: String?,
    ) = guarded {
        val lease = sessions.capture(requireProfile = false)
        sessions.request(lease) {
            transport.rpc(
                "finish_onboarding",
                buildJsonObject {
                    put("p_alias", alias)
                    put("p_username", username)
                },
                lease.credentials.accessToken,
            )
        }
        refreshProfile()
    }

    override suspend fun refreshProfile() =
        guarded {
            client.auth.awaitInitialization()
            sessions.synchronize()
            if (client.auth.currentSessionOrNull() == null) return@guarded
            val lease = sessions.capture(requireProfile = false)
            val profile =
                sessions.request(lease) {
                    Json.parseToJsonElement(
                        transport.rpc("my_auth_profile", buildJsonObject {}, lease.credentials.accessToken),
                    ) as JsonObject
                }
            sessions.publish(
                lease,
                CloudSession(
                    profile.getValue("profileId").jsonPrimitive.content,
                    profile.getValue("onboarded").jsonPrimitive.boolean,
                    profile.getValue("deletionRequested").jsonPrimitive.boolean,
                ),
            )
        }

    private suspend fun newLogin(block: suspend () -> Unit) {
        val transition = sessions.stop()
        guarded {
            mutations.withLock {
                sessions.requireTransition(transition)
                boundedAuth { block() }
                sessions.completeTransition(transition)
                refreshProfile()
            }
        }
    }

    override suspend fun signOut() {
        val transition = sessions.stop()
        try {
            boundedAuth {
                mutations.withLock {
                    sessions.requireTransition(transition)
                    guarded { client.auth.signOut(SignOutScope.GLOBAL) }
                }
            }
        } finally {
            // Auch ein abgebrochener Logout wartet auf ältere Auth-Mutationen und löscht deren Credentials.
            withContext(NonCancellable) {
                mutations.withLock {
                    if (sessions.isTransitionCurrent(transition)) client.auth.clearSession()
                }
            }
        }
    }

    private suspend fun bind(
        ticket: String,
        provider: String,
        identity: String,
    ) {
        rpc(
            "bind_age_ticket",
            buildJsonObject {
                put("p_ticket", ticket)
                put("p_provider", provider)
                put("p_identity", identity)
            },
        )
    }

    private suspend fun boundedAuth(block: suspend () -> Unit) {
        withTimeoutOrNull(requestTimeoutMillis) {
            block()
            true
        } ?: throw CloudException("connection_failed", false)
    }

    private suspend fun rpc(
        name: String,
        params: JsonObject,
    ): JsonObject {
        val result = Json.parseToJsonElement(transport.rpc(name, params, null)) as JsonObject
        result["error"]?.let { throw CloudException(it.jsonPrimitive.content, false) }
        return result
    }

    private suspend fun <T> guarded(block: suspend () -> T): T =
        try {
            if (closed) throw CloudException("cloud_closed", false)
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudException) {
            if (e.reason == "cloud_connection_failed") throw CloudException("connection_failed", e.permission)
            throw e
        } catch (e: Exception) {
            // Keine Rohantwort/Token an UI oder Logs weitergeben. Unbekannte Fehler bleiben generisch.
            val reason = REASONS.firstOrNull { e.message?.contains(it) == true } ?: "connection_failed"
            throw CloudException(reason, false)
        }

    private companion object {
        val REASONS =
            listOf(
                "profile_missing",
                "age_ticket_required",
                "age_ticket_expired",
                "age_ticket_bound",
                "age_retry_later",
                "rate_limit_exceeded",
                "country_not_supported",
                "age_below_minimum",
                "invalid_birth_date",
                "username_taken",
                "username_reserved",
                "invalid_username",
                "invalid_alias",
                "otp_expired",
                "already_onboarded",
            )
    }
}
