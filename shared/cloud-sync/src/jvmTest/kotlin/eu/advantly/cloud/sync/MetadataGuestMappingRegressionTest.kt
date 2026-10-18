package eu.advantly.cloud.sync

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.CloudMetadataKind
import eu.advantly.data.CloudMetadataOrigin
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.data.metadataState
import eu.advantly.data.toStoredEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataGuestMappingRegressionTest {
    private fun prepare(data: LocalData) {
        val me = PlayerId("me")
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val lineup = Lineup(listOf(me), listOf(PlayerId("guest")), me)
        data.players.ensureSelf("Alex", 0) { me }
        data.accountIdentities.initialize("phone", me.value, "PHONE")
        val previous = data.cloudUploads.activate("alice")
        data.cloudUploads.bindOwnedDevices(previous, setOf("phone"))
        data.cloudUploads.deactivate()
        val genesis =
            ScoreEvent(EventId("genesis"), MatchId("match"), DeviceId("phone"), 1, 1, 1, null, MatchCreated(format, lineup, createdBy = me))
        data.events.append(genesis.toStoredEvent())
        data.matches.insertIfAbsent(MatchId("match"), format, lineup, MatchMeta(), 1)
    }

    @Test
    fun httpAckForServerKnownPlayerDoesNotConfirmNameInsertedAfterCapture(): Unit =
        runBlocking {
            val driver = createInMemoryJvmDriver()
            val data = LocalData(driver)
            prepare(data)
            val gate = CloudSessionGate({ CloudCredentials("alice", "session", "token") }, data.cloudUploads)
            gate.publish(gate.capture(false), CloudSession("alice", true))
            val sent = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val http =
                CloudHttp(
                    "https://cloud.test",
                    "public",
                    HttpClient(
                        MockEngine { request ->
                            val response =
                                if (request.url.encodedPath.endsWith("bind_cloud_session_v1")) {
                                    """{"contractVersion":1,"profileId":"alice","sessionId":"session",
                "installationId":"installation","deviceId":"phone"}"""
                                } else {
                                    sent.complete(Unit)
                                    release.await()
                                    // Der Server kennt den Spieler bereits und bestätigt die Teilnehmer ohne Ablehnungshinweis.
                                    """{"players":[{"id":"me","result":"ok"}],"matches":[{"id":"match","result":"created","projection":"applied"}],"events":[{"id":"genesis","result":"accepted"}]}"""
                                }
                            respond(response, headers = headersOf("Content-Type", "application/json"))
                        },
                    ) { followRedirects = false },
                )
            try {
                val backend = SupabaseCloudBackend(gate, http, CloudInstallation("installation", "a".repeat(64)))
                backend.bindDevice("phone")
                val selected = backend.captureUpload()
                val oldRevision =
                    selected.snapshot.participants
                        .single()
                        .version.revision
                assertEquals(
                    "",
                    selected.batch.matches
                        .single()
                        .participants!!
                        .last()
                        .displayName,
                )
                val request = async { backend.pushSelected(selected, selected.batch) }
                withTimeout(5_000) { sent.await() }
                data.players.upsertRemote(Player(PlayerId("guest"), "Beatrice"), 2)
                release.complete(Unit)
                request.await()
                val state = data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")!!
                assertTrue(state.selected.revision > oldRevision, "Successful HTTP ack must not swallow the inserted name")
                assertEquals(oldRevision, state.uploadedRevision)
                assertEquals(
                    "Beatrice",
                    backend
                        .captureUpload()
                        .batch.matches
                        .single()
                        .participants!!
                        .last()
                        .displayName,
                )
            } finally {
                gate.stop()
                http.close()
                driver.close()
            }
        }

    @Test
    fun unknownGuestIsSentAsNamedSlotAndSuccessfulResponseClearsParticipants() {
        val driver = createInMemoryJvmDriver()
        try {
            val data = LocalData(driver)
            prepare(data)
            data.players.upsertRemote(Player(PlayerId("guest"), "Beatrice"), 2)
            val session = data.cloudUploads.activate("alice")
            val selected = data.cloudUploads.capture(session, 100)
            val batch = selected.toPushBatch()
            val guest =
                batch.matches
                    .single()
                    .participants!!
                    .last()
            assertEquals("guest", guest.slotId)
            assertEquals("Beatrice", guest.displayName)
            assertNull(guest.playerId)
            assertFalse(batch.players.any { it.id == "guest" })
            assertEquals(CloudMetadataOrigin.UNKNOWN, data.cloud.metadataState(CloudMetadataKind.PLAYER, "guest")?.origin)
            val response = PushResponse(matches = listOf(ItemResult("match", "updated")))
            val acknowledged = selected.acknowledgedMetadata(batch, response)
            assertTrue(acknowledged.any { it.kind == CloudMetadataKind.PARTICIPANTS })
            data.cloudUploads.acknowledge(selected, emptySet(), metadata = acknowledged)
            assertTrue(
                data.cloudUploads
                    .capture(session, 100)
                    .participants
                    .isEmpty(),
            )
        } finally {
            driver.close()
        }
    }
}
