package eu.advantly.data

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun pullRecord(
    id: String = "match",
    venue: String = "Cloud",
): MatchRecord =
    MatchRecord(
        MatchId(id),
        Presets.TENNIS_SINGLES_BO3.format(),
        Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a")),
        MatchMeta(venue = venue),
        1,
        1,
        MatchStatus.IN_PROGRESS,
        ScoreSummary.EMPTY,
        null,
    )

internal fun storePage(
    record: MatchRecord = pullRecord(),
    version: Long = 1,
    cursor: Long = version,
    epoch: Long = 1,
): CloudPullMetadataPage =
    CloudPullMetadataPage(
        listOf(CloudPullMatch(record.id.value, version, """{"version":$version}""", "member", 1, 0, false, record)),
        emptyList(),
        emptyList(),
        cursor,
        false,
        epoch,
    )

class CloudPullStoreTest {
    @Test
    fun attemptAndFailureFencesRollbackWithoutAcknowledgingUnverifiedWork() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver)
            val first = data.cloudPulls.beginRun("alice") {}
            data.cloudPulls.commitMetadata("alice", first.runSerial, 0, storePage()) {}
            val original = data.cloudPulls.eventWork("alice", "match")!!
            assertFailsWith<IllegalStateException> { data.cloudPulls.finishRound("alice", first.runSerial) {} }
            var fences = 0
            assertFailsWith<IllegalStateException> {
                data.cloudPulls.claimEventWork(original, first.runSerial) { check(++fences == 1) }
            }
            assertEquals(original, data.cloudPulls.eventWork("alice", "match"))
            val claimed = data.cloudPulls.claimEventWork(original, first.runSerial) {}
            assertEquals(first.runSerial, claimed.lastAttempt)
            assertEquals(0, claimed.checkedRound)
            fences = 0
            assertFailsWith<IllegalStateException> {
                data.cloudPulls.recordEventFailure("alice", "match", first.runSerial, false) { check(++fences == 1) }
            }
            assertEquals(claimed, data.cloudPulls.eventWork("alice", "match"))
            data.cloudPulls.recordEventFailure("alice", "match", first.runSerial, false) {}
            val failed = data.cloudPulls.eventWork("alice", "match")!!
            assertEquals(0, failed.checkedRound)
            assertEquals(0, failed.cursor)
            assertTrue(data.cloudPulls.receivedWatermarks(failed).isEmpty())
            assertEquals(1, data.cloudPulls.failedEventJobs("alice"))
            data.cloudPulls.finishRound("alice", first.runSerial) {}
            val second = data.cloudPulls.beginRun("alice") {}
            assertEquals(first.checkRound + 1, second.checkRound)
            assertEquals(null, data.cloudPulls.nextEventWork("alice", second.runSerial))
            data.cloudPulls.commitMetadata("alice", second.runSerial, 1, storePage()) {}
            val retry = data.cloudPulls.claimEventWork(data.cloudPulls.nextEventWork("alice", second.runSerial)!!, second.runSerial) {}
            assertNotNull(retry.failure)
            assertEquals(0, retry.checkedRound)
            assertTrue(data.cloudPulls.verifyEvents(retry, second.runSerial, emptyList()) {})
            assertEquals(0, data.cloudPulls.failedEventJobs("alice"))
            assertEquals(second.checkRound, data.cloudPulls.eventWork("alice", "match")?.checkedRound)
        }
    }

    @Test
    fun downloadedAssignmentProjectionStaysCleanOnlyForCleanCloudMaterializationOfReceivingProfile() {
        for (mode in listOf("clean", "dirty", "local", "peer", "other-profile")) {
            createInMemoryJvmDriver().use { driver ->
                val data = LocalData(driver)
                val record =
                    pullRecord().copy(
                        lineup = Lineup(listOf(PlayerId("a")), listOf(PlayerId("placeholder:slot")), PlayerId("a")),
                    )
                if (mode == "other-profile") {
                    val original = data.cloudPulls.beginRun("bob") {}.runSerial
                    data.cloudPulls.commitMetadata("bob", original, 0, storePage(record)) {}
                }
                val run = data.cloudPulls.beginRun("alice") {}.runSerial
                data.cloudPulls.commitMetadata("alice", run, 0, storePage(record)) {}
                if (mode == "dirty") {
                    driver.execute(
                        null,
                        "UPDATE cloud_metadata SET revision=revision+1 WHERE kind='PARTICIPANTS' AND entity_id='match'",
                        0,
                    )
                }
                if (mode in listOf("local", "peer")) {
                    driver.execute(
                        null,
                        "UPDATE cloud_metadata SET origin='${mode.uppercase()}', source_device_id='original' WHERE kind='PARTICIPANTS'",
                        0,
                    )
                }
                data.players.addGuest(PlayerId("unrelated"), "Local", 1)
                data.events.append(
                    StoredEvent(
                        "unrelated",
                        "elsewhere",
                        "local",
                        1,
                        1,
                        """{"type":"PointWon","side":"B"}""",
                        type = "PointWon",
                        confirmed = true,
                    ),
                )
                data.cloud.acknowledge(emptySet(), mapOf("unrelated" to "forbidden"))
                val untouched =
                    listOf(CloudMetadataKind.HEADER, CloudMetadataKind.PROJECTION, CloudMetadataKind.PLAYER)
                        .associateWith { data.cloud.metadataState(it, if (it == CloudMetadataKind.PLAYER) "unrelated" else "match") }
                val before = data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")!!
                val genesis =
                    StoredEvent(
                        "genesis",
                        "match",
                        "remote",
                        1,
                        1,
                        DataJson.encodeToString(EventPayload.serializer(), MatchCreated(record.format, record.lineup)),
                        type = "MatchCreated",
                    )
                data.cloudPulls.commitEvents(data.cloudPulls.eventWork("alice", "match")!!, run, listOf(CloudPullReceipt(genesis, 1)), {})
                val after = data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")!!
                assertTrue(after.selected.revision > before.selected.revision, mode)
                assertEquals(if (mode == "clean") after.selected.revision else before.uploadedRevision, after.uploadedRevision, mode)
                assertEquals(before.origin, after.origin, mode)
                assertEquals(before.sourceDeviceId, after.sourceDeviceId, mode)
                assertEquals(before.writerProfileId, after.writerProfileId, mode)
                untouched.forEach { (kind, state) ->
                    assertEquals(
                        state,
                        data.cloud.metadataState(
                            kind,
                            if (kind ==
                                CloudMetadataKind.PLAYER
                            ) {
                                "unrelated"
                            } else {
                                "match"
                            },
                        ),
                        mode,
                    )
                }
                assertEquals(1, data.events.unconfirmedCount(), mode)
                assertEquals(mapOf("unrelated" to "forbidden"), data.cloud.rejectedEvents(), mode)
                data.cloudPulls.commitMetadata("alice", run, 1, storePage(pullRecord(venue = "Next"), 2)) {}
                assertEquals(
                    if (mode == "clean") "Next" else "Cloud",
                    data.matches
                        .get(record.id)
                        ?.meta
                        ?.venue,
                    mode,
                )
            }
        }
    }

    @Test
    fun danglingReceiptCannotConcealMissingPayloadAndRepairRestoresIt() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver)
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            data.cloudPulls.commitMetadata("alice", run, 0, storePage()) {}
            val receipt =
                CloudPullReceipt(
                    StoredEvent(
                        "e",
                        "match",
                        "remote",
                        1,
                        1,
                        """{"type":"PointWon","side":"A"}""",
                        type = "PointWon",
                    ),
                    9,
                )
            data.cloudPulls.commitEvents(data.cloudPulls.eventWork("alice", "match")!!, run, listOf(receipt), {})
            driver.execute(null, "DELETE FROM match_event WHERE id='e'", 0)
            val old = data.cloudPulls.eventWork("alice", "match")!!
            assertTrue(data.cloudPulls.receivedWatermarks(old).isEmpty())
            assertEquals(false, data.cloudPulls.verifyEvents(old, run, listOf(CloudPullWatermark("remote", 1, 1)), {}))
            val repair = data.cloudPulls.eventWork("alice", "match")!!
            assertTrue(repair.repairing)
            assertEquals(0, repair.cursor)
            data.cloudPulls.commitEvents(repair, run, listOf(receipt), {})
            val complete = data.cloudPulls.eventWork("alice", "match")!!
            assertTrue(data.cloudPulls.verifyEvents(complete, run, listOf(CloudPullWatermark("remote", 1, 1)), {}))
            assertEquals(1, data.events.count("match"))
        }
    }

    @Test
    fun finalInvalidMatchOrPlayerRollsBackEntirePageAndDurableJobs() {
        for (invalidPlayer in listOf(true, false)) {
            createInMemoryJvmDriver().use { driver ->
                val data = LocalData(driver)
                val run = data.cloudPulls.beginRun("alice") {}.runSerial
                val valid = storePage()
                val invalid =
                    if (invalidPlayer) {
                        valid.copy(
                            players =
                                listOf(
                                    CloudPullPlayer(
                                        "good",
                                        2,
                                        "{}",
                                        false,
                                        LocalPlayer(PlayerId("good"), "Good", PlayerKind.GUEST, 1, null),
                                    ),
                                    CloudPullPlayer(
                                        "bad",
                                        3,
                                        "not-json",
                                        false,
                                        LocalPlayer(PlayerId("bad"), "Bad", PlayerKind.GUEST, 1, null),
                                    ),
                                ),
                        )
                    } else {
                        valid.copy(matches = valid.matches + valid.matches.single().copy(id = "", serverVersion = 2))
                    }
                assertFailsWith<IllegalArgumentException> { data.cloudPulls.commitMetadata("alice", run, 0, invalid) {} }
                assertEquals(0, data.cloudPulls.progress("alice").cursor)
                assertNull(data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "match"))
                assertNull(data.cloudPulls.snapshot("alice", CloudPullEntityKind.PLAYER, "good"))
                assertNull(data.cloudPulls.eventWork("alice", "match"))
                assertNull(data.matches.get(MatchId("match")))
                assertNull(data.players.get(PlayerId("good")))
            }
        }
    }

    @Test
    fun fenceAtTransactionEndRollsBackSnapshotsAndCursor() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver)
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            var checks = 0
            assertFailsWith<IllegalStateException> {
                data.cloudPulls.commitMetadata("alice", run, 0, storePage()) {
                    checks++
                    check(checks == 1)
                }
            }
            assertEquals(2, checks)
            assertEquals(0, data.cloudPulls.progress("alice").cursor)
            assertNull(data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "match"))
            assertNull(data.cloudPulls.eventWork("alice", "match"))
        }
    }

    @Test
    fun versionsAreMonotoneCloudUpdatesAreMaterializedAndLocalChangesKeepTheirOwnContent() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver)
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            data.cloudPulls.commitMetadata("alice", run, 0, storePage()) {}
            data.cloudPulls.commitMetadata("alice", run, 1, storePage(pullRecord(venue = "New"), 2)) {}
            assertEquals(
                "New",
                data.matches
                    .get(MatchId("match"))
                    ?.meta
                    ?.venue,
            )
            data.cloudPulls.commitMetadata("alice", run, 2, storePage(pullRecord(venue = "Old"), 1, cursor = 2)) {}
            assertEquals(
                "New",
                data.matches
                    .get(MatchId("match"))
                    ?.meta
                    ?.venue,
            )
            assertEquals(2, data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "match")?.serverVersion)
            data.matches.updateMeta(MatchId("match"), MatchMeta(venue = "Local edit"))
            data.cloudPulls.commitMetadata("alice", run, 2, storePage(pullRecord(venue = "Third"), 3)) {}
            assertEquals(
                "Local edit",
                data.matches
                    .get(MatchId("match"))
                    ?.meta
                    ?.venue,
            )
            val snapshot = assertNotNull(data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "match"))
            assertEquals(3, snapshot.serverVersion)
            assertEquals(2, snapshot.materializedVersion)
            assertEquals(CloudMetadataOrigin.CLOUD, data.cloud.metadataState(CloudMetadataKind.HEADER, "match")?.origin)
        }
    }

    @Test
    fun localPeerAndOtherAccountMaterializationsAreNeverReplaced() {
        for (origin in listOf("LOCAL", "PEER", "UNKNOWN")) {
            createInMemoryJvmDriver().use { driver ->
                val data = LocalData(driver)
                val local = pullRecord(venue = origin)
                data.matches.insertIfAbsent(local.id, local.format, local.lineup, local.meta, 1)
                driver.execute(null, "UPDATE cloud_metadata SET origin='$origin', source_device_id='original'", 0)
                val before = data.cloud.metadataState(CloudMetadataKind.HEADER, "match")
                val run = data.cloudPulls.beginRun("alice") {}.runSerial
                data.cloudPulls.commitMetadata("alice", run, 0, storePage()) {}
                assertEquals(
                    origin,
                    data.matches
                        .get(local.id)
                        ?.meta
                        ?.venue,
                )
                assertEquals(before, data.cloud.metadataState(CloudMetadataKind.HEADER, "match"))
                assertNull(data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "match")?.materializedVersion)
            }
        }
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver)
            val alice = data.cloudPulls.beginRun("alice") {}.runSerial
            data.cloudPulls.commitMetadata("alice", alice, 0, storePage()) {}
            val bob = data.cloudPulls.beginRun("bob") {}.runSerial
            data.cloudPulls.commitMetadata("bob", bob, 0, storePage(pullRecord(venue = "Bob"), 2)) {}
            assertEquals(
                "Cloud",
                data.matches
                    .get(MatchId("match"))
                    ?.meta
                    ?.venue,
            )
            assertNull(data.cloudPulls.snapshot("bob", CloudPullEntityKind.MATCH, "match")?.materializedVersion)
            assertEquals(1, data.cloudPulls.progress("alice").cursor)
            assertEquals(2, data.cloudPulls.progress("bob").cursor)
        }
    }

    @Test
    fun receiptAndEventCursorRollbackOnFinalFenceAndCallbacksOnlyFollowCommit() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver)
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            data.cloudPulls.commitMetadata("alice", run, 0, storePage()) {}
            val work = assertNotNull(data.cloudPulls.eventWork("alice", "match"))
            val receipt =
                CloudPullReceipt(StoredEvent("e", "match", "remote", 1, 1, """{"type":"PointWon","side":"A"}""", type = "PointWon"), 9)
            var notifications = 0
            var checks = 0
            assertFailsWith<IllegalStateException> {
                data.cloudPulls.commitEvents(work, run, listOf(receipt), { check(++checks == 1) }) { notifications++ }
            }
            assertEquals(0, data.events.count("match"))
            assertEquals(work, data.cloudPulls.eventWork("alice", "match"))
            assertTrue(data.cloudPulls.receivedWatermarks(work).isEmpty())
            assertEquals(0, notifications)
            data.cloudPulls.commitEvents(work, run, listOf(receipt), {}) {
                assertEquals(9, data.cloudPulls.eventWork("alice", "match")?.cursor)
                assertEquals(1, data.events.count("match"))
                notifications++
            }
            assertEquals(1, notifications)
        }
    }
}
