package eu.advantly.cloud.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : CloudAuthPort {
    private val current = MutableStateFlow<CloudSession?>(null)
    override val session = current.asStateFlow()

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
            client.postgrest.rpc("cancel_age_ticket", buildJsonObject { put("p_ticket", ticket) })
            Unit
        }

    override suspend fun signInWithIdToken(
        provider: IdTokenProvider,
        idToken: String,
        rawNonce: String?,
        ticket: String,
    ) = guarded {
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
        refreshProfile()
    }

    override suspend fun linkGoogle(
        idToken: String,
        rawNonce: String?,
    ) = guarded {
        client.auth.linkIdentityWithIdToken(Google, idToken) { nonce = rawNonce }
        refreshProfile()
    }

    override suspend fun sendEmailCode(
        email: String,
        ticket: String,
    ) = guarded {
        bind(ticket, "email", email)
        client.auth.signInWith(OTP) {
            this.email = email.trim()
            data = buildJsonObject { put("age_ticket", ticket) }
        }
    }

    override suspend fun verifyEmailCode(
        email: String,
        code: String,
    ) = guarded {
        client.auth.verifyEmailOtp(OtpType.Email.EMAIL, email.trim(), code.trim())
        refreshProfile()
    }

    override suspend fun completeOnboarding(
        alias: String,
        username: String?,
    ) = guarded {
        client.postgrest.rpc(
            "finish_onboarding",
            buildJsonObject {
                put("p_alias", alias)
                put("p_username", username)
            },
        )
        refreshProfile()
    }

    override suspend fun refreshProfile() =
        guarded {
            client.auth.awaitInitialization()
            if (client.auth.currentSessionOrNull() == null) {
                current.value = null
            } else {
                val profile = rpc("my_auth_profile", buildJsonObject {})
                current.value =
                    CloudSession(
                        profile.getValue("profileId").jsonPrimitive.content,
                        profile.getValue("onboarded").jsonPrimitive.boolean,
                        profile.getValue("deletionRequested").jsonPrimitive.boolean,
                    )
            }
        }

    override suspend fun signOut() {
        try {
            guarded { client.auth.signOut(SignOutScope.GLOBAL) }
        } finally {
            client.auth.clearSession()
            current.value = null
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

    private suspend fun rpc(
        name: String,
        params: JsonObject,
    ): JsonObject {
        val result = client.postgrest.rpc(name, params).decodeAs<JsonObject>()
        result["error"]?.let { throw CloudException(it.jsonPrimitive.content, false) }
        return result
    }

    private suspend fun <T> guarded(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudException) {
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
