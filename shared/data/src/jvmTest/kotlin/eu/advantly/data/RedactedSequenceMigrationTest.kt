package eu.advantly.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.PlayerAssigned
import eu.advantly.core.scoring.PointWon
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactedSequenceMigrationTest {
    private fun proof(index: Int): StoredEvent =
        StoredEvent(
            "proof-${index.toString().padStart(4, '0')}",
            "match",
            "source",
            index.toLong() + 1,
            index.toLong() + 1,
            DataJson.encodeToString(
                eu.advantly.core.scoring.EventPayload
                    .serializer(),
                PlayerAssigned(PlayerId("slot"), Player(PlayerId("player"), "Gelöschter Spieler"), PlayerId("owner")),
            ),
            type = "PlayerAssigned",
        )

    private fun point(): StoredEvent =
        proof(999).copy(
            id = "unrelated-point",
            type = "PointWon",
            payload =
                DataJson.encodeToString(
                    eu.advantly.core.scoring.EventPayload
                        .serializer(),
                    PointWon(Side.A),
                ),
        )

    private class OldDatabase : AutoCloseable {
        private val file = File.createTempFile("redaction-nine", ".db")
        var driver: JdbcSqliteDriver
        lateinit var data: LocalData

        init {
            File("src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            AdvantlyDatabase.Schema.migrate(driver, 1, 9).value
            driver.execute(null, "INSERT INTO setting VALUES ('assignments.projectionVersion','2')", 0)
        }

        fun insert(
            event: StoredEvent,
            rowId: String = event.id,
            json: String = DataJson.encodeToString(StoredEvent.serializer(), event),
        ) {
            driver.execute(null, "INSERT INTO replica_redacted_event(event_id,event_json) VALUES (?,?)", 2) {
                bindString(0, rowId)
                bindString(1, json)
            }
        }

        fun rows(sql: String): List<List<String?>> =
            driver
                .executeQuery(null, sql, { cursor ->
                    val result = mutableListOf<List<String?>>()
                    while (cursor.next().value) result += listOf(cursor.getString(0), cursor.getString(1))
                    QueryResult.Value(result)
                }, 0)
                .value

        fun indexed(): Long =
            rows(
                "SELECT count(*),NULL FROM replica_redacted_event WHERE match_id IS NOT NULL AND device_id IS NOT NULL AND device_seq IS NOT NULL",
            ).single().first()!!.toLong()

        fun proofs(): List<List<String?>> = rows("SELECT event_id,event_json FROM replica_redacted_event ORDER BY event_id")

        fun upgrade() {
            AdvantlyDatabase.Schema.migrate(driver, 9, LocalData.SCHEMA_VERSION).value
            reopen()
            var next = 0
            data.accountIdentities.select("scope", "account", 0, "PHONE") { "identity-${next++}" }
        }

        fun reopen() {
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            data = LocalData(driver)
        }

        override fun close() {
            driver.close()
            file.delete()
        }
    }

    @Test
    fun schemaNineProofsAreIndexedInPagesWithoutChangingJsonOrAcknowledgements() =
        OldDatabase().use { old ->
            val proofs = (0..204).map(::proof)
            proofs.forEach { old.insert(it) }
            old.driver.execute(
                null,
                "INSERT INTO match_event(id,match_id,device_id,device_seq,lamport,type,payload,confirmed,cloud_state) VALUES (?,?,?,?,?,?,?,1,1)",
                7,
            ) {
                val event = proofs.first()
                bindString(0, event.id)
                bindString(1, event.matchId)
                bindString(2, event.deviceId)
                bindLong(3, event.deviceSeq)
                bindLong(4, event.lamport)
                bindString(5, event.type)
                bindString(6, event.payload.replace("Gelöschter Spieler", "Private"))
            }
            val before = old.proofs()
            val raw = old.rows("SELECT payload,confirmed || ':' || cloud_state FROM match_event")
            old.upgrade()
            assertEquals(before, old.proofs())
            assertEquals(0, old.indexed())
            old.data.cloud.storeDownloaded(listOf(point()))
            assertEquals(205, old.indexed())
            assertEquals(before, old.proofs())
            assertEquals(raw, old.rows("SELECT payload,confirmed || ':' || cloud_state FROM match_event WHERE id='proof-0000'"))
            old.reopen()
            assertEquals(205, old.indexed())
            assertEquals(before, old.proofs())
            val final = proofs.last()
            old.data.cloud.storeDownloaded(listOf(final.copy(payload = final.payload.replace("Gelöschter Spieler", "Late name"))))
            assertEquals(
                listOf(listOf(final.payload, "0:1")),
                old.rows("SELECT payload,confirmed || ':' || cloud_state FROM match_event WHERE id='${final.id}'"),
            )
        }

    @Test
    fun lateConflictRollsBackAllIndexedPagesAndBlocksRawImportAfterReopen() =
        OldDatabase().use { old ->
            (0..204).forEach { old.insert(proof(it)) }
            old.insert(proof(0).copy(id = "z-conflicting-proof"))
            val before = old.proofs()
            old.upgrade()
            repeat(2) {
                assertFails { old.data.cloud.storeDownloaded(listOf(point())) }
                assertEquals(0, old.indexed())
                assertEquals(before, old.proofs())
                assertTrue(old.rows("SELECT id,payload FROM match_event").isEmpty())
                old.reopen()
            }
        }

    @Test
    fun malformedOldProofsDoNotBecomeAuthorityAndRemainUnchanged() {
        val valid = proof(1)
        val invalid =
            listOf(
                Triple(valid.id, valid, "{"),
                Triple("wrong-row-id", valid, DataJson.encodeToString(StoredEvent.serializer(), valid)),
                Triple(point().id, point(), DataJson.encodeToString(StoredEvent.serializer(), point())),
                valid.copy(deviceSeq = 0).let { Triple(it.id, it, DataJson.encodeToString(StoredEvent.serializer(), it)) },
                valid.copy(payload = valid.payload.replace("Gelöschter Spieler", "Unproven name")).let {
                    Triple(it.id, it, DataJson.encodeToString(StoredEvent.serializer(), it))
                },
            )
        for ((id, event, json) in invalid) {
            OldDatabase().use { old ->
                old.insert(proof(0))
                old.insert(event, id, json)
                val before = old.proofs()
                old.upgrade()
                assertFails { old.data.cloud.storeDownloaded(listOf(point())) }
                assertEquals(before, old.proofs())
                assertEquals(0, old.indexed())
                assertTrue(old.rows("SELECT id,payload FROM match_event").isEmpty())
            }
        }
    }

    @Test
    fun fenceFailureRollsBackBackfillAndRawWriteThenRetryUsesSameProofs() =
        OldDatabase().use { old ->
            (0..204).forEach { old.insert(proof(it)) }
            val before = old.proofs()
            old.upgrade()
            var sawSecondPage = false
            assertFails {
                old.data.cloud.storeDownloaded(listOf(point())) {
                    if (old.indexed() > 200) {
                        sawSecondPage = true
                        error("Authority lost")
                    }
                }
            }
            assertTrue(sawSecondPage)
            assertEquals(0, old.indexed())
            assertEquals(before, old.proofs())
            assertTrue(old.rows("SELECT id,payload FROM match_event").isEmpty())
            old.reopen()
            old.data.cloud.storeDownloaded(listOf(point()))
            assertEquals(205, old.indexed())
            assertEquals(before, old.proofs())
        }

    @Test
    fun conflictingOldProofsAlsoBlockCompletedTransferExportAndIdempotentCompletion() =
        OldDatabase().use { old ->
            val event = proof(0)
            old.insert(event)
            old.insert(event.copy(id = "other-id"))
            old.upgrade()
            val ctx = old.data.replicas.captureContext()
            old.data.replicas.applyAccess(ctx, "match", 1, ReplicaAccessLevel.MEMBER) {}
            val transfer = RedactionTransfer("old-completed", "match", 1, 1, 1)
            val from = old.data.replicas.beginRedaction(ctx, transfer) {}
            val page = RedactionPage(0, from, RedactionCursor(null, 1), emptyList(), listOf(ReplicaRedactionEvent(event, 1)), true, true)
            old.data.replicas.stageRedaction(ctx, transfer.id, page) {}
            old.driver.execute(null, "UPDATE replica_transfer SET completed=1 WHERE id='old-completed'", 0)
            val before = old.proofs()
            assertFails { old.data.replicas.exportRedactionPage(ctx, transfer.id, 0) }
            assertFails { old.data.replicas.completeRedaction(ctx, transfer.id) {} }
            assertEquals(0, old.indexed())
            assertEquals(before, old.proofs())
            assertFalse(
                old.data.replicas
                    .access(ctx, "match")!!
                    .ready,
            )
        }

    @Test
    fun oldProofMustAlsoMatchExistingRawIdentityAndImmutableContent() {
        for (sameId in listOf(false, true)) {
            OldDatabase().use { old ->
                val event = proof(0)
                old.insert(event)
                old.driver.execute(
                    null,
                    "INSERT INTO match_event(id,match_id,device_id,device_seq,lamport,type,payload,confirmed,cloud_state) VALUES (?,?,?,?,?,?,?,1,1)",
                    7,
                ) {
                    bindString(0, if (sameId) event.id else "other-raw-id")
                    bindString(1, event.matchId)
                    bindString(2, event.deviceId)
                    bindLong(3, event.deviceSeq)
                    bindLong(4, if (sameId) event.lamport + 1 else event.lamport)
                    bindString(5, event.type)
                    bindString(6, event.payload)
                }
                val proofs = old.proofs()
                val raw = old.rows("SELECT id,lamport || ':' || confirmed || ':' || cloud_state FROM match_event")
                old.upgrade()
                assertFails { old.data.cloud.storeDownloaded(listOf(point())) }
                assertEquals(0, old.indexed())
                assertEquals(proofs, old.proofs())
                assertEquals(raw, old.rows("SELECT id,lamport || ':' || confirmed || ':' || cloud_state FROM match_event"))
            }
        }
    }
}
