package eu.advantly.cloud.sync

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.SessionConfig
import eu.advantly.sync.protocol.PeerKind
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountMutationRevisionTest {
    private enum class Intent { ACCEPT, DECLINE, DELETE, CANCEL }

    private val snapshot = ConsentSnapshot("alice", ConsentStatus.REQUIRED, emptyList())
    private val profile =
        """{"profileId":"alice","onboarded":true,"deletionRequested":false,"consentStatus":"CURRENT"}"""

    private fun token(session: String = "session-a") =
        "h." +
            Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT)
                .encode("""{"sub":"alice","session_id":"$session"}""".encodeToByteArray()) + ".s"

    private suspend fun perform(
        auth: SupabasePhoneAuth,
        intent: Intent,
    ) {
        when (intent) {
            Intent.ACCEPT -> auth.decideConsent(snapshot, true)
            Intent.DECLINE -> auth.decideConsent(snapshot, false)
            Intent.DELETE -> auth.requestDeletion()
            Intent.CANCEL -> auth.cancelDeletion()
        }
    }

    private fun regression(
        older: Intent,
        newer: Intent,
        replaceSession: Boolean = false,
        delayProfile: Boolean = false,
    ): Unit =
        runBlocking {
            val sent = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var mutations = 0
            var profiles = 0
            var failNewer = true
            val sdk =
                createSupabaseClient("https://auth.test", "public-key") {
                    defaultLogLevel = LogLevel.NONE
                    install(Auth) { minimalConfig() }
                    httpEngine =
                        MockEngine { request ->
                            val path = request.url.encodedPath.substringAfterLast('/')
                            val body: String
                            var status = HttpStatusCode.OK
                            if (path == "my_auth_profile") {
                                profiles++
                                if (delayProfile && profiles == 2) {
                                    sent.complete(Unit)
                                    release.await()
                                }
                                body = profile
                            } else if (path == "export_my_data") {
                                body = "exported"
                            } else {
                                mutations++
                                if (path == "record_consent_v1") {
                                    val accept =
                                        Json
                                            .parseToJsonElement((request.body as TextContent).text)
                                            .jsonObject
                                            .getValue("p_accept")
                                            .jsonPrimitive.boolean
                                    assertEquals(if (mutations == 1) older == Intent.ACCEPT else mutations > 2, accept)
                                }
                                if (mutations == 1 && !delayProfile) {
                                    sent.complete(Unit)
                                    release.await()
                                } else if (mutations > 1 && failNewer) {
                                    status = HttpStatusCode.ServiceUnavailable
                                }
                                body =
                                    if (path ==
                                        "record_consent_v1"
                                    ) {
                                        CloudJson.encodeToString(snapshot.copy(status = ConsentStatus.CURRENT))
                                    } else {
                                        "{}"
                                    }
                            }
                            respond(body, status, headersOf("Content-Type", "application/json"))
                        }
                }
            sdk.auth.awaitInitialization()
            sdk.auth.importAuthToken(token(), autoRefresh = false)
            val data = LocalData(createInMemoryJvmDriver())
            val phone = MatchSession(data, null, ManualScheduler(), SessionConfig(MatchSessions.deviceId(data), PeerKind.PHONE))
            data.players.ensureSelf("Alex", 0) { PlayerId("self") }
            phone.start()
            val auth = SupabasePhoneAuth(sdk, "secret", uploads = data.cloudUploads, scope = this, ownership = phone)
            try {
                auth.refreshProfile()
                val originalDevice = phone.deviceId
                val originalScope = data.accountIdentities.scope
                val match =
                    phone.create(
                        Presets.TENNIS_SINGLES_BO3.format(),
                        Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a")),
                    )
                assertTrue(phone.recordPoint(Side.A))
                val historical = data.events.eventsFor(match.value)
                val old = async { runCatching { perform(auth, older) } }
                sent.await()
                if (replaceSession) {
                    sdk.auth.importAuthToken(token("session-new"), autoRefresh = false)
                    auth.refreshProfile()
                }
                val account = auth.accountSession.value!!
                assertEquals("connection_failed", assertFailsWith<CloudException> { perform(auth, newer) }.reason)
                assertFailsWith<CloudException> { auth.sessions.capture() }
                val beforeRelease = profiles
                release.complete(Unit)
                val failure = old.await().exceptionOrNull()
                if (!delayProfile || failure !is CancellationException) {
                    assertEquals("cloud_session_changed", assertIs<CloudException>(failure).reason)
                }
                assertEquals(beforeRelease, profiles, "An obsolete mutation must not start a profile refresh")
                assertFailsWith<CloudException> { auth.sessions.capture() }
                auth.requireAccountSession(account)
                assertEquals("exported", auth.exportData())
                auth.refreshProfile()
                assertFailsWith<CloudException> { auth.sessions.capture() }
                if (older == Intent.DELETE || newer == Intent.DELETE) {
                    assertNull(data.accountIdentities.profile)
                    assertNotEquals(originalDevice, phone.deviceId)
                    val guestMatch =
                        phone.create(
                            Presets.TENNIS_SINGLES_BO3.format(),
                            Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a")),
                        )
                    assertTrue(phone.recordPoint(Side.B))
                    assertTrue(data.events.eventsFor(guestMatch.value).all { it.deviceId == phone.deviceId.value })
                } else {
                    assertEquals("alice", data.accountIdentities.profile)
                    assertEquals(originalDevice, phone.deviceId)
                }
                assertEquals(historical, data.events.eventsFor(match.value))
                assertEquals(originalScope, data.accountIdentities.sourceScope(originalDevice.value))
                failNewer = false
                perform(auth, if (newer == Intent.DECLINE) Intent.ACCEPT else Intent.CANCEL)
                assertEquals(
                    "alice",
                    auth.sessions
                        .capture()
                        .credentials.profileId,
                )
            } finally {
                release.complete(Unit)
                auth.close()
                phone.close()
            }
        }

    @Test fun olderAcceptanceCannotAcknowledgeFailedLaterDecline() = regression(Intent.ACCEPT, Intent.DECLINE)

    @Test fun olderDeletionCannotAcknowledgeFailedLaterRecovery() = regression(Intent.DELETE, Intent.CANCEL)

    @Test fun olderRecoveryCannotAcknowledgeFailedLaterDeletion() = regression(Intent.CANCEL, Intent.DELETE)

    @Test fun oldSessionAcceptanceCannotClearNewSessionFailedDecline() = regression(Intent.ACCEPT, Intent.DECLINE, true)

    @Test fun oldSessionDeletionCannotClearNewSessionFailedRecovery() = regression(Intent.DELETE, Intent.CANCEL, true)

    @Test fun olderAcceptanceProfileCannotOutliveLaterFailedDecline() = regression(Intent.ACCEPT, Intent.DECLINE, delayProfile = true)

    @Test fun olderDeletionProfileCannotOutliveLaterFailedRecovery() = regression(Intent.DELETE, Intent.CANCEL, delayProfile = true)
}
