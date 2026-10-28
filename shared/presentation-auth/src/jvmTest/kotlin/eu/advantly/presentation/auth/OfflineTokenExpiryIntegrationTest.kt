package eu.advantly.presentation.auth

import eu.advantly.cloud.sync.SupabasePhoneAuth
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
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
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * P1-1 (Review 0.3.0): Ein abgelaufener Token, der sich offline nicht erneuern lässt (supabase-kt: RefreshFailure,
 * `currentSessionOrNull() == null`), ist kein Abmelden. Echter SupabaseClient und echte MatchSession: weder Kaltstart
 * noch laufende App verlassen das Konto, das offene Match bleibt, kein Konto-Tor; nach Netzrückkehr prüft der Host selbst.
 */
class OfflineTokenExpiryIntegrationTest {
    private val data = LocalData(createInMemoryJvmDriver())
    private var online = true
    private lateinit var scope: CoroutineScope
    private val profile = """{"profileId":"alice","onboarded":true,"deletionRequested":false,"consentStatus":"CURRENT"}"""
    private val consent =
        """{"profileId":"alice","status":"CURRENT","documents":[
           {"type":"TERMS","version":"2","language":"en","url":"https://advantly.eu/en/terms.html","approved":true},
           {"type":"PRIVACY","version":"5","language":"en","url":"https://advantly.eu/en/app-privacy.html","approved":true}]}"""

    private fun token(revision: Int) =
        "h." +
            Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT)
                .encode("""{"sub":"alice","session_id":"session-a","revision":$revision}""".encodeToByteArray()) + ".s"

    private fun session(
        revision: Int,
        expired: Boolean,
    ) = UserSession(
        token(revision),
        "refresh-$revision",
        expiresIn = 3600,
        tokenType = "bearer",
        expiresAt = if (expired) Clock.System.now() - 60.seconds else Clock.System.now() + 3600.seconds,
    )

    private fun client(storage: MemorySessionManager? = null): SupabaseClient =
        createSupabaseClient("https://auth.test", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Auth) {
                minimalConfig()
                if (storage != null) {
                    sessionManager = storage
                    autoLoadFromStorage = true
                }
                alwaysAutoRefresh = true
                retryDelay = 20.milliseconds
            }
            httpEngine =
                MockEngine { request ->
                    if (!online) throw IOException("offline")
                    val body =
                        when (request.url.encodedPath.substringAfterLast('/')) {
                            "my_auth_profile" -> profile

                            "my_consent_v1" -> consent

                            "token" -> """{"access_token":"${token(9)}","refresh_token":"refresh-9","expires_in":3600,
                                "token_type":"bearer","user":{"id":"alice","aud":"authenticated"}}"""

                            else -> error(request.url.encodedPath)
                        }
                    respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
        }

    private fun phone() =
        MatchSession(data, null, ManualScheduler(), SessionConfig(MatchSessions.deviceId(data), PeerKind.PHONE)).also { it.start() }

    /** Alles auf dem Testthread (serieller Host-Kontext wie Main); SDK und MockEngine laufen eigenständig. */
    private suspend fun <T> onScope(block: suspend () -> T): T = block()

    private suspend fun settled(model: AuthViewModel) = withTimeout(5_000) { model.state.first { !it.busy } }

    /** Erster Lauf online: angemeldet, Konto aktiv, ein offenes Match. */
    private suspend fun signedInWithOpenMatch(sdk: SupabaseClient): Triple<MatchSession, SupabasePhoneAuth, AuthViewModel> =
        onScope {
            data.players.ensureSelf("Alex", 0) { PlayerId("self") }
            val phone = phone()
            sdk.auth.awaitInitialization()
            sdk.auth.importSession(session(1, expired = false), autoRefresh = false)
            val auth = SupabasePhoneAuth(sdk, "secret", uploads = data.cloudUploads, scope = scope, ownership = phone)
            val model = AuthViewModel(auth, scope)
            settled(model)
            assertEquals(AuthStep.ACCOUNT, model.state.value.step)
            assertEquals("alice", data.accountIdentities.profile)
            phone.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a")))
            phone.recordPoint(Side.A)
            Triple(phone, auth, model)
        }

    @Test fun runningAppOfflineAfterTokenExpiryKeepsAccountOpenMatchAndNoGate(): Unit =
        runBlocking {
            scope = CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob())
            try {
                val sdk = client()
                val (phone, _, model) = signedInWithOpenMatch(sdk)
                val match = assertNotNull(onScope { phone.state.value.match })
                val device = onScope { phone.deviceId }
                online = false
                // Token läuft ab, die automatische Erneuerung scheitert ohne Netz: RefreshFailure.
                onScope { sdk.auth.importSession(session(2, expired = true), autoRefresh = true) }
                withTimeout(5_000) { sdk.auth.sessionStatus.first { it is SessionStatus.RefreshFailure } }
                delay(100)
                onScope {
                    assertEquals("alice", data.accountIdentities.profile, "kein Gastbereich")
                    assertEquals(device, phone.deviceId)
                    assertEquals(
                        match.id,
                        phone.state.value.match
                            ?.id,
                        "offenes Match bleibt",
                    )
                    assertFalse(model.state.value.accountRequired, "kein Konto-Tor")
                }
                online = true
                withTimeout(5_000) { sdk.auth.sessionStatus.first { it is SessionStatus.Authenticated } }
                delay(100)
                onScope {
                    assertEquals(
                        match.id,
                        phone.state.value.match
                            ?.id,
                    )
                    assertEquals(AuthStep.ACCOUNT, model.state.value.step)
                    assertFalse(model.state.value.accountRequired)
                }
            } finally {
                scope.cancel()
            }
        }

    @Test fun coldStartOfflineWithExpiredSessionKeepsCountingAndRecoversWhenOnline(): Unit =
        runBlocking {
            scope = CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob())
            try {
                val (first, firstAuth, _) = signedInWithOpenMatch(client())
                val match: MatchId =
                    onScope {
                        first.state.value.match!!
                            .id
                    }
                val device = onScope { first.deviceId }
                onScope {
                    firstAuth.close()
                    first.close()
                }
                // Neuer Prozess, offline, gespeicherte Sitzung inzwischen abgelaufen.
                online = false
                val storage = MemorySessionManager(session(1, expired = true))
                val sdk = client(storage)
                val (phone, model) =
                    onScope {
                        val phone = phone()
                        val auth = SupabasePhoneAuth(sdk, "secret", uploads = data.cloudUploads, scope = scope, ownership = phone)
                        phone to AuthViewModel(auth, scope)
                    }
                withTimeout(5_000) { sdk.auth.sessionStatus.first { it is SessionStatus.RefreshFailure } }
                val offline = settled(model)
                assertEquals("connection_failed", offline.error)
                onScope {
                    assertFalse(model.state.value.accountRequired, "weiterzählen statt Anmeldung")
                    assertEquals("alice", data.accountIdentities.profile)
                    assertEquals(device, phone.deviceId)
                    assertNotNull(data.matches.get(match), "Match bleibt sichtbar")
                }
                // Netz zurück: das SDK erneuert selbst, danach bestätigt der Host das Konto ohne weiteren Anstoß.
                online = true
                withTimeout(5_000) { model.state.first { it.step == AuthStep.ACCOUNT && !it.unconfirmed && !it.busy } }
                onScope {
                    assertFalse(model.state.value.accountRequired)
                    assertEquals("alice", data.accountIdentities.profile)
                    assertNotNull(data.matches.get(match))
                }
            } finally {
                scope.cancel()
            }
        }
}
