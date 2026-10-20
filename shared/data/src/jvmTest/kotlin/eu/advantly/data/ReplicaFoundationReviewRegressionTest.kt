package eu.advantly.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.PlayerAssigned
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.db.AdvantlyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplicaFoundationReviewRegressionTest {
    private val format = Presets.TENNIS_SINGLES_BO3.format()
    private val owner = PlayerId("owner")
    private val guest = PlayerId("guest")
    private val slot = Placeholders.id("opponent")
    private val lineup = Lineup(listOf(owner), listOf(slot), owner)
    private val fence: () -> Unit = {}

    private inner class Fixture(
        activated: Boolean,
    ) : AutoCloseable {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db: AdvantlyDatabase
        val data: LocalData

        init {
            AdvantlyDatabase.Schema.create(driver)
            db = AdvantlyDatabase(driver)
            data = LocalData(driver)
            var index = 0
            data.accountIdentities.select("scope-A", "A", 0, "PHONE") { "A-${index++}" }
            if (activated) data.replicas.activate(data.replicas.captureContext(), fence)
        }

        fun cloudMatch() {
            val ctx = data.replicas.captureContext()
            data.replicas.applyAccess(ctx, "match", 1, ReplicaAccessLevel.MEMBER, fence)
            data.replicas.markReady(ctx, "match", 1, 0, fence)
            data.cloud.storeDownloadedMatch(
                MatchRecord(MatchId("match"), format, lineup, MatchMeta(), 1, 1, MatchStatus.IN_PROGRESS, ScoreSummary.EMPTY, null),
                "A",
            )
            data.cloud.storeDownloaded(
                listOf(
                    event("genesis", 1, MatchCreated(format, lineup, createdBy = owner)),
                    event("assignment", 2, PlayerAssigned(slot, Player(guest, "Event name"), owner)),
                ),
                fence,
            )
        }

        fun redact(playerId: String) {
            val ctx = data.replicas.captureContext()
            val start = data.replicas.beginRedaction(ctx, RedactionTransfer("redaction", "match", 1, 1, 2), fence)
            data.replicas.stageRedaction(
                ctx,
                "redaction",
                RedactionPage(0, start, RedactionCursor(playerId, 0), listOf(playerId), emptyList(), true, true),
                fence,
            )
            data.replicas.completeRedaction(ctx, "redaction", fence)
        }

        override fun close() = driver.close()
    }

    private fun event(
        id: String,
        seq: Long,
        payload: EventPayload,
    ) = ScoreEvent(
        EventId(id),
        MatchId("match"),
        DeviceId("server-device"),
        seq,
        seq,
        seq,
        if (seq == 1L) null else EventId("genesis"),
        payload,
    ).toStoredEvent()

    @Test
    fun eventBeforeOwnedSnapshotDoesNotPreemptWriterOrDirectoryName() = assertSnapshotAfterEvent(owned = true)

    @Test
    fun eventBeforeForeignSnapshotDoesNotCreateDirectoryOrWriter() = assertSnapshotAfterEvent(owned = false)

    private fun assertSnapshotAfterEvent(owned: Boolean) {
        for (activated in listOf(false, true)) {
            Fixture(activated).use { f ->
                f.cloudMatch()
                assertNull(
                    f.db.playerQueries
                        .selectById(guest.value)
                        .executeAsOneOrNull(),
                )
                assertNull(f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value))
                assertFalse(
                    f.data.players
                        .all()
                        .any { it.id == guest },
                )
                assertEquals("Event name", f.data.assignments(MatchId("match"))[slot]?.displayName)
                val stored =
                    f.db.matchAssignmentQueries
                        .identitiesForMatch("match")
                        .executeAsOne()
                assertEquals(guest.value, stored.player_id)
                assertEquals(Player(guest, "Event name"), DataJson.decodeFromString(Player.serializer(), assertNotNull(stored.player_json)))
                val upload =
                    f.data.cloud
                        .uploadParticipants("match", "A")!!
                        .participants
                        .last()
                assertEquals("Event name", upload.displayName)
                assertNull(upload.playerId)

                val authoritative = LocalPlayer(guest, "Cloud name", PlayerKind.GUEST, 1, null)
                assertTrue(f.data.cloud.materializePullPlayer(CloudPullPlayer(guest.value, 1, "{}", owned, authoritative), "A", null))
                assertEquals(
                    "A".takeIf { owned },
                    f.data.cloud
                        .metadataState(CloudMetadataKind.PLAYER, guest.value)
                        ?.writerProfileId,
                )
                assertEquals(
                    "Cloud name",
                    f.data.players
                        .get(guest)
                        ?.displayName,
                )
                assertEquals("Cloud name", f.data.assignments(MatchId("match"))[slot]?.displayName)
                val state = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value)
                if (activated && !owned) {
                    assertFails { f.data.players.rename(guest, "Unproven edit") }
                    assertEquals(state, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value))
                } else if (owned) {
                    f.data.players.rename(guest, "Own edit")
                    assertEquals(
                        "Own edit",
                        f.data.players
                            .get(guest)
                            ?.displayName,
                    )
                }
            }
        }
    }

    @Test
    fun redactedLinkedGuestDoesNotEraseLiveProfileAliasesAcrossAccounts() {
        for (kind in listOf("guest", "self")) {
            Fixture(true).use { f ->
                f.cloudMatch()
                f.db.cloudPullQueries.insertSnapshot(
                    "A",
                    "PLAYER",
                    guest.value,
                    1,
                    """{"id":"guest","profileId":"profile","kind":"$kind","displayName":"Event name"}""",
                    null,
                    null,
                    0,
                    0,
                    0,
                )
                for (account in listOf("A", "B")) {
                    f.db.cloudPullQueries.insertSnapshot(
                        account,
                        "FRIENDSHIP",
                        "profile",
                        1,
                        """{"profileId":"profile","alias":"Live name"}""",
                        null,
                        null,
                        0,
                        0,
                        0,
                    )
                    f.db.cloudPullQueries.insertSnapshot(
                        account,
                        "MATCH",
                        "other",
                        1,
                        """{"roles":[{"profileId":"profile","alias":"Live name"}]}""",
                        null,
                        null,
                        0,
                        0,
                        0,
                    )
                }
                f.redact(guest.value)
                val expected = kind == "guest"
                for (account in listOf("A", "B")) {
                    assertEquals(
                        expected,
                        f.data.cloudPulls
                            .snapshot(account, CloudPullEntityKind.FRIENDSHIP, "profile")!!
                            .json
                            .contains("Live name"),
                    )
                    assertEquals(
                        expected,
                        f.data.cloudPulls
                            .snapshot(account, CloudPullEntityKind.MATCH, "other")!!
                            .json
                            .contains("Live name"),
                    )
                }
                assertEquals(
                    !expected,
                    f.db.replicaLifecycleQueries
                        .isRedactedProfile("profile")
                        .executeAsOne(),
                )
            }
        }
    }

    @Test
    fun localGenesisCannotAuthorizeForeignSourceTailAndRejectedBatchLeavesNoRows() =
        Fixture(true).use { f ->
            val self = PlayerId("A-1")
            val ownLineup = Lineup(listOf(self), listOf(slot), self)
            val genesis = event("local", 1, MatchCreated(format, ownLineup, createdBy = self)).copy(deviceId = "A-0")
            val ownPoint = event("own-point", 2, PointWon(Side.A)).copy(deviceId = "A-0")
            val foreignPoint = event("foreign-point", 3, PointWon(Side.B))
            assertFails { f.data.events.appendAll(listOf(genesis, ownPoint, foreignPoint)) }
            assertEquals(
                0,
                f.db.matchEventQueries
                    .countForMatch("match")
                    .executeAsOne(),
            )
            assertNull(f.data.cloud.metadataState(CloudMetadataKind.HEADER, "match"))
            assertEquals(listOf(AppendResult.INSERTED, AppendResult.INSERTED), f.data.events.appendAll(listOf(genesis, ownPoint)))
        }

    @Test
    fun emptyTypeLegacyAssignmentKeepsRealFallbackWithoutCreatingDirectory() =
        Fixture(false).use { f ->
            f.data.events.appendAll(
                listOf(
                    event("genesis", 1, MatchCreated(format, lineup, createdBy = owner)).copy(type = ""),
                    event("assignment", 2, PlayerAssigned(slot, Player(guest, "Legacy name"), owner)).copy(type = ""),
                ),
            )
            assertNull(
                f.db.playerQueries
                    .selectById(guest.value)
                    .executeAsOneOrNull(),
            )
            assertEquals("Legacy name", f.data.assignments(MatchId("match"))[slot]?.displayName)
            f.data.rebuildAllAssignments()
            assertEquals("Legacy name", f.data.assignments(MatchId("match"))[slot]?.displayName)
        }

    @Test
    fun redactionClearsFallbackButCannotCreatePhantomPlayerOrDirtyParticipantAcks() =
        Fixture(true).use { f ->
            f.cloudMatch()
            val state = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")
            f.redact(guest.value)
            val ctx = f.data.replicas.captureContext()
            f.data.replicas.markReady(ctx, "match", 1, 1, fence)
            assertNull(
                f.db.playerQueries
                    .selectById(guest.value)
                    .executeAsOneOrNull(),
            )
            assertNull(
                f.db.matchAssignmentQueries
                    .identitiesForMatch("match")
                    .executeAsOne()
                    .player_json,
            )
            assertEquals(state, f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match"))
            assertEquals(
                "",
                f.data.cloud
                    .uploadParticipants("match", "A")!!
                    .participants
                    .last()
                    .displayName,
            )
            f.data.rebuildAssignments(MatchId("match"))
            assertNull(
                f.db.matchAssignmentQueries
                    .identitiesForMatch("match")
                    .executeAsOne()
                    .player_json,
            )
            assertNull(
                f.db.playerQueries
                    .selectById(guest.value)
                    .executeAsOneOrNull(),
            )
        }

    @Test
    fun referenceOnlyAssignmentReplacesActualLegacyFallbackWithoutInventingPlayerJson() =
        Fixture(true).use { f ->
            f.cloudMatch()
            f.data.events.append(event("renamed", 3, PlayerAssigned(slot, Player(guest, "Later event name"), owner)))
            assertEquals("Later event name", f.data.assignments(MatchId("match"))[slot]?.displayName)
            f.data.events.append(event("reference", 4, PlayerAssigned.reference(slot, guest, owner)))
            val row =
                f.db.matchAssignmentQueries
                    .identitiesForMatch("match")
                    .executeAsOne()
            assertEquals(guest.value, row.player_id)
            assertNull(row.player_json)
            assertNull(f.data.players.get(guest))
            assertNull(f.data.assignments(MatchId("match"))[slot])
            assertEquals(
                listOf(guest),
                f.data.matches
                    .recentLineups()
                    .single()
                    .b,
            )
            assertEquals(
                "",
                f.data.cloud
                    .uploadParticipants("match", "A")!!
                    .participants
                    .last()
                    .displayName,
            )
        }

    @Test
    fun historicalSelfRedactionRequiresFenceAndPreservesUnrelatedIdenticalNames() =
        Fixture(true).use { f ->
            val historicalSelf = PlayerId("A-1")
            f.data.players.rename(historicalSelf, "Same name")
            val historicalState = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, historicalSelf.value)
            var index = 0
            f.data.accountIdentities.select("scope-B", "B", 0, "PHONE") { "B-${index++}" }
            f.data.players.rename(PlayerId("B-1"), "Same name")
            val unrelatedState = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "B-1")
            f.cloudMatch()
            val ctx = f.data.replicas.captureContext()
            f.data.replicas.registerPeer(ctx, "watch", fence)
            val beforeDelivery = f.data.replicas.pendingDeliveries(ctx, "watch", null, 20)
            val start = f.data.replicas.beginRedaction(ctx, RedactionTransfer("historical", "match", 1, 1, 2), fence)
            f.data.replicas.stageRedaction(
                ctx,
                "historical",
                RedactionPage(0, start, RedactionCursor(historicalSelf.value, 0), listOf(historicalSelf.value), emptyList(), true, true),
                fence,
            )
            // Die aktuelle Besetzung enthält diese frühere Self-ID nicht. Der autoritative kumulative Beleg darf sie dennoch betreffen.
            assertFalse(historicalSelf in lineup.a + lineup.b)
            assertFails {
                f.data.replicas.completeRedaction(ctx, "historical") {
                    check(
                        f.data.replicas
                            .access(ctx, "match")!!
                            .redactionEpoch == 0L,
                    ) { "Autorität vor Commit verloren" }
                }
            }
            assertEquals(
                "Same name",
                f.db.playerQueries
                    .selectById(historicalSelf.value)
                    .executeAsOne()
                    .display_name,
            )
            assertEquals(
                0,
                f.db.replicaLifecycleQueries
                    .isRedacted(historicalSelf.value)
                    .executeAsOne(),
            )
            assertEquals(beforeDelivery, f.data.replicas.pendingDeliveries(ctx, "watch", null, 20))
            f.data.replicas.completeRedaction(ctx, "historical", fence)
            assertEquals(
                "",
                f.db.playerQueries
                    .selectById(historicalSelf.value)
                    .executeAsOne()
                    .display_name,
            )
            assertEquals(
                "Same name",
                f.data.players
                    .get(PlayerId("B-1"))
                    ?.displayName,
            )
            assertEquals(historicalState, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, historicalSelf.value))
            assertEquals(unrelatedState, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "B-1"))
            val delivery = f.data.replicas.pendingDeliveries(ctx, "watch", null, 20)
            f.data.accountIdentities.select("scope-A", "A", 0, "PHONE") { error("Existing identity") }
            f.data.accountIdentities.select("scope-B", "B", 0, "PHONE") { error("Existing identity") }
            assertFails { f.data.replicas.ackRedaction(ctx, "watch", "match", 1, 1, fence) }
            assertEquals(delivery, f.data.replicas.pendingDeliveries(f.data.replicas.captureContext(), "watch", null, 20))
        }

    @Test
    fun downloadedLegacyNamesAreSanitizedBeforeBothInsertAndDuplicateComparison() {
        for (activated in listOf(false, true)) {
            Fixture(activated).use { f ->
                f.cloudMatch()
                val original = event("assignment", 2, PlayerAssigned(slot, Player(guest, "Event name"), owner))
                f.db.matchEventQueries.markConfirmedUpTo("match", "server-device", 2)
                f.redact(guest.value)
                val before =
                    f.db.cloudUploadQueries
                        .eventById("assignment")
                        .executeAsOne()
                f.data.cloud.storeDownloaded(listOf(original), fence)
                f.data.cloud.storeDownloaded(listOf(original.copy(type = "")), fence)
                assertEquals(
                    before,
                    f.db.cloudUploadQueries
                        .eventById("assignment")
                        .executeAsOne(),
                )
                val late = original.copy(id = "late-assignment", deviceSeq = 3, lamport = 3)
                f.data.cloud.storeDownloaded(listOf(late), fence)
                assertFalse(
                    f.db.cloudUploadQueries
                        .eventById(late.id)
                        .executeAsOne()
                        .payload
                        .contains("Event name"),
                )
                assertNull(
                    f.db.playerQueries
                        .selectById(guest.value)
                        .executeAsOneOrNull(),
                )
                val tamper: List<(StoredEvent) -> StoredEvent> =
                    listOf(
                        { it.copy(matchId = "other") },
                        { it.copy(deviceId = "other") },
                        { it.copy(deviceSeq = 99) },
                        { it.copy(lamport = 99) },
                        { it.copy(wallClockMs = 99) },
                        { it.copy(parentId = "other") },
                        { it.copy(type = "PointWon") },
                        { it.copy(payload = it.payload.replace("owner", "other")) },
                    )
                for (change in tamper) {
                    assertFails { f.data.cloud.storeDownloaded(listOf(change(original)), fence) }
                    assertEquals(
                        before,
                        f.db.cloudUploadQueries
                            .eventById("assignment")
                            .executeAsOne(),
                    )
                }
                val unredacted = event("unrelated", 4, PlayerAssigned(slot, Player(PlayerId("unrelated"), "Event name"), owner))
                f.data.cloud.storeDownloaded(listOf(unredacted), fence)
                assertTrue(
                    f.db.cloudUploadQueries
                        .eventById(unredacted.id)
                        .executeAsOne()
                        .payload
                        .contains("Event name"),
                )
                assertFails {
                    f.data.cloud.storeDownloaded(
                        listOf(unredacted.copy(payload = unredacted.payload.replace("Event name", "Changed"))),
                        fence,
                    )
                }
            }
        }
    }

    @Test
    fun sanitizedDownloadFinalFenceRollsBackInsertAndCloudAck() {
        for (activated in listOf(false, true)) {
            Fixture(activated).use { f ->
                f.cloudMatch()
                f.redact(guest.value)
                val ctx = f.data.replicas.captureContext()
                f.data.replicas.markReady(ctx, "match", 1, 1, fence)
                val pending = event("pending", 4, PointWon(Side.A))
                f.data.events.append(pending)
                val before =
                    f.db.cloudUploadQueries
                        .eventById(pending.id)
                        .executeAsOne()
                val late = event("late", 3, PlayerAssigned(slot, Player(guest, "Event name"), owner))
                assertFails {
                    f.data.cloud.storeDownloaded(listOf(late, pending)) {
                        check(
                            pending.id in
                                f.db.cloudUploadQueries
                                    .outstandingForMatch("match")
                                    .executeAsList(),
                        )
                    }
                }
                assertEquals(
                    before,
                    f.db.cloudUploadQueries
                        .eventById(pending.id)
                        .executeAsOne(),
                )
                assertTrue(
                    pending.id in
                        f.db.cloudUploadQueries
                            .outstandingForMatch("match")
                            .executeAsList(),
                )
                assertNull(
                    f.db.cloudUploadQueries
                        .eventById(late.id)
                        .executeAsOneOrNull(),
                )
                f.data.cloud.storeDownloaded(listOf(late, pending), fence)
                assertFalse(
                    f.db.cloudUploadQueries
                        .eventById(late.id)
                        .executeAsOne()
                        .payload
                        .contains("Event name"),
                )
            }
        }
    }

    private fun Fixture.pullMatch(
        id: String,
        snapshotProfile: String = "A",
        writable: Set<CloudMetadataKind> = emptySet(),
    ): MatchRecord {
        val record =
            MatchRecord(
                MatchId(id),
                format,
                Lineup(listOf(owner), listOf(guest), owner),
                MatchMeta(),
                1,
                1,
                MatchStatus.IN_PROGRESS,
                ScoreSummary.EMPTY,
                null,
            )
        data.cloud.storeDownloadedMatch(record, snapshotProfile, writable)
        db.cloudPullQueries.insertSnapshot(snapshotProfile, "MATCH", id, 1, "{}", "member", 1, 0, 0, 0)
        db.cloudPullQueries.materialized(1, snapshotProfile, "MATCH", id)
        return record
    }

    @Test
    fun confirmedPlayerDownloadKeepsCleanOwnCloudParticipantsMaterializable() {
        for (activated in listOf(false, true)) {
            Fixture(activated).use { f ->
                val record = f.pullMatch("clean")
                val before = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "clean")!!
                val player = LocalPlayer(guest, "Cloud name", PlayerKind.GUEST, 1, null)
                assertTrue(f.data.cloud.materializePullPlayer(CloudPullPlayer(guest.value, 1, "{}", true, player), "A", null))
                val after = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "clean")!!
                assertTrue(after.selected.revision > before.selected.revision)
                assertEquals(after.selected.revision, after.uploadedRevision)
                assertEquals(before.writerProfileId, after.writerProfileId)
                assertEquals(before.sourceDeviceId, after.sourceDeviceId)
                assertTrue(f.data.cloud.materializePullMatch(record.copy(updatedAtMs = 2), "A", 1))
            }
        }
    }

    @Test
    fun playerDownloadNeverAcknowledgesPreexistingEditsOrOtherAccountsMaterialization() {
        for (activated in listOf(false, true)) {
            Fixture(activated).use { f ->
                f.pullMatch("dirty")
                f.pullMatch("foreign", "B")
                f.pullMatch("shared")
                f.db.cloudPullQueries.insertSnapshot("B", "MATCH", "shared", 1, "{}", "member", 1, 0, 0, 0)
                f.db.cloudPullQueries.materialized(1, "B", "MATCH", "shared")
                f.db.matchAssignmentQueries.putIdentity("dirty", slot.value, guest.value, null)
                val ids = listOf("dirty", "foreign", "shared")
                val before = ids.associateWith { f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, it)!! }
                assertTrue(before.getValue("dirty").selected.revision > before.getValue("dirty").uploadedRevision)
                val player = LocalPlayer(guest, "Cloud name", PlayerKind.GUEST, 1, null)
                assertTrue(f.data.cloud.materializePullPlayer(CloudPullPlayer(guest.value, 1, "{}", true, player), "A", null))
                for (id in ids) {
                    val after = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, id)!!
                    assertEquals(before.getValue(id).uploadedRevision, after.uploadedRevision, id)
                    assertTrue(after.selected.revision > after.uploadedRevision, id)
                }
            }
        }
    }

    @Test
    fun declinedPlayerMaterializationDoesNotCleanParticipants() {
        for (activated in listOf(false, true)) {
            for (reason in listOf("local", "foreign", "missing-proof")) {
                Fixture(activated).use { f ->
                    f.pullMatch("clean")
                    val player = LocalPlayer(guest, "Old name", PlayerKind.GUEST, 1, null)
                    if (reason == "local") {
                        f.data.players.addGuest(guest, "Own pending name", 1)
                    } else {
                        f.data.cloud.storeDownloadedPlayer(player, "B")
                    }
                    if (reason == "foreign") {
                        f.db.cloudPullQueries.insertSnapshot("B", "PLAYER", guest.value, 1, "{}", null, null, 0, 0, 0)
                        f.db.cloudPullQueries.materialized(1, "B", "PLAYER", guest.value)
                    }
                    val before = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "clean")
                    assertFalse(
                        f.data.cloud.materializePullPlayer(
                            CloudPullPlayer(guest.value, 2, "{}", true, player.copy(displayName = "New name")),
                            "A",
                            if (reason == "missing-proof") null else 1,
                        ),
                    )
                    assertEquals(before, f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "clean"))
                }
            }
        }
    }

    @Test
    fun playerSnapshotUpdatesCleanParticipantsButNeverAcknowledgesStaleOrPendingChanges() {
        for (activated in listOf(false, true)) {
            Fixture(activated).use { f ->
                f.pullMatch("clean")
                val run =
                    f.data.cloudPulls
                        .beginRun("A", fence)
                        .runSerial

                fun page(
                    version: Long,
                    cursor: Long,
                    name: String,
                ) = CloudPullMetadataPage(
                    emptyList(),
                    listOf(CloudPullPlayer(guest.value, version, "{}", true, LocalPlayer(guest, name, PlayerKind.GUEST, 1, null))),
                    emptyList(),
                    cursor,
                    true,
                    1,
                )
                assertTrue(f.data.cloudPulls.commitMetadata("A", run, 0, page(2, 1, "First"), fence))
                assertTrue(f.data.cloudPulls.commitMetadata("A", run, 1, page(3, 2, "Updated"), fence))
                val clean = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "clean")!!
                assertEquals(clean.selected.revision, clean.uploadedRevision)
                assertEquals(
                    "Updated",
                    f.db.playerQueries
                        .selectById(guest.value)
                        .executeAsOne()
                        .display_name,
                )
                assertTrue(f.data.cloudPulls.commitMetadata("A", run, 2, page(2, 3, "Stale"), fence))
                assertEquals(clean, f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "clean"))
                assertEquals(
                    "Updated",
                    f.db.playerQueries
                        .selectById(guest.value)
                        .executeAsOne()
                        .display_name,
                )
                f.data.players.rename(guest, "Own pending edit")
                val pending = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "clean")!!
                assertTrue(pending.selected.revision > pending.uploadedRevision)
                assertTrue(f.data.cloudPulls.commitMetadata("A", run, 3, page(4, 4, "Server conflict"), fence))
                assertEquals(pending, f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "clean"))
                assertEquals(
                    "Own pending edit",
                    f.db.playerQueries
                        .selectById(guest.value)
                        .executeAsOne()
                        .display_name,
                )
                assertEquals(
                    3,
                    f.data.cloudPulls
                        .snapshot("A", CloudPullEntityKind.PLAYER, guest.value)!!
                        .materializedVersion,
                )
            }
        }
    }

    @Test
    fun playerDownloadFinalFenceRollsBackDirectoryParticipantAckSnapshotAndCursor() {
        for (activated in listOf(false, true)) {
            Fixture(activated).use { f ->
                f.pullMatch("clean")
                val before = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "clean")
                val run =
                    f.data.cloudPulls
                        .beginRun("A", fence)
                        .runSerial
                val page =
                    CloudPullMetadataPage(
                        emptyList(),
                        listOf(CloudPullPlayer(guest.value, 1, "{}", true, LocalPlayer(guest, "Cloud name", PlayerKind.GUEST, 1, null))),
                        emptyList(),
                        1,
                        true,
                        1,
                    )
                var sawCleanDownload = false
                assertFails {
                    f.data.cloudPulls.commitMetadata("A", run, 0, page) {
                        if (f.db.playerQueries
                                .selectById(guest.value)
                                .executeAsOneOrNull() != null
                        ) {
                            val after = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "clean")!!
                            assertEquals(after.selected.revision, after.uploadedRevision)
                            sawCleanDownload = true
                            error("Context expired after materialization")
                        }
                    }
                }
                assertTrue(sawCleanDownload)
                assertEquals(before, f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "clean"))
                assertNull(
                    f.db.playerQueries
                        .selectById(guest.value)
                        .executeAsOneOrNull(),
                )
                assertNull(f.data.cloudPulls.snapshot("A", CloudPullEntityKind.PLAYER, guest.value))
                assertEquals(
                    0,
                    f.data.cloudPulls
                        .progress("A")
                        .cursor,
                )
            }
        }
    }

    @Test
    fun playerDownloadCleansAllCandidatePagesAndAssignmentOnlyReferences() {
        Fixture(true).use { f ->
            repeat(201) { f.pullMatch("match-${it.toString().padStart(3, '0')}") }
            val assignment =
                MatchRecord(
                    MatchId("assignment-only"),
                    format,
                    lineup,
                    MatchMeta(),
                    1,
                    1,
                    MatchStatus.IN_PROGRESS,
                    ScoreSummary.EMPTY,
                    null,
                )
            f.data.cloud.storeDownloadedMatch(assignment, "A")
            f.db.matchAssignmentQueries.putIdentity("assignment-only", slot.value, guest.value, null)
            f.db.cloudMetadataQueries.recordDownload(null, "PARTICIPANTS", "assignment-only")
            f.db.cloudPullQueries.insertSnapshot("A", "MATCH", "assignment-only", 1, "{}", "member", 1, 0, 0, 0)
            f.db.cloudPullQueries.materialized(1, "A", "MATCH", "assignment-only")
            val player = LocalPlayer(guest, "Cloud name", PlayerKind.GUEST, 1, null)
            assertTrue(f.data.cloud.materializePullPlayer(CloudPullPlayer(guest.value, 1, "{}", true, player), "A", null))
            for (id in (0..200).map { "match-${it.toString().padStart(3, '0')}" } + "assignment-only") {
                val state = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, id)!!
                assertEquals(state.selected.revision, state.uploadedRevision, id)
            }
        }
    }

    private fun playerPage(
        version: Long,
        owned: Boolean,
    ) = CloudPullMetadataPage(
        emptyList(),
        listOf(CloudPullPlayer(guest.value, version, "{}", owned, LocalPlayer(guest, "Cloud name", PlayerKind.GUEST, 1, null))),
        emptyList(),
        version,
        true,
        1,
    )

    @Test
    fun pendingPlayerGrantCleansOnlyReadonlyTriggerRevisionBeforeActivation() = assertPendingPlayerAuthorityChange(false, true)

    @Test
    fun pendingPlayerGrantCleansOnlyReadonlyTriggerRevisionAfterActivation() = assertPendingPlayerAuthorityChange(true, true)

    @Test
    fun pendingPlayerRevocationCleansOnlyReadonlyTriggerRevisionBeforeActivation() = assertPendingPlayerAuthorityChange(false, false)

    @Test
    fun pendingPlayerRevocationCleansOnlyReadonlyTriggerRevisionAfterActivation() = assertPendingPlayerAuthorityChange(true, false)

    private fun assertPendingPlayerAuthorityChange(
        activated: Boolean,
        grant: Boolean,
    ) {
        Fixture(activated).use { f ->
            val run =
                f.data.cloudPulls
                    .beginRun("A", fence)
                    .runSerial
            assertTrue(f.data.cloudPulls.commitMetadata("A", run, 0, playerPage(1, true), fence))
            f.pullMatch("previously-dirty")
            f.data.players.rename(guest, "Own pending edit")
            if (grant) assertTrue(f.data.cloudPulls.commitMetadata("A", run, 1, playerPage(2, false), fence))
            val cursor = if (grant) 2L else 1L
            val playerBefore = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value)!!
            assertTrue(playerBefore.selected.revision > playerBefore.uploadedRevision)
            assertEquals(if (grant) null else "A", playerBefore.writerProfileId)
            val record = f.pullMatch("new-readonly")
            val cleanBefore = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "new-readonly")!!
            val dirtyBefore = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "previously-dirty")!!
            assertEquals(cleanBefore.selected.revision, cleanBefore.uploadedRevision)
            assertTrue(dirtyBefore.selected.revision > dirtyBefore.uploadedRevision)
            assertTrue(f.data.cloudPulls.commitMetadata("A", run, cursor, playerPage(cursor + 1, grant), fence))
            val playerAfter = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value)!!
            assertEquals(playerBefore.selected, playerAfter.selected)
            assertEquals(playerBefore.uploadedRevision, playerAfter.uploadedRevision)
            assertEquals(if (grant) "A" else null, playerAfter.writerProfileId)
            assertEquals(
                "Own pending edit",
                f.db.playerQueries
                    .selectById(guest.value)
                    .executeAsOne()
                    .display_name,
            )
            assertEquals(
                1,
                f.data.cloudPulls
                    .snapshot("A", CloudPullEntityKind.PLAYER, guest.value)!!
                    .materializedVersion,
            )
            val dirtyAfter = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "previously-dirty")!!
            assertEquals(dirtyBefore.uploadedRevision, dirtyAfter.uploadedRevision)
            val cleanAfter = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "new-readonly")!!
            assertEquals(cleanBefore.selected.revision + 1, cleanAfter.selected.revision)
            assertEquals(cleanAfter.selected.revision, cleanAfter.uploadedRevision)
            assertTrue(f.data.cloud.materializePullMatch(record.copy(updatedAtMs = 2), "A", 1))
        }
    }

    @Test
    fun writableCloudParticipantReferenceRemainsPendingBeforeActivation() = assertWritableParticipantsRemainPending(false)

    @Test
    fun writableCloudParticipantReferenceRemainsPendingAfterActivation() = assertWritableParticipantsRemainPending(true)

    private fun assertWritableParticipantsRemainPending(activated: Boolean) {
        Fixture(activated).use { f ->
            val run =
                f.data.cloudPulls
                    .beginRun("A", fence)
                    .runSerial
            assertTrue(f.data.cloudPulls.commitMetadata("A", run, 0, playerPage(1, false), fence))
            f.pullMatch("writable", writable = setOf(CloudMetadataKind.PARTICIPANTS))
            val before = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "writable")!!
            assertEquals("A", before.writerProfileId)
            assertEquals(before.selected.revision, before.uploadedRevision)
            assertNull(
                f.data.cloud
                    .uploadParticipants("writable", "A")!!
                    .participants
                    .last()
                    .playerId,
            )
            assertTrue(f.data.cloudPulls.commitMetadata("A", run, 1, playerPage(2, true), fence))
            assertEquals(
                guest.value,
                f.data.cloud
                    .uploadParticipants("writable", "A")!!
                    .participants
                    .last()
                    .playerId,
            )
            val after = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "writable")!!
            assertEquals(before.selected.revision + 1, after.selected.revision)
            assertEquals(before.uploadedRevision, after.uploadedRevision)
            assertTrue(after.selected.revision > after.uploadedRevision)
        }
    }

    @Test
    fun authorityOnlyDownloadFinalFenceRollsBackWriterAndParticipantAck() {
        for (activated in listOf(false, true)) {
            Fixture(activated).use { f ->
                val run =
                    f.data.cloudPulls
                        .beginRun("A", fence)
                        .runSerial
                assertTrue(f.data.cloudPulls.commitMetadata("A", run, 0, playerPage(1, true), fence))
                f.data.players.rename(guest, "Own pending edit")
                f.pullMatch("new-readonly")
                val playerBefore = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value)
                val participantsBefore = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "new-readonly")
                val snapshotBefore = f.data.cloudPulls.snapshot("A", CloudPullEntityKind.PLAYER, guest.value)
                var observedParticipants: CloudMetadataState? = null
                assertFails {
                    f.data.cloudPulls.commitMetadata("A", run, 1, playerPage(2, false)) {
                        if (f.data.cloud
                                .metadataState(CloudMetadataKind.PLAYER, guest.value)!!
                                .writerProfileId == null
                        ) {
                            observedParticipants = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "new-readonly")
                            error("Context expired after authority change")
                        }
                    }
                }
                val observed = assertNotNull(observedParticipants)
                assertEquals(observed.selected.revision, observed.uploadedRevision)
                assertEquals(playerBefore, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value))
                assertEquals(participantsBefore, f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "new-readonly"))
                assertEquals(snapshotBefore, f.data.cloudPulls.snapshot("A", CloudPullEntityKind.PLAYER, guest.value))
                assertEquals(
                    1,
                    f.data.cloudPulls
                        .progress("A")
                        .cursor,
                )
            }
        }
    }
}
