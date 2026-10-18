package eu.advantly.cloud.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupabaseSessionLifecycleTest {
    private val profile = """{"profileId":"alice","onboarded":true,"deletionRequested":false}"""

    private fun token(
        owner: String = "alice",
        session: String = "session-a",
        revision: Int = 1,
    ): String =
        "header." +
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(
                """{"sub":"$owner","session_id":"$session","revision":$revision}""".encodeToByteArray(),
            ) + ".signature"

    private fun client(reply: suspend (HttpRequestData) -> Pair<String, HttpStatusCode>): SupabaseClient =
        createSupabaseClient("https://auth.test", "public-key") {
            httpEngine =
                MockEngine { request ->
                    val (body, status) = reply(request)
                    respond(body, status, headersOf("Content-Type", "application/json"))
                }
            defaultLogLevel = LogLevel.NONE
            install(Auth) { minimalConfig() }
        }

    private suspend fun SupabaseClient.restore(value: String = token()) {
        auth.awaitInitialization()
        auth.importAuthToken(value, autoRefresh = false)
    }

    @Test fun authDeadlineBecomesVisibleConnectionFailure(): Unit =
        runBlocking {
            val sdk =
                client {
                    CompletableDeferred<Unit>().await()
                    profile to HttpStatusCode.OK
                }
            val auth = SupabasePhoneAuth(sdk, "age-secret", scope = this, requestTimeoutMillis = 20)
            try {
                assertEquals("connection_failed", assertFailsWith<CloudException> { auth.issueAgeTicket("AT", "2000-01-01") }.reason)
                assertTrue(coroutineContext[kotlinx.coroutines.Job]!!.isActive)
            } finally {
                auth.close()
            }
        }

    @Test fun restoredSdkSessionStaysDisabledUntilProfileResponseWasVerified(): Unit =
        runBlocking {
            val sent = CompletableDeferred<HttpRequestData>()
            val release = CompletableDeferred<Unit>()
            val sdk =
                client {
                    sent.complete(it)
                    release.await()
                    profile to HttpStatusCode.OK
                }
            sdk.restore()
            val auth = SupabasePhoneAuth(sdk, "age-secret", scope = this)
            try {
                assertNull(auth.session.value)
                val pending = async { auth.refreshProfile() }
                assertEquals("Bearer ${token()}", withTimeout(5_000) { sent.await() }.headers["Authorization"])
                assertNull(auth.session.value)
                assertFailsWith<CloudException> { auth.sessions.capture() }
                release.complete(Unit)
                pending.await()
                assertEquals("alice", auth.session.value?.profileId)
            } finally {
                auth.close()
            }
            assertTrue(coroutineContext[kotlinx.coroutines.Job]!!.isActive)
        }

    @Test fun profileResponseFromOldSdkSessionCannotPublishAfterAccountSwitch(): Unit =
        runBlocking {
            val sent = CompletableDeferred<HttpRequestData>()
            val release = CompletableDeferred<Unit>()
            val sdk =
                client {
                    sent.complete(it)
                    release.await()
                    profile to HttpStatusCode.OK
                }
            sdk.restore()
            val auth = SupabasePhoneAuth(sdk, "age-secret", scope = this)
            try {
                val pending = async { runCatching { auth.refreshProfile() } }
                val request = withTimeout(5_000) { sent.await() }
                sdk.restore(token("bob", "session-b"))
                release.complete(Unit)
                assertTrue(pending.await().isFailure)
                assertNull(auth.session.value)
                assertEquals("Bearer ${token()}", request.headers["Authorization"])
            } finally {
                auth.close()
            }
        }

    @Test fun offlineLogoutDisablesBeforeNetworkCompletesAndClearsSdk(): Unit =
        runBlocking {
            val logoutStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sdk =
                client {
                    if (it.url.encodedPath.endsWith("logout")) {
                        logoutStarted.complete(Unit)
                        release.await()
                        "sensitive-server-detail" to HttpStatusCode.ServiceUnavailable
                    } else {
                        profile to HttpStatusCode.OK
                    }
                }
            sdk.restore()
            val auth = SupabasePhoneAuth(sdk, "age-secret", scope = this)
            try {
                auth.refreshProfile()
                val logout = async { runCatching { auth.signOut() } }
                withTimeout(5_000) { logoutStarted.await() }
                assertNull(auth.session.value)
                assertFailsWith<CloudException> { auth.sessions.capture() }
                release.complete(Unit)
                assertTrue(logout.await().isFailure)
                assertNull(sdk.auth.currentSessionOrNull())
            } finally {
                auth.close()
            }
        }

    @Test fun sameAccountExplicitLoginChangesGenerationEvenWithSameJwtSessionId(): Unit =
        runBlocking {
            val sdk =
                client {
                    if (it.url.encodedPath.endsWith("verify")) {
                        """{"access_token":"${token()}","refresh_token":"refresh","expires_in":3600,"token_type":"bearer"}""" to
                            HttpStatusCode.OK
                    } else {
                        profile to HttpStatusCode.OK
                    }
                }
            sdk.restore()
            val auth = SupabasePhoneAuth(sdk, "age-secret", scope = this)
            try {
                auth.refreshProfile()
                val old = auth.sessions.capture()
                auth.verifyEmailCode("alice@example.com", "123456")
                assertTrue(auth.session.value!!.generation > old.generation)
                assertFailsWith<CloudException> { auth.sessions.requireCurrent(old) }
            } finally {
                auth.close()
            }
        }

    @Test fun refreshedTokenKeepsSessionAndLossIsCheckedSynchronously(): Unit =
        runBlocking {
            val headers = mutableListOf<String?>()
            val sdk =
                client {
                    headers += it.headers["Authorization"]
                    profile to HttpStatusCode.OK
                }
            sdk.restore()
            val auth = SupabasePhoneAuth(sdk, "age-secret", scope = this)
            try {
                auth.refreshProfile()
                val old = auth.sessions.capture()
                sdk.restore(token(revision = 2))
                auth.refreshProfile()
                assertEquals(old.generation, auth.session.value?.generation)
                auth.sessions.requireCurrent(old)
                assertEquals("Bearer ${token(revision = 2)}", headers.last())
                sdk.auth.clearSession()
                assertFailsWith<CloudException> { auth.sessions.requireCurrent(old) }
                assertNull(auth.session.value)
            } finally {
                auth.close()
            }
        }

    @Test fun deletionRequestAndDeletedProfileCannotReenableUploads(): Unit =
        runBlocking {
            var deleted = false
            val sdk = client { profile.replace("false", deleted.toString()) to HttpStatusCode.OK }
            sdk.restore()
            val auth = SupabasePhoneAuth(sdk, "age-secret", scope = this)
            try {
                auth.refreshProfile()
                val old = auth.sessions.capture()
                auth.stopForDeletion()
                assertFailsWith<CloudException> { auth.sessions.requireCurrent(old) }
                auth.refreshProfile()
                assertFailsWith<CloudException> { auth.sessions.capture() }
                assertTrue(auth.accountSession.value?.onboarded == true)
                assertFailsWith<CloudException> { auth.sessions.requireCurrent(old) }
            } finally {
                auth.close()
            }
            val restored = client { profile.replace("false", "true") to HttpStatusCode.OK }
            restored.restore()
            val restoredAuth = SupabasePhoneAuth(restored, "age-secret", scope = this)
            try {
                restoredAuth.refreshProfile()
                assertTrue(restoredAuth.session.value!!.deletionRequested)
                assertFailsWith<CloudException> { restoredAuth.sessions.capture() }
            } finally {
                restoredAuth.close()
            }
        }
}
