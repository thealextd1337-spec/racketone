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

/** D1 bindet jeden Lauf an den ausgewählten Replikatkontext; das Cloudprofil ist dessen Kontoschlüssel. */
internal fun LocalData.selectPullAccount(profile: String = "alice"): LocalData {
    var index = 0
    accountIdentities.select("scope-$profile", profile, 0, "PHONE") { "$profile-device-${index++}" }
    return this
}

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
    fun fenceAfterPartialReceiptWritesRollsBackEventsCloudAcksAndCursorTogether() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount()
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            data.cloudPulls.commitMetadata("alice", run, 0, storePage()) {}
            val first =
                StoredEvent("first", "match", "remote", 1, 1, """{"type":"PointWon","side":"A"}""", type = "PointWon", confirmed = true)
            val second = first.copy(id = "second", deviceSeq = 2, lamport = 2, confirmed = false)
            val third = first.copy(id = "third", deviceSeq = 3, lamport = 3, confirmed = false)
            data.events.appendAll(listOf(first, second))
            data.cloud.acknowledge(emptySet(), mapOf("second" to "forbidden"))
            val work = data.cloudPulls.eventWork("alice", "match")!!
            val metadata = data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")
            val page = listOf(first, second, third).mapIndexed { index, event -> CloudPullReceipt(event, index + 1L) }
            var notifications = 0
            var reachedReceipts = false
            assertFailsWith<IllegalStateException> {
                data.cloudPulls.commitEvents(work, run, page, {
                    val stored = data.cloudPulls.receivedWatermarks(work).sumOf { it.eventCount }
                    if (stored == 2L) reachedReceipts = true
                    check(stored < 2)
                }) { notifications++ }
            }
            assertTrue(reachedReceipts)
            assertEquals(work, data.cloudPulls.eventWork("alice", "match"))
            assertEquals(listOf(first.copy(confirmed = false), second), data.events.eventsFor("match"))
            assertEquals(listOf(second), data.events.unconfirmedEvents("match"))
            assertEquals(1, data.cloud.pendingCount())
            assertEquals(mapOf("second" to "forbidden"), data.cloud.rejectedEvents())
            assertEquals(metadata, data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match"))
            assertTrue(data.cloudPulls.receivedWatermarks(work).isEmpty())
            assertEquals(0, notifications)
            data.cloudPulls.commitEvents(work, run, page, {}) { notifications++ }
            assertEquals(3, data.cloudPulls.eventWork("alice", "match")!!.cursor)
            assertEquals(3, data.cloudPulls.receivedWatermarks(work).sumOf { it.eventCount })
            assertEquals(listOf(second, third), data.events.unconfirmedEvents("match"))
            assertEquals(0, data.cloud.pendingCount())
            assertTrue(data.cloud.rejectedEvents().isEmpty())
            assertEquals(1, notifications)
        }
    }

    @Test
    fun metadataRefreshPreservesRepairsReceiptsFailuresAndChecksExceptChangedMatch() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount()
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            val original = storePage()
            val matches =
                listOf("stable", "changed", "repair", "failed", "scheduled").map { id ->
                    original.matches.single().copy(id = id, record = if (id == "scheduled") null else pullRecord(id))
                }
            data.cloudPulls.commitMetadata("alice", run, 0, original.copy(matches = matches)) {}
            for (id in listOf("stable", "changed")) {
                assertTrue(data.cloudPulls.verifyEvents(data.cloudPulls.eventWork("alice", id)!!, run, emptyList()) {})
            }
            val receipt =
                CloudPullReceipt(
                    StoredEvent("received", "repair", "remote", 1, 1, """{"type":"PointWon","side":"A"}""", type = "PointWon"),
                    1,
                )
            data.cloudPulls.commitEvents(data.cloudPulls.eventWork("alice", "repair")!!, run, listOf(receipt), {})
            assertTrue(
                !data.cloudPulls.verifyEvents(
                    data.cloudPulls.eventWork("alice", "repair")!!,
                    run,
                    listOf(CloudPullWatermark("remote", 2, 2)),
                    {},
                ),
            )
            data.cloudPulls.recordEventFailure("alice", "failed", run, false) {}
            val before = matches.associate { it.id to data.cloudPulls.eventWork("alice", it.id)!! }
            repeat(12) { data.cloudPulls.recordEventRequest(before.getValue("repair"), run) {} }
            val refreshed = matches.map { if (it.id == "changed") it.copy(serverVersion = 2, json = """{"version":2}""") else it }
            data.cloudPulls.commitMetadata("alice", run, 1, original.copy(matches = refreshed, cursor = 2, hasMore = true)) {}
            for (id in listOf("stable", "repair", "failed", "scheduled")) {
                assertEquals(before.getValue(id), data.cloudPulls.eventWork("alice", id), id)
            }
            assertEquals(before.getValue("changed").copy(checkedRound = 0), data.cloudPulls.eventWork("alice", "changed"))
            val state = data.cloudPulls.progress("alice")
            assertEquals(1, state.checkRound)
            assertTrue(state.roundCatalogReady)
            assertTrue(!state.metadataComplete)
            assertEquals(0, state.eventRequestsSinceMetadata)
            assertEquals(1, data.cloudPulls.failedEventJobs("alice"))
            assertEquals(1, data.cloudPulls.pendingMaterializations("alice"))
            val repair = data.cloudPulls.eventWork("alice", "repair")!!
            assertTrue(repair.repairing)
            assertEquals(0, repair.cursor)
            assertEquals(1, data.events.count("repair"))
            assertEquals(listOf(CloudPullWatermark("remote", 1, 1)), data.cloudPulls.receivedWatermarks(repair.copy(repairing = false)))
            assertTrue(data.cloudPulls.receivedWatermarks(repair).isEmpty())
            assertNotNull(data.cloudPulls.nextEventWork("alice", run))
            assertFailsWith<IllegalStateException> { data.cloudPulls.finishRound("alice", run) {} }
        }
    }

    @Test
    fun requestQuotaAndMetadataResetAreFencedAndEpochRestartKeepsMetadataDue() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount()
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            data.cloudPulls.commitMetadata("alice", run, 0, storePage()) {}
            val work = data.cloudPulls.eventWork("alice", "match")!!
            repeat(11) { data.cloudPulls.recordEventRequest(work, run) {} }
            val before = data.cloudPulls.progress("alice")
            var fences = 0
            assertFailsWith<IllegalStateException> {
                data.cloudPulls.recordEventRequest(work, run) { check(++fences == 1) }
            }
            assertEquals(before, data.cloudPulls.progress("alice"))
            data.cloudPulls.recordEventRequest(work, run) {}
            assertEquals(12, data.cloudPulls.progress("alice").eventRequestsSinceMetadata)
            assertFailsWith<IllegalStateException> { data.cloudPulls.recordEventRequest(work, run) {} }
            val due = data.cloudPulls.progress("alice")
            fences = 0
            assertFailsWith<IllegalStateException> {
                data.cloudPulls.commitMetadata("alice", run, 1, storePage(version = 2)) { check(++fences == 1) }
            }
            assertEquals(due, data.cloudPulls.progress("alice"))
            assertEquals(work, data.cloudPulls.eventWork("alice", "match"))
            assertTrue(!data.cloudPulls.commitMetadata("alice", run, 1, storePage(version = 2, epoch = 2)) {})
            val scan = data.cloudPulls.progress("alice")
            assertEquals(0, scan.cursor)
            assertEquals(12, scan.eventRequestsSinceMetadata)
            assertEquals(1, scan.visibilityEpoch)
            assertEquals(2, scan.targetEpoch)
            assertTrue(!scan.metadataComplete && scan.roundCatalogReady)
            data.cloudPulls.commitMetadata("alice", run, 0, storePage(version = 2, epoch = 2).copy(hasMore = true)) {}
            assertEquals(0, data.cloudPulls.progress("alice").eventRequestsSinceMetadata)
            assertEquals(2, data.cloudPulls.progress("alice").targetEpoch)
            assertTrue(!data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "match")!!.removalPending)
            assertNotNull(data.cloudPulls.nextEventWork("alice", run))
            assertFailsWith<IllegalStateException> { data.cloudPulls.finishRound("alice", run) {} }
            data.cloudPulls.recordEventRequest(data.cloudPulls.eventWork("alice", "match")!!, run) {}
            assertEquals(1, data.cloudPulls.progress("alice").eventRequestsSinceMetadata)
        }
    }

    @Test
    fun attemptAndFailureFencesRollbackWithoutAcknowledgingUnverifiedWork() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount()
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
                val data = LocalData(driver).selectPullAccount()
                val record =
                    pullRecord().copy(
                        lineup = Lineup(listOf(PlayerId("a")), listOf(PlayerId("placeholder:slot")), PlayerId("a")),
                    )
                if (mode == "other-profile") {
                    val original =
                        data
                            .selectPullAccount("bob")
                            .cloudPulls
                            .beginRun("bob") {}
                            .runSerial
                    data.cloudPulls.commitMetadata("bob", original, 0, storePage(record)) {}
                    data.selectPullAccount()
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
            val data = LocalData(driver).selectPullAccount()
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
                val data = LocalData(driver).selectPullAccount()
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
            val data = LocalData(driver).selectPullAccount()
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
    fun matchCreatedByReadsServerCreatorFromPulledSnapshotOnly() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount()
            val run = data.cloudPulls.beginRun("alice") {}.runSerial

            fun commit(
                version: Long,
                json: String,
            ) = data.cloudPulls.commitMetadata(
                "alice",
                run,
                version - 1,
                CloudPullMetadataPage(
                    listOf(CloudPullMatch("match", version, json, "member", 1, 0, false, pullRecord())),
                    emptyList(),
                    emptyList(),
                    version,
                    false,
                    1,
                ),
            ) {}
            assertNull(data.cloudPulls.matchCreatedBy("alice", "match"))
            commit(1, """{"version":1}""")
            assertNull(data.cloudPulls.matchCreatedBy("alice", "match"))
            commit(2, """{"version":2,"createdBy":42}""")
            assertNull(data.cloudPulls.matchCreatedBy("alice", "match"))
            commit(3, """{"version":3,"createdBy":"alice"}""")
            assertEquals("alice", data.cloudPulls.matchCreatedBy("alice", "match"))
            assertNull(data.cloudPulls.matchCreatedBy("bob", "match"))
            assertNull(data.cloudPulls.matchCreatedBy("alice", "other"))
        }
    }

    @Test
    fun versionsAreMonotoneCloudUpdatesAreMaterializedAndLocalChangesKeepTheirOwnContent() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount()
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
                val data = LocalData(driver).selectPullAccount()
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
            val data = LocalData(driver).selectPullAccount()
            val alice = data.cloudPulls.beginRun("alice") {}.runSerial
            data.cloudPulls.commitMetadata("alice", alice, 0, storePage()) {}
            val bob =
                data
                    .selectPullAccount("bob")
                    .cloudPulls
                    .beginRun("bob") {}
                    .runSerial
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
            val data = LocalData(driver).selectPullAccount()
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

    @Test
    fun lostEventWorkIsRecreatedForUnchangedSnapshotInSameScan() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount()
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            assertTrue(data.cloudPulls.commitMetadata("alice", run, 0, storePage()) {})
            // Etwa ein Generationswechsel außerhalb dieses Scans hat die Eventarbeit entfernt; der Snapshot blieb gleich.
            driver.execute(null, "DELETE FROM cloud_pull_event_work", 0)
            assertTrue(data.cloudPulls.commitMetadata("alice", run, 1, storePage(cursor = 2)) {})
            val work = assertNotNull(data.cloudPulls.eventWork("alice", "match"))
            assertEquals(0, work.checkedRound)
        }
    }

    @Test
    fun participantAckInNotReadyMemberWindowNeedsOwnMaterializationProof() {
        for (revoked in listOf(false, true)) {
            createInMemoryJvmDriver().use { driver ->
                val data = LocalData(driver).selectPullAccount()
                val record =
                    pullRecord().copy(
                        lineup = Lineup(listOf(PlayerId("a")), listOf(PlayerId("placeholder:slot")), PlayerId("a")),
                    )
                val run = data.cloudPulls.beginRun("alice") {}.runSerial
                data.cloudPulls.commitMetadata("alice", run, 0, storePage(record)) {}
                val ctx = data.replicas.captureContext()
                data.replicas.markReady(ctx, "match", 1, 0) {}
                // Neue Sichtbarkeitsepoche: zuerst Neustart des Scans, dann derselbe Snapshot bei G = 2.
                val next =
                    storePage(record, cursor = 2, epoch = 2).let {
                        if (revoked) it.copy(matches = it.matches.map { m -> m.copy(access = "feed", record = null) }) else it
                    }
                assertTrue(!data.cloudPulls.commitMetadata("alice", run, 1, next) {})
                assertTrue(data.cloudPulls.commitMetadata("alice", run, 0, next) {})
                val access = assertNotNull(data.replicas.access(ctx, "match"))
                assertEquals(2, access.generation)
                assertTrue(!access.ready)
                if (revoked) {
                    assertEquals(ReplicaAccessLevel.FEED, access.access)
                    assertNull(data.cloudPulls.eventWork("alice", "match"))
                    assertNull(data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "match")?.materializedVersion)
                    return@use
                }
                assertEquals(1, data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "match")?.materializedVersion)
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
                // Die Projektion dieser Serverevents bleibt sauber, obwohl ready bei G = 2 noch fehlt.
                val after = data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")!!
                assertTrue(after.selected.revision > before.selected.revision)
                assertEquals(after.selected.revision, after.uploadedRevision)
                assertTrue(!data.replicas.access(ctx, "match")!!.ready)
            }
        }
    }

    @Test
    fun fullRescanDropsFriendshipsTheServerNoLongerDeliversButKeepsUnchangedContent() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount()
            val run = data.cloudPulls.beginRun("alice") {}.runSerial

            fun friendship(
                id: String,
                alias: String,
            ) = CloudPullFriendship(id, 1, """{"profileId":"$id","alias":"$alias","status":"accepted","outgoing":true,"serverVersion":1}""")
            val page = storePage().copy(friendships = listOf(friendship("bob", "Bob"), friendship("carol", "Carol")))
            data.cloudPulls.commitMetadata("alice", run, 0, page) {}
            assertEquals(listOf("bob", "carol"), data.cloudPulls.friendships("alice").map { it.id })
            // Delta ohne Epochenwechsel entfernt nichts.
            data.cloudPulls.commitMetadata("alice", run, 1, storePage(version = 2).copy(friendships = emptyList())) {}
            assertEquals(2, data.cloudPulls.friendships("alice").size)
            // Entfreunden erhöht die Sichtbarkeits-Epoche: Vollabgleich ab null liefert nur noch Bob (gleiche Version).
            assertTrue(!data.cloudPulls.commitMetadata("alice", run, 2, storePage(version = 3, epoch = 2)) {})
            data.cloudPulls.commitMetadata(
                "alice",
                run,
                0,
                storePage(version = 3, epoch = 2).copy(friendships = listOf(friendship("bob", "Changed"))),
            ) {}
            val left = data.cloudPulls.friendships("alice")
            assertEquals(listOf("bob"), left.map { it.id })
            assertTrue("\"Bob\"" in left.single().json)
            assertTrue(data.cloudPulls.friendships("other").isEmpty())
        }
    }

    @Test
    fun feedListsOnlyThisAccountsCurrentFeedSnapshotsAndDropsThemAfterARescan() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount()
            val run = data.cloudPulls.beginRun("alice") {}.runSerial

            fun feed(
                id: String,
                version: Long,
            ) = CloudPullMatch(id, version, """{"id":"$id","access":"feed"}""", "feed", null, 0, false, null)
            val page = storePage().copy(matches = storePage().matches + feed("feed-old", 2) + feed("feed-new", 3), cursor = 3)
            data.cloudPulls.commitMetadata("alice", run, 0, page) {}
            // Mitglieds-Matches gehören nicht in den Feed; neueste Serverversion zuerst.
            assertEquals(
                listOf("feed-new", "feed-old"),
                data.cloudPulls.feedMatches("alice").map {
                    it.substringAfter("\"id\":\"").substringBefore('"')
                },
            )
            assertTrue(data.cloudPulls.feedMatches("other").isEmpty())
            // Entfreunden erhöht die Sichtbarkeits-Epoche: ein Vollabgleich ohne die Feed-Matches entfernt sie.
            assertTrue(!data.cloudPulls.commitMetadata("alice", run, 3, storePage(version = 4, epoch = 2)) {})
            data.cloudPulls.commitMetadata("alice", run, 0, storePage(version = 4, epoch = 2)) {}
            assertTrue(data.cloudPulls.feedMatches("alice").isEmpty())
        }
    }

    @Test
    fun ownHiddenFlagRevokesTheLocalCopyLikeOnASecondDeviceAndUnhidingRestoresIt() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount()
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            val id = pullRecord().id
            data.cloudPulls.commitMetadata("alice", run, 0, storePage()) {}
            assertTrue(data.matches.get(id) != null)

            // Ausblenden erhöht G: der Vollabgleich liefert das Match weiter als member, aber mit eigenem hidden.
            val hidden =
                storePage(version = 2, epoch = 2).let {
                    it.copy(
                        matches =
                            it.matches.map { m ->
                                m.copy(hidden = true, record = null)
                            },
                    )
                }
            assertTrue(!data.cloudPulls.commitMetadata("alice", run, 1, hidden) {})
            data.cloudPulls.commitMetadata("alice", run, 0, hidden) {}
            assertEquals(null, data.matches.get(id))
            assertEquals(null, data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, id.value))

            // Wieder einblenden: neues G, wieder member – die Kopie kommt zurück.
            assertTrue(!data.cloudPulls.commitMetadata("alice", run, 2, storePage(version = 3, epoch = 3)) {})
            data.cloudPulls.commitMetadata("alice", run, 0, storePage(version = 3, epoch = 3)) {}
            assertTrue(data.matches.get(id) != null)
        }
    }
}
