package eu.advantly.cloud.sync

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.ServeTracking
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.data.metadataState
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
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsentUploadRunnerIntegrationTest {
    private class Fixture(
        scope: CoroutineScope,
    ) {
        val driver = createInMemoryJvmDriver()
        val data = LocalData(driver)
        val phone = MatchSession(data, null, ManualScheduler(), SessionConfig(MatchSessions.deviceId(data), PeerKind.PHONE))
        var owner = "alice"
        var consent = ConsentStatus.CURRENT
        var loseResponse = true
        var holdUpload = false
        var holdAccount = false
        val uploadSent = CompletableDeferred<Unit>()
        val uploadRelease = CompletableDeferred<Unit>()
        val exportSent = CompletableDeferred<Unit>()
        val profileSent = CompletableDeferred<Unit>()
        val accountRelease = CompletableDeferred<Unit>()
        val batches = mutableListOf<PushBatch>()
        val committed = mutableSetOf<String>()
        val sdk =
            createSupabaseClient("https://cloud.test", "public-key") {
                defaultLogLevel = LogLevel.NONE
                install(Auth) { minimalConfig() }
                httpEngine =
                    MockEngine { request ->
                        val body = CloudJson.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val response =
                            when (val path = request.url.encodedPath.substringAfterLast('/')) {
                                "my_auth_profile" -> {
                                    val profile =
                                        """{"profileId":"$owner","onboarded":true,"deletionRequested":false,"consentStatus":"$consent"}"""
                                    if (holdAccount) {
                                        profileSent.complete(Unit)
                                        accountRelease.await()
                                    }
                                    profile
                                }

                                "export_my_data" -> {
                                    exportSent.complete(Unit)
                                    if (holdAccount) accountRelease.await()
                                    """{"exported":true}"""
                                }

                                "bind_cloud_session_v1" -> {
                                    val device = body.getValue("p_device_id").jsonPrimitive.content
                                    """{"contractVersion":1,"profileId":"$owner","sessionId":"session-$owner",
                                        "installationId":"installation","deviceId":"$device"}"""
                                }

                                "cloud_sync_push_v1" -> {
                                    val batch = CloudJson.decodeFromJsonElement(PushBatch.serializer(), body.getValue("p_batch"))
                                    batches += batch
                                    val response =
                                        PushResponse(
                                            devices = batch.devices.map { ItemResult(it.id, "ok") },
                                            players = batch.players.map { ItemResult(it.id, "ok") },
                                            matches =
                                                batch.matches.map {
                                                    ItemResult(it.id, "updated", projection = it.projection?.let { "applied" })
                                                },
                                            events =
                                                batch.events.map {
                                                    ItemResult(it.id, if (committed.add(it.id)) "accepted" else "duplicate")
                                                },
                                        )
                                    if (holdUpload) {
                                        uploadSent.complete(Unit)
                                        uploadRelease.await()
                                    }
                                    if (loseResponse) throw IOException("lost response")
                                    CloudJson.encodeToString(PushResponse.serializer(), response)
                                }

                                else -> {
                                    error(path)
                                }
                            }
                        respond(response, headers = headersOf("Content-Type", "application/json"))
                    }
            }
        val auth = SupabasePhoneAuth(sdk, "secret", uploads = data.cloudUploads, scope = scope, ownership = phone)
        val backend = auth.cloudBackend(CloudInstallation("installation", "a".repeat(64)))
        val runner = CloudUploadRunner(backend, { 100L }, maxBatchesPerRun = 1)

        suspend fun signIn() {
            val token =
                "h." +
                    Base64.UrlSafe
                        .withPadding(Base64.PaddingOption.ABSENT)
                        .encode("""{"sub":"$owner","session_id":"session-$owner"}""".encodeToByteArray()) + ".s"
            sdk.auth.importAuthToken(token, autoRefresh = false)
            auth.refreshProfile()
        }

        fun close() {
            uploadRelease.complete(Unit)
            accountRelease.complete(Unit)
            runner.close()
            auth.close()
            phone.close()
            driver.close()
        }
    }

    @Test
    fun consentLossFencesRunnerRetryAndAckWhileIndependentAccountRequestsAndRetainedIdentitySurvive(): Unit =
        runBlocking {
            for (duringRequest in listOf(false, true)) {
                val host = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
                val fixture = Fixture(host)
                try {
                    withTimeout(15_000) {
                        fixture.data.players.ensureSelf("Alex", 0) { PlayerId("self") }
                        fixture.phone.start()
                        fixture.sdk.auth.awaitInitialization()
                        fixture.signIn()
                        val device = fixture.phone.deviceId
                        val scope = fixture.data.accountIdentities.scope
                        val match =
                            fixture.phone.create(
                                Presets.TENNIS_SINGLES_BO3.format(),
                                Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a")),
                                // zählt ausstehende Events: ohne automatische Aufschlag-Info
                                MatchMeta(serveTracking = ServeTracking.OFF),
                            )
                        assertTrue(fixture.phone.recordPoint(Side.A))
                        val historical = fixture.data.events.eventsFor(match.value)
                        fixture.backend.bindDevice(device.value)
                        val old = fixture.backend.captureUpload(500)
                        val metadata = old.snapshot.metadataRevisions.map { fixture.data.cloud.metadataState(it.kind, it.id) }
                        fixture.holdUpload = duringRequest
                        fixture.loseResponse = !duringRequest
                        val upload = async { runCatching { fixture.runner.uploadNow() } }
                        if (duringRequest) {
                            fixture.uploadSent.await()
                        } else {
                            assertEquals(
                                CloudUploadFailureKind.TRANSIENT,
                                upload
                                    .await()
                                    .getOrThrow()
                                    .failure
                                    ?.kind,
                            )
                        }

                        fixture.holdAccount = true
                        val export = async { fixture.auth.exportData() }
                        fixture.exportSent.await()
                        fixture.auth.sessions.suspendCloud()
                        fixture.consent = ConsentStatus.REQUIRED
                        val profile = async { fixture.auth.refreshProfile() }
                        fixture.profileSent.await()
                        fixture.uploadRelease.complete(Unit)
                        if (duringRequest) assertTrue(upload.await().exceptionOrNull() is CancellationException)
                        fixture.accountRelease.complete(Unit)
                        assertEquals("""{"exported":true}""", export.await())
                        profile.await()
                        assertEquals(
                            ConsentStatus.REQUIRED,
                            fixture.auth.accountSession.value
                                ?.consentStatus,
                        )
                        assertEquals("alice", fixture.data.accountIdentities.profile)
                        assertEquals(scope, fixture.data.accountIdentities.scope)
                        assertEquals(device, fixture.phone.deviceId)
                        assertEquals(historical, fixture.data.events.eventsFor(match.value))
                        assertEquals(historical.size.toLong(), fixture.data.cloud.pendingCount())
                        assertEquals(metadata, old.snapshot.metadataRevisions.map { fixture.data.cloud.metadataState(it.kind, it.id) })
                        assertNull(fixture.runner.state.value.lastSuccessAtMs)

                        // Vor dem Lauf verweigerte Zustimmung darf keinen weiteren HTTP-Push auslösen.
                        val blocked = CloudUploadRunner(fixture.backend, { 100L })
                        assertEquals(CloudUploadFailureKind.PERMISSION, blocked.uploadNow().failure?.kind)
                        blocked.close()
                        assertEquals(1, fixture.batches.size)
                        fixture.holdAccount = false
                        fixture.consent = ConsentStatus.CURRENT
                        fixture.auth.refreshProfile()
                        assertFailsWith<CloudException> { fixture.backend.requireCurrentUpload(old) }
                        assertFailsWith<CloudException> {
                            fixture.auth.sessions.acknowledge(old.lease, old.snapshot, historical.map { it.id }.toSet(), emptyMap())
                        }
                        assertEquals(historical.size.toLong(), fixture.data.cloud.pendingCount())
                        if (!duringRequest) {
                            val staleRetry = fixture.runner.uploadNow()
                            assertEquals(CloudUploadFailureKind.PERMISSION, staleRetry.failure?.kind)
                            assertNull(staleRetry.profileId)
                            assertNull(staleRetry.lastSuccessAtMs)
                            assertEquals(1, fixture.batches.size)
                        }
                        fixture.loseResponse = false
                        fixture.holdUpload = false
                        val success = fixture.runner.uploadNow()
                        assertTrue(success.fullyUploaded)
                        assertEquals(100L, success.lastSuccessAtMs)
                        assertEquals(0, fixture.data.cloud.pendingCount())
                        assertEquals(historical.size, fixture.committed.size)

                        // Ein neuer Retry von A darf nach dem echten SDK-Wechsel weder A noch B quittieren.
                        assertTrue(fixture.phone.recordPoint(Side.B))
                        fixture.loseResponse = true
                        assertEquals(
                            CloudUploadFailureKind.TRANSIENT,
                            fixture.runner
                                .uploadNow()
                                .failure
                                ?.kind,
                        )
                        val priorPushes = fixture.batches.size
                        val aliceHistory = fixture.data.events.eventsFor(match.value)
                        fixture.auth.sessions.suspendCloud()
                        fixture.owner = "bob"
                        fixture.signIn()
                        assertEquals("bob", fixture.data.accountIdentities.profile)
                        assertNotEquals(device, fixture.phone.deviceId)
                        assertNotEquals(scope, fixture.data.accountIdentities.scope)
                        fixture.backend.bindDevice(fixture.phone.deviceId.value)
                        val foreignRetry = fixture.runner.uploadNow()
                        assertEquals(CloudUploadFailureKind.PERMISSION, foreignRetry.failure?.kind)
                        assertNull(foreignRetry.profileId)
                        assertNull(foreignRetry.lastSuccessAtMs)
                        assertFalse(foreignRetry.fullyUploaded)
                        assertEquals(priorPushes, fixture.batches.size)
                        assertEquals(1, fixture.data.cloud.pendingCount())
                        assertEquals(aliceHistory, fixture.data.events.eventsFor(match.value))
                        assertEquals(
                            0L,
                            fixture.backend
                                .readUploadStatus()
                                .inventory.pendingEvents,
                        )
                        assertEquals(scope, fixture.data.accountIdentities.sourceScope(device.value))
                    }
                } finally {
                    fixture.close()
                    host.cancel()
                }
            }
        }
}
