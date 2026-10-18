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
import eu.advantly.data.CloudUploadInventory
import eu.advantly.data.LocalData
import eu.advantly.data.ScoreSummary
import eu.advantly.data.StoredEvent
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.data.metadataState
import eu.advantly.data.toStoredEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class CloudUploadRunnerTest {
    private class Fixture(
        val eventCount: Int = 1,
        val director: Boolean = false,
        val answer: suspend (PushBatch, Int) -> PushResponse = { batch, _ -> accepted(batch) },
    ) : AutoCloseable {
        val driver = InventoryRecordingDriver(createInMemoryJvmDriver())
        val data = LocalData(driver)
        val hostThread = Thread.currentThread()
        var credentials: CloudCredentials? = CloudCredentials("alice", "session", "token")
        val gate = CloudSessionGate({ credentials }, data.cloudUploads, { Thread.currentThread() === hostThread })
        val batches = mutableListOf<PushBatch>()
        val bindings = mutableListOf<String>()
        val registeredDevices = mutableSetOf<String>()
        var clock = 100L
        var failureStatus: HttpStatusCode? = null
        var failureBody = ""
        val http =
            CloudHttp(
                "https://cloud.test",
                "public",
                HttpClient(
                    MockEngine { request ->
                        assertEquals("Bearer token", request.headers["Authorization"])
                        assertEquals("installation", request.headers["X-Advantly-Installation-Id"])
                        assertEquals("a".repeat(64), request.headers["X-Advantly-Installation-Proof"])
                        val body = CloudJson.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val response =
                            when (request.url.encodedPath) {
                                "/rest/v1/rpc/bind_cloud_session_v1" -> {
                                    bindings += body.getValue("p_device_id").toString()
                                    registeredDevices += "caller-phone"
                                    """{"contractVersion":1,"profileId":"alice","sessionId":"session",
                                        "installationId":"installation","deviceId":"caller-phone"}"""
                                }

                                "/rest/v1/rpc/cloud_sync_push_v1" -> {
                                    val batch = CloudJson.decodeFromJsonElement(PushBatch.serializer(), body.getValue("p_batch"))
                                    batches += batch
                                    failureStatus?.let {
                                        return@MockEngine respond(failureBody, it, headersOf("Content-Type", "application/json"))
                                    }
                                    registeredDevices += batch.devices.map { it.id }
                                    val response = answer(batch, batches.size)
                                    val checked =
                                        response.copy(
                                            events =
                                                response.events.map { result ->
                                                    val event = batch.events.singleOrNull { it.id == result.id }
                                                    if (event != null && event.deviceId !in registeredDevices) {
                                                        ItemResult(event.id, "rejected", "device_not_owned")
                                                    } else {
                                                        result
                                                    }
                                                },
                                        )
                                    CloudJson.encodeToString(PushResponse.serializer(), checked)
                                }

                                else -> {
                                    error("Unexpected RPC")
                                }
                            }
                        respond(response, headers = headersOf("Content-Type", "application/json"))
                    },
                ) { followRedirects = false },
            )
        val backend = SupabaseCloudBackend(gate, http, CloudInstallation("installation", "a".repeat(64)))

        init {
            data.players.ensureSelf("Alex", 0) { PlayerId("me") }
            data.accountIdentities.initialize("phone-source", "me", "PHONE")
            data.players.addGuest(PlayerId("guest"), "Bea", 0)
            val session = data.cloudUploads.activate("alice")
            data.cloudUploads.bindOwnedDevices(session, setOf("phone-source"))
            data.cloudUploads.deactivate()
            val format = Presets.TENNIS_SINGLES_BO3.format()
            if (director) data.players.addGuest(PlayerId("guest2"), "Chris", 0)
            val lineup = Lineup(listOf(PlayerId(if (director) "guest2" else "me")), listOf(PlayerId("guest")), PlayerId("guest"))
            val created = MatchCreated(format, lineup, createdBy = PlayerId("me"))
            data.events.append(
                ScoreEvent(EventId("genesis"), MatchId("match"), DeviceId("phone-source"), 1, 1, 1, null, created).toStoredEvent(),
            )
            data.matches.insertIfAbsent(MatchId("match"), format, lineup, MatchMeta(), 1)
            for (seq in 2..eventCount) append(seq.toLong())
            progress(eventCount)
            publish()
        }

        fun append(
            seq: Long,
            id: String = "e$seq",
            device: String = "phone-source",
        ) {
            data.events.append(
                StoredEvent(id, "match", device, seq, seq, """{"type":"PointWon","side":"A"}""", type = "PointWon"),
            )
        }

        fun progress(count: Int) {
            data.matches.updateProgress(MatchId("match"), MatchStatus.IN_PROGRESS, ScoreSummary(eventCount = count), count.toLong())
        }

        fun publish() {
            gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
        }

        fun runner(
            maxBatches: Int = 4,
            maxEvents: Int = 500,
        ) = CloudUploadRunner(
            backend,
            { clock },
            maxBatches,
            maxEvents,
            { Thread.currentThread() === hostThread },
            sourceDevices =
                listOf(
                    DeviceDto("phone-source", "phone", "android"),
                    DeviceDto("own-watch", "watch", "wearos"),
                    DeviceDto("foreign-watch", "watch", "wearos"),
                ),
        )

        override fun close() {
            gate.stop()
            http.close()
            driver.close()
        }
    }

    @Test
    fun lostResponseRetriesIdenticalSelectionAndLeavesConcurrentRevisionsPending(): Unit =
        runBlocking {
            val committedEvents = mutableSetOf<String>()
            Fixture(answer = { batch, call ->
                val response =
                    accepted(batch).copy(
                        events = batch.events.map { ItemResult(it.id, if (committedEvents.add(it.id)) "accepted" else "duplicate") },
                    )
                if (call == 1) throw IOException("response lost")
                response
            }).use { fixture ->
                val runner = fixture.runner(maxBatches = 1)
                fixture.backend.bindDevice("caller-phone")
                val failed = runner.uploadNow()
                assertEquals(CloudUploadFailureKind.TRANSIENT, failed.failure?.kind)
                assertNull(failed.lastSuccessAtMs)
                assertEquals(1, fixture.data.cloud.pendingCount())
                fixture.append(2)
                fixture.progress(2)
                fixture.data.players.rename(PlayerId("guest"), "Beatrice")
                fixture.clock = 200
                val retried = runner.uploadNow()
                assertEquals(fixture.batches[0], fixture.batches[1])
                assertEquals(200L, retried.lastSuccessAtMs)
                assertEquals(CloudUploadOutcome.LIMIT_REACHED, retried.outcome)
                assertEquals(1L, retried.inventory?.pendingEvents)
                assertTrue(requireNotNull(retried.inventory).pendingMetadata > 0)
                assertFalse(retried.fullyUploaded)
                val final = runner.uploadNow()
                assertTrue(final.fullyUploaded)
                assertEquals(setOf("genesis", "e2"), committedEvents)
                assertEquals(
                    listOf("e2"),
                    fixture.batches
                        .last()
                        .events
                        .map { it.id },
                )
                assertEquals(
                    "Beatrice",
                    fixture.batches
                        .last()
                        .players
                        .single()
                        .displayName,
                )
                assertEquals(2, fixture.data.events.unconfirmedCount())
                assertEquals(listOf("\"caller-phone\""), fixture.bindings)
                assertEquals(setOf("caller-phone", "phone-source"), fixture.registeredDevices)
            }
        }

    @Test
    fun moreThanFiveHundredEventsUseActualRemainderIncludingConcurrentAppend(): Unit =
        runBlocking {
            val sent = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            Fixture(eventCount = 502, answer = { batch, call ->
                if (call == 1) {
                    sent.complete(Unit)
                    release.await()
                }
                accepted(batch)
            }).use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val runner = fixture.runner()
                val run = async { runner.uploadNow() }
                withTimeout(5_000) { sent.await() }
                fixture.append(503)
                fixture.progress(503)
                release.complete(Unit)
                val result = run.await()
                assertTrue(result.fullyUploaded)
                assertEquals(listOf(500, 3), fixture.batches.map { it.events.size })
                assertNull(
                    fixture.batches
                        .first()
                        .matches
                        .single()
                        .projection,
                )
                assertEquals(
                    503,
                    fixture.batches
                        .last()
                        .matches
                        .single()
                        .projection
                        ?.eventCount,
                )
                assertEquals(2, result.completedBatches)
            }
        }

    @Test
    fun partialResponsePreservesMissingAndRetryableIdsWithoutAnImmediateLoop(): Unit =
        runBlocking {
            Fixture(eventCount = 4, answer = { batch, call ->
                if (call == 1) {
                    accepted(batch).copy(
                        events =
                            listOf(
                                ItemResult("genesis", "accepted"),
                                ItemResult("e2", "rejected", "id_conflict"),
                                ItemResult("e3", "rejected", "unknown_match"),
                            ),
                        matches = batch.matches.map { ItemResult(it.id, "created", projection = "ahead_of_events") },
                    )
                } else {
                    accepted(batch)
                }
            }).use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val runner = fixture.runner()
                val partial = runner.uploadNow()
                assertEquals(CloudUploadOutcome.PARTIAL, partial.outcome)
                assertEquals(1, fixture.batches.size)
                assertEquals(setOf("e4"), partial.missingEventResponseIds)
                assertEquals(CloudUploadInventory(2, 1, 1), partial.inventory)
                assertEquals(mapOf("e2" to "id_conflict"), fixture.data.cloud.rejectedEvents())
                val remaining = runner.uploadNow()
                assertEquals(
                    setOf("e3", "e4"),
                    fixture.batches
                        .last()
                        .events
                        .map { it.id }
                        .toSet(),
                )
                assertEquals(CloudUploadInventory(0, 1, 1), remaining.inventory)
                assertFalse(remaining.fullyUploaded)
                val before = fixture.batches.size
                assertFalse(runner.uploadNow().fullyUploaded)
                assertEquals(before, fixture.batches.size)
            }
        }

    @Test
    fun metadataOnlyChangesAndPlaceholderExclusionKeepRevisionAcksHonest(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val runner = fixture.runner()
                assertTrue(runner.uploadNow().fullyUploaded)
                fixture.data.players.markPlaceholder(PlayerId("placeholder"), 0)
                fixture.data.players.rename(PlayerId("guest"), "Beatrice")
                fixture.data.matches.updateMeta(MatchId("match"), MatchMeta(venue = "Court 2"))
                fixture.data.matches.updateProgress(MatchId("match"), MatchStatus.PAUSED, ScoreSummary(eventCount = 1), 2)
                assertTrue(requireNotNull(runner.refresh().inventory).pendingMetadata >= 4)
                assertTrue(runner.uploadNow().fullyUploaded)
                val batch = fixture.batches.last()
                assertTrue(batch.events.isEmpty())
                assertEquals(listOf("guest"), batch.players.map { it.id })
                assertEquals(
                    "Beatrice",
                    batch.matches
                        .single()
                        .participants
                        ?.last()
                        ?.displayName,
                )
                assertEquals(
                    "paused",
                    batch.matches
                        .single()
                        .projection
                        ?.status,
                )
                assertEquals(
                    0,
                    fixture.data.cloud
                        .metadataState(CloudMetadataKind.PLAYER, "placeholder")
                        ?.uploadedRevision,
                )
            }
        }

    @Test
    fun metadataNotesAndMissingResponsesStopTheRunAndRemainVisible(): Unit =
        runBlocking {
            Fixture(answer = { batch, _ ->
                accepted(batch).copy(
                    players = emptyList(),
                    matches =
                        batch.matches.map {
                            ItemResult(
                                it.id,
                                "updated",
                                projection = "ahead_of_events",
                                notes = listOf(ParticipantNote("guest", "not_friend")),
                            )
                        },
                )
            }).use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val state = fixture.runner().uploadNow()
                assertEquals(1, fixture.batches.size)
                assertEquals(CloudUploadOutcome.PARTIAL, state.outcome)
                assertEquals(CloudUploadInventory(0, 0, 4), state.inventory)
                assertFalse(state.fullyUploaded)
            }
        }

    @Test
    fun directorBackupAndAdoptedWatchUploadWithoutInferringCallerFromEventSource(): Unit =
        runBlocking {
            Fixture(director = true).use { fixture ->
                fixture.data.accountIdentities.recordSource("own-watch", "me", "WATCH", "watch-scope", false)
                val owned = fixture.data.cloudUploads.activate("alice")
                fixture.data.cloudUploads.bindOwnedDevices(owned, setOf("own-watch"))
                fixture.gate.completeTransition(fixture.gate.stop())
                fixture.publish()
                fixture.append(2, "watch-event", "own-watch")
                fixture.append(3, "foreign-event", "foreign-watch")
                fixture.progress(3)
                fixture.backend.bindDevice("caller-phone")
                val state = fixture.runner().uploadNow()
                assertEquals(setOf("caller-phone", "phone-source", "own-watch"), fixture.registeredDevices)
                assertEquals(
                    setOf("phone-source", "own-watch"),
                    fixture.batches
                        .flatMap { it.events }
                        .map { it.deviceId }
                        .toSet(),
                )
                assertEquals(listOf("\"caller-phone\""), fixture.bindings)
                assertEquals(1, fixture.data.cloud.pendingCount())
                assertEquals(0L, state.inventory?.pendingEvents)
                assertEquals(1L, state.inventory?.pendingMetadata)
                assertFalse(state.fullyUploaded)
                assertTrue(fixture.batches.flatMap { it.matches }.any { it.format != null })
            }
        }

    @Test
    fun concurrentCallsSerializeAndRunBudgetIsFinite(): Unit =
        runBlocking {
            val sent = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            Fixture(eventCount = 5, answer = { batch, call ->
                if (call == 1) {
                    sent.complete(Unit)
                    release.await()
                }
                accepted(batch)
            }).use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val runner = fixture.runner(maxBatches = 1, maxEvents = 2)
                val first = async { runner.uploadNow() }
                withTimeout(5_000) { sent.await() }
                val second = async { runner.uploadNow() }
                assertEquals(1, fixture.batches.size)
                release.complete(Unit)
                assertEquals(CloudUploadOutcome.LIMIT_REACHED, first.await().outcome)
                assertEquals(CloudUploadOutcome.LIMIT_REACHED, second.await().outcome)
                assertEquals(listOf(2, 2), fixture.batches.map { it.events.size })
                assertEquals(
                    1L,
                    runner.state.value.inventory
                        ?.pendingEvents,
                )
                assertTrue(runner.uploadNow().fullyUploaded)
            }
        }

    @Test
    fun cancellationKeepsRetryAndFinallyClearsRunning(): Unit =
        runBlocking {
            val sent = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            Fixture(answer = { batch, call ->
                if (call == 1) {
                    sent.complete(Unit)
                    release.await()
                }
                accepted(batch, duplicate = true)
            }).use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val runner = fixture.runner(maxBatches = 1)
                val request = async { runner.uploadNow() }
                withTimeout(5_000) { sent.await() }
                request.cancelAndJoin()
                release.complete(Unit)
                assertEquals(CloudUploadOutcome.CANCELLED, runner.state.value.outcome)
                assertFalse(runner.state.value.running)
                assertNull(runner.state.value.lastSuccessAtMs)
                fixture.append(2)
                fixture.progress(2)
                runner.uploadNow()
                assertEquals(fixture.batches[0], fixture.batches[1])
                assertEquals(
                    1L,
                    runner.state.value.inventory
                        ?.pendingEvents,
                )
            }
        }

    @Test
    fun accountSwitchDuringRequestNeverAcknowledgesOrPublishesOldSuccess(): Unit =
        runBlocking {
            val sent = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            Fixture(answer = { batch, _ ->
                sent.complete(Unit)
                release.await()
                accepted(batch)
            }).use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val runner = fixture.runner()
                val request = async { runner.uploadNow() }
                withTimeout(5_000) { sent.await() }
                fixture.credentials = CloudCredentials("bob", "bob-session", "other-token")
                release.complete(Unit)
                runCatching { request.await() }
                assertEquals(1, fixture.data.cloud.pendingCount())
                assertNull(runner.state.value.inventory)
                assertNull(runner.state.value.profileId)
                assertNull(runner.state.value.lastSuccessAtMs)
                assertFalse(runner.state.value.running)
            }
        }

    @Test
    fun missingBindingAndConsentStayClosedAndCloseCancelsOnlyRunnerChild(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                val runner = fixture.runner()
                assertEquals(CloudUploadFailureKind.PERMISSION, runner.uploadNow().failure?.kind)
                assertTrue(fixture.bindings.isEmpty() && fixture.batches.isEmpty())
                fixture.backend.bindDevice("caller-phone")
                fixture.gate.publish(fixture.gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.UNAVAILABLE))
                assertEquals(CloudUploadFailureKind.PERMISSION, runner.uploadNow().failure?.kind)
                assertTrue(fixture.batches.isEmpty())
                runner.close()
                assertEquals(CloudUploadOutcome.CLOSED, runner.state.value.outcome)
                assertFailsWith<IllegalStateException> { runner.uploadNow() }
            }
            val sent = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            Fixture(answer = { batch, _ ->
                sent.complete(Unit)
                release.await()
                accepted(batch)
            }).use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val runner = fixture.runner()
                val request = async { runner.uploadNow() }
                withTimeout(5_000) { sent.await() }
                runner.close()
                assertFailsWith<CancellationException> { request.await() }
                release.complete(Unit)
                assertEquals(CloudUploadOutcome.CLOSED, runner.state.value.outcome)
                assertEquals(1, fixture.data.cloud.pendingCount())
                assertEquals(
                    "alice",
                    fixture.gate.session.value
                        ?.profileId,
                )
            }
        }

    @Test
    fun malformedResponseIdsNeverAckAndErrorStateContainsNoServerText(): Unit =
        runBlocking {
            val invalidResults =
                listOf(
                    listOf(ItemResult("foreign", "accepted")),
                    listOf(ItemResult(null, "accepted")),
                    listOf(ItemResult("genesis", "accepted"), ItemResult("genesis", "duplicate")),
                )
            for (results in invalidResults) {
                Fixture(answer = { batch, _ -> accepted(batch).copy(events = results) }).use { fixture ->
                    fixture.backend.bindDevice("caller-phone")
                    val state = fixture.runner().uploadNow()
                    assertEquals(CloudUploadFailure(CloudUploadFailureKind.RESPONSE, "cloud_response_invalid"), state.failure)
                    assertNull(state.lastSuccessAtMs)
                    assertEquals(1, fixture.data.cloud.pendingCount())
                }
            }
        }

    @Test
    fun delayedSuccessAcknowledgesOnlySelectedMetadataRevisions(): Unit =
        runBlocking {
            val sent = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            Fixture(answer = { batch, call ->
                if (call == 1) {
                    sent.complete(Unit)
                    release.await()
                }
                accepted(batch)
            }).use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val runner = fixture.runner(maxBatches = 1)
                val request = async { runner.uploadNow() }
                withTimeout(5_000) { sent.await() }
                fixture.data.players.rename(PlayerId("guest"), "Beatrice")
                fixture.data.matches.updateMeta(MatchId("match"), MatchMeta(venue = "Court 2"))
                fixture.data.matches.updateProgress(MatchId("match"), MatchStatus.PAUSED, ScoreSummary(eventCount = 1), 2)
                release.complete(Unit)
                val state = request.await()
                assertEquals(CloudUploadOutcome.LIMIT_REACHED, state.outcome)
                assertEquals(CloudUploadInventory(0, 0, 4), state.inventory)
                assertFalse(state.fullyUploaded)
                assertTrue(runner.uploadNow().fullyUploaded)
            }
        }

    @Test
    fun httpPermissionAndTransientFailuresUseStableKeysAndNeverInventSuccess(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val runner = fixture.runner()
                fixture.failureStatus = HttpStatusCode.Forbidden
                fixture.failureBody = """{"message":"device_revoked","detail":"credential must never appear in state"}"""
                val permission = runner.uploadNow()
                assertEquals(CloudUploadFailure(CloudUploadFailureKind.PERMISSION, "device_revoked"), permission.failure)
                assertNull(permission.lastSuccessAtMs)
                assertFalse(permission.toString().contains("credential"))
                fixture.failureStatus = HttpStatusCode.ServiceUnavailable
                fixture.failureBody = "untrusted server text"
                val transient = runner.uploadNow()
                assertEquals(CloudUploadFailure(CloudUploadFailureKind.TRANSIENT, "cloud_connection_failed"), transient.failure)
                assertEquals(1, fixture.data.cloud.pendingCount())
                fixture.failureStatus = null
                assertTrue(runner.uploadNow().fullyUploaded)
                val success = runner.state.value.lastSuccessAtMs
                fixture.clock = 500
                assertEquals(success, runner.refresh().lastSuccessAtMs)
                assertEquals(success, runner.uploadNow().lastSuccessAtMs)
            }
        }

    @Test
    fun backendThreadGuardAndExplicitRunnerGuardPreventOffHostWork(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val runner = fixture.runner()
                assertFailsWith<IllegalStateException> {
                    withContext(Dispatchers.Default) { runner.uploadNow() }
                }
                val guarded = CloudUploadRunner(fixture.backend, { 0L }, threadGuard = { false })
                assertFailsWith<IllegalStateException> { guarded.uploadNow() }
                assertFalse(runner.state.value.running)
                assertTrue(fixture.batches.isEmpty())
                assertFailsWith<IllegalArgumentException> { fixture.runner(maxBatches = 0) }
                assertFailsWith<IllegalArgumentException> { fixture.runner(maxEvents = 501) }
            }
        }

    @Test
    fun refreshInventoryWithTwentyThousandAcknowledgedHistoryHeaders(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val runner = fixture.runner()
                assertTrue(runner.uploadNow().fullyUploaded)
                val empty = CloudUploadInventory(0, 0, 0)
                val small = List(5) { measureTime { assertEquals(empty, runner.refresh().inventory) }.inWholeMicroseconds }.sorted()[2]
                fixture.driver.execute(
                    null,
                    """
                    WITH RECURSIVE history(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM history WHERE n < 20000)
                    INSERT INTO match_header(id,sport,discipline,format_json,lineup_json,meta_json,created_at_ms,updated_at_ms,status)
                    SELECT 'history-' || n,'TENNIS','SINGLES','{}','{}','{}',1,1,'FINISHED' FROM history
                    """.trimIndent(),
                    0,
                )
                fixture.driver.execute(
                    null,
                    """UPDATE cloud_metadata SET origin='LOCAL', source_device_id='phone-source', uploaded_revision=revision
                WHERE entity_id LIKE 'history-%'""",
                    0,
                )
                runner.refresh()
                val large = List(3) { measureTime { assertEquals(empty, runner.refresh().inventory) }.inWholeMicroseconds }.sorted()[1]
                println("Inventory refresh on host: small=${small}us, acknowledgedHistory20000=${large}us")
                fixture.driver.recording = true
                runner.refresh()
                assertSingleScalarInventoryRead(fixture.driver)
                fixture.driver.reads.clear()
                runner.uploadNow()
                assertEquals(3, fixture.driver.reads.count { it.trimStart().startsWith("SELECT count(*)", ignoreCase = true) })
                assertEquals(1, fixture.batches.size)
                fixture.driver.recording = false
                fixture.driver.execute(
                    null,
                    "UPDATE cloud_metadata SET origin='UNKNOWN', source_device_id=NULL WHERE entity_id LIKE 'history-%'",
                    0,
                )
                fixture.driver.reads.clear()
                fixture.driver.recording = true
                var localSession: eu.advantly.data.CloudUploadSession? = null
                val activate = measureTime { localSession = fixture.data.cloudUploads.activate("alice") }.inWholeMicroseconds
                val bind =
                    measureTime {
                        fixture.data.cloudUploads.bindOwnedDevices(requireNotNull(localSession), setOf("phone-source"))
                    }.inWholeMicroseconds
                println("Acknowledged history20000 on host: activate=${activate}us, bind=${bind}us")
                assertEquals(2, fixture.driver.reads.count { it.contains("ORDER BY m.entity_id LIMIT 500") })
                assertTrue(
                    fixture.driver.reads.none {
                        it.contains("payload", ignoreCase = true) ||
                            it.contains("SELECT * FROM match_header")
                    },
                )
                assertEquals(empty, fixture.data.cloudUploads.inventory(requireNotNull(localSession)))
            }
        }

    @Test
    fun oneThousandAndOneEventsRemainBoundedWithOnlyTheFinalProjection(): Unit =
        runBlocking {
            Fixture(eventCount = 1001).use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val result = fixture.runner().uploadNow()
                assertTrue(result.fullyUploaded)
                assertEquals(listOf(500, 500, 1), fixture.batches.map { it.events.size })
                assertEquals(
                    1001,
                    fixture.batches
                        .flatMap { it.events }
                        .map { it.id }
                        .distinct()
                        .size,
                )
                assertTrue(
                    fixture.batches
                        .dropLast(1)
                        .flatMap { it.matches }
                        .all { it.projection == null },
                )
                assertEquals(
                    1001,
                    fixture.batches
                        .last()
                        .matches
                        .single()
                        .projection
                        ?.eventCount,
                )
            }
        }

    @Test
    fun initialRefreshReadsOnlyOneInventoryWithoutSelectingEventsOrMetadata(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val before = fixture.driver.persistedState()
                fixture.driver.recording = true
                val runner = fixture.runner()
                val status = runner.refresh()
                assertEquals(before, fixture.driver.persistedState())
                assertSingleScalarInventoryRead(fixture.driver)
                assertEquals(CloudUploadInventory(1, 0, 5), status.inventory)
                fixture.driver.recording = false
                val selected = fixture.backend.captureUpload()
                assertTrue(selected.batch.players.any { it.id == "me" })
                assertEquals(status.inventory, selected.inventory)
                val afterSelection = fixture.driver.persistedState()
                fixture.driver.reads.clear()
                fixture.driver.writes.clear()
                fixture.driver.recording = true
                assertEquals(status.inventory, runner.refresh().inventory)
                assertEquals(afterSelection, fixture.driver.persistedState())
                assertSingleScalarInventoryRead(fixture.driver)
                fixture.driver.recording = false
                assertNull(status.lastSuccessAtMs)
                assertTrue(fixture.batches.isEmpty())
                assertEquals(1L, fixture.data.cloud.pendingCount())
                assertTrue(runner.uploadNow().fullyUploaded)
                val uploaded = fixture.driver.persistedState()
                fixture.driver.reads.clear()
                fixture.driver.writes.clear()
                fixture.driver.recording = true
                assertEquals(CloudUploadInventory(0, 0, 0), runner.refresh().inventory)
                assertEquals(uploaded, fixture.driver.persistedState())
                assertSingleScalarInventoryRead(fixture.driver)
                fixture.driver.recording = false
                fixture.data.players.rename(PlayerId("guest"), "Beatrice")
                val changed = fixture.driver.persistedState()
                fixture.driver.reads.clear()
                fixture.driver.writes.clear()
                fixture.driver.recording = true
                assertEquals(CloudUploadInventory(0, 0, 2), runner.refresh().inventory)
                assertEquals(changed, fixture.driver.persistedState())
                assertSingleScalarInventoryRead(fixture.driver)
                fixture.driver.recording = false
                assertEquals(CloudUploadInventory(0, 0, 2), fixture.backend.captureUpload().inventory)
            }
        }

    @Test
    fun directInventoryRejectsAccountLeasesOldConsentAndReplacedLocalSessions() {
        Fixture().use { fixture ->
            assertFailsWith<CloudException> { fixture.gate.uploadInventory(fixture.gate.capture(false)) }
            assertFailsWith<CloudException> { fixture.gate.uploadInventory(fixture.gate.captureProfile()) }
            val lease = fixture.gate.capture()
            assertEquals(1L, fixture.gate.uploadInventory(lease).pendingEvents)
            fixture.gate.publish(fixture.gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.UNAVAILABLE))
            assertFailsWith<CloudException> { fixture.gate.uploadInventory(lease) }
            fixture.publish()
            assertFailsWith<CloudException> { fixture.gate.uploadInventory(lease) }
            val current = fixture.gate.capture()
            fixture.data.cloudUploads.activate("alice")
            assertFailsWith<IllegalStateException> { fixture.gate.uploadInventory(current) }
        }
    }

    @Test
    fun inventoryDoesNotPublishAnAccountChangeThatHappensDuringAggregateReads(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val runner = fixture.runner()
                runner.refresh()
                fixture.driver.afterRead = { sql ->
                    if (sql.startsWith("SELECT count(*) FROM cloud_metadata")) {
                        fixture.credentials = CloudCredentials("bob", "bob-session", "other-token")
                    }
                }
                val state = runner.refresh()
                assertNull(state.inventory)
                assertNull(state.profileId)
                assertNull(state.lastSuccessAtMs)
                assertTrue(fixture.batches.isEmpty())
                assertEquals(1, fixture.data.cloud.pendingCount())
            }
        }

    @Test
    fun inventoryRequiresTheSameBindingBeforeAndAfterReadingAndNeverRecapturesAStaleRetry(): Unit =
        runBlocking {
            Fixture(answer = { _, _ -> throw IOException("response lost") }).use { fixture ->
                fixture.backend.bindDevice("caller-phone")
                val runner = fixture.runner()
                runner.uploadNow()
                fixture.backend.bindDevice("caller-phone")
                assertNull(runner.refresh().inventory)
                assertEquals(1, fixture.batches.size)
                val selected = fixture.backend.captureUpload()
                fixture.backend.bindDevice("caller-phone")
                assertFailsWith<CloudException> { fixture.backend.uploadInventory(selected) }
                fixture.driver.afterRead = { sql ->
                    if (sql.startsWith("SELECT count(*) FROM cloud_metadata")) {
                        fixture.driver.afterRead = {}
                        runBlocking { fixture.backend.bindDevice("caller-phone") }
                    }
                }
                val error = assertFailsWith<CloudException> { fixture.backend.readUploadStatus() }
                assertEquals("device_not_authorized", error.reason)
                assertEquals(1, fixture.batches.size)
            }
        }

    private companion object {
        fun assertSingleScalarInventoryRead(driver: InventoryRecordingDriver) {
            assertTrue(driver.writes.isEmpty(), "Statuslesen darf keine SQL-Schreibaufrufe ausführen: ${driver.writes}")
            val inventoryReads = driver.reads.filterNot { it.contains("cloud_upload_epoch") }
            assertEquals(3, inventoryReads.size)
            assertTrue(inventoryReads.all { it.trimStart().startsWith("SELECT count(*)", ignoreCase = true) })
            assertTrue(inventoryReads.none { it.contains("json", ignoreCase = true) || it.contains("ORDER BY", ignoreCase = true) })
        }

        fun accepted(
            batch: PushBatch,
            duplicate: Boolean = false,
        ) = PushResponse(
            devices = batch.devices.map { ItemResult(it.id, "ok") },
            players = batch.players.map { ItemResult(it.id, "ok") },
            matches = batch.matches.map { ItemResult(it.id, "updated", projection = it.projection?.let { "applied" }) },
            events = batch.events.map { ItemResult(it.id, if (duplicate) "duplicate" else "accepted") },
        )
    }
}
