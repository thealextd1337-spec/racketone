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
}
