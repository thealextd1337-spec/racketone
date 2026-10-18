package eu.advantly.cloud.sync

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ConsentAuthTest {
    private fun token(
        owner: String = "alice",
        session: String = "session-a",
    ): String =
        "header." +
            Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT)
                .encode("""{"sub":"$owner","session_id":"$session"}""".encodeToByteArray()) + ".signature"

    private val snapshot =
        """
        {"profileId":"alice","status":"REQUIRED","documents":[
          {"type":"TERMS","version":"server-2","language":"de","url":"https://advantly.eu/terms","approved":true},
          {"type":"PRIVACY","version":"server-3","language":"de","url":"https://advantly.eu/app-datenschutz.html","approved":true}
        ]}
        """.trimIndent()

    private fun client(reply: suspend (HttpRequestData) -> Pair<String, HttpStatusCode>) =
        createSupabaseClient("https://auth.test", "public-key") {
            httpEngine =
                MockEngine { request ->
                    val (body, status) = reply(request)
                    respond(body, status, headers = headersOf("Content-Type", "application/json"))
                }
            defaultLogLevel = LogLevel.NONE
            install(Auth) { minimalConfig() }
        }

    @Test fun decisionUsesServerVersionsAndFrozenBearerAndNeverClientTime(): Unit =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            val sdk =
                client { request ->
                    requests += request
                    (
                        if (request.url.encodedPath.endsWith("my_auth_profile")) {
                            """{"profileId":"alice","onboarded":true,"deletionRequested":false,"consentStatus":"CURRENT"}"""
                        } else {
                            snapshot
                        }
                    ) to HttpStatusCode.OK
                }
            sdk.auth.awaitInitialization()
            sdk.auth.importAuthToken(token(), autoRefresh = false)
            val auth = SupabasePhoneAuth(sdk, "secret", scope = this)
            try {
                val consent = auth.consent("de")
                assertEquals(ConsentStatus.REQUIRED, consent.status)
                auth.decideConsent(consent, true)
                assertEquals(
                    listOf("my_consent_v1", "record_consent_v1", "my_auth_profile"),
                    requests.map { it.url.encodedPath.substringAfterLast('/') },
                )
                assertEquals(List(3) { "Bearer ${token()}" }, requests.map { it.headers["Authorization"] })
                val body = Json.parseToJsonElement((requests[1].body as TextContent).text).jsonObject
                assertEquals(setOf("p_documents", "p_accept"), body.keys)
                assertEquals("true", body.getValue("p_accept").jsonPrimitive.content)
                assertEquals(
                    listOf("server-2", "server-3"),
                    body.getValue("p_documents").jsonArray.map {
                        it.jsonObject
                            .getValue("version")
                            .jsonPrimitive.content
                    },
                )
            } finally {
                auth.close()
            }
        }

    @Test fun missingProfileConsentFieldFailsClosed(): Unit =
        runBlocking {
            val sdk = client { """{"profileId":"alice","onboarded":true,"deletionRequested":false}""" to HttpStatusCode.OK }
            sdk.auth.awaitInitialization()
            sdk.auth.importAuthToken(token(), autoRefresh = false)
            val auth = SupabasePhoneAuth(sdk, "secret", scope = this)
            try {
                auth.refreshProfile()
                assertEquals(ConsentStatus.UNAVAILABLE, auth.session.value?.consentStatus)
            } finally {
                auth.close()
            }
        }

    @Test fun staleConsentResponseCannotCrossAccountOrSameAccountRelogin(): Unit =
        runBlocking {
            for ((owner, session) in listOf(
                "bob" to "session-b",
                "alice" to "session-new",
                "bob" to "decision-b",
                "alice" to "decision-a",
            )) {
                val sent = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val sdk =
                    client {
                        sent.complete(Unit)
                        release.await()
                        snapshot to HttpStatusCode.OK
                    }
                sdk.auth.awaitInitialization()
                sdk.auth.importAuthToken(token(), autoRefresh = false)
                val auth = SupabasePhoneAuth(sdk, "secret", scope = this)
                try {
                    val pending =
                        async {
                            runCatching {
                                if (session.startsWith("decision")) {
                                    auth.decideConsent(CloudJson.decodeFromString<ConsentSnapshot>(snapshot), true)
                                } else {
                                    auth.consent("de")
                                }
                            }
                        }
                    sent.await()
                    sdk.auth.importAuthToken(token(owner, session), autoRefresh = false)
                    release.complete(Unit)
                    assertEquals("cloud_session_changed", (pending.await().exceptionOrNull() as CloudException).reason)
                } finally {
                    auth.close()
                }
            }
        }

    @Test fun failedDeclineAndDeletionStayLocallyBlockedButAllowVerifiedRetry(): Unit =
        runBlocking {
            val sdk =
                client { request ->
                    if (request.url.encodedPath.endsWith("my_auth_profile")) {
                        """
                        {"profileId":"alice","onboarded":true,"deletionRequested":false,"consentStatus":"REQUIRED"}
                        """.trimIndent() to HttpStatusCode.OK
                    } else {
                        "sensitive-server-error" to HttpStatusCode.ServiceUnavailable
                    }
                }
            sdk.auth.awaitInitialization()
            sdk.auth.importAuthToken(token(), autoRefresh = false)
            val auth = SupabasePhoneAuth(sdk, "secret", scope = this)
            try {
                auth.refreshProfile()
                assertEquals(
                    "connection_failed",
                    assertFailsWith<CloudException> {
                        auth.decideConsent(CloudJson.decodeFromString<ConsentSnapshot>(snapshot), false)
                    }.reason,
                )
                assertFailsWith<CloudException> { auth.sessions.capture() }
                auth.refreshProfile()
                assertEquals(ConsentStatus.REQUIRED, auth.session.value?.consentStatus)
                assertEquals("connection_failed", assertFailsWith<CloudException> { auth.requestDeletion() }.reason)
                assertFailsWith<CloudException> { auth.sessions.capture() }
                auth.refreshProfile()
                assertEquals(ConsentStatus.REQUIRED, auth.session.value?.consentStatus)
            } finally {
                auth.close()
            }
        }

    @Test fun deliberateDeclinePublishesVerifiedStateAfterLocalGenerationChange(): Unit =
        runBlocking {
            val sdk =
                client { request ->
                    (
                        if (request.url.encodedPath.endsWith("my_auth_profile")) {
                            """{"profileId":"alice","onboarded":true,"deletionRequested":false,"consentStatus":"DECLINED"}"""
                        } else {
                            snapshot.replace("REQUIRED", "DECLINED")
                        }
                    ) to HttpStatusCode.OK
                }
            sdk.auth.awaitInitialization()
            sdk.auth.importAuthToken(token(), autoRefresh = false)
            val auth = SupabasePhoneAuth(sdk, "secret", scope = this)
            try {
                val result = auth.decideConsent(CloudJson.decodeFromString<ConsentSnapshot>(snapshot), false)
                assertEquals(ConsentStatus.DECLINED, result.status)
                assertEquals(ConsentStatus.DECLINED, auth.session.value?.consentStatus)
                assertEquals("alice", auth.session.value?.profileId)
            } finally {
                auth.close()
            }
        }

    @Test fun consentErrorsKeepStableReasonsAndUnknownErrorsStaySanitized(): Unit =
        runBlocking {
            for (reason in listOf(
                "consent_required",
                "consent_unavailable",
                "consent_version_changed",
                "consent_documents_invalid",
                "consent_language_invalid",
                "no_deletion_pending",
                "internal-secret-detail",
            )) {
                val sdk = client { """{"message":"$reason"}""" to HttpStatusCode.BadRequest }
                sdk.auth.awaitInitialization()
                sdk.auth.importAuthToken(token(), autoRefresh = false)
                val auth = SupabasePhoneAuth(sdk, "secret", scope = this)
                try {
                    val error = assertFailsWith<CloudException> { auth.consent("de") }
                    assertEquals(if (reason == "internal-secret-detail") "connection_failed" else reason, error.reason)
                    assertFalse(error.message!!.contains("internal-secret"))
                } finally {
                    auth.close()
                }
            }
        }

    @Test fun deletionGraceAllowsConsentExportAndRecoveryWithoutCloudLease(): Unit =
        runBlocking {
            val paths = mutableListOf<String>()
            val sdk =
                client { request ->
                    paths += request.url.encodedPath.substringAfterLast('/')
                    when (paths.last()) {
                        "my_auth_profile" -> {
                            """
                            {"profileId":"alice","onboarded":false,"deletionRequested":true,"consentStatus":"DECLINED"}
                            """.trimIndent()
                        }

                        "my_consent_v1" -> {
                            snapshot.replace("REQUIRED", "DECLINED")
                        }

                        else -> {
                            "{}"
                        }
                    } to HttpStatusCode.OK
                }
            sdk.auth.awaitInitialization()
            sdk.auth.importAuthToken(token(), autoRefresh = false)
            val auth = SupabasePhoneAuth(sdk, "secret", scope = this)
            try {
                auth.refreshProfile()
                assertFailsWith<CloudException> { auth.sessions.capture() }
                assertEquals(ConsentStatus.DECLINED, auth.consent("de").status)
                assertEquals("{}", auth.exportData())
                auth.cancelDeletion()
                assertEquals(
                    listOf("my_auth_profile", "my_consent_v1", "export_my_data", "cancel_account_deletion", "my_auth_profile"),
                    paths,
                )
            } finally {
                auth.close()
            }
        }
}
