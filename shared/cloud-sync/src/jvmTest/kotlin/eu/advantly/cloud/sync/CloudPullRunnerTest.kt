package eu.advantly.cloud.sync

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.scoring.Presets
import eu.advantly.data.CloudPullEntityKind
import eu.advantly.data.CloudPullStore
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.data.db.AdvantlyDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Gemeinsame echte Gate-/SQLDelight-/HTTP-Fixture; keine nachgebildete Persistenz. */
internal class PullFixture(
    val driver: SqlDriver = createInMemoryJvmDriver(),
) : AutoCloseable {
    val data = LocalData(driver)
    var credentials: CloudCredentials? = CloudCredentials("alice", "session-alice", "token-alice")
    val gate = CloudSessionGate({ credentials }, data.cloudUploads)
    var metadata: suspend (Long, Int) -> PullResponse = { cursor, _ -> PullResponse(cursor = cursor, visibilityEpoch = 1) }
    var events: suspend (String, Long, Int) -> List<PulledEvent> = { _, _, _ -> emptyList() }
    var watermarks: suspend (String) -> List<EventWatermark> = { emptyList() }
    val calls = mutableListOf<Pair<String, JsonObject>>()
    val bearers = mutableListOf<String?>()
    private val client =
        HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath.substringAfterLast('/')
                val body = CloudJson.parseToJsonElement((request.body as TextContent).text).jsonObject
                calls += path to body
                bearers += request.headers["Authorization"]
                val text =
                    when (path) {
                        "bind_cloud_session_v1" -> {
                            val current = requireNotNull(credentials)
                            """{"contractVersion":1,"profileId":"${current.profileId}","sessionId":"${current.sessionId}",
                    "installationId":"installation","deviceId":"${body.getValue("p_device_id").jsonPrimitive.content}"}"""
                        }

                        "cloud_sync_pull_v1" -> {
                            CloudJson.encodeToString(
                                PullResponse.serializer(),
                                metadata(body.long("p_cursor"), body.long("p_limit").toInt()),
                            )
                        }

                        "cloud_pull_match_events_v1" -> {
                            CloudJson.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(PulledEvent.serializer()),
                                events(
                                    body.getValue("p_match_id").jsonPrimitive.content,
                                    body.long("p_after_server_seq"),
                                    body.long("p_limit").toInt(),
                                ),
                            )
                        }

                        "cloud_match_event_watermarks_v1" -> {
                            CloudJson.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(EventWatermark.serializer()),
                                watermarks(body.getValue("p_match_id").jsonPrimitive.content),
                            )
                        }

                        else -> {
                            error("Unerwarteter RPC")
                        }
                    }
                respond(text, headers = headersOf("Content-Type", "application/json"))
            },
        )
    val backend =
        SupabaseCloudBackend(gate, CloudHttp("https://cloud.test", "public-key", client), CloudInstallation("installation", "p".repeat(64)))

    suspend fun bind() {
        publish()
        backend.bindDevice("phone")
    }

    fun publish(
        consent: ConsentStatus = ConsentStatus.CURRENT,
        deleting: Boolean = false,
    ) {
        gate.publish(
            gate.capture(false),
            CloudSession(requireNotNull(credentials).profileId, true, deletionRequested = deleting, consentStatus = consent),
        )
    }

    override fun close() {
        client.close()
        driver.close()
    }
}

internal fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.content.toLong()

internal fun pullMatch(
    id: String = "match",
    version: Long = 1,
    access: String = "member",
): PulledMatch {
    val format = Presets.TENNIS_SINGLES_BO3.format()
    return PulledMatch(
        id = id,
        access = access,
        sport = "tennis",
        discipline = "singles",
        format = CloudJson.encodeToJsonElement(MatchFormat.serializer(), format).jsonObject,
        meta = JsonObject(emptyMap()),
        status = "in_progress",
        serverVersion = version,
        accessEpoch = if (access == "member") 1 else null,
        participants =
            listOf(
                PulledParticipant("slot-a", "A", 1, displayName = "A"),
                PulledParticipant("slot-b", "B", 1, displayName = "B"),
            ),
    )
}

internal fun pullEvent(
    seq: Long,
    match: String = "match",
    serverSeq: Long = seq,
): PulledEvent =
    PulledEvent(
        "event-$seq",
        match,
        "remote",
        seq,
        seq,
        seq,
        type = "PointWon",
        payload = JsonObject(mapOf("type" to JsonPrimitive("PointWon"), "side" to JsonPrimitive("A"))),
        serverSeq = serverSeq,
    )

class CloudPullRunnerTest {
    @Test
    fun movingRepairObservesRepeatedEpochChangesAndFinishesSameRoundWhenUploadsStop() =
        runBlocking {
            val file = databaseFile()
            var max = 1L
            var metadataCalls = 0
            val observedEpochs = mutableSetOf<Long?>()
            var complete = false
            try {
                for (execution in 0..95) {
                    reopen(file).use { f ->
                        f.bind()
                        val epoch =
                            if (execution < 14) {
                                1L
                            } else if (execution < 29) {
                                2L
                            } else {
                                3L
                            }
                        val matches =
                            listOf(pullMatch("live", 1)) +
                                (if (execution > 0) listOf(pullMatch("later", 2)) else emptyList()) +
                                (if (epoch == 3L) listOf(pullMatch("latest", 3)) else emptyList())
                        f.metadata = { cursor, _ ->
                            metadataCalls++
                            PullResponse(
                                matches.filter { it.serverVersion > cursor },
                                cursor = matches.maxOf { it.serverVersion },
                                visibilityEpoch = epoch,
                            )
                        }
                        f.events = { id, after, limit ->
                            if (id == "live") (1L..max).filter { it > after }.take(limit).map { pullEvent(it, id) } else emptyList()
                        }
                        f.watermarks = { id ->
                            if (id == "live") {
                                if (execution < 60) max++
                                listOf(EventWatermark("remote", max, max))
                            } else {
                                emptyList()
                            }
                        }
                        val result = CloudPullRunner(f.backend, f.data, maxRpcsPerRun = 1, maxPagesPerRun = 1).pullNow()
                        assertTrue(result.rpcCount <= 1)
                        assertTrue(result.completedMetadataPages + result.completedEventPages <= 1)
                        val progress = f.data.cloudPulls.progress("alice")
                        observedEpochs += progress.visibilityEpoch
                        assertEquals(1, progress.checkRound)
                        if (execution < 60) assertEquals(CloudPullOutcome.LIMIT_REACHED, result.outcome)
                        if (result.outcome == CloudPullOutcome.COMPLETE) {
                            assertTrue(execution >= 60)
                            assertEquals(3, progress.visibilityEpoch)
                            assertNull(progress.targetEpoch)
                            assertTrue(progress.metadataComplete && !progress.roundActive)
                            for (id in listOf("live", "later", "latest")) {
                                val work = f.data.cloudPulls.eventWork("alice", id)!!
                                assertEquals(1, work.checkedRound)
                                assertFalse(work.repairing)
                                assertFalse(
                                    f.data.cloudPulls
                                        .snapshot("alice", CloudPullEntityKind.MATCH, id)!!
                                        .removalPending,
                                )
                            }
                            val live = f.data.cloudPulls.eventWork("alice", "live")!!
                            assertEquals(max, live.cursor)
                            assertEquals(
                                max,
                                f.data.cloudPulls
                                    .receivedWatermarks(live)
                                    .single()
                                    .eventCount,
                            )
                            complete = true
                        }
                    }
                    if (complete) break
                }
                assertTrue(complete)
                assertTrue(metadataCalls >= 4)
                assertTrue(setOf(1L, 2L, 3L).all { it in observedEpochs })
            } finally {
                file.delete()
            }
        }

    @Test
    fun metadataPagesAndLongEventHistoryBothProgressWithinPersistedQuotaAfterReopen() =
        runBlocking {
            val file = databaseFile()
            val calls = mutableListOf<String>()
            var checkedCalls = 0
            var metadataCalls = 0
            try {
                repeat(60) { execution ->
                    reopen(file).use { f ->
                        f.bind()
                        f.metadata = { cursor, _ ->
                            calls += "metadata"
                            metadataCalls++
                            if (cursor == 0L) {
                                PullResponse(listOf(pullMatch("a-checked", 1), pullMatch("live", 2)), cursor = 2, visibilityEpoch = 1)
                            } else {
                                val next = cursor + 1
                                PullResponse(listOf(pullMatch("later-$next", next)), cursor = next, hasMore = true, visibilityEpoch = 1)
                            }
                        }
                        f.events = { id, after, limit ->
                            calls += "events"
                            if (id == "live") (1L..1000L).filter { it > after }.take(limit).map { pullEvent(it, id) } else emptyList()
                        }
                        f.watermarks = { id ->
                            calls += "watermarks"
                            if (id == "a-checked") checkedCalls++
                            emptyList()
                        }
                        val result = CloudPullRunner(f.backend, f.data, maxRpcsPerRun = 1, maxPagesPerRun = 1, eventPageSize = 1).pullNow()
                        assertTrue(result.rpcCount <= 1)
                        assertTrue(result.completedMetadataPages + result.completedEventPages <= 1)
                        assertEquals(CloudPullOutcome.LIMIT_REACHED, result.outcome)
                        if (execution == 59) {
                            val progress = f.data.cloudPulls.progress("alice")
                            assertEquals(1, progress.checkRound)
                            assertTrue(progress.roundActive && progress.roundCatalogReady)
                            assertFalse(progress.metadataComplete)
                            assertTrue(progress.eventRequestsSinceMetadata <= CloudPullStore.EVENT_REQUESTS_PER_METADATA_TURN)
                            assertEquals(
                                1,
                                f.data.cloudPulls
                                    .eventWork("alice", "a-checked")!!
                                    .checkedRound,
                            )
                            assertEquals(
                                0,
                                f.data.cloudPulls
                                    .eventWork("alice", "live")!!
                                    .checkedRound,
                            )
                            assertTrue(
                                f.data.cloudPulls
                                    .eventWork("alice", "live")!!
                                    .cursor > 35,
                            )
                            assertNotNull(f.data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "later-3"))
                            assertFalse(
                                f.data.cloudPulls
                                    .snapshot("alice", CloudPullEntityKind.MATCH, "a-checked")!!
                                    .removalPending,
                            )
                        }
                    }
                }
                assertTrue(metadataCalls >= 4)
                assertEquals(1, checkedCalls)
                var eventRequests = 0
                for (call in calls) {
                    if (call == "metadata") eventRequests = 0 else eventRequests++
                    assertTrue(eventRequests <= CloudPullStore.EVENT_REQUESTS_PER_METADATA_TURN)
                }
            } finally {
                file.delete()
            }
        }

    @Test
    fun callbackFailuresDoNotPreventFiniteTailDownloadAndWatermarkAfterReopen() =
        runBlocking {
            val file = File.createTempFile("d1-r2-callback", ".db")
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { AdvantlyDatabase.Schema.create(it).value }
            var callbacks = 0
            val outcomes = mutableListOf<CloudPullOutcome>()
            try {
                repeat(8) {
                    PullFixture(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")).use { f ->
                        f.bind()
                        f.metadata =
                            { cursor, _ ->
                                PullResponse(
                                    if (cursor ==
                                        0L
                                    ) {
                                        listOf(pullMatch())
                                    } else {
                                        emptyList()
                                    },
                                    cursor = 1,
                                    visibilityEpoch = 1,
                                )
                            }
                        f.events = { _, after, limit -> (1L..5L).filter { it > after }.take(limit).map { pullEvent(it) } }
                        f.watermarks = { listOf(EventWatermark("remote", 5, 5)) }
                        val result =
                            CloudPullRunner(
                                f.backend,
                                f.data,
                                eventPageSize = 2,
                                maxPagesPerRun = 1,
                                eventsCommitted = {
                                    callbacks++
                                    error("notification unavailable")
                                },
                            ).pullNow()
                        outcomes += result.outcome
                        if (result.outcome == CloudPullOutcome.FAILED) assertEquals(1L, result.failedEventJobs)
                        if (it == 7) {
                            println("R2 callback tail: outcomes=$outcomes, callbacks=$callbacks, events=${f.data.events.count("match")}")
                            assertEquals(5L, f.data.events.count("match"))
                            assertTrue(CloudPullOutcome.COMPLETE in outcomes)
                            assertEquals(0L, f.data.cloudPulls.failedEventJobs("alice"))
                        }
                    }
                }
            } finally {
                file.delete()
            }
        }

    @Test
    fun movingWatermarkMustNotPermanentlyStarveMetadataAndNewMatchesAcrossReopen() =
        runBlocking {
            val file = File.createTempFile("d1-r2-live", ".db")
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { AdvantlyDatabase.Schema.create(it).value }
            var max = 1L
            var metadataCalls = 0
            val outcomes = mutableListOf<CloudPullOutcome>()
            try {
                repeat(18) { execution ->
                    PullFixture(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")).use { f ->
                        f.bind()
                        f.metadata = { cursor, _ ->
                            metadataCalls++
                            val all =
                                if (execution == 0) {
                                    listOf(pullMatch("live", 1))
                                } else {
                                    listOf(pullMatch("live", 1), pullMatch("later", 2))
                                }
                            PullResponse(
                                all.filter { it.serverVersion > cursor },
                                cursor = if (execution == 0) 1 else 2,
                                visibilityEpoch = if (execution == 0) 1 else 2,
                            )
                        }
                        f.events = { id, after, limit ->
                            if (id == "live") {
                                (1L..max).filter { it > after }.take(limit).map { pullEvent(it, id) }
                            } else {
                                emptyList()
                            }
                        }
                        f.watermarks = { id ->
                            // Ein legaler neuer Upload zwischen Eventseite und separat ausgeführtem Wasserstands-RPC.
                            if (id == "live") {
                                max++
                                listOf(EventWatermark("remote", max, max))
                            } else {
                                emptyList()
                            }
                        }
                        val result = CloudPullRunner(f.backend, f.data, maxRpcsPerRun = 1, maxPagesPerRun = 1).pullNow()
                        outcomes += result.outcome
                        assertTrue(result.rpcCount <= 1)
                        assertFalse(result.outcome == CloudPullOutcome.COMPLETE)
                        if (execution == 17) {
                            val progress = f.data.cloudPulls.progress("alice")
                            val work = f.data.cloudPulls.eventWork("alice", "live")!!
                            println(
                                "R2 live repair: outcomes=$outcomes, metadataCalls=$metadataCalls, max=$max, " +
                                    "progress=$progress, work=$work",
                            )
                            assertNotNull(
                                f.data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "later"),
                                "Laufende Eventuploads dürfen neue Metadaten/Epochen nicht dauerhaft verdrängen",
                            )
                        }
                    }
                }
            } finally {
                file.delete()
            }
        }

    @Test
    fun movingWatermarkAlsoStarvesMetadataWithDefaultBudget() =
        runBlocking<Unit> {
            PullFixture().use { f ->
                f.bind()
                var max = 1L
                var newMatch = false
                var metadataCalls = 0
                f.metadata = { cursor, _ ->
                    metadataCalls++
                    val all = if (newMatch) listOf(pullMatch("live", 1), pullMatch("later", 2)) else listOf(pullMatch("live", 1))
                    PullResponse(all.filter { it.serverVersion > cursor }, cursor = if (newMatch) 2 else 1, visibilityEpoch = 1)
                }
                f.events = { id, after, limit ->
                    if (id == "live") (1L..max).filter { it > after }.take(limit).map { pullEvent(it, id) } else emptyList()
                }
                f.watermarks = { id ->
                    if (id == "live") {
                        max++
                        listOf(EventWatermark("remote", max, max))
                    } else {
                        emptyList()
                    }
                }
                val runner = CloudPullRunner(f.backend, f.data)
                val outcomes =
                    (1..12).map {
                        val result = runner.pullNow()
                        newMatch = true
                        assertTrue(result.rpcCount <= 8)
                        result.outcome
                    }
                println(
                    "R2 default live repair: outcomes=$outcomes, metadataCalls=$metadataCalls, max=$max, " +
                        "progress=${f.data.cloudPulls.progress(
                            "alice",
                        )}",
                )
                assertNotNull(
                    f.data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "later"),
                    "Auch das Standardbudget benötigt eine von erfolgreicher Reparatur unabhängige Metadatenfairness",
                )
            }
        }

    @Test
    fun deadlineFailureDoesNotFreezeTheRoundOrLaterMetadataAfterReopen() =
        runBlocking {
            val file = databaseFile()

            fun configure(f: PullFixture) {
                f.metadata = { cursor, _ ->
                    PullResponse(
                        if (cursor < 3) listOf(pullMatch("c-later", 3)) else emptyList(),
                        cursor = 3,
                        visibilityEpoch = 1,
                    )
                }
                f.events = { id, after, _ ->
                    if (id == "a-timeout") delay(1000)
                    if (after == 0L) listOf(pullEvent(1, id).copy(id = "event-$id")) else emptyList()
                }
                f.watermarks = { listOf(EventWatermark("remote", 1, 1)) }
            }
            try {
                reopen(file).use { f ->
                    f.bind()
                    configure(f)
                    val attempt = f.backend.capturePull()
                    val fence = { f.backend.requireCurrentPull(attempt) }
                    val serial =
                        f.data.cloudPulls
                            .beginRun("alice", fence)
                            .runSerial
                    val page =
                        PullMapping.metadata(
                            PullResponse(
                                listOf(pullMatch("a-timeout", 1), pullMatch("b-healthy", 2)),
                                cursor = 2,
                                visibilityEpoch = 1,
                            ),
                            0,
                            200,
                        )
                    f.data.cloudPulls.commitMetadata("alice", serial, 0, page, fence)
                    val result = CloudPullRunner(f.backend, f.data, deadlineMillis = 200).pullNow()
                    assertEquals(CloudPullOutcome.DEADLINE_REACHED, result.outcome)
                    assertEquals(1, result.failedEventJobs)
                    assertEquals(
                        0,
                        f.data.cloudPulls
                            .eventWork("alice", "a-timeout")
                            ?.cursor,
                    )
                    assertEquals(
                        1,
                        f.data.cloudPulls
                            .eventWork("alice", "a-timeout")
                            ?.failedRound,
                    )
                }
                reopen(file).use { f ->
                    f.bind()
                    configure(f)
                    assertEquals(CloudPullOutcome.FAILED, CloudPullRunner(f.backend, f.data, maxPagesPerRun = 1).pullNow().outcome)
                    assertEquals(1, f.data.events.count("b-healthy"))
                    assertFalse(
                        f.data.cloudPulls
                            .progress("alice")
                            .roundActive,
                    )
                }
                reopen(file).use { f ->
                    f.bind()
                    configure(f)
                    assertEquals(CloudPullOutcome.LIMIT_REACHED, CloudPullRunner(f.backend, f.data, maxPagesPerRun = 1).pullNow().outcome)
                    assertNotNull(f.data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "c-later"))
                }
                reopen(file).use { f ->
                    f.bind()
                    configure(f)
                    assertEquals(CloudPullOutcome.LIMIT_REACHED, CloudPullRunner(f.backend, f.data, maxPagesPerRun = 1).pullNow().outcome)
                    assertEquals(1, f.data.events.count("c-later"))
                    assertEquals(0, f.data.events.count("a-timeout"))
                    assertEquals(
                        0,
                        f.data.cloudPulls
                            .eventWork("alice", "a-timeout")
                            ?.checkedRound,
                    )
                }
            } finally {
                file.delete()
            }
        }

    private fun databaseFile(): File =
        File.createTempFile("pull-r1", ".db").also { file ->
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { AdvantlyDatabase.Schema.create(it).value }
        }

    private fun reopen(file: File): PullFixture = PullFixture(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}"))

    @Test
    fun failedSelectionSurvivesReopenAndNeverAcknowledgesConflictWhileHealthyWorkProgresses() =
        runBlocking {
            val file = databaseFile()
            val bad = pullEvent(1, "a-conflict")

            fun configure(f: PullFixture) {
                f.metadata = { cursor, _ ->
                    PullResponse(
                        if (cursor == 0L) listOf(pullMatch("a-conflict", 1), pullMatch("b-healthy", 2)) else emptyList(),
                        cursor = 2,
                        visibilityEpoch = 1,
                    )
                }
                f.events = { id, after, _ ->
                    if (after == 0L) listOf(pullEvent(1, id).copy(id = if (id == "a-conflict") bad.id else "healthy")) else emptyList()
                }
                f.watermarks = { listOf(EventWatermark("remote", 1, 1)) }
            }
            try {
                reopen(file).use { f ->
                    f.bind()
                    configure(f)
                    f.data.events.append(EventRows.toStored(bad.copy(wallClockMs = 999).toRow()).copy(confirmed = true))
                    f.data.cloud.acknowledge(emptySet(), mapOf(bad.id to "forbidden"))
                    val result = CloudPullRunner(f.backend, f.data).pullNow()
                    assertEquals(CloudPullOutcome.FAILED, result.outcome)
                    assertEquals(1, result.failedEventJobs)
                    val work = f.data.cloudPulls.eventWork("alice", "a-conflict")!!
                    assertEquals(1, work.lastAttempt)
                    assertEquals(1, work.failedRound)
                    assertEquals(0, work.checkedRound)
                    assertEquals(0, work.cursor)
                    assertNotNull(work.failure)
                    assertTrue(
                        f.data.cloudPulls
                            .receivedWatermarks(work)
                            .isEmpty(),
                    )
                }
                reopen(file).use { f ->
                    f.bind()
                    configure(f)
                    val result = CloudPullRunner(f.backend, f.data, maxPagesPerRun = 1).pullNow()
                    assertEquals(CloudPullOutcome.FAILED, result.outcome)
                    assertEquals(1, result.failedEventJobs)
                    assertEquals(1, f.data.events.count("b-healthy"))
                    assertFalse(f.calls.any { it.first == "cloud_sync_pull_v1" })
                    val badWork = f.data.cloudPulls.eventWork("alice", "a-conflict")!!
                    assertEquals(0, badWork.cursor)
                    assertEquals(0, badWork.checkedRound)
                    assertNotNull(badWork.failure)
                    assertEquals(
                        1,
                        f.data.cloudPulls
                            .eventWork("alice", "b-healthy")
                            ?.checkedRound,
                    )
                    assertFalse(
                        f.data.cloudPulls
                            .progress("alice")
                            .roundActive,
                    )
                    assertEquals(mapOf(bad.id to "forbidden"), f.data.cloud.rejectedEvents())
                    assertEquals(1, f.data.events.unconfirmedCount())
                }
            } finally {
                file.delete()
            }
        }

    @Test
    fun persistedRoundCompletesAfterReopenThenChecksNewGapsWithoutMetadataChanges() =
        runBlocking {
            val file = databaseFile()
            var cloud = listOf(pullEvent(1, "m-1"), pullEvent(3, "m-1"))
            val checks = mutableSetOf<String>()
            var metadataCalls = 0

            fun configure(f: PullFixture) {
                f.metadata = { cursor, _ ->
                    metadataCalls++
                    PullResponse(
                        if (cursor ==
                            0L
                        ) {
                            (1..4).map { pullMatch("m-$it", it.toLong()) }
                        } else {
                            emptyList()
                        },
                        cursor = 4,
                        visibilityEpoch = 1,
                    )
                }
                f.events = { id, after, _ -> if (id == "m-1") cloud.filter { it.serverSeq > after } else emptyList() }
                f.watermarks = { id ->
                    checks += id
                    if (id == "m-1") listOf(EventWatermark("remote", 3, cloud.size.toLong())) else emptyList()
                }
            }

            suspend fun round(): CloudPullState {
                var result = CloudPullState()
                for (index in 1..12) {
                    reopen(file).use { f ->
                        f.bind()
                        configure(f)
                        result = CloudPullRunner(f.backend, f.data, maxPagesPerRun = 1).pullNow()
                        assertTrue(result.rpcCount <= 8)
                        assertTrue(result.completedMetadataPages + result.completedEventPages <= 1)
                    }
                    if (result.outcome == CloudPullOutcome.COMPLETE) return result
                    assertEquals(CloudPullOutcome.LIMIT_REACHED, result.outcome)
                }
                error("Persistierte Runde wurde nicht beendet")
            }
            try {
                assertEquals(CloudPullOutcome.COMPLETE, round().outcome)
                assertEquals(setOf("m-1", "m-2", "m-3", "m-4"), checks)
                assertEquals(1, metadataCalls)
                reopen(file).use { f ->
                    assertEquals(
                        1,
                        f.data.cloudPulls
                            .progress("alice")
                            .checkRound,
                    )
                    assertFalse(
                        f.data.cloudPulls
                            .progress("alice")
                            .roundActive,
                    )
                    f.data.events.markConfirmedUpTo("m-1", "remote", 3)
                    f.data.events.append(EventRows.toStored(pullEvent(2, "m-1").toRow()))
                    f.data.events.append(EventRows.toStored(pullEvent(1, "unrelated").copy(id = "rejected").toRow()).copy(confirmed = true))
                    f.data.cloud.acknowledge(emptySet(), mapOf("rejected" to "forbidden"))
                    assertEquals(3, f.data.events.count("m-1"))
                    assertEquals(3, f.data.events.maxDeviceSeq("m-1", "remote"))
                }
                cloud = (1L..3L).map { pullEvent(it, "m-1") }
                checks.clear()
                assertEquals(CloudPullOutcome.COMPLETE, round().outcome)
                assertEquals(setOf("m-1", "m-2", "m-3", "m-4"), checks)
                assertEquals(2, metadataCalls)
                reopen(file).use { f ->
                    assertEquals(
                        2,
                        f.data.cloudPulls
                            .progress("alice")
                            .checkRound,
                    )
                    val work = f.data.cloudPulls.eventWork("alice", "m-1")!!
                    assertEquals(2, work.checkedRound)
                    assertFalse(work.repairing)
                    assertEquals(
                        3,
                        f.data.cloudPulls
                            .receivedWatermarks(work)
                            .single()
                            .eventCount,
                    )
                    assertEquals(1, f.data.events.unconfirmedCount())
                    assertEquals(mapOf("rejected" to "forbidden"), f.data.cloud.rejectedEvents())
                    assertTrue(
                        f.data.cloud
                            .pendingEvents(10)
                            .isEmpty(),
                    )
                }
            } finally {
                file.delete()
            }
        }

    @Test
    fun stackSizedRoundCompletesAcrossRealDatabaseReopensWithinHarnessBudget() =
        runBlocking {
            val file = databaseFile()
            val checked = mutableSetOf<String>()
            var completed = false
            try {
                for (index in 1..20) {
                    reopen(file).use { f ->
                        f.bind()
                        f.metadata = { cursor, limit ->
                            val rows = (1L..201L).filter { it > cursor }.take(limit)
                            PullResponse(
                                rows.map { pullMatch("m-$it", it) },
                                cursor = rows.lastOrNull() ?: 201,
                                hasMore = rows.lastOrNull() == 200L,
                                visibilityEpoch = 1,
                            )
                        }
                        f.events = { id, after, limit ->
                            if (id == "m-1") (1L..501L).filter { it > after }.take(limit).map { pullEvent(it, id) } else emptyList()
                        }
                        f.watermarks = { id ->
                            checked += id
                            if (id == "m-1") listOf(EventWatermark("remote", 501, 501)) else emptyList()
                        }
                        val result =
                            CloudPullRunner(
                                f.backend,
                                f.data,
                                maxRpcsPerRun = 100,
                                maxPagesPerRun = 100,
                                maxEventJobsPerRun = 100,
                                deadlineMillis = 120_000,
                            ).pullNow()
                        assertEquals(
                            1,
                            f.data.cloudPulls
                                .progress("alice")
                                .checkRound,
                        )
                        assertTrue(result.rpcCount <= 100)
                        if (result.outcome == CloudPullOutcome.COMPLETE) {
                            completed = true
                            assertEquals(201, checked.size)
                            assertEquals(501, f.data.events.count("m-1"))
                        } else {
                            assertEquals(CloudPullOutcome.LIMIT_REACHED, result.outcome)
                        }
                    }
                    if (completed) break
                }
                assertTrue(completed)
            } finally {
                file.delete()
            }
        }

    @Test
    fun singleRpcAndSinglePagePersistEventAndWatermarkPhasesWithoutExceedingBudget() =
        runBlocking {
            val file = databaseFile()
            try {
                for (index in 1..4) {
                    reopen(file).use { f ->
                        f.bind()
                        f.metadata =
                            { cursor, _ ->
                                PullResponse(
                                    if (cursor ==
                                        0L
                                    ) {
                                        listOf(pullMatch())
                                    } else {
                                        emptyList()
                                    },
                                    cursor = 1,
                                    visibilityEpoch = 1,
                                )
                            }
                        f.events =
                            {
                                _,
                                after,
                                limit,
                                ->
                                (1L..501L).filter { it * 2 > after }.take(limit).map { pullEvent(it, serverSeq = it * 2) }
                            }
                        f.watermarks = { listOf(EventWatermark("remote", 501, 501)) }
                        val result = CloudPullRunner(f.backend, f.data, maxRpcsPerRun = 1, maxPagesPerRun = 1).pullNow()
                        assertEquals(1, result.rpcCount)
                        assertTrue(result.completedMetadataPages + result.completedEventPages <= 1)
                        assertEquals(if (index == 4) CloudPullOutcome.COMPLETE else CloudPullOutcome.LIMIT_REACHED, result.outcome)
                        val work = f.data.cloudPulls.eventWork("alice", "match")!!
                        assertEquals(if (index == 4) 1 else 0, work.checkedRound)
                        if (index == 1) assertEquals(0, work.lastAttempt)
                        if (index == 3) {
                            assertEquals(1002, work.cursor)
                            assertTrue(work.needsVerification)
                        }
                        if (index == 4) {
                            assertEquals(501, f.data.events.count("match"))
                            assertFalse(work.needsVerification)
                            assertFalse(
                                f.data.cloudPulls
                                    .progress("alice")
                                    .roundActive,
                            )
                            assertEquals(listOf("bind_cloud_session_v1", "cloud_match_event_watermarks_v1"), f.calls.map { it.first })
                        }
                    }
                }
            } finally {
                file.delete()
            }
        }

    @Test
    fun fullScanAndRepairSurviveClosingDatabaseMidway() =
        runBlocking {
            val file = File.createTempFile("pull-durable-repair", ".db")
            var cloud = listOf(pullEvent(1), pullEvent(3))
            var epoch = 1L
            var stagedScan = false

            fun configure(f: PullFixture) {
                f.metadata = { cursor, _ ->
                    if (stagedScan && cursor == 0L) {
                        PullResponse(listOf(pullMatch()), cursor = 1, hasMore = true, visibilityEpoch = epoch)
                    } else {
                        PullResponse(
                            if (cursor ==
                                0L
                            ) {
                                listOf(pullMatch())
                            } else {
                                emptyList()
                            },
                            cursor = maxOf(1, cursor),
                            visibilityEpoch = epoch,
                        )
                    }
                }
                f.events = { _, after, limit -> cloud.filter { it.serverSeq > after }.take(limit) }
                f.watermarks = { listOf(EventWatermark("remote", 3, cloud.size.toLong())) }
            }
            try {
                val initial = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
                AdvantlyDatabase.Schema.create(initial).value
                PullFixture(initial).use { f ->
                    f.bind()
                    configure(f)
                    assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                    cloud = (1L..3L).map(::pullEvent)
                    assertEquals(CloudPullOutcome.LIMIT_REACHED, CloudPullRunner(f.backend, f.data, maxPagesPerRun = 2).pullNow().outcome)
                    assertTrue(
                        f.data.cloudPulls
                            .eventWork("alice", "match")!!
                            .repairing,
                    )
                }
                PullFixture(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")).use { f ->
                    f.bind()
                    configure(f)
                    assertTrue(
                        f.data.cloudPulls
                            .eventWork("alice", "match")!!
                            .repairing,
                    )
                    assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                    assertEquals(3, f.data.events.count("match"))
                    epoch = 2
                    stagedScan = true
                    assertEquals(CloudPullOutcome.LIMIT_REACHED, CloudPullRunner(f.backend, f.data, maxPagesPerRun = 2).pullNow().outcome)
                    assertEquals(
                        2,
                        f.data.cloudPulls
                            .progress("alice")
                            .targetEpoch,
                    )
                    assertEquals(
                        1,
                        f.data.cloudPulls
                            .progress("alice")
                            .cursor,
                    )
                }
                PullFixture(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")).use { f ->
                    f.bind()
                    configure(f)
                    assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                    assertEquals(
                        2,
                        f.data.cloudPulls
                            .progress("alice")
                            .visibilityEpoch,
                    )
                    assertNull(
                        f.data.cloudPulls
                            .progress("alice")
                            .targetEpoch,
                    )
                    assertEquals(0, f.data.cloudPulls.pendingRemovals("alice"))
                }
            } finally {
                file.delete()
            }
        }

    @Test
    fun emptyPullOverLargeHistoryReadsNeitherGlobalPayloadsNorUnboundedMatchIds() =
        runBlocking {
            val recording = InventoryRecordingDriver(createInMemoryJvmDriver())
            PullFixture(recording).use { f ->
                f.bind()
                recording.execute(
                    null,
                    """WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<20000)
                INSERT INTO match_event(id,match_id,device_id,device_seq,lamport,payload,cloud_state)
                SELECT 'old-'||x,'local-'||x,'local',1,x,'private-payload',1 FROM n""",
                    0,
                )
                recording.execute(
                    null,
                    """WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<20000)
                INSERT INTO cloud_pull_snapshot(profile_id,kind,entity_id,server_version,snapshot_json,access)
                SELECT 'alice','MATCH','feed-'||x,1,'{}','feed' FROM n""",
                    0,
                )
                recording.recording = true
                val result = CloudPullRunner(f.backend, f.data).pullNow()
                recording.recording = false
                assertEquals(CloudPullOutcome.BLOCKED, result.outcome) // Vollscan meldet fehlende Feed-Snapshots nur als D2-Arbeit.
                assertEquals(1, result.rpcCount)
                assertTrue(recording.reads.none { it.contains("FROM match_event", true) })
                assertTrue(recording.reads.none { it.contains("snapshot_json", true) })
                assertTrue(recording.reads.none { it.contains("SELECT") && it.contains("FROM match_header") })
                assertTrue(recording.reads.filter { it.contains("FROM cloud_pull_event_work") }.all { it.contains("LIMIT 1") })
            }
        }

    @Test
    fun malformedLastEventCannotConfirmReadablePrefix() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                f.metadata = { _, _ -> PullResponse(listOf(pullMatch()), cursor = 1, visibilityEpoch = 1) }
                f.events =
                    { _, _, _ ->
                        listOf(
                            pullEvent(1),
                            pullEvent(2).copy(
                                type = "FutureType",
                                payload =
                                    JsonObject(
                                        mapOf("type" to JsonPrimitive("FutureType")),
                                    ),
                            ),
                        )
                    }
                assertEquals(CloudPullOutcome.FAILED, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                assertEquals(0, f.data.events.count("match"))
                val work = f.data.cloudPulls.eventWork("alice", "match")!!
                assertEquals(0, work.cursor)
                assertTrue(
                    f.data.cloudPulls
                        .receivedWatermarks(work)
                        .isEmpty(),
                )
            }
        }

    @Test
    fun twoHundredAndOneMatchesResumeAfterReopenAndDeduplicateRepeatedEntities() =
        runBlocking {
            val file = File.createTempFile("pull-pages", ".db")
            try {
                var driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
                AdvantlyDatabase.Schema.create(driver).value

                fun configure(f: PullFixture) {
                    f.metadata = { cursor, limit ->
                        val next = (1L..201L).filter { it > cursor }.take(limit)
                        PullResponse(
                            matches = next.map { pullMatch("match-$it", it, "feed") },
                            players = listOf(PulledPlayer("player", "self", "Remote", false, serverVersion = 205)),
                            friendships = listOf(PulledFriendship("friend", status = "accepted", outgoing = false, serverVersion = 206)),
                            cursor = if (next.lastOrNull() == 200L) 200 else 210,
                            hasMore = next.lastOrNull() == 200L,
                            visibilityEpoch = 1,
                        )
                    }
                }
                PullFixture(driver).use { f ->
                    f.bind()
                    configure(f)
                    assertEquals(CloudPullOutcome.LIMIT_REACHED, CloudPullRunner(f.backend, f.data, maxPagesPerRun = 1).pullNow().outcome)
                    assertEquals(
                        200,
                        f.data.cloudPulls
                            .progress("alice")
                            .cursor,
                    )
                    assertEquals(
                        1,
                        f.data.cloudPulls
                            .progress("alice")
                            .targetEpoch,
                    )
                    assertNull(f.data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "match-201"))
                }
                driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
                PullFixture(driver).use { f ->
                    f.bind()
                    configure(f)
                    assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                    assertEquals(
                        210,
                        f.data.cloudPulls
                            .progress("alice")
                            .cursor,
                    )
                    assertNull(
                        f.data.cloudPulls
                            .progress("alice")
                            .targetEpoch,
                    )
                    assertEquals(
                        1,
                        f.data.cloudPulls
                            .progress("alice")
                            .visibilityEpoch,
                    )
                    assertNotNull(f.data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "match-201"))
                    assertEquals(
                        205,
                        f.data.cloudPulls
                            .snapshot("alice", CloudPullEntityKind.PLAYER, "player")
                            ?.serverVersion,
                    )
                    assertNull(f.data.players.self())
                    assertEquals(0, f.data.cloudPulls.pendingRemovals("alice"))
                }
            } finally {
                file.delete()
            }
        }

    @Test
    fun moreThanFiveHundredEventsUseIndependentDurablePages() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                f.metadata =
                    { cursor, _ -> PullResponse(if (cursor == 0L) listOf(pullMatch()) else emptyList(), cursor = 1, visibilityEpoch = 1) }
                f.events = { _, after, limit -> (1L..501L).filter { it * 2 > after }.take(limit).map { pullEvent(it, serverSeq = it * 2) } }
                f.watermarks = { listOf(EventWatermark("remote", 501, 501)) }
                val first = CloudPullRunner(f.backend, f.data, maxPagesPerRun = 2).pullNow()
                assertEquals(CloudPullOutcome.LIMIT_REACHED, first.outcome)
                assertEquals(
                    1,
                    f.data.cloudPulls
                        .progress("alice")
                        .cursor,
                )
                assertEquals(
                    1000,
                    f.data.cloudPulls
                        .eventWork("alice", "match")
                        ?.cursor,
                )
                assertEquals(500, f.data.events.count("match"))
                val second = CloudPullRunner(f.backend, f.data).pullNow()
                assertEquals(CloudPullOutcome.COMPLETE, second.outcome)
                assertEquals(501, f.data.events.count("match"))
                assertEquals(501, f.data.events.unconfirmedCount())
                assertEquals(0, f.data.cloud.pendingCount())
                assertEquals(
                    1002,
                    f.data.cloudPulls
                        .eventWork("alice", "match")
                        ?.cursor,
                )
            }
        }

    @Test
    fun gapBelowCursorIsHealedWithoutChangedMetadataDespiteLocalPendingAndRejectedExtras() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                f.metadata =
                    { cursor, _ -> PullResponse(if (cursor == 0L) listOf(pullMatch()) else emptyList(), cursor = 1, visibilityEpoch = 1) }
                var cloud = listOf(pullEvent(1), pullEvent(3))
                f.events = { _, after, limit -> cloud.filter { it.serverSeq > after }.take(limit) }
                f.watermarks = { listOf(EventWatermark("remote", 3, cloud.size.toLong())) }
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                // Lokal liegen count=3/max=3 vor; trotzdem fehlt der Serverempfang von Event 2.
                val pending = EventRows.toStored(pullEvent(2).toRow())
                val rejected = EventRows.toStored(pullEvent(5, match = "unrelated").toRow())
                f.data.events.appendAll(listOf(pending, rejected))
                f.data.cloud.acknowledge(emptySet(), mapOf(rejected.id to "forbidden"))
                cloud = (1L..3L).map(::pullEvent)
                assertEquals(3, f.data.events.count("match"))
                assertEquals(3, f.data.events.maxDeviceSeq("match", "remote"))
                val firstRepair = CloudPullRunner(f.backend, f.data, maxPagesPerRun = 2).pullNow()
                assertEquals(CloudPullOutcome.LIMIT_REACHED, firstRepair.outcome)
                assertTrue(
                    f.data.cloudPulls
                        .eventWork("alice", "match")!!
                        .repairing,
                )
                assertEquals(
                    0,
                    f.data.cloudPulls
                        .eventWork("alice", "match")
                        ?.cursor,
                )
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                assertEquals(3, f.data.events.count("match"))
                assertEquals(mapOf(rejected.id to "forbidden"), f.data.cloud.rejectedEvents())
                assertEquals(
                    emptyList(),
                    f.data.cloud
                        .pendingEvents(10)
                        .map { it.id },
                )
                assertEquals(
                    3,
                    f.data.cloudPulls
                        .receivedWatermarks(f.data.cloudPulls.eventWork("alice", "match")!!)
                        .single()
                        .eventCount,
                )
            }
        }

    @Test
    fun invalidFinalMetadataEntryAndNonAdvancingHasMoreNeverCommitPage() =
        runBlocking {
            for (malformedPlayer in listOf(true, false)) {
                PullFixture().use { f ->
                    f.bind()
                    f.metadata = { _, _ ->
                        if (malformedPlayer) {
                            PullResponse(
                                listOf(pullMatch()),
                                players = listOf(PulledPlayer("bad", "unknown", "bad", false, serverVersion = 2)),
                                cursor = 2,
                                visibilityEpoch = 1,
                            )
                        } else {
                            PullResponse(listOf(pullMatch()), cursor = 0, hasMore = true, visibilityEpoch = 1)
                        }
                    }
                    assertEquals(CloudPullOutcome.FAILED, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                    assertEquals(0, f.data.cloud.cursor("alice", CloudPullStore.METADATA_STREAM))
                    assertNull(f.data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "match"))
                    assertNull(f.data.matches.get(MatchId("match")))
                }
            }
        }

    @Test
    fun finalEventConflictRollsBackEventsReceiptsCursorAndAfterCommitNotification() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                f.metadata = { _, _ -> PullResponse(listOf(pullMatch()), cursor = 1, visibilityEpoch = 1) }
                val existing =
                    EventRows.toStored(
                        pullEvent(2)
                            .copy(
                                payload =
                                    JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("PointWon"),
                                            "side" to JsonPrimitive("B"),
                                        ),
                                    ),
                            ).toRow(),
                    )
                f.data.events.append(existing)
                f.events = { _, _, _ -> listOf(pullEvent(1), pullEvent(2).copy(redacted = true)) }
                var notifications = 0
                val result = CloudPullRunner(f.backend, f.data, eventsCommitted = { notifications++ }).pullNow()
                assertEquals(CloudPullOutcome.FAILED, result.outcome)
                assertEquals(
                    1,
                    f.data.cloudPulls
                        .progress("alice")
                        .cursor,
                )
                assertEquals(listOf(existing), f.data.events.eventsFor("match"))
                val work = f.data.cloudPulls.eventWork("alice", "match")!!
                assertEquals(0, work.cursor)
                assertTrue(
                    f.data.cloudPulls
                        .receivedWatermarks(work)
                        .isEmpty(),
                )
                assertEquals(0, notifications)
            }
        }

    @Test
    fun repeatedEpochChangesRestartScanAndOnlyFinalSameEpochPageMarksMissing() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                f.metadata = { _, _ -> PullResponse(listOf(pullMatch("old", access = "feed")), cursor = 10, visibilityEpoch = 1) }
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                var call = 0
                f.metadata = { cursor, _ ->
                    call++
                    when (call) {
                        1 -> PullResponse(cursor = 10, visibilityEpoch = 2)
                        2 -> PullResponse(listOf(pullMatch("new", 11, "feed")), cursor = 11, hasMore = true, visibilityEpoch = 2)
                        3 -> PullResponse(cursor = 11, visibilityEpoch = 3)
                        4 -> PullResponse(listOf(pullMatch("new", 11, "feed")), cursor = 11, hasMore = true, visibilityEpoch = 3)
                        else -> PullResponse(cursor = cursor, visibilityEpoch = 3)
                    }
                }
                assertEquals(CloudPullOutcome.LIMIT_REACHED, CloudPullRunner(f.backend, f.data, maxPagesPerRun = 3).pullNow().outcome)
                assertEquals(0, f.data.cloudPulls.pendingRemovals("alice"))
                assertEquals(
                    3,
                    f.data.cloudPulls
                        .progress("alice")
                        .targetEpoch,
                )
                assertEquals(
                    0,
                    f.data.cloudPulls
                        .progress("alice")
                        .cursor,
                )
                val completed = CloudPullRunner(f.backend, f.data).pullNow()
                assertEquals(CloudPullOutcome.BLOCKED, completed.outcome)
                assertEquals(1, completed.pendingRemovals)
                assertEquals(
                    3,
                    f.data.cloudPulls
                        .progress("alice")
                        .visibilityEpoch,
                )
                assertFalse(f.data.matches.isDeleted(MatchId("old")))
            }
        }

    @Test
    fun stackHarnessBudgetCannotFinish201MemberMatches() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                f.metadata = { cursor, limit ->
                    val rows = (1L..201L).filter { it > cursor }.take(limit)
                    PullResponse(
                        rows.map { pullMatch("m-$it", it) },
                        cursor = rows.lastOrNull() ?: 201L,
                        hasMore = rows.lastOrNull() == 200L,
                        visibilityEpoch = 1,
                    )
                }
                f.events = { id, after, limit ->
                    if (id == "m-1") (1L..501L).filter { it > after }.take(limit).map { pullEvent(it, id) } else emptyList()
                }
                f.watermarks = { id -> if (id == "m-1") listOf(EventWatermark("remote", 501, 501)) else emptyList() }
                val runner =
                    CloudPullRunner(
                        f.backend,
                        f.data,
                        maxRpcsPerRun = 100,
                        maxPagesPerRun = 100,
                        maxEventJobsPerRun = 100,
                        deadlineMillis = 120_000,
                    )
                val outcomes = (1..20).map { runner.pullNow().outcome }
                val checked =
                    f.calls
                        .filter { it.first == "cloud_match_event_watermarks_v1" }
                        .map { it.second.getValue("p_match_id").toString() }
                        .toSet()
                println("R1 stack budget: outcomes=$outcomes, checked=${checked.size}, events=${f.data.events.count("m-1")}")
                assertEquals(201, checked.size)
                assertEquals(501L, f.data.events.count("m-1"))
                assertTrue(CloudPullOutcome.COMPLETE in outcomes, "Stack-Test complete() bricht nach exakt 20 Versuchen ab")
            }
        }

    @Test
    fun finiteUnchangingInventoryEventuallyCompletesAcrossBoundedRuns() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                f.metadata = { cursor, _ ->
                    PullResponse(
                        if (cursor == 0L) (1..4).map { pullMatch("m-$it", it.toLong()) } else emptyList(),
                        cursor = 4,
                        visibilityEpoch = 1,
                    )
                }
                val runner = CloudPullRunner(f.backend, f.data)
                val outcomes = (1..12).map { runner.pullNow().outcome }
                val checked =
                    f.calls
                        .filter { it.first == "cloud_match_event_watermarks_v1" }
                        .map { it.second.getValue("p_match_id").toString() }
                        .toSet()
                println("R1 finite inventory: outcomes=$outcomes, checked=$checked")
                assertEquals(4, checked.size)
                assertTrue(
                    CloudPullOutcome.COMPLETE in outcomes,
                    "Ein endlicher unveränderter Bestand muss nach Wiederaufnahme abschließbar sein",
                )
            }
        }

    @Test
    fun failedFirstMatchMustNotPermanentlyStarveHealthyMatch() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                f.metadata = { cursor, _ ->
                    PullResponse(
                        if (cursor == 0L) listOf(pullMatch("a-conflict", 1), pullMatch("b-healthy", 2)) else emptyList(),
                        cursor = 2,
                        visibilityEpoch = 1,
                    )
                }
                val bad = pullEvent(1, "a-conflict")
                f.data.events.append(EventRows.toStored(bad.copy(wallClockMs = 999).toRow()))
                f.events =
                    { id, after, _ ->
                        if (after ==
                            0L
                        ) {
                            listOf(pullEvent(1, id).copy(id = if (id == "a-conflict") bad.id else "healthy-event"))
                        } else {
                            emptyList()
                        }
                    }
                f.watermarks = { listOf(EventWatermark("remote", 1, 1)) }
                val runner = CloudPullRunner(f.backend, f.data)
                val outcomes = (1..8).map { runner.pullNow().outcome }
                println("R1 conflict fairness: outcomes=$outcomes, healthyEvents=${f.data.events.count("b-healthy")}")
                assertEquals(1L, f.data.events.count("b-healthy"), "Konflikt darf unabhängige dauerhafte Jobs nicht für immer sperren")
            }
        }

    @Test
    fun supportedSinglePageBudgetStillMakesEventProgress() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                f.metadata =
                    { cursor, _ -> PullResponse(if (cursor == 0L) listOf(pullMatch()) else emptyList(), cursor = 1, visibilityEpoch = 1) }
                f.events = { _, after, _ -> if (after == 0L) listOf(pullEvent(1)) else emptyList() }
                f.watermarks = { listOf(EventWatermark("remote", 1, 1)) }
                val runner = CloudPullRunner(f.backend, f.data, maxPagesPerRun = 1)
                val outcomes = (1..8).map { runner.pullNow().outcome }
                println("R1 one-page: outcomes=$outcomes, cursor=${f.data.cloudPulls.eventWork("alice", "match")?.cursor}")
                assertEquals(1L, f.data.events.count("match"))
            }
        }

    @Test
    fun callbackExceptionPreservesCommittedEventsAndNextRunCanVerify() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                f.metadata =
                    { cursor, _ -> PullResponse(if (cursor == 0L) listOf(pullMatch()) else emptyList(), cursor = 1, visibilityEpoch = 1) }
                f.events = { _, after, _ -> if (after == 0L) listOf(pullEvent(1)) else emptyList() }
                f.watermarks = { listOf(EventWatermark("remote", 1, 1)) }
                val runner = CloudPullRunner(f.backend, f.data, eventsCommitted = { error("review callback") })
                assertEquals(CloudPullOutcome.FAILED, runner.pullNow().outcome)
                assertEquals(1L, f.data.events.count("match"))
                assertEquals(
                    1L,
                    f.data.cloudPulls
                        .eventWork("alice", "match")
                        ?.cursor,
                )
                assertEquals(CloudPullOutcome.COMPLETE, runner.pullNow().outcome)
            }
        }
}
