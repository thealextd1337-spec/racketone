package eu.advantly.cloud.sync

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.CloudMetadataKind
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.data.metadataState
import eu.advantly.data.toStoredEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P2-E: Freund als Mitspieler geht mit `profileId` hoch; endgültige Server-Hinweise quittieren und bleiben sichtbar. */
class FriendParticipantUploadTest {
    private fun upload(
        notes: String,
        check: (LocalData, SupabaseCloudBackend) -> Unit,
    ) = runBlocking {
        val driver = createInMemoryJvmDriver()
        val data = LocalData(driver)
        val me = PlayerId("me")
        // Lokale Spieler-ID wie bei Gästen; die Profil-ID steht nur in der Zuordnung des Kontos.
        val friend = PlayerId("bob-player")
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val lineup = Lineup(listOf(me), listOf(friend), me)
        data.players.ensureSelf("Alex", 0) { me }
        data.players.rememberFriend("alice", "bob-profile", "Bob", 0) { friend }
        data.accountIdentities.initialize("phone", me.value, "PHONE")
        data.cloudUploads.bindOwnedDevices(data.cloudUploads.activate("alice"), setOf("phone"))
        data.cloudUploads.deactivate()
        val genesis =
            ScoreEvent(EventId("genesis"), MatchId("match"), DeviceId("phone"), 1, 1, 1, null, MatchCreated(format, lineup, createdBy = me))
        data.events.append(genesis.toStoredEvent())
        data.matches.insertIfAbsent(MatchId("match"), format, lineup, MatchMeta(), 1)
        val gate = CloudSessionGate({ CloudCredentials("alice", "session", "token") }, data.cloudUploads)
        gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
        val http =
            CloudHttp(
                "https://cloud.test",
                "public",
                HttpClient(
                    MockEngine { request ->
                        val response =
                            if (request.url.encodedPath.endsWith("bind_cloud_session_v1")) {
                                """{"contractVersion":1,"profileId":"alice","sessionId":"session","installationId":"installation","deviceId":"phone"}"""
                            } else {
                                """{"players":[{"id":"me","result":"ok"}],"matches":[{"id":"match","result":"created","projection":"applied",
                                "notes":$notes}],"events":[{"id":"genesis","result":"accepted"}]}"""
                            }
                        respond(response, headers = headersOf("Content-Type", "application/json"))
                    },
                ) { followRedirects = false },
            )
        try {
            val backend = SupabaseCloudBackend(gate, http, CloudInstallation("installation", "a".repeat(64)))
            backend.bindDevice("phone")
            check(data, backend)
        } finally {
            gate.stop()
            http.close()
            driver.close()
        }
    }

    @Test
    fun friendSlotCarriesProfileIdButIsNeverUploadedAsCloudPlayer() =
        upload("[]") { data, backend ->
            val selected = backend.captureUpload()
            val participants =
                selected.batch.matches
                    .single()
                    .participants!!
            assertEquals(ParticipantDto("me", "A", 1, "Alex", "me", null), participants.first())
            assertEquals(ParticipantDto("bob-player", "B", 1, "Bob", null, "bob-profile"), participants.last())
            assertEquals(listOf("me"), selected.batch.players.map { it.id })
            // Die Profil-ID steht ausschließlich im Feld profileId: weder Slot, Lineup noch Event-Payload tragen sie.
            val withoutProfileField =
                selected.batch.copy(
                    matches =
                        selected.batch.matches.map { m ->
                            m.copy(participants = m.participants?.map { it.copy(profileId = null) })
                        },
                )
            assertFalse(CloudJson.encodeToString(PushBatch.serializer(), withoutProfileField).contains("bob-profile"))
            runBlocking { backend.pushSelected(selected, selected.batch) }
            val state = data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")!!
            assertEquals(state.selected.revision, state.uploadedRevision)
            assertTrue(data.cloudUploads.linkNotes("alice", "match").isEmpty())
        }

    @Test
    fun finalLinkNoteAcknowledgesParticipantsAndIsStoredForTheMatchDetail() =
        upload("""[{"slotId":"bob-player","note":"not_friend"}]""") { data, backend ->
            val selected = backend.captureUpload()
            runBlocking { backend.pushSelected(selected, selected.batch) }
            val state = data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")!!
            assertEquals(state.selected.revision, state.uploadedRevision)
            assertEquals(mapOf("bob-player" to "not_friend"), data.cloudUploads.linkNotes("alice", "match"))
            assertTrue(data.cloudUploads.linkNotes("bob", "match").isEmpty())
            assertNull(
                backend
                    .captureUpload()
                    .batch.matches
                    .firstOrNull { it.participants != null },
            )
        }

    @Test
    fun retryableNoteKeepsParticipantsPendingWithoutStoringNotes() =
        upload("""[{"slotId":"bob-player","note":"player_not_owned"}]""") { data, backend ->
            val selected = backend.captureUpload()
            runBlocking { backend.pushSelected(selected, selected.batch) }
            assertEquals(0, data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")!!.uploadedRevision)
            assertTrue(data.cloudUploads.linkNotes("alice", "match").isEmpty())
        }
}
