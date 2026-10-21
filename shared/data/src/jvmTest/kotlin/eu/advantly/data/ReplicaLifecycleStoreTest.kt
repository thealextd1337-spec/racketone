package eu.advantly.data

import app.cash.sqldelight.db.QueryResult
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
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.PlayerAssigned
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplicaLifecycleStoreTest {
    private val fence: () -> Unit = {}
    private val format = Presets.TENNIS_SINGLES_BO3.format()
    private val owner = PlayerId("owner")
    private val slot = Placeholders.id("opponent")
    private val lineup = Lineup(listOf(owner), listOf(slot), owner)

    private inner class Fixture(
        activate: Boolean = true,
    ) : AutoCloseable {
        val file = File.createTempFile("replica-lifecycle", ".db")
        var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        var data: LocalData
        var db: AdvantlyDatabase

        init {
            AdvantlyDatabase.Schema.create(driver)
            data = LocalData(driver)
            db = AdvantlyDatabase(driver)
            select("A")
            if (activate) data.replicas.activate(data.replicas.captureContext(), fence)
        }

        fun select(account: String): ReplicaContext {
            var index = 0
            data.accountIdentities.select("scope-$account", account, 0, "PHONE") { "$account-${index++}" }
            return data.replicas.captureContext()
        }

        fun reopen() {
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            data = LocalData(driver)
            db = AdvantlyDatabase(driver)
        }

        fun count(
            table: String,
            where: String = "1=1",
        ): Long =
            driver
                .executeQuery(null, "SELECT count(*) FROM $table WHERE $where", { c ->
                    c.next()
                    QueryResult.Value(c.getLong(0)!!)
                }, 0)
                .value

        fun sql(sql: String) {
            driver.execute(null, sql, 0)
        }

        fun member(
            account: String,
            generation: Long = 1,
        ): ReplicaContext {
            val ctx = select(account)
            data.replicas.applyAccess(ctx, "match", generation, ReplicaAccessLevel.MEMBER, fence)
            data.replicas.markReady(ctx, "match", generation, 0, fence)
            return ctx
        }

        fun cloudMatch(assignment: Boolean = false): List<StoredEvent> {
            val record = MatchRecord(MatchId("match"), format, lineup, MatchMeta(), 1, 1, MatchStatus.IN_PROGRESS, ScoreSummary.EMPTY, null)
            data.cloud.storeDownloadedMatch(record, "A")
            val events = mutableListOf(event("genesis", 1, MatchCreated(format, lineup, createdBy = owner)))
            if (assignment) events += event("assignment", 2, PlayerAssigned(slot, Player(PlayerId("deleted"), "Sensitive name"), owner))
            events += event("point", 3, PointWon(Side.A))
            data.cloud.storeDownloaded(events, fence)
            return events
        }

        fun cloudDirectoryPlayer() {
            // Eigenständiger autoritativer Verzeichnissnapshot; Eventnamen dürfen diesen nicht mehr vorwegnehmen.
            data.cloud.storeDownloadedPlayer(LocalPlayer(PlayerId("deleted"), "Sensitive name", PlayerKind.GUEST, 0, null), "A")
        }

        override fun close() {
            driver.close()
            file.delete()
        }
    }

    private fun event(
        id: String,
        seq: Long,
        payload: eu.advantly.core.scoring.EventPayload,
    ): StoredEvent =
        ScoreEvent(
            EventId(id),
            MatchId("match"),
            DeviceId("server-device"),
            seq,
            seq,
            seq,
            if (seq ==
                1L
            ) {
                null
            } else {
                EventId("genesis")
            },
            payload,
        ).toStoredEvent()

    @Test
    fun memberGenerationRefreshRetainsOwnMaterializationProofWhileBlockingReads() {
        Fixture().use { f ->
            val ctx = f.member("A")
            f.cloudMatch()
            f.sql(
                """INSERT INTO cloud_pull_snapshot(profile_id,kind,entity_id,server_version,snapshot_json,access,access_epoch,materialized_version)
                    VALUES ('A','MATCH','match',1,'{}','member',1,1)""",
            )
            val previous = f.data.cloudPulls.snapshot("A", CloudPullEntityKind.MATCH, "match")
            assertNotNull(previous)
            f.sql(
                """INSERT INTO cloud_pull_snapshot(profile_id,kind,entity_id,server_version,snapshot_json,access,access_epoch,materialized_version)
                    VALUES ('B','MATCH','match',7,'{}','member',7,7)""",
            )
            val unrelated = f.data.cloudPulls.snapshot("B", CloudPullEntityKind.MATCH, "match")
            var fenceCalls = 0
            assertFails {
                f.data.replicas.applyAccess(ctx, "match", 2, ReplicaAccessLevel.MEMBER) {
                    if (++fenceCalls == 2) error("Lease changed")
                }
            }
            assertTrue(f.data.replicas.canReadMember(ctx, "match"))
            assertEquals(previous, f.data.cloudPulls.snapshot("A", CloudPullEntityKind.MATCH, "match"))
            f.data.replicas.applyAccess(ctx, "match", 2, ReplicaAccessLevel.MEMBER, fence)
            assertFalse(f.data.replicas.canReadMember(ctx, "match"))
            assertNull(f.data.matches.get(MatchId("match")))
            assertEquals(previous, f.data.cloudPulls.snapshot("A", CloudPullEntityKind.MATCH, "match"))
            assertEquals(unrelated, f.data.cloudPulls.snapshot("B", CloudPullEntityKind.MATCH, "match"))
            f.reopen()
            val resumed = f.data.replicas.captureContext()
            assertFalse(f.data.replicas.canReadMember(resumed, "match"))
            assertEquals(previous, f.data.cloudPulls.snapshot("A", CloudPullEntityKind.MATCH, "match"))
            f.data.replicas.applyAccess(resumed, "match", 3, ReplicaAccessLevel.FEED, fence)
            assertNull(f.data.cloudPulls.snapshot("A", CloudPullEntityKind.MATCH, "match"))
        }
    }

    @Test
    fun manifestBeforeRawDownloadRejectsConflictingProofSequencesInOnePage() {
        Fixture().use { f ->
            val ctx = f.data.replicas.captureContext()
            f.data.replicas.applyAccess(ctx, "match", 1, ReplicaAccessLevel.MEMBER, fence)
            val first = event("first-proof", 2, PlayerAssigned(slot, Player(PlayerId("living"), "Gelöschter Spieler"), owner))
            val second = first.copy(id = "conflicting-proof")
            val transfer = RedactionTransfer("proof-conflict", "match", 1, 1, 3)
            val from = f.data.replicas.beginRedaction(ctx, transfer, fence)
            f.data.replicas.stageRedaction(
                ctx,
                transfer.id,
                RedactionPage(
                    0,
                    from,
                    RedactionCursor(null, 3),
                    emptyList(),
                    listOf(ReplicaRedactionEvent(first, 2), ReplicaRedactionEvent(second, 3)),
                    true,
                    true,
                ),
                fence,
            )
            assertFails { f.data.replicas.completeRedaction(ctx, transfer.id, fence) }
            assertEquals(0, f.count("replica_redacted_event"))
            assertEquals(0, f.count("match_event"))
            assertEquals(
                0,
                f.data.replicas
                    .access(ctx, "match")!!
                    .redactionEpoch,
            )
            assertFalse(
                f.data.replicas
                    .redactionProgress(ctx, "match")!!
                    .completed,
            )
            assertFails { f.data.replicas.exportRedactionPage(ctx, transfer.id, 0) }
        }
    }

    @Test
    fun manifestBeforeRawDownloadRejectsConflictingProofSequenceAfterPurgeAndReopen() {
        Fixture().use { f ->
            var ctx = f.member("A")
            val original = f.cloudMatch(assignment = true).single { it.id == "assignment" }
            val redacted = original.copy(payload = original.payload.replace("Sensitive name", "Gelöschter Spieler"))
            val first = RedactionTransfer("before-purge", "match", 1, 1, 3)
            val from = f.data.replicas.beginRedaction(ctx, first, fence)
            f.data.replicas.stageRedaction(
                ctx,
                first.id,
                RedactionPage(0, from, RedactionCursor(null, 2), emptyList(), listOf(ReplicaRedactionEvent(redacted, 2)), true, true),
                fence,
            )
            f.data.replicas.completeRedaction(ctx, first.id, fence)
            f.data.replicas.applyAccess(ctx, "match", 2, ReplicaAccessLevel.ABSENT, fence)
            assertEquals(0, f.count("match_event"))
            assertEquals(1, f.count("replica_redacted_event"))
            f.reopen()
            ctx = f.data.replicas.captureContext()
            f.data.replicas.applyAccess(ctx, "match", 3, ReplicaAccessLevel.MEMBER, fence)
            val next = RedactionTransfer("after-purge", "match", 3, 2, 3)
            val nextFrom = f.data.replicas.beginRedaction(ctx, next, fence)
            f.data.replicas.stageRedaction(
                ctx,
                next.id,
                RedactionPage(
                    0,
                    nextFrom,
                    RedactionCursor(null, 3),
                    emptyList(),
                    listOf(ReplicaRedactionEvent(redacted.copy(id = "reused-sequence"), 3)),
                    true,
                    true,
                ),
                fence,
            )
            assertFails { f.data.replicas.completeRedaction(ctx, next.id, fence) }
            assertEquals(1, f.count("replica_redacted_event"))
            assertEquals(0, f.count("match_event"))
            assertEquals(
                1,
                f.data.replicas
                    .access(ctx, "match")!!
                    .redactionEpoch,
            )
            assertFalse(
                f.data.replicas
                    .redactionProgress(ctx, "match")!!
                    .completed,
            )
            assertEquals(redacted, f.data.replicas.sanitizeLegacyEvent(original))
        }
    }

    @Test
    fun rawDownloadCannotReuseRedactedSequenceAfterPurgeAndReopen() {
        Fixture().use { f ->
            var ctx = f.member("A")
            val original = f.cloudMatch(assignment = true).single { it.id == "assignment" }
            val redacted = original.copy(payload = original.payload.replace("Sensitive name", "Gelöschter Spieler"))
            val transfer = RedactionTransfer("reserved-sequence", "match", 1, 1, 3)
            val from = f.data.replicas.beginRedaction(ctx, transfer, fence)
            f.data.replicas.stageRedaction(
                ctx,
                transfer.id,
                RedactionPage(0, from, RedactionCursor(null, 2), emptyList(), listOf(ReplicaRedactionEvent(redacted, 2)), true, true),
                fence,
            )
            f.data.replicas.completeRedaction(ctx, transfer.id, fence)
            f.data.replicas.applyAccess(ctx, "match", 2, ReplicaAccessLevel.ABSENT, fence)
            assertEquals(0, f.count("match_event"))
            f.reopen()
            ctx = f.data.replicas.captureContext()
            f.data.replicas.applyAccess(ctx, "match", 3, ReplicaAccessLevel.MEMBER, fence)
            for (conflict in listOf(original.copy(id = "different-assignment"), event("different-point", 2, PointWon(Side.A)))) {
                assertFails { f.data.cloud.storeDownloaded(listOf(conflict), fence) }
                assertEquals(0, f.count("match_event"))
                assertEquals(1, f.count("replica_redacted_event"))
            }
            f.data.cloud.storeDownloaded(listOf(original), fence)
            assertEquals(
                redacted.payload,
                f.db.cloudUploadQueries
                    .eventById(original.id)
                    .executeAsOne()
                    .payload,
            )
            val otherDevice = event("other-device-event", 2, PointWon(Side.A)).copy(deviceId = "another-device")
            val otherMatch = event("other-match-event", 2, PointWon(Side.A)).copy(matchId = "another-match")
            f.data.cloud.storeDownloaded(listOf(otherDevice, otherMatch), fence)
            assertEquals(3, f.count("match_event"))
        }
    }

    @Test
    fun manifestBeforeRawDownloadPersistsEventProofWithoutGrantingReadinessOrGlobalIdentity() {
        for (active in listOf(false, true)) {
            for (legacyType in listOf("", "PlayerAssigned")) {
                Fixture(activate = active).use { f ->
                    var ctx = f.data.replicas.captureContext()
                    f.data.replicas.applyAccess(ctx, "match", 1, ReplicaAccessLevel.MEMBER, fence)
                    f.cloudDirectoryPlayer()
                    val player = f.data.players.get(PlayerId("deleted"))
                    val playerMetadata = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "deleted")
                    val original =
                        event("assignment", 2, PlayerAssigned(slot, Player(PlayerId("deleted"), "Sensitive name"), owner))
                            .copy(type = legacyType)
                    val redacted = original.copy(payload = original.payload.replace("Sensitive name", "Gelöschter Spieler"))
                    val transfer = RedactionTransfer("before-raw", "match", 1, 1, 3)
                    val from = f.data.replicas.beginRedaction(ctx, transfer, fence)
                    val first =
                        RedactionPage(
                            0,
                            from,
                            RedactionCursor(null, 2),
                            emptyList(),
                            listOf(ReplicaRedactionEvent(redacted, 2)),
                            false,
                            true,
                        )
                    f.data.replicas.stageRedaction(ctx, transfer.id, first, fence)
                    assertFails { f.data.replicas.completeRedaction(ctx, transfer.id, fence) }
                    assertEquals(original, f.data.replicas.sanitizeLegacyEvent(original))
                    assertEquals(0, f.count("replica_redacted_event"))
                    f.reopen()
                    ctx = f.data.replicas.captureContext()
                    f.data.replicas.stageRedaction(
                        ctx,
                        transfer.id,
                        RedactionPage(1, first.next, first.next, emptyList(), emptyList(), true, true),
                        fence,
                    )
                    f.data.replicas.completeRedaction(ctx, transfer.id, fence)
                    assertEquals(redacted, f.data.replicas.sanitizeLegacyEvent(original))
                    assertEquals(1, f.count("replica_redacted_event"))
                    assertEquals(0, f.count("replica_redacted_player"))
                    assertEquals(0, f.count("match_event"))
                    assertEquals(0, f.count("cloud_pull_receipt"))
                    assertEquals(0, f.data.cloud.cursor("A", CloudPullStore.eventStream("match")))
                    assertFalse(
                        f.data.replicas
                            .access(ctx, "match")!!
                            .ready,
                    )
                    assertEquals(player, f.data.players.get(PlayerId("deleted")))
                    assertEquals(playerMetadata, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "deleted"))
                    assertEquals(first, f.data.replicas.exportRedactionPage(ctx, transfer.id, 0))

                    f.reopen()
                    ctx = f.data.replicas.captureContext()
                    assertTrue(
                        f.data.replicas
                            .redactionProgress(ctx, "match")!!
                            .completed,
                    )
                    f.data.replicas.completeRedaction(ctx, transfer.id, fence)
                    f.cloudMatch(assignment = true)
                    assertEquals(
                        redacted.payload,
                        f.db.cloudUploadQueries
                            .eventById("assignment")
                            .executeAsOne()
                            .payload,
                    )
                    assertTrue(
                        slot.value in
                            f.db.matchAssignmentQueries
                                .redactedSlots("match")
                                .executeAsList(),
                    )
                    assertFalse(
                        f.data.replicas
                            .access(ctx, "match")!!
                            .ready,
                    )
                    if (active) assertFalse(f.data.replicas.canReadMember(ctx, "match"))
                    assertEquals(player, f.data.players.get(PlayerId("deleted")))
                    assertEquals(playerMetadata, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "deleted"))
                    val unrelated = original.copy(id = "unrelated-assignment", matchId = "unrelated")
                    assertEquals(unrelated, f.data.replicas.sanitizeLegacyEvent(unrelated))
                    assertFails { f.data.cloud.storeDownloaded(listOf(original.copy(lamport = 99)), fence) }
                    assertFails {
                        f.data.cloud.storeDownloaded(
                            listOf(original.copy(payload = original.payload.replace("\"owner\"", "\"outsider\""))),
                            fence,
                        )
                    }
                    assertEquals(
                        redacted.payload,
                        f.db.cloudUploadQueries
                            .eventById("assignment")
                            .executeAsOne()
                            .payload,
                    )
                }
            }
        }
    }

    @Test
    fun manifestBeforeRawDownloadFinalFenceRollsBackProofAndEpoch() {
        Fixture().use { f ->
            val ctx = f.data.replicas.captureContext()
            f.data.replicas.applyAccess(ctx, "match", 1, ReplicaAccessLevel.MEMBER, fence)
            val redacted = event("missing", 2, PlayerAssigned(slot, Player(PlayerId("living"), "Gelöschter Spieler"), owner))
            val transfer = RedactionTransfer("before-raw-rollback", "match", 1, 1, 2)
            val from = f.data.replicas.beginRedaction(ctx, transfer, fence)
            f.data.replicas.stageRedaction(
                ctx,
                transfer.id,
                RedactionPage(0, from, RedactionCursor(null, 2), emptyList(), listOf(ReplicaRedactionEvent(redacted, 2)), true, true),
                fence,
            )
            var reachedFinalFence = false
            assertFails {
                f.data.replicas.completeRedaction(ctx, transfer.id) {
                    if (f.data.replicas
                            .redactionProgress(ctx, "match")!!
                            .completed
                    ) {
                        reachedFinalFence = true
                        error("Autorität nach Belegspeicherung entzogen")
                    }
                }
            }
            assertTrue(reachedFinalFence)
            assertEquals(0, f.count("replica_redacted_event"))
            assertEquals(0, f.count("replica_redacted_player"))
            assertEquals(0, f.count("match_event"))
            assertEquals(
                0,
                f.data.replicas
                    .access(ctx, "match")!!
                    .redactionEpoch,
            )
            assertFalse(
                f.data.replicas
                    .redactionProgress(ctx, "match")!!
                    .completed,
            )
        }
    }

    @Test
    fun manifestBeforeRawDownloadRejectsMalformedAuthorityAtomically() {
        val clean = event("bad", 3, PlayerAssigned(slot, Player(PlayerId("living"), "Gelöschter Spieler"), owner))
        val invalid =
            listOf(
                clean.copy(id = ""),
                clean.copy(deviceId = ""),
                clean.copy(deviceSeq = 0),
                clean.copy(lamport = -1),
                clean.copy(type = "PointWon"),
                clean.copy(payload = clean.payload.replace("Gelöschter Spieler", "Sensitive name")),
                clean.copy(payload = clean.payload.replace("\"living\"", "\"\"")),
                clean.copy(payload = clean.payload.replace("\"owner\"", "\"\"")),
                clean.copy(payload = clean.payload.replace("\"${slot.value}\"", "\"\"")),
                event("reference", 3, PlayerAssigned.reference(slot, PlayerId("living"), owner)),
                event("point", 3, PointWon(Side.A)),
            )
        for ((index, bad) in invalid.withIndex()) {
            Fixture().use { f ->
                val ctx = f.data.replicas.captureContext()
                f.data.replicas.applyAccess(ctx, "match", 1, ReplicaAccessLevel.MEMBER, fence)
                val transfer = RedactionTransfer("invalid-missing-$index", "match", 1, 1, 3)
                val from = f.data.replicas.beginRedaction(ctx, transfer, fence)
                f.data.replicas.stageRedaction(
                    ctx,
                    transfer.id,
                    RedactionPage(
                        0,
                        from,
                        RedactionCursor(null, 3),
                        emptyList(),
                        listOf(ReplicaRedactionEvent(clean.copy(id = "valid-first", deviceSeq = 2), 2), ReplicaRedactionEvent(bad, 3)),
                        true,
                        true,
                    ),
                    fence,
                )
                assertFails { f.data.replicas.completeRedaction(ctx, transfer.id, fence) }
                assertEquals(0, f.count("replica_redacted_event"), "Malformed entry $index must roll back the earlier valid proof")
                assertEquals(0, f.count("match_event"))
                assertEquals(
                    0,
                    f.data.replicas
                        .access(ctx, "match")!!
                        .redactionEpoch,
                )
                assertFalse(
                    f.data.replicas
                        .redactionProgress(ctx, "match")!!
                        .completed,
                )
            }
        }
    }

    @Test
    fun eventOnlyRedactionPreservesLivingDirectoryAndUnrelatedMatchAfterReopenAndPurge() {
        for (active in listOf(false, true)) {
            Fixture(activate = active).use { f ->
                var ctx = f.member("A")
                val original = f.cloudMatch(assignment = true).single { it.id == "assignment" }
                f.cloudDirectoryPlayer()
                val redacted = original.copy(payload = original.payload.replace("Sensitive name", "Gelöschter Spieler"))
                val transfer = RedactionTransfer("event-only", "match", 1, 1, 3)
                val from = f.data.replicas.beginRedaction(ctx, transfer, fence)
                f.data.replicas.stageRedaction(
                    ctx,
                    transfer.id,
                    RedactionPage(0, from, RedactionCursor(null, 2), emptyList(), listOf(ReplicaRedactionEvent(redacted, 2)), true, true),
                    fence,
                )
                var invalidations = 0
                val stopObserving = f.data.observeDirectoryChanges { invalidations++ }
                f.data.replicas.completeRedaction(ctx, transfer.id, fence)
                assertTrue(invalidations > 0)
                stopObserving()
                assertFalse(f.data.replicas.isRedacted("deleted"))
                assertEquals(0, f.count("replica_redacted_player"))
                f.data.replicas.markReady(ctx, "match", 1, 1, fence)
                f.data.rebuildAssignments(MatchId("match"))
                assertEquals(
                    "Sensitive name",
                    f.data.players
                        .get(PlayerId("deleted"))!!
                        .displayName,
                )
                assertEquals("", f.data.storedAssignments(MatchId("match"))[slot]!!.displayName)
                assertEquals("", f.data.assignments(MatchId("match"))[slot]!!.displayName)
                assertEquals(
                    "",
                    f.data.cloud
                        .uploadParticipants("match", "A")!!
                        .participants
                        .single { it.slotId == slot.value }
                        .displayName,
                )
                assertEquals(
                    redacted.payload,
                    f.data.replicas
                        .sanitizeLegacyEvent(original)
                        .payload,
                )
                val unrelated = original.copy(id = "other-assignment", matchId = "other-match")
                assertEquals(unrelated, f.data.replicas.sanitizeLegacyEvent(unrelated))

                f.reopen()
                ctx = f.data.replicas.captureContext()
                assertEquals("", f.data.assignments(MatchId("match"))[slot]!!.displayName)
                f.data.replicas.applyAccess(ctx, "match", 2, ReplicaAccessLevel.ABSENT, fence)
                assertEquals(0, f.count("match_event"))
                assertEquals(
                    redacted.payload,
                    f.data.replicas
                        .sanitizeLegacyEvent(original)
                        .payload,
                )
                assertFails { f.data.replicas.sanitizeLegacyEvent(original.copy(wallClockMs = original.wallClockMs + 1)) }
                assertFails { f.data.replicas.sanitizeLegacyEvent(original.copy(matchId = "other-match")) }
                f.data.replicas.applyAccess(ctx, "match", 3, ReplicaAccessLevel.MEMBER, fence)
                f.data.cloud.storeDownloaded(listOf(original), fence)
                assertEquals(
                    redacted.payload,
                    f.db.cloudUploadQueries
                        .eventById(original.id)
                        .executeAsOne()
                        .payload,
                )
            }
        }
    }

    @Test
    fun eventOnlyLegacyEmptyTypeAcceptsCanonicalCloudDuplicateWithoutWeakeningTypeChecks() {
        Fixture().use { f ->
            val ctx = f.member("A")
            val original = f.cloudMatch(assignment = true).single { it.id == "assignment" }
            f.sql("UPDATE match_event SET type='' WHERE id='assignment'")
            val redacted = original.copy(type = "", payload = original.payload.replace("Sensitive name", "Gelöschter Spieler"))
            val transfer = RedactionTransfer("event-empty-type", "match", 1, 1, 3)
            val from = f.data.replicas.beginRedaction(ctx, transfer, fence)
            f.data.replicas.stageRedaction(
                ctx,
                transfer.id,
                RedactionPage(0, from, RedactionCursor(null, 2), emptyList(), listOf(ReplicaRedactionEvent(redacted, 2)), true, true),
                fence,
            )
            f.data.replicas.completeRedaction(ctx, transfer.id, fence)
            f.data.cloud.storeDownloaded(listOf(original), fence)
            assertEquals(
                redacted.payload,
                f.db.cloudUploadQueries
                    .eventById(original.id)
                    .executeAsOne()
                    .payload,
            )
            assertFails { f.data.cloud.storeDownloaded(listOf(original.copy(type = "PointWon")), fence) }
        }
    }

    @Test
    fun eventOnlyRedactionFollowsWinningAssignmentWithoutRetiringItsPlayer() {
        Fixture().use { f ->
            val ctx = f.member("A")
            val original = f.cloudMatch(assignment = true).single { it.id == "assignment" }
            f.cloudDirectoryPlayer()
            val redacted = original.copy(payload = original.payload.replace("Sensitive name", "Gelöschter Spieler"))
            val transfer = RedactionTransfer("event-winner", "match", 1, 1, 3)
            val from = f.data.replicas.beginRedaction(ctx, transfer, fence)
            f.data.replicas.stageRedaction(
                ctx,
                transfer.id,
                RedactionPage(0, from, RedactionCursor(null, 2), emptyList(), listOf(ReplicaRedactionEvent(redacted, 2)), true, true),
                fence,
            )
            f.data.replicas.completeRedaction(ctx, transfer.id, fence)
            f.data.replicas.markReady(ctx, "match", 1, 1, fence)
            val before = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")!!
            val invalid = event("invalid-assignment", 4, PlayerAssigned(slot, Player(PlayerId("deleted"), "Other"), PlayerId("outsider")))
            f.data.cloud.storeDownloaded(listOf(invalid), fence)
            assertEquals("", f.data.storedAssignments(MatchId("match"))[slot]!!.displayName)
            assertEquals(before, f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match"))
            val next = event("new-assignment", 5, PlayerAssigned(slot, Player(PlayerId("deleted"), "Other"), owner))
            f.data.cloud.storeDownloaded(listOf(next), fence)
            assertEquals("Sensitive name", f.data.storedAssignments(MatchId("match"))[slot]!!.displayName)
            val after = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")!!
            assertTrue(after.selected.revision > before.selected.revision)
            assertEquals(before.uploadedRevision, after.uploadedRevision)
            assertEquals(
                redacted.payload,
                f.data.replicas
                    .sanitizeLegacyEvent(original)
                    .payload,
            )
        }
    }

    @Test
    fun eventOnlyRedactionRejectsChangedPayloadAndEnvelopeAtomically() {
        val changes: List<(StoredEvent) -> StoredEvent> =
            listOf(
                { it.copy(lamport = it.lamport + 1) },
                { it.copy(payload = it.payload.replace("\"deleted\"", "\"other\"")) },
                { it.copy(payload = it.payload.replace("\"owner\"", "\"outsider\"")) },
            )
        for ((index, change) in changes.withIndex()) {
            Fixture().use { f ->
                val ctx = f.member("A")
                val original = f.cloudMatch(assignment = true).single { it.id == "assignment" }
                val redacted = original.copy(payload = original.payload.replace("Sensitive name", "Gelöschter Spieler"))
                val transfer = RedactionTransfer("invalid-$index", "match", 1, 1, 3)
                val from = f.data.replicas.beginRedaction(ctx, transfer, fence)
                f.data.replicas.stageRedaction(
                    ctx,
                    transfer.id,
                    RedactionPage(
                        0,
                        from,
                        RedactionCursor(null, 2),
                        emptyList(),
                        listOf(ReplicaRedactionEvent(change(redacted), 2)),
                        true,
                        true,
                    ),
                    fence,
                )
                assertFails { f.data.replicas.completeRedaction(ctx, transfer.id, fence) }
                assertEquals(original, f.data.replicas.sanitizeLegacyEvent(original))
                assertEquals(
                    original.payload,
                    f.db.cloudUploadQueries
                        .eventById(original.id)
                        .executeAsOne()
                        .payload,
                )
                assertEquals(0, f.count("replica_redacted_event"))
                assertEquals(
                    0,
                    f.data.replicas
                        .access(ctx, "match")!!
                        .redactionEpoch,
                )
            }
        }
    }

    @Test
    fun eventOnlyRedactionFinalFenceRollsBackPayloadAndDurableAuthority() {
        Fixture().use { f ->
            val ctx = f.member("A")
            val original = f.cloudMatch(assignment = true).single { it.id == "assignment" }
            val redacted = original.copy(payload = original.payload.replace("Sensitive name", "Gelöschter Spieler"))
            val transfer = RedactionTransfer("event-only-rollback", "match", 1, 1, 3)
            val from = f.data.replicas.beginRedaction(ctx, transfer, fence)
            f.data.replicas.stageRedaction(
                ctx,
                transfer.id,
                RedactionPage(0, from, RedactionCursor(null, 2), emptyList(), listOf(ReplicaRedactionEvent(redacted, 2)), true, true),
                fence,
            )
            var sawCompleted = false
            assertFails {
                f.data.replicas.completeRedaction(ctx, transfer.id) {
                    if (f.data.replicas
                            .redactionProgress(ctx, "match")!!
                            .completed
                    ) {
                        sawCompleted = true
                        error("Finaler Autoritätsverlust")
                    }
                }
            }
            assertTrue(sawCompleted)
            assertEquals(
                original.payload,
                f.db.cloudUploadQueries
                    .eventById(original.id)
                    .executeAsOne()
                    .payload,
            )
            assertEquals(original, f.data.replicas.sanitizeLegacyEvent(original))
            assertFalse(
                f.data.replicas
                    .redactionProgress(ctx, "match")!!
                    .completed,
            )
        }
    }

    @Test
    fun persistedProgressResumesIndependentCursorsAndExportsOnlyCompletedPages() {
        Fixture().use { f ->
            var ctx = f.member("A")
            val transfer = RedactionTransfer("resume", "match", 1, 1, 3)
            val start = f.data.replicas.beginRedaction(ctx, transfer, fence)
            val first = RedactionPage(0, start, RedactionCursor("deleted", 0), listOf("deleted"), emptyList(), true, false)
            f.data.replicas.stageRedaction(ctx, transfer.id, first, fence)
            assertEquals(
                RedactionProgress(transfer, first.next, 1, true, false, false),
                f.data.replicas.redactionProgress(ctx, "match"),
            )
            assertFails { f.data.replicas.exportRedactionPage(ctx, transfer.id, 0) }
            f.reopen()
            assertFails { f.data.replicas.redactionProgress(ctx, "match") }
            ctx = f.data.replicas.captureContext()
            val resumed = assertNotNull(f.data.replicas.redactionProgress(ctx, "match"))
            val last = RedactionPage(resumed.nextPageIndex, resumed.cursor, resumed.cursor, emptyList(), emptyList(), true, true)
            f.data.replicas.stageRedaction(ctx, transfer.id, last, fence)
            assertFails { f.data.replicas.exportRedactionPage(ctx, transfer.id, 0) }
            f.data.replicas.completeRedaction(ctx, transfer.id, fence)
            f.reopen()
            ctx = f.data.replicas.captureContext()
            assertEquals(
                RedactionProgress(transfer, last.next, 2, true, true, true),
                f.data.replicas.redactionProgress(ctx, "match"),
            )
            assertEquals(first, f.data.replicas.exportRedactionPage(ctx, transfer.id, 0))
            assertEquals(last, f.data.replicas.exportRedactionPage(ctx, transfer.id, 1))
            assertNull(f.data.replicas.exportRedactionPage(ctx, transfer.id, 2))
            assertFails { f.data.replicas.exportRedactionPage(ctx, transfer.id, -1) }
        }
    }

    @Test
    fun progressAndManifestExportRejectOtherAccountsAndRetiredGenerations() {
        Fixture().use { f ->
            val ctx = f.member("A")
            val transfer = RedactionTransfer("private", "match", 1, 1, 0)
            val start = f.data.replicas.beginRedaction(ctx, transfer, fence)
            f.data.replicas.stageRedaction(ctx, transfer.id, RedactionPage(0, start, start, emptyList(), emptyList(), true, true), fence)
            f.data.replicas.completeRedaction(ctx, transfer.id, fence)
            val other = f.select("B")
            assertNull(f.data.replicas.redactionProgress(other, "match"))
            assertFails { f.data.replicas.exportRedactionPage(other, transfer.id, 0) }
            assertFails { f.data.replicas.exportRedactionPage(ctx, transfer.id, 0) }
            val current = f.select("A")
            assertNotNull(f.data.replicas.exportRedactionPage(current, transfer.id, 0))
            f.data.replicas.applyAccess(current, "match", 2, ReplicaAccessLevel.FEED, fence)
            assertNull(f.data.replicas.redactionProgress(current, "match"))
            assertFails { f.data.replicas.exportRedactionPage(current, transfer.id, 0) }
        }
    }

    @Test
    fun accountRevocationAndRegrantKeepOtherMemberAcrossReopen(): Unit =
        Fixture().use { f ->
            f.member("B")
            val a = f.member("A")
            val original = f.cloudMatch()
            assertEquals(original.size.toLong(), f.data.events.count("match"))
            for ((generation, level) in listOf(2L to ReplicaAccessLevel.FEED, 3L to ReplicaAccessLevel.ABSENT)) {
                val ctx = f.data.replicas.captureContext()
                f.sql(
                    """INSERT OR REPLACE INTO cloud_pull_snapshot(profile_id,kind,entity_id,server_version,snapshot_json)
                    VALUES ('A','MATCH','match',1,'{"private":true}')""",
                )
                f.data.replicas.applyAccess(ctx, "match", generation, level, fence)
                f.reopen()
                assertNull(f.data.matches.get(MatchId("match")))
                assertTrue(
                    f.data.matches
                        .history()
                        .isEmpty(),
                )
                assertTrue(
                    f.data.matches
                        .recentLineups()
                        .isEmpty(),
                )
                assertEquals(0, f.data.events.count("match"))
                assertTrue(
                    f.data.events
                        .eventsFor("match")
                        .isEmpty(),
                )
                assertEquals(0, f.count("cloud_pull_snapshot", "profile_id='A'"))
                assertEquals(original.size.toLong(), f.count("match_event"))
                val b = f.select("B")
                assertTrue(f.data.replicas.canReadMember(b, "match"))
                assertEquals(
                    original.map { it.payload },
                    f.data.events
                        .eventsFor("match")
                        .map { it.payload },
                )
                f.select("A")
            }
            assertFails { f.data.replicas.access(a, "match") }
            val again = f.data.replicas.captureContext()
            f.data.replicas.applyAccess(again, "match", 4, ReplicaAccessLevel.MEMBER, fence)
            assertFalse(f.data.replicas.canReadMember(again, "match"))
            f.data.replicas.markReady(again, "match", 4, 0, fence)
            assertTrue(f.data.replicas.canReadMember(again, "match"))
        }

    @Test
    fun lastReferencePurgesPayloadAndReceiptsWithoutTombstoneButKeepsMarker(): Unit =
        Fixture().use { f ->
            val ctx = f.member("A")
            val events = f.cloudMatch()
            f.sql("INSERT INTO cloud_pull_receipt VALUES ('A','match','genesis',1,'server-device',1,0)")
            f.sql("INSERT INTO cloud_cursor VALUES ('A','pull:events:match',3)")
            f.data.replicas.applyAccess(ctx, "match", 2, ReplicaAccessLevel.ABSENT, fence)
            f.reopen()
            listOf("match_event", "match_header", "match_assignment", "cloud_pull_receipt", "cloud_cursor", "match_tombstone").forEach {
                assertEquals(0, f.count(it), it)
            }
            assertTrue(f.data.replicas.isCloudSeen("match"))
            assertFails { f.data.events.append(events.first()) }
            assertFails { f.data.matches.insertIfAbsent(MatchId("match"), format, lineup, MatchMeta(), 0) }
        }

    @Test
    fun contextRevisionIsPersistentTransactionalAndStoreBound(): Unit =
        Fixture().use { f ->
            val a = f.data.replicas.captureContext()
            f.select("A")
            assertNull(f.data.replicas.access(a, "match"))
            assertFails {
                f.data.transaction {
                    f.select("B")
                    error("rollback")
                }
            }
            assertNull(f.data.replicas.access(a, "match"))
            f.select("B")
            f.select("A")
            assertFails { f.data.replicas.applyAccess(a, "match", 1, ReplicaAccessLevel.MEMBER, fence) }
            val new = f.data.replicas.captureContext()
            var calls = 0
            assertFails {
                f.data.replicas.applyAccess(
                    new,
                    "match",
                    1,
                    ReplicaAccessLevel.MEMBER,
                ) { if (++calls == 2) error("lost authority") }
            }
            assertNull(f.data.replicas.access(new, "match"))
            assertFalse(f.data.replicas.isCloudSeen("match"))
            f.reopen()
            assertFails { f.data.replicas.access(new, "match") }
        }

    @Test
    fun lowerGenerationIsIgnoredAndSameGenerationConflictsRollback(): Unit =
        Fixture().use { f ->
            val ctx = f.member("A", 4)
            assertEquals(TransitionResult.IGNORED, f.data.replicas.applyAccess(ctx, "match", 3, ReplicaAccessLevel.ABSENT, fence))
            assertFails { f.data.replicas.applyAccess(ctx, "match", 4, ReplicaAccessLevel.FEED, fence) }
            assertEquals(
                ReplicaAccessLevel.MEMBER,
                f.data.replicas
                    .access(ctx, "match")!!
                    .access,
            )
            f.data.replicas.applyAccess(ctx, "match", 5, ReplicaAccessLevel.MEMBER, fence)
            assertFalse(
                f.data.replicas
                    .access(ctx, "match")!!
                    .ready,
            )
            assertFails { f.data.replicas.markReady(ctx, "match", 4, 0, fence) }
            f.data.replicas.applyAccess(ctx, "unknown", null, ReplicaAccessLevel.UNKNOWN, fence)
            assertFalse(f.data.replicas.canReadMember(ctx, "unknown"))
        }

    @Test
    fun unsavedOwnQueueSurvivesRevocationButDoesNotBecomeSendableUntilRevalidation(): Unit =
        Fixture().use { f ->
            val ctx = f.member("A")
            f.cloudMatch()
            val device = f.data.settings.get("session.deviceId")!!
            val own = event("own", 4, PointWon(Side.B)).copy(deviceId = device, deviceSeq = 1)
            f.data.events.append(own)
            f.data.replicas.applyAccess(ctx, "match", 2, ReplicaAccessLevel.ABSENT, fence)
            assertEquals(1, f.count("match_event", "id='own' AND cloud_state=0 AND confirmed=0"))
            assertEquals(0, f.data.events.unconfirmedCount())
            f.data.replicas.applyAccess(ctx, "match", 3, ReplicaAccessLevel.MEMBER, fence)
            assertTrue(
                f.data.events
                    .unconfirmedEvents("match")
                    .isEmpty(),
            )
            f.data.replicas.markReady(ctx, "match", 3, 0, fence)
            assertTrue(
                f.data.events
                    .unconfirmedEvents("match")
                    .any { it.id == "own" },
            )
        }

    @Test
    fun localGenesisIsReadableButOwnPointDoesNotOwnForeignGenesis(): Unit =
        Fixture().use { f ->
            val source =
                f.db.localIdentityQueries
                    .identityForScope("scope-A")
                    .executeAsOne()
            val self = PlayerId(source.self_id)
            val localLineup = Lineup(listOf(self), listOf(PlayerId("guest")), self)
            val genesis = event("local", 1, MatchCreated(format, localLineup, createdBy = self)).copy(deviceId = source.device_id)
            f.data.events.append(genesis)
            f.data.matches.insertIfAbsent(MatchId("match"), format, localLineup, MatchMeta(), 1)
            assertTrue(f.data.replicas.canReadMember(f.data.replicas.captureContext(), "match"))
            assertNotNull(f.data.matches.get(MatchId("match")))
            assertFails {
                f.data.events.append(
                    event("foreign-own-point", 2, PointWon(Side.A)).copy(matchId = "foreign", deviceId = source.device_id),
                )
            }
            assertFails {
                f.data.events.append(
                    event(
                        "foreign-genesis",
                        1,
                        MatchCreated(format, lineup, createdBy = owner),
                    ).copy(matchId = "foreign", deviceId = source.device_id),
                )
            }
        }

    @Test
    fun stagedRedactionResumesAndLostAcknowledgementReplaysIndependentlyOfEventAcks(): Unit =
        Fixture().use { f ->
            var ctx = f.member("A")
            val original = f.cloudMatch(assignment = true)
            f.cloudDirectoryPlayer()
            f.data.events.markConfirmedUpTo("match", "server-device", 100)
            f.data.replicas.registerPeer(ctx, "watch", fence)
            f.data.replicas.ackAccess(ctx, "watch", "match", 1, ReplicaAccessLevel.MEMBER, fence)
            f.data.replicas.ackRedaction(ctx, "watch", "match", 1, 0, fence)
            val transfer = RedactionTransfer("transfer", "match", 1, 1, 3)
            val start = f.data.replicas.beginRedaction(ctx, transfer, fence)
            val ids = RedactionPage(0, start, RedactionCursor("deleted", 0), listOf("deleted"), emptyList(), true, false)
            f.data.replicas.stageRedaction(ctx, "transfer", ids, fence)
            f.data.replicas.stageRedaction(ctx, "transfer", ids, fence)
            assertFails { f.data.replicas.stageRedaction(ctx, "transfer", ids.copy(playerIds = listOf("other")), fence) }
            assertEquals(0, f.count("replica_redacted_player"))
            f.reopen()
            ctx = f.data.replicas.captureContext()
            assertEquals(ids.next, f.data.replicas.beginRedaction(ctx, transfer, fence))
            val redacted =
                original
                    .single {
                        it.id == "assignment"
                    }.let { it.copy(payload = it.payload.replace("Sensitive name", "Gelöschter Spieler")) }
            f.data.replicas.stageRedaction(
                ctx,
                "transfer",
                RedactionPage(
                    1,
                    ids.next,
                    RedactionCursor("deleted", 2),
                    emptyList(),
                    listOf(ReplicaRedactionEvent(redacted, 2)),
                    true,
                    true,
                ),
                fence,
            )
            f.sql(
                "UPDATE cloud_metadata SET revision=revision+3 WHERE (kind='PARTICIPANTS' AND entity_id='match') OR (kind='PLAYER' AND entity_id='deleted')",
            )
            val revision = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")
            val playerRevision = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "deleted")
            assertTrue(revision!!.selected.revision > revision.uploadedRevision)
            assertTrue(playerRevision!!.selected.revision > playerRevision.uploadedRevision)
            f.data.replicas.completeRedaction(ctx, "transfer", fence)
            f.data.replicas.completeRedaction(ctx, "transfer", fence)
            assertEquals(revision, f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match"))
            assertEquals(playerRevision, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "deleted"))
            assertEquals(original.size.toLong(), f.count("match_event", "confirmed=1 AND cloud_state=1"))
            assertEquals(
                1,
                f.data.replicas
                    .access(ctx, "match")!!
                    .redactionEpoch,
            )
            assertFalse(
                f.data.replicas
                    .access(ctx, "match")!!
                    .ready,
            )
            f.data.replicas.markReady(ctx, "match", 1, 1, fence)
            assertEquals(
                ScoreSummary.of(
                    ScoringEngine
                        .replay(
                            original.map {
                                it.toScoreEvent()
                            },
                        ).state,
                ),
                ScoreSummary.of(ScoringEngine.replay(f.data.events.scoreEvents(MatchId("match"))).state),
            )
            assertEquals(
                "",
                f.data.players
                    .get(PlayerId("deleted"))!!
                    .displayName,
            )
            f.data.players.upsertRemote(Player(PlayerId("deleted"), "restored"), 9)
            assertFails { f.data.players.rename(PlayerId("deleted"), "restored") }
            assertEquals(
                "",
                f.data.players
                    .get(PlayerId("deleted"))!!
                    .displayName,
            )
            f.reopen()
            ctx = f.data.replicas.captureContext()
            val delivery =
                f.data.replicas
                    .pendingDeliveries(ctx, "watch", null, 20)
                    .deliveries
                    .single()
            assertTrue(delivery.accessAcknowledged)
            assertFalse(delivery.redactionAcknowledged)
            f.data.replicas.ackRedaction(ctx, "watch", "match", 1, 0, fence)
            assertEquals(
                1,
                f.data.replicas
                    .pendingDeliveries(ctx, "watch", null, 20)
                    .deliveries.size,
            )
            f.data.replicas.ackRedaction(ctx, "watch", "match", 1, 1, fence)
            assertTrue(
                f.data.replicas
                    .pendingDeliveries(ctx, "watch", null, 20)
                    .deliveries
                    .isEmpty(),
            )
            f.member("B")
            assertEquals(
                "",
                f.data.players
                    .get(PlayerId("deleted"))!!
                    .displayName,
            )
        }

    @Test
    fun tamperingWithEveryImmutableEventComponentRollsBackManifestAndEpoch() {
        val changes: List<(StoredEvent) -> StoredEvent> =
            listOf(
                { it.copy(id = "other") },
                { it.copy(deviceId = "other") },
                { it.copy(deviceSeq = 99) },
                { it.copy(lamport = 99) },
                { it.copy(wallClockMs = 99) },
                { it.copy(parentId = "other") },
                { it.copy(type = "PointWon") },
                { it.copy(payload = it.payload.replace("owner", "other")) },
                { it.copy(payload = it.payload.replace("Gelöschter Spieler", "Unauthorized")) },
            )
        changes.forEach { change ->
            Fixture().use { f ->
                val ctx = f.member("A")
                val old = f.cloudMatch(true).single { it.id == "assignment" }
                val replacement = change(old.copy(payload = old.payload.replace("Sensitive name", "Gelöschter Spieler")))
                val start = f.data.replicas.beginRedaction(ctx, RedactionTransfer("t", "match", 1, 1, 3), fence)
                f.data.replicas.stageRedaction(
                    ctx,
                    "t",
                    RedactionPage(
                        0,
                        start,
                        RedactionCursor("deleted", 2),
                        listOf("deleted"),
                        listOf(ReplicaRedactionEvent(replacement, 2)),
                        true,
                        true,
                    ),
                    fence,
                )
                assertFails { f.data.replicas.completeRedaction(ctx, "t", fence) }
                assertEquals(0, f.count("replica_redacted_player"))
                assertEquals(
                    0,
                    f.data.replicas
                        .access(ctx, "match")!!
                        .redactionEpoch,
                )
                assertEquals(
                    old.payload,
                    f.db.cloudUploadQueries
                        .eventById("assignment")
                        .executeAsOne()
                        .payload,
                )
            }
        }
    }

    @Test
    fun pagesEnforceSizeSequenceHighWatermarkAndTerminalFlags(): Unit =
        Fixture().use { f ->
            val ctx = f.member("A")
            f.cloudMatch()
            val start = f.data.replicas.beginRedaction(ctx, RedactionTransfer("t", "match", 1, 1, 3), fence)
            val empty = RedactionPage(0, start, start, emptyList(), emptyList(), false, false)
            assertFails { f.data.replicas.stageRedaction(ctx, "t", empty, fence) }
            assertFails { f.data.replicas.stageRedaction(ctx, "t", empty.copy(playerIds = List(201) { "p$it" }), fence) }
            val huge = "x".repeat(65536)
            assertFails {
                f.data.replicas.stageRedaction(
                    ctx,
                    "t",
                    empty.copy(playerIds = listOf(huge), next = RedactionCursor(huge, 0)),
                    fence,
                )
            }
            assertFails {
                f.data.replicas.stageRedaction(
                    ctx,
                    "t",
                    empty.copy(events = listOf(ReplicaRedactionEvent(event("x", 4, PointWon(Side.A)), 4)), next = RedactionCursor(null, 4)),
                    fence,
                )
            }
            val terminal = empty.copy(idsDone = true, eventsDone = true)
            f.data.replicas.stageRedaction(ctx, "t", terminal, fence)
            f.data.replicas.completeRedaction(ctx, "t", fence)
            assertFails { f.data.replicas.stageRedaction(ctx, "t", terminal.copy(index = 1, idsDone = false), fence) }
        }

    @Test
    fun registerAndReadMoreThanTenThousandDeliveriesUsesBoundedKeysetPages(): Unit =
        Fixture().use { f ->
            val ctx = f.data.replicas.captureContext()
            f.data.transaction {
                repeat(10001) { f.db.replicaLifecycleQueries.putAccess("A", "m${it.toString().padStart(5,'0')}", 1, "MEMBER") }
            }
            f.data.replicas.registerPeer(ctx, "watch", fence)
            var after: String? = null
            var count = 0
            var pages = 0
            do {
                val page = f.data.replicas.pendingDeliveries(ctx, "watch", after, 200)
                assertTrue(page.deliveries.size <= 200)
                count += page.deliveries.size
                pages++
                after = page.nextId
            } while (page.hasMore)
            assertEquals(10001, count)
            assertEquals(51, pages)
            val sql = File("src/commonMain/sqldelight/eu/advantly/data/db/ReplicaLifecycle.sq").readText().replace("\r\n", "\n")
            assertTrue(
                sql.contains(
                    "INSERT OR IGNORE INTO replica_delivery(account_key,peer_id,match_id,generation,access,redaction_epoch)\nSELECT",
                ),
            )
            assertTrue(sql.contains("match_id > :afterId"))
            assertFalse(sql.contains("OFFSET"))
        }

    @Test
    fun deliberateU2AdoptionStillAllowsLegacyOwnedGenesisWithoutCloudMarker(): Unit =
        Fixture(activate = false).use { f ->
            f.data.accountIdentities.recordSource("watch", "watch-self", "WATCH", "old-watch", local = false)
            val self = PlayerId("watch-self")
            val legacyLineup = Lineup(listOf(self), listOf(slot), self)
            f.data.events.append(event("legacy", 1, MatchCreated(format, legacyLineup, createdBy = self)).copy(deviceId = "watch"))
            f.data.matches.insertIfAbsent(MatchId("match"), format, legacyLineup, MatchMeta(), 0)
            f.data.replicas.activate(f.data.replicas.captureContext(), fence)
            assertFalse(f.data.replicas.canReadMember(f.data.replicas.captureContext(), "match"))
            val session = f.data.cloudUploads.activate("A")
            f.data.accountIdentities.adopt(session, setOf("watch"))
            assertTrue(f.data.replicas.canReadMember(f.data.replicas.captureContext(), "match"))
            assertFalse(f.data.replicas.isCloudSeen("match"))
        }

    @Test
    fun incompleteRedactionCannotBeBypassedByRegrantOrEpochRollback(): Unit =
        Fixture().use { f ->
            val ctx = f.member("A")
            f.cloudMatch(true)
            f.data.replicas.beginRedaction(ctx, RedactionTransfer("old", "match", 1, 2, 3), fence)
            f.data.replicas.applyAccess(ctx, "match", 2, ReplicaAccessLevel.MEMBER, fence)
            assertFails { f.data.replicas.markReady(ctx, "match", 2, 0, fence) }
            assertFails { f.data.replicas.beginRedaction(ctx, RedactionTransfer("old", "match", 2, 2, 3), fence) }
            val start = f.data.replicas.beginRedaction(ctx, RedactionTransfer("new", "match", 2, 2, 3), fence)
            f.data.replicas.stageRedaction(ctx, "new", RedactionPage(0, start, start, emptyList(), emptyList(), true, true), fence)
            f.data.replicas.completeRedaction(ctx, "new", fence)
            assertFails { f.data.replicas.markReady(ctx, "match", 2, 1, fence) }
            f.data.replicas.markReady(ctx, "match", 2, 2, fence)
            assertTrue(f.data.replicas.canReadMember(ctx, "match"))
        }

    @Test
    fun commitFenceRollsBackRedactionAcrossEveryStorageLayer(): Unit =
        Fixture().use { f ->
            val ctx = f.member("A")
            val old = f.cloudMatch(true).single { it.id == "assignment" }
            f.cloudDirectoryPlayer()
            val start = f.data.replicas.beginRedaction(ctx, RedactionTransfer("t", "match", 1, 1, 3), fence)
            val page =
                RedactionPage(
                    0,
                    start,
                    RedactionCursor("deleted", 2),
                    listOf("deleted"),
                    listOf(ReplicaRedactionEvent(old.copy(payload = old.payload.replace("Sensitive name", "Gelöschter Spieler")), 2)),
                    true,
                    true,
                )
            f.data.replicas.stageRedaction(ctx, "t", page, fence)
            assertFails {
                f.data.replicas.completeRedaction(ctx, "t") {
                    if (f.count("replica_redaction_state") > 0) error("authority revoked at commit")
                }
            }
            assertEquals(0, f.count("replica_redacted_player"))
            assertEquals(0, f.count("replica_redaction_state"))
            assertEquals(
                old.payload,
                f.db.cloudUploadQueries
                    .eventById("assignment")
                    .executeAsOne()
                    .payload,
            )
            assertEquals(
                "Sensitive name",
                f.db.playerQueries
                    .selectById("deleted")
                    .executeAsOne()
                    .display_name,
            )
        }

    @Test
    fun redactedNamesCannotReturnThroughDirectoryFavoritesOrLegacyAssignmentRebuild(): Unit =
        Fixture().use { f ->
            val ctx = f.member("A")
            f.cloudMatch(true)
            f.cloudDirectoryPlayer()
            f.data.players.upsertRemote(Player(owner, "Owner"), 0)
            val favorite =
                FavoriteLineup(
                    "favorite",
                    format.sport,
                    format.discipline,
                    listOf(owner),
                    listOf(PlayerId("deleted")),
                    "Sensitive name",
                    1,
                    1,
                )
            f.data.favorites.merge(favorite)
            f.sql(
                """INSERT INTO cloud_pull_snapshot(profile_id,kind,entity_id,server_version,snapshot_json)
                    VALUES ('B','MATCH','match',1,'{"participants":[{"playerId":"deleted","displayName":"Sensitive name"}]}')""",
            )
            val start = f.data.replicas.beginRedaction(ctx, RedactionTransfer("t", "match", 1, 1, 3), fence)
            f.data.replicas.stageRedaction(
                ctx,
                "t",
                RedactionPage(0, start, RedactionCursor("deleted", 0), listOf("deleted"), emptyList(), true, true),
                fence,
            )
            f.data.replicas.completeRedaction(ctx, "t", fence)
            f.data.replicas.markReady(ctx, "match", 1, 1, fence)
            f.data.favorites.merge(favorite)
            f.data.favorites.rename("favorite", "Sensitive name")
            assertFails { f.data.players.addGuest(PlayerId("deleted"), "Sensitive name", 2) }
            f.data.players.upsertRemote(Player(PlayerId("deleted"), "Sensitive name"), 2)
            f.data.rebuildAssignments(MatchId("match"))
            assertEquals(
                "",
                f.data.players
                    .get(PlayerId("deleted"))!!
                    .displayName,
            )
            assertNull(
                f.data.favorites
                    .get("favorite")!!
                    .name,
            )
            assertTrue(
                f.data
                    .assignments(MatchId("match"))
                    .values
                    .none { it.displayName == "Sensitive name" },
            )
            val snapshot =
                f.db.cloudPullQueries
                    .snapshot("B", "MATCH", "match")
                    .executeAsOne()
            assertFalse(snapshot.snapshot_json.contains("Sensitive name"))
            f.select("B")
            assertTrue(f.data.assignments(MatchId("match")).isEmpty())
            assertTrue(
                f.data.favorites
                    .recent()
                    .isEmpty(),
            )
        }

    @Test
    fun tenThousandManifestIdsResumeInBoundedPagesWithoutLogReplay(): Unit =
        Fixture().use { f ->
            var ctx = f.member("A")
            f.cloudMatch()
            val transfer = RedactionTransfer("large", "match", 1, 1, 3)
            var cursor = f.data.replicas.beginRedaction(ctx, transfer, fence)
            repeat(51) { index ->
                val ids = (index * 200 until minOf(index * 200 + 200, 10001)).map { "p${it.toString().padStart(5,'0')}" }
                val page = RedactionPage(index.toLong(), cursor, RedactionCursor(ids.last(), 0), ids, emptyList(), index == 50, true)
                f.data.replicas.stageRedaction(ctx, "large", page, fence)
                cursor = page.next
                if (index == 25) {
                    f.reopen()
                    ctx = f.data.replicas.captureContext()
                    assertEquals(cursor, f.data.replicas.beginRedaction(ctx, transfer, fence))
                }
            }
            assertEquals(51, f.count("replica_transfer_page"))
            assertEquals(0, f.count("replica_redacted_player"))
            f.data.replicas.completeRedaction(ctx, "large", fence)
            assertEquals(10001, f.count("replica_redacted_player"))
            val source = File("src/commonMain/kotlin/eu/advantly/data/ReplicaLifecycleStore.kt").readText()
            assertFalse(source.contains("eventsFor("))
            assertFalse(source.contains("scoreEvents("))
            assertTrue(source.contains("queries.page(t.id, index)"))
        }

    @Test
    fun notReadyOtherMemberRetainsRowsWithoutGrantingReadAndDirectEventStoreCannotBypassScope(): Unit =
        Fixture().use { f ->
            val b = f.select("B")
            f.data.replicas.applyAccess(b, "match", 1, ReplicaAccessLevel.MEMBER, fence)
            val a = f.member("A")
            f.cloudMatch(true)
            f.cloudDirectoryPlayer()
            f.data.replicas.applyAccess(a, "match", 2, ReplicaAccessLevel.ABSENT, fence)
            assertTrue(f.count("match_event") > 0)
            assertTrue(SqlDelightEventStore(f.driver).eventsFor("match").isEmpty())
            assertNull(f.data.players.get(PlayerId("deleted")))
            assertFails { f.data.players.rename(PlayerId("deleted"), "not allowed") }
            f.data.players.upsertRemote(Player(PlayerId("deleted"), "not allowed"), 4)
            assertEquals(
                "Sensitive name",
                f.db.playerQueries
                    .selectById("deleted")
                    .executeAsOne()
                    .display_name,
            )
            val nowB = f.select("B")
            assertFalse(f.data.replicas.canReadMember(nowB, "match"))
            f.data.replicas.markReady(nowB, "match", 1, 0, fence)
            assertTrue(f.data.replicas.canReadMember(nowB, "match"))
        }

    @Test
    fun referenceOnlyAssignmentsStoreIdsAndResolveDirectoryWithoutDuplicatingNames(): Unit =
        Fixture().use { f ->
            f.member("A")
            f.cloudMatch()
            val player = PlayerId("reference")
            val assignment = PlayerAssigned.reference(slot, player, owner)
            f.data.events.append(event("reference-event", 4, assignment))
            val row =
                f.db.matchAssignmentQueries
                    .identitiesForMatch("match")
                    .executeAsList()
                    .single()
            assertEquals(player.value, row.player_id)
            assertNull(row.player_json)
            assertEquals(
                listOf(player),
                f.data.matches
                    .recentLineups()
                    .single()
                    .b,
            )
            f.data.players.upsertRemote(Player(player, "Current name"), 3)
            assertEquals("Current name", f.data.assignments(MatchId("match"))[slot]!!.displayName)
            assertFalse(
                f.data.events
                    .eventsFor("match")
                    .any { it.payload.contains("Current name") },
            )
        }

    @Test
    fun localCreationBatchUsesOneProjectionAndNeverPromotesExistingForeignLog(): Unit =
        Fixture().use { f ->
            val source =
                f.db.localIdentityQueries
                    .identityForScope("scope-A")
                    .executeAsOne()
            val self = PlayerId(source.self_id)
            val localLineup = Lineup(listOf(self), listOf(slot), self)
            val genesis = event("local", 1, MatchCreated(format, localLineup, createdBy = self)).copy(deviceId = source.device_id)
            val point = event("local-point", 2, PointWon(Side.A)).copy(deviceId = source.device_id)
            f.data.events.appendAll(listOf(genesis, point))
            f.data.matches.insertIfAbsent(MatchId("match"), format, localLineup, MatchMeta(), 0)
            assertEquals(2, f.data.events.count("match"))
            val foreign = event("foreign", 1, MatchCreated(format, lineup, createdBy = owner)).copy(matchId = "foreign")
            f.data.cloud.storeDownloaded(listOf(foreign), fence)
            assertFails { f.data.events.append(genesis.copy(id = "replacement-genesis", matchId = "foreign", lamport = 0)) }
        }

    @Test
    fun accountRoundTripPreservesDistinctSelfNamesWithoutDirtyingTheirRevisions(): Unit =
        Fixture().use { f ->
            val a = PlayerId("A-1")
            val b = PlayerId("B-1")
            f.data.players.rename(a, "Alex")
            f.select("B")
            f.data.players.rename(b, "Beatrice")
            val aState = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, a.value)
            val bState = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, b.value)
            repeat(3) {
                f.select("A")
                assertEquals(
                    "Alex",
                    f.data.players
                        .self()!!
                        .displayName,
                )
                assertNull(f.data.players.get(b))
                f.reopen()
                f.select("B")
                assertEquals(
                    "Beatrice",
                    f.data.players
                        .self()!!
                        .displayName,
                )
                assertNull(f.data.players.get(a))
            }
            assertEquals(1, f.count("player", "kind='SELF'"))
            assertEquals(aState, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, a.value))
            assertEquals(bState, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, b.value))
        }

    @Test
    fun ownedCloudDirectoryIsScopedWithoutRequiringALocalSourceOrMatch(): Unit =
        Fixture().use { f ->
            val a = PlayerId("cloud-a")
            val b = PlayerId("cloud-b")
            f.data.cloud.storeDownloadedPlayer(LocalPlayer(a, "A guest", PlayerKind.GUEST, 0, null), "A", owned = true)
            f.data.cloud.storeDownloadedPlayer(LocalPlayer(b, "B guest", PlayerKind.GUEST, 0, null), "B", owned = true)
            assertEquals(
                "A guest",
                f.data.players
                    .get(a)!!
                    .displayName,
            )
            assertNull(f.data.players.get(b))
            f.data.players.rename(a, "A changed")
            assertFails { f.data.players.rename(b, "Wrong account") }
            f.reopen()
            f.select("B")
            assertEquals(
                "B guest",
                f.data.players
                    .get(b)!!
                    .displayName,
            )
            assertNull(f.data.players.get(a))
            assertFails { f.data.players.rename(a, "Wrong account") }
            f.data.favorites.delete("missing")
        }

    @Test
    fun activationNormalizesLegacyAssignmentsByStructuredIdWithoutDirtyingParticipants(): Unit =
        Fixture(activate = false).use { f ->
            val ctx = f.member("A")
            f.cloudMatch()
            val legacy = Player(PlayerId("legacy-person"), "Legacy name")
            f.data.players.upsertRemote(legacy, 0)
            f.db.matchAssignmentQueries.put("match", slot.value, DataJson.encodeToString(Player.serializer(), legacy))
            val state = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")
            val other = "other-match"
            val missing = Player(PlayerId("missing-legacy"), "Keep legacy fallback")
            f.db.matchAssignmentQueries.put("missing-assignment", slot.value, DataJson.encodeToString(Player.serializer(), missing))
            f.data.cloud.storeDownloadedMatch(
                MatchRecord(
                    MatchId(other),
                    format,
                    Lineup(listOf(owner), listOf(missing.id), owner),
                    MatchMeta(),
                    1,
                    1,
                    MatchStatus.IN_PROGRESS,
                    ScoreSummary.EMPTY,
                    null,
                ),
                "A",
            )
            f.sql("UPDATE cloud_metadata SET uploaded_revision=revision WHERE kind='PARTICIPANTS' AND entity_id='other-match'")
            val otherState = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, other)!!
            assertEquals(
                "",
                f.data.cloud
                    .uploadParticipants(other, "A")!!
                    .participants
                    .last()
                    .displayName,
            )
            var calls = 0
            assertFails { f.data.replicas.activate(ctx) { if (++calls == 3) error("lease expired") } }
            assertNull(
                f.db.matchAssignmentQueries
                    .identitiesForMatch("match")
                    .executeAsOne()
                    .player_id,
            )
            f.data.replicas.activate(ctx, fence)
            val row =
                f.db.matchAssignmentQueries
                    .identitiesForMatch("match")
                    .executeAsOne()
            assertEquals(legacy.id.value, row.player_id)
            assertNull(row.player_json)
            assertEquals(
                "Legacy name",
                f.data.players
                    .get(legacy.id)!!
                    .displayName,
            )
            assertEquals(state, f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match"))
            val updatedOther = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, other)!!
            assertEquals(otherState.uploadedRevision, updatedOther.uploadedRevision)
            assertEquals(otherState, updatedOther)
            assertEquals(
                "",
                f.data.cloud
                    .uploadParticipants(other, "A")!!
                    .participants
                    .last()
                    .displayName,
            )
            assertEquals(0, f.count("player", "id='missing-legacy'"))
            val missingRow =
                f.db.matchAssignmentQueries
                    .identitiesForMatch("missing-assignment")
                    .executeAsOne()
            assertNull(missingRow.player_id)
            assertEquals(DataJson.encodeToString(Player.serializer(), missing), missingRow.player_json)
            assertEquals(
                CloudMetadataOrigin.UNKNOWN,
                f.data.cloud
                    .metadataState(CloudMetadataKind.PLAYER, legacy.id.value)!!
                    .origin,
            )
            assertNull(
                f.data.cloud
                    .uploadParticipants("match", "A")!!
                    .participants
                    .last()
                    .playerId,
            )
            f.reopen()
            f.select("B")
            assertNull(f.data.players.get(legacy.id))
            f.select("A")
            assertEquals(
                "Legacy name",
                f.data.players
                    .get(legacy.id)!!
                    .displayName,
            )
        }

    @Test
    fun anotherScopeCannotAmendAnExistingLocalHeader(): Unit =
        Fixture().use { f ->
            val self = PlayerId("A-1")
            val localLineup = Lineup(listOf(self), listOf(slot), self)
            f.data.events.append(event("own", 1, MatchCreated(format, localLineup, createdBy = self)).copy(deviceId = "A-0"))
            f.data.matches.insertIfAbsent(MatchId("match"), format, localLineup, MatchMeta(), 1)
            val header =
                f.db.matchHeaderQueries
                    .selectById("match")
                    .executeAsOne()
            val metadata = f.data.cloud.metadataState(CloudMetadataKind.HEADER, "match")
            f.select("B")
            assertFails {
                f.data.matches.insertIfAbsent(MatchId("match"), format, localLineup, MatchMeta(), 1, MatchId("foreign-previous"))
            }
            assertEquals(
                header,
                f.db.matchHeaderQueries
                    .selectById("match")
                    .executeAsOne(),
            )
            assertEquals(metadata, f.data.cloud.metadataState(CloudMetadataKind.HEADER, "match"))
        }

    @Test
    fun missingDirectoryDoesNotAbortMatchUseOrConsumeVisibleFavoritePage(): Unit =
        Fixture().use { f ->
            val self = PlayerId("A-1")
            val missing = PlayerId("missing")
            val skipped = f.data.favorites.recordUse(format.sport, format.discipline, listOf(self), listOf(missing), 1)
            assertEquals(0, skipped.useCount)
            assertNull(skipped.name)
            assertEquals(0, f.count("lineup_favorite"))
            repeat(201) { index ->
                f.db.lineupFavoriteQueries.insertOrIgnore(
                    "unknown-$index",
                    format.sport.name,
                    format.discipline.name,
                    "[\"A-1\"]",
                    "[\"missing\"]",
                    "Private name",
                    500L - index,
                    1,
                )
            }
            val guest = PlayerId("own-guest")
            f.data.players.addGuest(guest, "Guest", 0)
            val visible = f.data.favorites.recordUse(format.sport, format.discipline, listOf(self), listOf(guest), 0)
            assertEquals(listOf(visible), f.data.favorites.recent(limit = 1))
            assertNull(f.data.favorites.get("unknown-0"))
            f.reopen()
            assertEquals(listOf(visible), f.data.favorites.recent(limit = 1))
        }

    @Test
    fun visibleForeignCloudPlayerDoesNotAcquireAnotherProfilesWriteAuthority(): Unit =
        Fixture().use { f ->
            f.member("A")
            val guest = PlayerId("bob-player")
            f.data.cloud.storeDownloadedPlayer(LocalPlayer(guest, "Bob name", PlayerKind.GUEST, 0, null), "B", owned = true)
            val sharedLineup = Lineup(listOf(owner), listOf(guest), owner)
            f.data.cloud.storeDownloadedMatch(
                MatchRecord(
                    MatchId("match"),
                    format,
                    sharedLineup,
                    MatchMeta(),
                    1,
                    1,
                    MatchStatus.IN_PROGRESS,
                    ScoreSummary.EMPTY,
                    null,
                ),
                "A",
            )
            assertEquals(
                "Bob name",
                f.data.players
                    .get(guest)!!
                    .displayName,
            )
            val state = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value)
            assertFails { f.data.players.rename(guest, "Alice edit") }
            f.data.players.upsertRemote(Player(guest, "Peer edit"), 2)
            assertFalse(f.data.players.deleteGuest(guest))
            assertEquals(state, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value))
            f.select("B")
            assertEquals(
                "Bob name",
                f.data.players
                    .get(guest)!!
                    .displayName,
            )
            f.data.players.rename(guest, "Bob edit")
            f.select("A")
            assertEquals(
                "Bob edit",
                f.data.players
                    .get(guest)!!
                    .displayName,
            )
            assertFails { f.data.players.rename(guest, "Still Alice") }
        }

    @Test
    fun cloudAssignmentGuestRemainsReadOnlyWithoutVerifiedPlayerWriter(): Unit =
        Fixture(activate = false).use { f ->
            val ctx = f.member("A")
            f.cloudMatch(assignment = true)
            f.cloudDirectoryPlayer()
            f.data.replicas.activate(ctx, fence)
            val guest = PlayerId("deleted")
            val state = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value)!!
            assertEquals(CloudMetadataOrigin.CLOUD, state.origin)
            assertEquals(
                "Sensitive name",
                f.data.players
                    .get(guest)!!
                    .displayName,
            )
            assertFails { f.data.players.rename(guest, "Alice edit") }
            f.data.players.upsertRemote(Player(guest, "Peer edit"), 2)
            assertFalse(f.data.players.deleteGuest(guest))
            f.member("B")
            assertEquals(
                "Sensitive name",
                f.data.players
                    .get(guest)!!
                    .displayName,
            )
            assertFails { f.data.players.rename(guest, "Still unproven") }
            assertEquals(state, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value))
            // Simulierter autoritativer Root-Abgleich; normale Peer-Metadaten vergeben keine Schreibrechte.
            f.db.cloudPullQueries.updatePullPlayerWriter("B", guest.value)
            f.data.players.rename(guest, "Bob edit")
            f.select("A")
            assertEquals(
                "Bob edit",
                f.data.players
                    .get(guest)!!
                    .displayName,
            )
            assertFails { f.data.players.rename(guest, "Alice again") }
        }

    @Test
    fun visibleForeignLocalGuestRejectsEveryPlayerWriteWithProfiles() = assertVisibleGuestWriteIsolation(profile = true)

    @Test
    fun visibleForeignLocalGuestRejectsEveryPlayerWriteWithLocalScopes() = assertVisibleGuestWriteIsolation(profile = false)

    @Test
    fun visibleBoundForeignGuestRejectsEveryPlayerWrite() = assertVisibleGuestWriteIsolation(profile = true, bound = true)

    @Test
    fun visibleUnknownGuestRejectsEveryPlayerWrite() = assertVisibleGuestWriteIsolation(profile = true, unknown = true)

    private fun selectPlayerScope(
        f: Fixture,
        account: String,
        profile: Boolean,
    ) {
        var index = 0
        f.data.accountIdentities.select("scope-$account", account.takeIf { profile }, 0, "PHONE") { "$account-${index++}" }
    }

    private fun exposeGuestInOwnMatch(
        f: Fixture,
        guest: PlayerId,
        account: String,
    ) {
        val self = checkNotNull(f.data.players.self()).id
        val localLineup = Lineup(listOf(self), listOf(guest), self)
        val match = MatchId("$account-local-match")
        f.data.events.append(
            ScoreEvent(
                EventId("$account-local-genesis"),
                match,
                DeviceId("$account-0"),
                1,
                1,
                1,
                null,
                MatchCreated(format, localLineup, createdBy = self),
            ).toStoredEvent(),
        )
        f.data.matches.insertIfAbsent(match, format, localLineup, MatchMeta(), 1)
        assertNotNull(f.data.matches.get(match))
        assertNotNull(f.data.players.get(guest))
    }

    private fun attemptGuestWrite(
        data: LocalData,
        guest: PlayerId,
        operation: String,
    ): Any? =
        when (operation) {
            "rename" -> data.players.rename(guest, "Changed")
            "remoteUpsert" -> data.players.upsertRemote(Player(guest, "Changed"), 9)
            "remotePlaceholder" -> data.players.upsertRemote(Player(guest, "Opponent", isPlaceholder = true), 9)
            "placeholder" -> data.players.markPlaceholder(guest, 9)
            "archive" -> data.players.archiveRemote(guest, 9)
            "deleteGuest" -> data.players.deleteGuest(guest)
            "markUsed" -> data.players.markUsed(listOf(guest), 9)
            "existingGuest" -> data.players.addGuest(guest, "Changed", 9)
            else -> error(operation)
        }

    private fun assertVisibleGuestWriteIsolation(
        profile: Boolean,
        bound: Boolean = false,
        unknown: Boolean = false,
    ) {
        val failures = mutableListOf<String>()
        for (operation in listOf(
            "rename",
            "remoteUpsert",
            "remotePlaceholder",
            "placeholder",
            "archive",
            "deleteGuest",
            "markUsed",
            "existingGuest",
        )) {
            val result =
                runCatching {
                    Fixture().use { f ->
                        selectPlayerScope(f, "B", profile)
                        if (bound) f.data.accountIdentities.bindCurrent(f.data.cloudUploads.activate("B"))
                        val guest = PlayerId("b-guest")
                        if (unknown) {
                            f.data.players.upsertRemote(Player(guest, "Opponent"), 0)
                        } else {
                            f.data.players.addGuest(guest, "Seed", 0)
                            f.db.cloudMetadataQueries.acknowledge(1, "PLAYER", guest.value)
                            f.data.players.rename(guest, "Opponent")
                        }
                        val before =
                            f.db.playerQueries
                                .selectById(guest.value)
                                .executeAsOne()
                        val metadata = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value)
                        selectPlayerScope(f, "A", profile)
                        assertNull(f.data.players.get(guest))
                        exposeGuestInOwnMatch(f, guest, "A")
                        val mutation = runCatching { attemptGuestWrite(f.data, guest, operation) }
                        val after =
                            f.db.playerQueries
                                .selectById(guest.value)
                                .executeAsOne()
                        val afterMetadata = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value)
                        assertEquals(before, after, "$operation: player row")
                        assertEquals(metadata, afterMetadata, "$operation: revision, ACK and provenance")
                        if (operation in listOf("rename", "existingGuest")) {
                            assertTrue(mutation.isFailure, "$operation must reject")
                        } else {
                            mutation.getOrThrow()
                            if (operation == "deleteGuest") assertEquals(false, mutation.getOrThrow())
                        }
                        assertEquals("Opponent", checkNotNull(f.data.players.get(guest)).displayName)
                        selectPlayerScope(f, "B", profile)
                        assertEquals(
                            before,
                            f.db.playerQueries
                                .selectById(guest.value)
                                .executeAsOne(),
                        )
                        assertEquals(metadata, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value))
                        selectPlayerScope(f, "A", profile)
                        f.reopen()
                        assertEquals("Opponent", checkNotNull(f.data.players.get(guest)).displayName)
                        assertFails { f.data.players.rename(guest, "Changed after A-B-A and reopen") }
                        assertEquals(metadata, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value))
                    }
                }
            result.exceptionOrNull()?.let { failures += "$operation: $it" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun freshAndMigratedSchemasHaveParity() {
        fun schema(driver: JdbcSqliteDriver): Map<String, String> =
            driver
                .executeQuery(
                    null,
                    "SELECT type || ':' || name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name",
                    { c ->
                        val result = mutableMapOf<String, String>()
                        while (c.next().value) {
                            result[c.getString(0)!!] =
                                c
                                    .getString(1)!!
                                    .replace(Regex("--[^\\r\\n]*"), "")
                                    .replace(Regex("\\s+"), " ")
                                    .replace(Regex("\\s+([,)])"), "$1")
                                    .trim()
                        }
                        QueryResult.Value(result)
                    },
                    0,
                ).value
        val file = File.createTempFile("review-parity", ".db")
        File("src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { migrated ->
            AdvantlyDatabase.Schema.migrate(migrated, 1, LocalData.SCHEMA_VERSION).value
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { fresh ->
                AdvantlyDatabase.Schema.create(fresh)
                assertEquals(schema(migrated), schema(fresh))
            }
        }
        file.delete()
    }

    @Test
    fun legacyEmptyTypeAssignmentCannotRetainRedactedName() =
        Fixture().use { f ->
            val ctx = f.member("A")
            f.cloudMatch(true)
            f.sql("UPDATE match_event SET type='', confirmed=1")
            val before =
                f.db.cloudUploadQueries
                    .eventById("assignment")
                    .executeAsOne()
            val point =
                f.db.cloudUploadQueries
                    .eventById("point")
                    .executeAsOne()
            val state = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")
            val playerState = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "deleted")
            val score = ScoreSummary.of(ScoringEngine.replay(f.data.events.scoreEvents(MatchId("match"))).state)
            val start = f.data.replicas.beginRedaction(ctx, RedactionTransfer("legacy-t", "match", 1, 1, 3), fence)
            f.data.replicas.stageRedaction(
                ctx,
                "legacy-t",
                RedactionPage(0, start, RedactionCursor("deleted", 0), listOf("deleted"), emptyList(), true, true),
                fence,
            )
            f.data.replicas.completeRedaction(ctx, "legacy-t", fence)
            f.data.replicas.markReady(ctx, "match", 1, 1, fence)
            val payload =
                f.data.events
                    .eventsFor("match")
                    .single { it.id == "assignment" }
                    .payload
            assertFalse(payload.contains("Sensitive name"))
            assertEquals(
                before.copy(payload = before.payload.replace("Sensitive name", "Gel\u00f6schter Spieler")),
                f.db.cloudUploadQueries
                    .eventById("assignment")
                    .executeAsOne(),
            )
            assertEquals(
                point,
                f.db.cloudUploadQueries
                    .eventById("point")
                    .executeAsOne(),
            )
            assertEquals(state, f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match"))
            assertEquals(playerState, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "deleted"))
            assertEquals(score, ScoreSummary.of(ScoringEngine.replay(f.data.events.scoreEvents(MatchId("match"))).state))
            f.reopen()
            assertFalse(
                f.data.events
                    .eventsFor("match")
                    .any { it.payload.contains("Sensitive name") },
            )
        }

    @Test
    fun preActivationCloudGuestDoesNotBecomeWritableAfterLaterGrant(): Unit =
        Fixture(activate = false).use { f ->
            val ctx = f.data.replicas.captureContext()
            f.cloudMatch(true)
            f.data.replicas.applyAccess(ctx, "match", 1, ReplicaAccessLevel.MEMBER, fence)
            f.data.replicas.markReady(ctx, "match", 1, 0, fence)
            f.data.replicas.activate(ctx, fence)
            assertNull(f.data.players.get(PlayerId("deleted")))
            assertNull(f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "deleted"))
            assertEquals("Sensitive name", f.data.assignments(MatchId("match"))[slot]?.displayName)
            f.cloudDirectoryPlayer()
            val state = checkNotNull(f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "deleted"))
            assertEquals(CloudMetadataOrigin.CLOUD, state.origin)
            assertNull(state.writerProfileId)
            assertFails { f.data.players.rename(PlayerId("deleted"), "Unauthorized edit") }
            assertEquals(state, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "deleted"))
        }

    @Test
    fun globalRedactionRemovesKnownProfileAliasesFromActualSnapshotShapes() =
        Fixture().use { f ->
            val ctx = f.member("A")
            f.cloudMatch(true)
            val role =
                """
                {"id":"other-match","roles":[
{"profileId":"deleted-profile","alias":"Sensitive name","role":"scorer","status":"accepted"},
                {"profileId":"unrelated-profile","alias":"Sensitive name","role":"viewer","status":"accepted"}]}
                """.trimIndent()
            val friendship =
                """{"profileId":"deleted-profile","alias":"Sensitive name","status":"accepted","outgoing":true,"serverVersion":1}"""
            for (account in listOf("A", "B")) {
                for (index in 0..200) {
                    f.sql(
                        """INSERT INTO cloud_pull_snapshot(profile_id,kind,entity_id,server_version,snapshot_json)
                    VALUES ('$account','MATCH','other-$index',1,'$role')""",
                    )
                }
                f.sql(
                    """INSERT INTO cloud_pull_snapshot(profile_id,kind,entity_id,server_version,snapshot_json)
                    VALUES ('$account','FRIENDSHIP','deleted-profile',1,'$friendship')""",
                )
            }
            // Beleg steht hinter den Alias-Seiten; Zeilenreihenfolge darf die Redaktion nicht beeinflussen.
            f.sql(
                """INSERT INTO cloud_pull_snapshot(profile_id,kind,entity_id,server_version,snapshot_json)
                    VALUES ('B','PLAYER','deleted',1,'
                    {"id":"deleted","profileId":"deleted-profile","displayName":"Sensitive name","kind":"self","owned":false,"serverVersion":1}')""",
            )
            // Eine widersprüchliche Entity-ID ist kein positiver Profilbeleg.
            f.sql(
                """INSERT INTO cloud_pull_snapshot(profile_id,kind,entity_id,server_version,snapshot_json)
                    VALUES ('A','PLAYER','different',1,'
                    {"id":"deleted","profileId":"unrelated-profile","displayName":"Sensitive name"}')""",
            )
            val start = f.data.replicas.beginRedaction(ctx, RedactionTransfer("alias-t", "match", 1, 1, 3), fence)
            f.data.replicas.stageRedaction(
                ctx,
                "alias-t",
                RedactionPage(0, start, RedactionCursor("deleted", 0), listOf("deleted"), emptyList(), true, true),
                fence,
            )
            f.data.replicas.completeRedaction(ctx, "alias-t", fence)

            fun verifySnapshots() {
                for (account in listOf("A", "B")) {
                    for (index in listOf(0, 199, 200)) {
                        val actual =
                            f.data.cloudPulls
                                .snapshot(account, CloudPullEntityKind.MATCH, "other-$index")!!
                                .json
                        val expected =
                            role.replace(
                                "\"deleted-profile\",\"alias\":\"Sensitive name\"",
                                "\"deleted-profile\",\"alias\":\"\"",
                            )
                        assertEquals(DataJson.parseToJsonElement(expected), DataJson.parseToJsonElement(actual))
                    }
                    assertEquals(
                        DataJson.parseToJsonElement(friendship.replace("Sensitive name", "")),
                        DataJson.parseToJsonElement(
                            f.data.cloudPulls
                                .snapshot(account, CloudPullEntityKind.FRIENDSHIP, "deleted-profile")!!
                                .json,
                        ),
                    )
                }
            }
            verifySnapshots()
            f.sql("DELETE FROM cloud_pull_snapshot WHERE kind='PLAYER'")
            f.reopen()
            f.select("B")
            verifySnapshots()
            assertEquals(
                DataJson.parseToJsonElement(friendship.replace("Sensitive name", "")),
                f.data.replicas.sanitizeJson(DataJson.parseToJsonElement(friendship)),
            )
            val unproven = """{"profileId":"unrelated-profile","alias":"Sensitive name"}"""
            assertEquals(DataJson.parseToJsonElement(unproven), f.data.replicas.sanitizeJson(DataJson.parseToJsonElement(unproven)))
        }

    @Test
    fun schemaSevenLegacyTypePayloadRemainsPrivateAfterMigrationAndRedaction() {
        val file = File.createTempFile("review-old-type", ".db")
        File("src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
        val before =
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                AdvantlyDatabase.Schema.migrate(driver, 1, 7).value
                val assignment =
                    event(
                        "legacy-assignment",
                        2,
                        PlayerAssigned(slot, Player(PlayerId("deleted"), "Sensitive name"), owner),
                    ).copy(type = "")
                SqlDelightEventStore(driver).append(assignment)
                driver.execute(null, "UPDATE match_event SET confirmed=1, cloud_state=1", 0)
                val stored = AdvantlyDatabase(driver).cloudUploadQueries.eventById("legacy-assignment").executeAsOne()
                AdvantlyDatabase.Schema.migrate(driver, 7, LocalData.SCHEMA_VERSION).value
                stored
            }
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            val db = AdvantlyDatabase(driver)
            assertEquals(before, db.cloudUploadQueries.eventById("legacy-assignment").executeAsOne())
            val data = LocalData(driver)
            data.cloud.storeDownloadedMatch(
                MatchRecord(MatchId("match"), format, lineup, MatchMeta(), 1, 1, MatchStatus.IN_PROGRESS, ScoreSummary.EMPTY, null),
                "A",
            )
            var index = 0
            data.accountIdentities.select("scope-A", "A", 0, "PHONE") { "id-${index++}" }
            val ctx = data.replicas.captureContext()
            data.replicas.applyAccess(ctx, "match", 1, ReplicaAccessLevel.MEMBER, fence)
            data.replicas.activate(ctx, fence)
            val start = data.replicas.beginRedaction(ctx, RedactionTransfer("migration-t", "match", 1, 1, 2), fence)
            data.replicas.stageRedaction(
                ctx,
                "migration-t",
                RedactionPage(0, start, RedactionCursor("deleted", 0), listOf("deleted"), emptyList(), true, true),
                fence,
            )
            data.replicas.completeRedaction(ctx, "migration-t", fence)
            data.replicas.markReady(ctx, "match", 1, 1, fence)
            val result = data.events.eventsFor("match").single()
            assertFalse(result.payload.contains("Sensitive name"))
            assertEquals(
                before.copy(payload = before.payload.replace("Sensitive name", "Gel\u00f6schter Spieler")),
                db.cloudUploadQueries.eventById("legacy-assignment").executeAsOne(),
            )
        }
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            val data = LocalData(driver)
            assertFalse(
                data.events
                    .eventsFor("match")
                    .single()
                    .payload
                    .contains("Sensitive name"),
            )
            assertEquals(1, data.replicas.access(data.replicas.captureContext(), "match")!!.redactionEpoch)
        }
        file.delete()
    }

    @Test
    fun ownProfileSourceKeepsEveryPlayerWrite() = assertPositivePlayerAuthority("own-profile")

    @Test
    fun verifiedPeerSourceKeepsEveryPlayerWrite() = assertPositivePlayerAuthority("peer")

    @Test
    fun ownLocalScopeKeepsEveryPlayerWrite() = assertPositivePlayerAuthority("own-local")

    @Test
    fun deliberatelyAdoptedProfileSourceKeepsEveryPlayerWrite() = assertPositivePlayerAuthority("adopt-profile")

    @Test
    fun deliberatelyAcceptedLocalSourceKeepsEveryPlayerWrite() = assertPositivePlayerAuthority("adopt-local")

    @Test
    fun verifiedCloudWriterKeepsEveryPlayerWrite() = assertPositivePlayerAuthority("writer")

    @Test
    fun verifiedCloudOwnerKeepsEveryPlayerWrite() = assertPositivePlayerAuthority("bound")

    private fun assertPositivePlayerAuthority(authority: String) {
        for (operation in listOf(
            "rename",
            "remoteUpsert",
            "remotePlaceholder",
            "placeholder",
            "archive",
            "deleteGuest",
            "markUsed",
            "existingGuest",
        )) {
            Fixture().use { f ->
                val profile = !authority.endsWith("local")
                val adopted = authority.startsWith("adopt")
                val source = if (adopted) "old" else "A"
                selectPlayerScope(f, source, profile)
                val guest = PlayerId("own-guest")
                if (authority == "writer") {
                    f.data.cloud.storeDownloadedPlayer(LocalPlayer(guest, "Opponent", PlayerKind.GUEST, 0, null), "A", owned = true)
                } else if (authority == "peer") {
                    f.data.accountIdentities.recordSource("watch", guest.value, "WATCH", "scope-A", local = false)
                    f.data.players.upsertRemote(Player(guest, "Opponent"), 0)
                    assertEquals(
                        CloudMetadataOrigin.PEER,
                        f.data.cloud
                            .metadataState(CloudMetadataKind.PLAYER, guest.value)!!
                            .origin,
                    )
                } else {
                    f.data.players.addGuest(guest, "Opponent", 0)
                }
                exposeGuestInOwnMatch(f, guest, source)
                if (adopted) {
                    selectPlayerScope(f, "A", profile)
                    exposeGuestInOwnMatch(f, guest, "A")
                    assertFails { f.data.players.rename(guest, "Not yet adopted") }
                    if (profile) {
                        val session = f.data.cloudUploads.activate("A")
                        assertTrue(
                            f.data.accountIdentities
                                .adoptionSources()
                                .any { it.deviceId == "old-0" },
                        )
                        f.data.accountIdentities.adopt(session, setOf("old-0"))
                    } else {
                        f.data.accountIdentities.acceptOwnSources("scope-A", setOf("old-0"))
                    }
                }
                if (authority == "bound") f.data.accountIdentities.bindCurrent(f.data.cloudUploads.activate("A"))
                if (authority == "writer") {
                    assertEquals(
                        "A",
                        f.data.cloud
                            .metadataState(CloudMetadataKind.PLAYER, guest.value)!!
                            .writerProfileId,
                    )
                }
                selectPlayerScope(f, "B", profile)
                selectPlayerScope(f, "A", profile)
                f.reopen()
                val before = checkNotNull(f.data.players.get(guest))
                val metadata = checkNotNull(f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value))
                val result = attemptGuestWrite(f.data, guest, operation)
                if (operation == "deleteGuest") assertEquals(true, result)
                val expected =
                    when (operation) {
                        "rename", "remoteUpsert" -> before.copy(displayName = "Changed")
                        "remotePlaceholder", "placeholder" -> before.copy(kind = PlayerKind.PLACEHOLDER)
                        "archive", "deleteGuest" -> before.copy(kind = PlayerKind.ARCHIVED)
                        "markUsed" -> before.copy(lastUsedAtMs = 9)
                        else -> before
                    }
                assertEquals(expected, f.data.players.get(guest), "$authority / $operation")
                val revisionDelta = if (operation in listOf("markUsed", "existingGuest")) 0 else 1
                assertEquals(
                    metadata.copy(selected = metadata.selected.copy(revision = metadata.selected.revision + revisionDelta)),
                    f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value),
                    "$authority / $operation must preserve ACK and provenance",
                )
            }
        }
    }

    @Test
    fun preActivationCloudAssignmentPreservesExistingLocalPlayerRevisionAndAck() =
        Fixture(activate = false).use { f ->
            val guest = PlayerId("deleted")
            f.data.players.addGuest(guest, "Own name", 0)
            f.db.cloudMetadataQueries.acknowledge(1, "PLAYER", guest.value)
            f.data.players.rename(guest, "Own pending name")
            val before = f.data.players.get(guest)
            val state = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value)
            f.cloudMatch(true)
            val ctx = f.data.replicas.captureContext()
            f.data.replicas.applyAccess(ctx, "match", 1, ReplicaAccessLevel.MEMBER, fence)
            f.data.replicas.markReady(ctx, "match", 1, 0, fence)
            f.data.replicas.activate(ctx, fence)
            assertEquals(before, f.data.players.get(guest))
            assertEquals(state, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value))
            f.data.players.rename(guest, "Still own")
        }

    @Test
    fun visibleForeignGuestCannotIncrementOriginalRevision() {
        for (profile in listOf(true, false)) {
            Fixture().use { f ->
                selectPlayerScope(f, "B", profile)
                val guest = PlayerId("b-original")
                f.data.players.addGuest(guest, "B original", 0)
                val original = checkNotNull(f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value))
                assertEquals(1, original.selected.revision)
                assertEquals(0, original.uploadedRevision)
                selectPlayerScope(f, "A", profile)
                exposeGuestInOwnMatch(f, guest, "A")
                val result = runCatching { f.data.players.rename(guest, "A overwrote B") }
                selectPlayerScope(f, "B", profile)
                assertEquals(original, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, guest.value))
                assertEquals("B original", checkNotNull(f.data.players.get(guest)).displayName)
                assertTrue(result.isFailure)
            }
        }
    }

    @Test
    fun profileEvidenceIsPagedAndRollsBackTogetherWithManifestAndAliases() =
        Fixture().use { f ->
            val ctx = f.member("A")
            f.cloudMatch()
            val ids = (0..204).map { "player-${it.toString().padStart(3, '0')}" }
            for (id in ids) {
                f.sql(
                    """INSERT INTO cloud_pull_snapshot(profile_id,kind,entity_id,server_version,snapshot_json)
                VALUES ('B','PLAYER','$id',1,'{"id":"$id","kind":"self","profileId":"profile-$id","displayName":"Private"}')""",
                )
                f.sql(
                    """INSERT INTO cloud_pull_snapshot(profile_id,kind,entity_id,server_version,snapshot_json)
                VALUES ('A','FRIENDSHIP','profile-$id',1,'{"profileId":"profile-$id","alias":"Private"}')""",
                )
            }
            var cursor = f.data.replicas.beginRedaction(ctx, RedactionTransfer("profile-pages", "match", 1, 1, 0), fence)
            for ((index, page) in ids.chunked(200).withIndex()) {
                val next = RedactionCursor(page.last(), 0)
                f.data.replicas.stageRedaction(
                    ctx,
                    "profile-pages",
                    RedactionPage(index.toLong(), cursor, next, page, emptyList(), index == 1, true),
                    fence,
                )
                cursor = next
            }
            assertFails {
                f.data.replicas.completeRedaction(ctx, "profile-pages") {
                    check(f.count("replica_redacted_profile") == 0L)
                }
            }
            assertEquals(0, f.count("replica_redacted_profile"))
            assertEquals(0, f.count("replica_redacted_player"))
            assertEquals(
                0,
                f.data.replicas
                    .access(ctx, "match")!!
                    .redactionEpoch,
            )
            assertTrue(
                f.data.cloudPulls
                    .snapshot("A", CloudPullEntityKind.FRIENDSHIP, "profile-${ids.first()}")!!
                    .json
                    .contains("Private"),
            )
            f.data.replicas.completeRedaction(ctx, "profile-pages", fence)
            assertEquals(205, f.count("replica_redacted_profile"))
            val page =
                f.db.replicaLifecycleQueries
                    .redactedPlayerSnapshotPage(0)
                    .executeAsList()
            assertEquals(200, page.size)
            assertEquals(
                5,
                f.db.replicaLifecycleQueries
                    .redactedPlayerSnapshotPage(page.last().snapshot_rowid)
                    .executeAsList()
                    .size,
            )
            for (id in ids) {
                assertFalse(
                    f.data.cloudPulls
                        .snapshot("A", CloudPullEntityKind.FRIENDSHIP, "profile-$id")!!
                        .json
                        .contains("Private"),
                )
            }
            f.reopen()
            assertEquals(205, f.count("replica_redacted_profile"))
            assertEquals(
                1,
                f.data.replicas
                    .access(f.data.replicas.captureContext(), "match")!!
                    .redactionEpoch,
            )
        }
}
