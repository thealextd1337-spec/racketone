package eu.advantly.presentation.auth

import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudInstallation
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.SupabasePhoneAuth
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ConsentAccountIntegrationTest {
    private fun token(
        owner: String = "alice",
        session: String = "session-a",
    ): String =
        "header." +
            Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT)
                .encode("""{"sub":"$owner","session_id":"$session"}""".encodeToByteArray()) + ".signature"

    @Test fun failedDeclineKeepsExportDeletionAndRecoveryReachableWithoutManualRefresh(): Unit =
        runBlocking {
            val calls = mutableListOf<String>()
            var deletion = false
            var revoked = false
            val sdk =
                createSupabaseClient("https://auth.test", "public-key") {
                    defaultLogLevel = LogLevel.NONE
                    install(Auth) { minimalConfig() }
                    httpEngine =
                        MockEngine { request ->
                            val name = request.url.encodedPath.substringAfterLast('/')
                            calls += name
                            val (body, status) =
                                when {
                                    name == "record_consent_v1" -> {
                                        "server-offline" to HttpStatusCode.ServiceUnavailable
                                    }

                                    revoked -> {
                                        """{"message":"cloud_session_changed"}""" to HttpStatusCode.Forbidden
                                    }

                                    name == "my_auth_profile" -> {
                                        """
                                        {"profileId":"alice","onboarded":${!deletion},"deletionRequested":$deletion,"consentStatus":"REQUIRED"}
                                        """.trimIndent() to HttpStatusCode.OK
                                    }

                                    name == "my_consent_v1" -> {
                                        """{"profileId":"alice","status":"REQUIRED","documents":[]}""" to HttpStatusCode.OK
                                    }

                                    name == "request_account_deletion" -> {
                                        deletion = true
                                        "{}" to HttpStatusCode.OK
                                    }

                                    name == "cancel_account_deletion" -> {
                                        deletion = false
                                        "{}" to HttpStatusCode.OK
                                    }

                                    else -> {
                                        """{"consentHistory":[]}""" to HttpStatusCode.OK
                                    }
                                }
                            respond(body, status, headers = headersOf("Content-Type", "application/json"))
                        }
                }
            sdk.auth.awaitInitialization()
            sdk.auth.importAuthToken(token(), autoRefresh = false)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val auth = SupabasePhoneAuth(sdk, "secret", scope = scope)
            val model = AuthViewModel(auth, scope)

            suspend fun settled() = withTimeout(5_000) { model.state.first { !it.busy } }
            try {
                settled()
                val identity = auth.accountSession.value!!
                model.decideConsent(false)
                assertEquals("connection_failed", settled().error)
                assertEquals(identity, auth.accountSession.value)
                assertFailsWith<CloudException> {
                    auth.cloudBackend(CloudInstallation("installation", "a".repeat(64))).bindDevice("phone")
                }
                val profiles = calls.count { it == "my_auth_profile" }
                model.exportData()
                assertEquals("{\"consentHistory\":[]}", settled().exportedData)
                assertEquals(profiles, calls.count { it == "my_auth_profile" })
                model.requestDeletion()
                assertEquals(true, settled().deletionRequested)
                model.exportData()
                assertEquals(null, settled().error)
                model.cancelDeletion()
                assertFalse(settled().deletionRequested)
                assertEquals(ConsentStatus.REQUIRED, auth.accountSession.value?.consentStatus)
                assertEquals(identity.generation, auth.accountSession.value?.generation)
                revoked = true
                model.exportData()
                settled()
                assertEquals(null, auth.accountSession.value)
                assertFalse(model.state.value.authenticated)
            } finally {
                auth.close()
                scope.cancel()
            }
        }

    @Test fun delayedSdkFlowCannotAuthorizeRightsForAnotherAccountOrSameAccountRelogin(): Unit =
        runBlocking {
            val sdk =
                createSupabaseClient("https://auth.test", "public-key") {
                    defaultLogLevel = LogLevel.NONE
                    install(Auth) { minimalConfig() }
                    httpEngine =
                        MockEngine {
                            respond(
                                """{"profileId":"alice","onboarded":true,"deletionRequested":false,"consentStatus":"CURRENT"}""",
                                headers = headersOf("Content-Type", "application/json"),
                            )
                        }
                }
            sdk.auth.awaitInitialization()
            sdk.auth.importAuthToken(token(), autoRefresh = false)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val auth = SupabasePhoneAuth(sdk, "secret", scope = scope)
            try {
                auth.refreshProfile()
                val identity = auth.accountSession.value!!
                sdk.auth.importAuthToken(token("alice", "session-new"), autoRefresh = false)
                assertFailsWith<CloudException> { auth.requireAccountSession(identity) }
                sdk.auth.importAuthToken(token("bob", "session-b"), autoRefresh = false)
                assertFailsWith<CloudException> { auth.requireAccountSession(identity) }
            } finally {
                auth.close()
                scope.cancel()
            }
        }
}
