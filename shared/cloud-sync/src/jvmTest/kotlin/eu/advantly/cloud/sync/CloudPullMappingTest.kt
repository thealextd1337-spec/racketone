package eu.advantly.cloud.sync

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.Presets
import eu.advantly.data.CloudMetadataKind
import eu.advantly.data.CloudMetadataOrigin
import eu.advantly.data.CloudPullEntityKind
import eu.advantly.data.PlayerKind
import eu.advantly.data.metadataState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudPullMappingTest {
    @Test
    fun allServerStatusesAreExplicitAndScheduledSnapshotWaitsForRealEngineMaterialization() =
        runBlocking {
            val engineStatuses =
                mapOf(
                    "scheduled" to null,
                    "in_progress" to eu.advantly.core.scoring.MatchStatus.IN_PROGRESS,
                    "paused" to eu.advantly.core.scoring.MatchStatus.PAUSED,
                    "awaiting_decision" to eu.advantly.core.scoring.MatchStatus.AWAITING_DECISION,
                    "finished" to eu.advantly.core.scoring.MatchStatus.FINISHED,
                )
            for ((server, engine) in engineStatuses) {
                val mapped = PullMapping.metadata(PullResponse(listOf(pullMatch().copy(status = server)), cursor = 1), 0, 200)
                assertEquals(
                    engine,
                    mapped.matches
                        .single()
                        .record
                        ?.status,
                    server,
                )
            }
            for (access in listOf("member", "feed")) {
                assertFailsWith<IllegalArgumentException> {
                    PullMapping.metadata(
                        PullResponse(listOf(pullMatch(access = access).copy(status = "future_status")), cursor = 1),
                        0,
                        200,
                    )
                }
            }
            PullFixture().use { f ->
                f.bind()
                var row = pullMatch().copy(status = "scheduled", scheduledAt = "2026-10-19T12:00:00Z")
                f.metadata = { _, _ -> PullResponse(listOf(row), cursor = row.serverVersion, visibilityEpoch = 1) }
                f.events = { _, after, _ -> if (after == 0L) listOf(pullEvent(1)) else emptyList() }
                f.watermarks = { listOf(EventWatermark("remote", 1, 1)) }
                val runner = CloudPullRunner(f.backend, f.data)
                val scheduled = runner.pullNow()
                assertEquals(CloudPullOutcome.BLOCKED, scheduled.outcome)
                assertEquals(1, scheduled.pendingMaterializations)
                assertNull(f.data.matches.get(MatchId("match")))
                val snapshot = f.data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "match")!!
                assertTrue(snapshot.json.contains("scheduled"))
                assertNull(snapshot.materializedVersion)
                val waiting = f.data.cloudPulls.eventWork("alice", "match")!!
                assertTrue(waiting.materializationPending)
                assertEquals(0, waiting.checkedRound)
                assertEquals(0, waiting.cursor)
                assertFalse(f.calls.any { it.first in setOf("cloud_pull_match_events_v1", "cloud_match_event_watermarks_v1") })
                row = row.copy(status = "in_progress", serverVersion = 2)
                val active = runner.pullNow()
                assertEquals(CloudPullOutcome.COMPLETE, active.outcome)
                assertEquals(0, active.pendingMaterializations)
                assertEquals(1, f.data.events.count("match"))
                assertEquals(
                    eu.advantly.core.scoring.MatchStatus.IN_PROGRESS,
                    f.data.matches
                        .get(MatchId("match"))
                        ?.status,
                )
                assertEquals(
                    CloudMetadataOrigin.CLOUD,
                    f.data.cloud
                        .metadataState(CloudMetadataKind.HEADER, "match")
                        ?.origin,
                )
                assertNull(
                    f.data.cloud
                        .metadataState(CloudMetadataKind.HEADER, "match")
                        ?.writerProfileId,
                )
                assertEquals(
                    2,
                    f.data.cloudPulls
                        .snapshot("alice", CloudPullEntityKind.MATCH, "match")
                        ?.materializedVersion,
                )
            }
        }

    @Test
    fun scheduledSnapshotNeverReplacesExistingLocalEngineStatus() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                val record =
                    PullMapping
                        .metadata(PullResponse(listOf(pullMatch()), cursor = 1), 0, 200)
                        .matches
                        .single()
                        .record!!
                f.data.matches.insertIfAbsent(record.id, record.format, record.lineup, record.meta, 1)
                f.data.matches.updateProgress(record.id, eu.advantly.core.scoring.MatchStatus.PAUSED, record.score, 1)
                f.driver.execute(null, "UPDATE cloud_metadata SET origin='LOCAL', source_device_id='original' WHERE entity_id='match'", 0)
                val before = f.data.cloud.metadataState(CloudMetadataKind.HEADER, "match")
                f.metadata = { _, _ -> PullResponse(listOf(pullMatch().copy(status = "scheduled")), cursor = 1, visibilityEpoch = 1) }
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                assertEquals(
                    eu.advantly.core.scoring.MatchStatus.PAUSED,
                    f.data.matches
                        .get(record.id)
                        ?.status,
                )
                assertEquals(before, f.data.cloud.metadataState(CloudMetadataKind.HEADER, "match"))
                assertNull(
                    f.data.cloudPulls
                        .snapshot("alice", CloudPullEntityKind.MATCH, "match")
                        ?.materializedVersion,
                )
            }
        }

    @Test
    fun legacyFormatFailsStrictlyButFeedSummaryRemainsRawAndNeverCreatesEventWork() =
        runBlocking {
            val legacy =
                pullMatch().copy(
                    format = JsonObject(mapOf("sport" to JsonPrimitive("TENNIS"), "discipline" to JsonPrimitive("SINGLES"))),
                )
            assertFailsWith<IllegalArgumentException> { PullMapping.metadata(PullResponse(listOf(legacy), cursor = 1), 0, 200) }
            PullFixture().use { f ->
                f.bind()
                val feed = legacy.copy(access = "feed", summary = JsonObject(mapOf("score" to JsonPrimitive("6:3"))))
                f.metadata = { _, _ -> PullResponse(listOf(feed), cursor = 1, visibilityEpoch = 1) }
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                assertNull(f.data.matches.get(MatchId("match")))
                assertNull(f.data.cloudPulls.eventWork("alice", "match"))
                assertTrue(
                    f.data.cloudPulls
                        .snapshot("alice", CloudPullEntityKind.MATCH, "match")!!
                        .json
                        .contains("6:3"),
                )
            }
        }

    @Test
    fun slotsAreNotPlayerOrProfileAliasesAndCloudSelfNeverAdoptsLocalIdentity() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                f.data.players.ensureSelf("Local", 1) { PlayerId("local-self") }
                val row =
                    pullMatch().copy(
                        participants =
                            listOf(
                                PulledParticipant("slot-b", "B", 1, "person-b", "profile-b", displayName = "B"),
                                PulledParticipant("slot-a", "A", 1, "person-a", "profile-a", displayName = "A"),
                            ),
                    )
                f.metadata =
                    {
                        _,
                        _,
                        ->
                        PullResponse(
                            listOf(row),
                            players = listOf(PulledPlayer("cloud-self", "self", "Remote", true, serverVersion = 2)),
                            cursor = 2,
                            visibilityEpoch = 1,
                        )
                    }
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                val record = assertNotNull(f.data.matches.get(MatchId("match")))
                assertEquals(listOf(PlayerId("slot-a")), record.lineup.a)
                assertEquals(listOf(PlayerId("slot-b")), record.lineup.b)
                assertEquals(
                    "local-self",
                    f.data.players
                        .self()
                        ?.id
                        ?.value,
                )
                assertEquals(
                    PlayerKind.GUEST,
                    f.data.players
                        .get(PlayerId("cloud-self"))
                        ?.kind,
                )
                assertTrue(f.data.selfAliases().isEmpty())
                assertEquals(
                    "alice",
                    f.data.cloud
                        .metadataState(CloudMetadataKind.PLAYER, "cloud-self")
                        ?.writerProfileId,
                )
                assertNull(
                    f.data.cloud
                        .metadataState(CloudMetadataKind.HEADER, "match")
                        ?.writerProfileId,
                )
            }
        }

    @Test
    fun ownEventSourceCannotPromoteForeignDownloadAndDeviceAcksSurviveDuplicates() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                f.data.accountIdentities.initialize("phone", "self", "PHONE")
                val device = DeviceId("phone")
                val self = PlayerId("self")
                val format = Presets.TENNIS_SINGLES_BO3.format()
                val genesis =
                    pullEvent(1).copy(
                        deviceId = device.value,
                        type = "MatchCreated",
                        payload =
                            CloudJson
                                .encodeToJsonElement(
                                    EventPayload.serializer(),
                                    MatchCreated(
                                        format,
                                        Lineup(listOf(self), listOf(PlayerId("other")), self),
                                        scorerDeviceId = device,
                                        createdBy = self,
                                    ),
                                ).jsonObject,
                    )
                f.metadata =
                    { cursor, _ -> PullResponse(if (cursor == 0L) listOf(pullMatch()) else emptyList(), cursor = 1, visibilityEpoch = 1) }
                f.events = { _, after, _ -> if (after == 0L) listOf(genesis) else emptyList() }
                f.watermarks = { listOf(EventWatermark(device.value, 1, 1)) }
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                val state = f.data.cloud.metadataState(CloudMetadataKind.HEADER, "match")!!
                assertEquals(CloudMetadataOrigin.CLOUD, state.origin)
                assertNull(state.sourceDeviceId)
                assertNull(state.writerProfileId)
                assertEquals(1, f.data.events.unconfirmedCount())
                f.data.events.markConfirmedUpTo("match", device.value, 1)
                f.data.cloud.saveCursor(
                    "alice",
                    eu.advantly.data.CloudPullStore
                        .eventStream("match"),
                    0,
                )
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                assertEquals(0, f.data.events.unconfirmedCount())
            }
        }

    @Test
    fun downgradeAndObservedRedactionRemainPendingWithoutDeletingOrOverwritingEvents() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                var epoch = 1L
                var row = pullMatch()
                f.metadata = { _, _ -> PullResponse(listOf(row), cursor = 1, visibilityEpoch = epoch) }
                f.events = { _, after, _ -> if (after == 0L) listOf(pullEvent(1)) else emptyList() }
                f.watermarks = { listOf(EventWatermark("remote", 1, 1)) }
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                val original = f.data.events.eventsFor("match")
                epoch = 2
                row = row.copy(access = "feed", redactionEpoch = 2)
                val result = CloudPullRunner(f.backend, f.data).pullNow()
                assertEquals(CloudPullOutcome.BLOCKED, result.outcome)
                assertEquals(1, result.pendingRemovals)
                assertEquals(1, result.pendingRedactions)
                val snapshot = f.data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "match")!!
                assertEquals("feed", snapshot.access)
                assertEquals(2, snapshot.redactionObserved)
                assertEquals(0, snapshot.redactionApplied)
                assertEquals(original, f.data.events.eventsFor("match"))
                assertTrue(
                    f.data.matches
                        .deleted()
                        .isEmpty(),
                )
            }
        }

    @Test
    fun validScheduledServerRowMustNotPoisonMetadataPage() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                f.metadata =
                    {
                        _,
                        _,
                        ->
                        PullResponse(
                            listOf(pullMatch().copy(status = "scheduled", scheduledAt = "2026-10-19T12:00:00Z")),
                            cursor = 1,
                            visibilityEpoch = 1,
                        )
                    }
                val result = CloudPullRunner(f.backend, f.data).pullNow()
                println(
                    "R1 scheduled: outcome=${result.outcome}, failure=${result.failure}, cursor=${f.data.cloudPulls.progress(
                        "alice",
                    ).cursor}",
                )
                assertNotNull(
                    f.data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "match"),
                    "Gültigen Serverzustand vollständig als Snapshot aufbewahren",
                )
                assertEquals(
                    1L,
                    f.data.cloudPulls
                        .progress("alice")
                        .cursor,
                )
            }
        }
}
