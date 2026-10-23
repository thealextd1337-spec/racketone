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
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** K4/K5: Sperre im Kontoprofil schließt die lokale Cloud-Arbeit; Geräteliste und Widerruf bleiben Kontorechte. */
class AccountSupportAuthTest {
    @Test fun suspendedProfileClosesCloudWorkButKeepsDeviceRights(): Unit =
        runBlocking {
            val requests = mutableListOf<Pair<String, String>>()
            val sdk =
                client { request ->
                    val name = request.url.encodedPath.substringAfterLast('/')
                    requests += name to ((request.body as? TextContent)?.text ?: "")
                    when (name) {
                        "my_auth_profile" -> {
                            """{"profileId":"alice","onboarded":true,"deletionRequested":false,"consentStatus":"CURRENT","suspended":true}"""
                        }

                        "cloud_list_devices_v1" -> {
                            """[{"deviceId":"d1","installationId":"i1","kind":"phone","platform":"android","model":"Pixel 9",
                            "appVersion":"1.0","lastSeenAt":"2026-10-22T08:00:00Z","revokedAt":null},
                            {"deviceId":"d2","kind":"watch","platform":"wearos","revokedAt":"2026-10-23T08:00:00Z","current":true}]"""
                        }

                        else -> {
                            """{"deviceId":"d1","revoked":true}"""
                        }
                    } to HttpStatusCode.OK
                }
            sdk.auth.awaitInitialization()
            sdk.auth.importAuthToken(token(), autoRefresh = false)
            val auth = SupabasePhoneAuth(sdk, "secret", scope = this)
            try {
                auth.refreshProfile()
                assertEquals(true, auth.session.value?.suspended)
                assertEquals(listOf(false, false), auth.devices().map { it.current }, "ohne eigene Installation kein eigenes Gerät")
                auth.cloudBackend(CloudInstallation("i1", "0".repeat(64)))
                assertEquals("account_suspended", assertFailsWith<CloudException> { auth.sessions.capture() }.reason)
                val devices = auth.devices()
                assertEquals(listOf("d1", "d2"), devices.map { it.deviceId })
                assertEquals("Pixel 9", devices[0].model)
                assertEquals(null, devices[0].revokedAt)
                assertEquals("2026-10-23T08:00:00Z", devices[1].revokedAt)
                assertEquals(listOf(true, false), devices.map { it.current }, "eigenes Gerät über die Installations-ID, nie vom Server")
                auth.revokeDevice("d1")
                assertEquals("cloud_revoke_device_v1" to """{"p_device_id":"d1"}""", requests.last())
                assertTrue(requests.none { it.second.contains("push", ignoreCase = true) })
            } finally {
                auth.close()
            }
        }

    @Test fun unsuspendedOrOlderServerProfileStaysUsableAndServerReasonStaysStable(): Unit =
        runBlocking {
            val sdk =
                client { request ->
                    if (request.url.encodedPath.endsWith("my_auth_profile")) {
                        """{"profileId":"alice","onboarded":true,"deletionRequested":false,"consentStatus":"CURRENT"}""" to
                            HttpStatusCode.OK
                    } else {
                        """{"code":"42501","message":"account_suspended"}""" to HttpStatusCode.Forbidden
                    }
                }
            sdk.auth.awaitInitialization()
            sdk.auth.importAuthToken(token(), autoRefresh = false)
            val auth = SupabasePhoneAuth(sdk, "secret", scope = this)
            try {
                auth.refreshProfile()
                assertEquals(false, auth.session.value?.suspended)
                auth.sessions.capture()
                assertEquals("account_suspended", assertFailsWith<CloudException> { auth.revokeDevice("d1") }.reason)
            } finally {
                auth.close()
            }
        }

    @Test fun restoreSendsThisPhonesInstallationProofAndKeepsServerReasons(): Unit =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            var reply = """{"deviceId":"d2","revoked":false}""" to HttpStatusCode.OK
            val sdk =
                client { request ->
                    requests += request
                    if (request.url.encodedPath.endsWith("my_auth_profile")) {
                        """{"profileId":"alice","onboarded":true,"deletionRequested":false,"consentStatus":"CURRENT"}""" to
                            HttpStatusCode.OK
                    } else {
                        reply
                    }
                }
            sdk.auth.awaitInitialization()
            sdk.auth.importAuthToken(token(), autoRefresh = false)
            val auth = SupabasePhoneAuth(sdk, "secret", scope = this)
            try {
                auth.refreshProfile()
                assertEquals("device_restore_unavailable", assertFailsWith<CloudException> { auth.restoreDevice("d2") }.reason)
                assertTrue(requests.none { it.url.encodedPath.endsWith("cloud_unrevoke_device_v1") }, "ohne Installation kein Aufruf")
                auth.cloudBackend(CloudInstallation("i1", "a".repeat(64)))
                auth.restoreDevice("d2")
                val sent = requests.last()
                assertEquals("cloud_unrevoke_device_v1", sent.url.encodedPath.substringAfterLast('/'))
                assertEquals("""{"p_device_id":"d2"}""", (sent.body as TextContent).text)
                assertEquals("i1", sent.headers["X-Advantly-Installation-Id"])
                assertEquals("a".repeat(64), sent.headers["X-Advantly-Installation-Proof"])
                for ((server, expected) in listOf(
                    "device_unrevoke_self" to "device_unrevoke_self",
                    "device_not_found" to "device_not_found",
                    "device_not_revoked" to "device_not_revoked",
                    "account_deletion_pending" to "account_deletion_pending",
                    "device_revoked" to "device_restore_unavailable",
                    "device_not_authorized" to "device_restore_unavailable",
                )) {
                    reply = """{"code":"42501","message":"$server"}""" to HttpStatusCode.Forbidden
                    assertEquals(expected, assertFailsWith<CloudException> { auth.restoreDevice("d2") }.reason, server)
                }
                assertEquals("alice", auth.session.value?.profileId, "Ablehnungen beenden die Kontositzung nicht")
            } finally {
                auth.close()
            }
        }

    private fun token(): String =
        "header." +
            Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT)
                .encode("""{"sub":"alice","session_id":"session-a"}""".encodeToByteArray()) + ".signature"

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
}
