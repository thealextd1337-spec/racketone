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
import io.ktor.client.request.HttpRequestData
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountExportIntegrationTest {
    private val id = "11111111-1111-1111-1111-111111111111"

    private fun token(
        profile: String = "alice",
        session: String = "session-a",
        refresh: String = "a",
    ) = "h." +
        Base64.UrlSafe
            .withPadding(Base64.PaddingOption.ABSENT)
            .encode("""{"sub":"$profile","session_id":"$session","refresh":"$refresh"}""".encodeToByteArray()) + ".s"

    private class Fixture(
        scope: CoroutineScope,
    ) {
        var profileId = "alice"
        var consent = ConsentStatus.REQUIRED
        var onboarded = false
        var deletion = false
        val requests = mutableListOf<HttpRequestData>()
        var holdRequest: CompletableDeferred<Unit>? = null
        var releaseRequest: CompletableDeferred<Unit>? = null
        var download = false
        val sdk =
            createSupabaseClient("https://auth.test", "public-key") {
                defaultLogLevel = LogLevel.NONE
                install(Auth) { minimalConfig() }
                httpEngine =
                    MockEngine { request ->
                        requests += request
                        val path = request.url.encodedPath
                        if (path.startsWith("/functions/")) {
                            holdRequest?.complete(Unit)
                            releaseRequest?.let { withContext(NonCancellable) { it.await() } }
                            if (download) {
                                respond(ByteArray(20_000) { 7 }, headers = headersOf("Content-Type", "application/zip"))
                            } else {
                                respond(
                                    """{"contractVersion":1,"requestId":"11111111-1111-1111-1111-111111111111",
                            "status":"pending","retryAfterSeconds":3}""",
                                    io.ktor.http.HttpStatusCode.Accepted,
                                    headersOf("Content-Type" to listOf("application/json"), "Retry-After" to listOf("3")),
                                )
                            }
                        } else if (path.endsWith("verify")) {
                            val accessToken =
                                "h." +
                                    Base64.UrlSafe
                                        .withPadding(Base64.PaddingOption.ABSENT)
                                        .encode("""{"sub":"alice","session_id":"session-a","refresh":"a"}""".encodeToByteArray()) + ".s"
                            respond(
                                """{"access_token":"$accessToken","refresh_token":"refresh","expires_in":3600,"token_type":"bearer"}""",
                                headers = headersOf("Content-Type", "application/json"),
                            )
                        } else if (path.endsWith("record_consent_v1")) {
                            respond(
                                CloudJson.encodeToString(ConsentSnapshot(profileId, consent, emptyList())),
                                headers = headersOf("Content-Type", "application/json"),
                            )
                        } else if (path.endsWith("my_auth_profile")) {
                            respond(
                                """{"profileId":"$profileId","onboarded":$onboarded,"deletionRequested":$deletion,"consentStatus":"$consent"}""",
                                headers = headersOf("Content-Type", "application/json"),
                            )
                        } else {
                            respond("{}", headers = headersOf("Content-Type", "application/json"))
                        }
                    }
            }
        val data = LocalData(createInMemoryJvmDriver())
        val phone = MatchSession(data, null, ManualScheduler(), SessionConfig(MatchSessions.deviceId(data), PeerKind.PHONE))
        private val hostThread = Thread.currentThread()
        val auth =
            SupabasePhoneAuth(
                sdk,
                "secret",
                uploads = data.cloudUploads,
                scope = scope,
                ownership = phone,
                threadGuard = { Thread.currentThread() == hostThread },
            )
        val api = auth.dataExports()

        suspend fun start(accessToken: String) {
            sdk.auth.awaitInitialization()
            sdk.auth.importAuthToken(accessToken, autoRefresh = false)
            data.players.ensureSelf("Alex", 0) { PlayerId("self") }
            phone.start()
            auth.refreshProfile()
        }

        fun close() {
            releaseRequest?.complete(Unit)
            auth.close()
            phone.close()
        }
    }

    @Test fun accountRightsDoNotRequireConsentOnboardingOrCloudDeviceBinding(): Unit =
        runBlocking {
            for (consent in ConsentStatus.entries.filter { it != ConsentStatus.CURRENT }) {
                val fixture = Fixture(this)
                fixture.consent = consent
                try {
                    fixture.start(token())
                    val originalDevice = fixture.phone.deviceId
                    val originalScope = fixture.data.accountIdentities.scope
                    val context = fixture.api.context.value!!
                    assertEquals(CloudDataExportStatus.Pending(id, 3), fixture.api.request(context, id))
                    fixture.onboarded = true
                    fixture.deletion = true
                    fixture.auth.refreshProfile()
                    assertEquals(context, fixture.api.context.value)
                    assertEquals(CloudDataExportStatus.Pending(id, 3), fixture.api.status(context, id))
                    fixture.auth.sessions.suspendCloud()
                    fixture.api.requireCurrent(context)
                    assertEquals(CloudDataExportStatus.Pending(id, 3), fixture.api.request(context, id))
                    assertFailsWith<CloudException> { fixture.auth.sessions.capture() }
                    assertNull(fixture.data.accountIdentities.profile)
                    assertEquals(originalDevice, fixture.phone.deviceId)
                    assertEquals(originalScope, fixture.data.accountIdentities.scope)
                    assertTrue(
                        fixture.requests.all {
                            it.url.encodedPath.endsWith("my_auth_profile") ||
                                it.url.encodedPath.startsWith("/functions/")
                        },
                    )
                    val match =
                        fixture.phone.create(
                            Presets.TENNIS_SINGLES_BO3.format(),
                            Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a")),
                        )
                    assertTrue(fixture.phone.recordPoint(Side.A))
                    assertTrue(
                        fixture.data.events
                            .eventsFor(match.value)
                            .isNotEmpty(),
                    )
                } finally {
                    fixture.close()
                }
            }
        }

    @Test fun tokenRefreshKeepsExportContextAndEachAttemptSendsItsCapturedBearer(): Unit =
        runBlocking {
            val fixture = Fixture(this)
            try {
                fixture.start(token())
                val context = fixture.api.context.value!!
                fixture.api.request(context, id)
                fixture.sdk.auth.importAuthToken(token(refresh = "b"), autoRefresh = false)
                fixture.api.status(context, id)
                assertEquals(context, fixture.api.context.value)
                assertEquals(
                    listOf(token(), token(refresh = "b")).map {
                        "Bearer $it"
                    },
                    fixture.requests.filter { it.url.encodedPath.startsWith("/functions/") }.map { it.headers["Authorization"] },
                )
                assertEquals(1, fixture.requests.count { it.url.encodedPath.endsWith("my_auth_profile") })
            } finally {
                fixture.close()
            }
        }

    @Test fun inFlightRefreshDoesNotReplaceTheActuallySentBearer(): Unit =
        runBlocking {
            val fixture = Fixture(this)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                fixture.start(token())
                val context = fixture.api.context.value!!
                fixture.holdRequest = entered
                fixture.releaseRequest = release
                val running = async { fixture.api.request(context, id) }
                withTimeout(5_000) { entered.await() }
                fixture.sdk.auth.importAuthToken(token(refresh = "new"), autoRefresh = false)
                release.complete(Unit)
                assertEquals(CloudDataExportStatus.Pending(id, 3), withTimeout(5_000) { running.await() })
                assertEquals(context, fixture.api.context.value)
                assertEquals("Bearer ${token()}", fixture.requests.last().headers["Authorization"])
            } finally {
                release.complete(Unit)
                fixture.close()
            }
        }

    @Test fun consentMutationDoesNotCancelAnIndependentExportOrChangeItsContext(): Unit =
        runBlocking {
            val fixture = Fixture(this)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                fixture.start(token())
                val context = fixture.api.context.value!!
                fixture.holdRequest = entered
                fixture.releaseRequest = release
                val running = async { fixture.api.request(context, id) }
                withTimeout(5_000) { entered.await() }
                fixture.consent = ConsentStatus.DECLINED
                fixture.auth.decideConsent(ConsentSnapshot("alice", ConsentStatus.REQUIRED, emptyList()), false)
                assertEquals(context, fixture.api.context.value)
                assertFalse(running.isCancelled)
                release.complete(Unit)
                assertEquals(CloudDataExportStatus.Pending(id, 3), withTimeout(5_000) { running.await() })
            } finally {
                release.complete(Unit)
                fixture.close()
            }
        }

    @Test fun synchronousSdkFenceRejectsChangedAccountBeforeCollectorRuns(): Unit =
        runBlocking {
            for ((profile, session) in listOf("bob" to "session-b", "alice" to "session-new")) {
                val fixture = Fixture(this)
                try {
                    fixture.start(token())
                    val old = fixture.api.context.value!!
                    fixture.sdk.auth.importAuthToken(token(profile, session), autoRefresh = false)
                    assertEquals("cloud_session_changed", assertFailsWith<CloudException> { fixture.api.requireCurrent(old) }.reason)
                    assertNull(fixture.api.context.value)
                    assertFailsWith<CloudException> { fixture.api.request(old, id) }
                    fixture.profileId = profile
                    fixture.auth.refreshProfile()
                    val current = fixture.api.context.value!!
                    assertNotEquals(old.generation, current.generation)
                    fixture.api.request(current, id)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test fun explicitLoginOfSameAccountAndJwtSessionStillInvalidatesExportGeneration(): Unit =
        runBlocking {
            val fixture = Fixture(this)
            try {
                fixture.start(token())
                val old = fixture.api.context.value!!
                fixture.auth.verifyEmailCode("alice@example.com", "123456")
                val current = fixture.api.context.value!!
                assertTrue(current.generation > old.generation)
                assertFailsWith<CloudException> { fixture.api.requireCurrent(old) }
                fixture.api.request(current, id)
            } finally {
                fixture.close()
            }
        }

    @Test fun deletionBeginPermanentlyInvalidatesOldContextButAllowsExplicitNewAction(): Unit =
        runBlocking {
            for (stopOnly in listOf(false, true)) {
                val fixture = Fixture(this)
                try {
                    fixture.start(token())
                    val old = fixture.api.context.value!!
                    if (stopOnly) fixture.auth.stopForDeletion() else fixture.auth.requestDeletion()
                    val current = fixture.api.context.value!!
                    assertEquals(old.generation, current.generation)
                    assertTrue(current.revision > old.revision)
                    assertFailsWith<CloudException> { fixture.api.requireCurrent(old) }
                    fixture.api.request(current, id)
                    assertEquals(1, fixture.requests.count { it.url.encodedPath.startsWith("/functions/") })
                } finally {
                    fixture.close()
                }
            }
        }

    @Test fun delayedNonCooperativeResponseCannotPublishAfterInvalidation(): Unit =
        runBlocking {
            val fixture = Fixture(this)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                fixture.start(token())
                val old = fixture.api.context.value!!
                fixture.holdRequest = entered
                fixture.releaseRequest = release
                val running = async { runCatching { fixture.api.request(old, id) } }
                withTimeout(5_000) { entered.await() }
                fixture.auth.stopForDeletion()
                assertFailsWith<CloudException> { fixture.api.requireCurrent(old) }
                val newer = fixture.api.context.value!!
                release.complete(Unit)
                assertTrue(withTimeout(5_000) { running.await() }.isFailure)
                assertEquals(newer, fixture.api.context.value)
                fixture.holdRequest = null
                fixture.releaseRequest = null
                fixture.api.request(newer, id)
            } finally {
                release.complete(Unit)
                fixture.close()
            }
        }

    @Test fun nonCooperativeSinkMustDrainAndCannotPublishAfterDeletionOrAccountChange(): Unit =
        runBlocking {
            for (deletion in listOf(true, false)) {
                val fixture = Fixture(this)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                try {
                    fixture.start(token())
                    fixture.download = true
                    val old = fixture.api.context.value!!
                    var writes = 0
                    val running =
                        async {
                            runCatching {
                                fixture.api.download(
                                    old,
                                    CloudDataExportStatus.Ready(id, 0, 1, 20_000, "a".repeat(64)),
                                    CloudDataExportSink { _, _, _ ->
                                        writes++
                                        entered.complete(Unit)
                                        withContext(NonCancellable) { release.await() }
                                    },
                                )
                            }
                        }
                    withTimeout(5_000) { entered.await() }
                    if (deletion) {
                        fixture.auth.stopForDeletion()
                    } else {
                        fixture.sdk.auth.importAuthToken(token("bob", "session-b"), autoRefresh = false)
                        assertFailsWith<CloudException> { fixture.api.requireCurrent(old) }
                    }
                    assertFalse(running.isCompleted)
                    release.complete(Unit)
                    assertTrue(withTimeout(5_000) { running.await() }.isFailure)
                    assertEquals(1, writes)
                } finally {
                    release.complete(Unit)
                    fixture.close()
                }
            }
        }

    @Test fun logoutAndCloseRevokeContextWithoutClosingProcessMatchSession(): Unit =
        runBlocking {
            for (logout in listOf(true, false)) {
                val fixture = Fixture(this)
                try {
                    fixture.start(token())
                    val old = fixture.api.context.value!!
                    if (logout) fixture.auth.signOut() else fixture.auth.close()
                    assertNull(fixture.api.context.value)
                    assertFailsWith<CloudException> { fixture.api.requireCurrent(old) }
                    fixture.phone.create(
                        Presets.TENNIS_SINGLES_BO3.format(),
                        Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a")),
                    )
                    assertTrue(fixture.phone.recordPoint(Side.B))
                } finally {
                    fixture.close()
                }
            }
        }
}
