package eu.advantly.cloud.sync

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.CloudMetadataKind
import eu.advantly.data.LocalData
import eu.advantly.data.ScoreSummary
import eu.advantly.data.StoredEvent
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.data.metadataState
import eu.advantly.data.toStoredEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetadataUploadBoundaryTest {
    private fun prepare(data: LocalData) {
        val me = PlayerId("me")
        val guest = PlayerId("guest")
        data.players.ensureSelf("Alex", 0) { me }
        data.accountIdentities.initialize("phone", "me", "PHONE")
        data.players.addGuest(guest, "Bea", 0)
        val old = data.cloudUploads.activate("alice")
        data.cloudUploads.bindOwnedDevices(old, setOf("phone"))
        data.cloudUploads.deactivate()
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val lineup = Lineup(listOf(me), listOf(guest), me)
        val created = MatchCreated(format, lineup, createdBy = me)
        data.events.append(ScoreEvent(EventId("genesis"), MatchId("match"), DeviceId("phone"), 1, 1, 1, null, created).toStoredEvent())
        data.matches.insertIfAbsent(MatchId("match"), format, lineup, MatchMeta(), 1)
        data.matches.updateProgress(MatchId("match"), MatchStatus.IN_PROGRESS, ScoreSummary(eventCount = 1), 1)
    }

    @Test
    fun httpUsesFrozenMetadataAndDelayedSuccessLeavesConcurrentChangesPending(): Unit =
        runBlocking {
            val driver = createInMemoryJvmDriver()
            val data = LocalData(driver)
            prepare(data)
            val gate = CloudSessionGate({ CloudCredentials("alice", "session", "token") }, data.cloudUploads)
            gate.publish(gate.capture(false), CloudSession("alice", true))
            val sent = CompletableDeferred<PushBatch>()
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
                                    val body =
                                        CloudJson
                                            .parseToJsonElement(
                                                (request.body as TextContent).text,
                                            ).jsonObject
                                            .getValue("p_batch")
                                    sent.complete(CloudJson.decodeFromJsonElement(PushBatch.serializer(), body))
                                    release.await()
                                    """{"players":[{"id":"me","result":"ok"},{"id":"guest","result":"ok"}],"matches":[{"id":"match","result":"created","projection":"applied"}],"events":[{"id":"genesis","result":"accepted"}]}"""
                                }
                            respond(response, headers = headersOf("Content-Type", "application/json"))
                        },
                    ) { followRedirects = false },
                )
            try {
                val backend = SupabaseCloudBackend(gate, http, CloudInstallation("installation", "a".repeat(64)))
                backend.bindDevice("phone")
                val attempt = backend.captureUpload()
                val mutableMatches = attempt.batch.matches.toMutableList()
                val request = async { backend.pushSelected(attempt, attempt.batch.copy(matches = mutableMatches)) }
                val wire = withTimeout(5_000) { sent.await() }
                assertEquals("Bea", wire.players.single { it.id == "guest" }.displayName)
                mutableMatches.clear()
                data.players.rename(PlayerId("guest"), "Beatrice")
                data.matches.updateMeta(MatchId("match"), MatchMeta(venue = "Court 2"))
                data.matches.updateProgress(MatchId("match"), MatchStatus.PAUSED, ScoreSummary(eventCount = 1), 2)
                release.complete(Unit)
                request.await()
                val next = backend.captureUpload().batch
                assertTrue(next.events.isEmpty())
                assertEquals("Beatrice", next.players.single().displayName)
                assertEquals(
                    "Beatrice",
                    next.matches
                        .single()
                        .participants
                        ?.last()
                        ?.displayName,
                )
                assertEquals(
                    "paused",
                    next.matches
                        .single()
                        .projection
                        ?.status,
                )
                assertEquals(
                    "Bea",
                    attempt.batch.players
                        .single { it.id == "guest" }
                        .displayName,
                )
                attempt.snapshot.metadataRevisions.forEach { version ->
                    assertEquals(version.revision, data.cloud.metadataState(version.kind, version.id)?.uploadedRevision)
                }
                assertEquals(1, data.events.unconfirmedCount())
            } finally {
                gate.stop()
                http.close()
                driver.close()
            }
        }

    @Test
    fun partialMetadataResponseKeepsParticipantsWithNotesAndUnappliedProjectionPending() {
        val driver = createInMemoryJvmDriver()
        try {
            val data = LocalData(driver)
            prepare(data)
            val session = data.cloudUploads.activate("alice")
            val snapshot = data.cloudUploads.capture(session, 100)
            val batch = snapshot.toPushBatch()
            val response =
                PushResponse(
                    matches =
                        listOf(
                            ItemResult(
                                "match",
                                "updated",
                                projection = "ahead_of_events",
                                notes = listOf(ParticipantNote("guest", "player_not_owned")),
                            ),
                        ),
                )
            val revisions = snapshot.acknowledgedMetadata(batch, response)
            assertEquals(setOf(CloudMetadataKind.HEADER), revisions.map { it.kind }.toSet())
            val projectionOnly =
                snapshot.acknowledgedMetadata(
                    batch,
                    PushResponse(matches = listOf(ItemResult("match", "not_owner", projection = "applied"))),
                )
            assertEquals(setOf(CloudMetadataKind.PROJECTION), projectionOnly.map { it.kind }.toSet())
            data.cloudUploads.acknowledge(snapshot, emptySet(), metadata = revisions)
            val pending = data.cloudUploads.capture(session, 100)
            assertTrue(pending.headers.isEmpty())
            assertTrue(pending.participants.isNotEmpty() && pending.projections.isNotEmpty() && pending.players.isNotEmpty())
            assertFailsWith<IllegalArgumentException> {
                snapshot.acknowledgedMetadata(batch, PushResponse(matches = listOf(ItemResult("foreign", "ok"))))
            }
            assertFailsWith<IllegalArgumentException> {
                snapshot.acknowledgedMetadata(batch, PushResponse(players = listOf(ItemResult("me", "ok"), ItemResult("me", "ok"))))
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun limitedSelectionDoesNotAdvertiseProjectionAsFinalAndRejectedEventsKeepItBlocked() {
        val driver = createInMemoryJvmDriver()
        try {
            val data = LocalData(driver)
            prepare(data)
            (2L..502L).forEach { seq ->
                data.events.append(
                    StoredEvent("e$seq", "match", "phone", seq, seq, """{"type":"PointWon","side":"A"}""", type = "PointWon"),
                )
            }
            data.matches.updateProgress(MatchId("match"), MatchStatus.FINISHED, ScoreSummary(eventCount = 502), 502)
            val session = data.cloudUploads.activate("alice")
            val first = data.cloudUploads.capture(session, 500)
            assertEquals(500, first.events.size)
            assertFalse("match" in first.completeEventMatches)
            assertEquals(
                null,
                first
                    .toPushBatch()
                    .matches
                    .single()
                    .projection,
            )
            data.cloudUploads.acknowledge(first, first.events.mapTo(mutableSetOf()) { it.id })
            val last = data.cloudUploads.capture(session, 500)
            assertEquals(2, last.events.size)
            assertTrue("match" in last.completeEventMatches)
            assertEquals(
                502,
                last
                    .toPushBatch()
                    .matches
                    .single()
                    .projection
                    ?.eventCount,
            )
            data.cloudUploads.acknowledge(last, setOf("e501"), mapOf("e502" to "forbidden"))
            assertEquals(
                null,
                data.cloudUploads
                    .capture(session, 500)
                    .toPushBatch()
                    .matches
                    .single()
                    .projection,
            )
        } finally {
            driver.close()
        }
    }
}
