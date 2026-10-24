package eu.advantly.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CloudUploadInventoryTest {
    @Test
    fun aggregatesPreserveLegacyOwnerWriterTombstonePlaceholderAndBlockedProjectionRules() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver)
            val database = AdvantlyDatabase(driver)

            fun sql(statement: String) {
                driver.execute(null, statement, 0)
            }
            sql("INSERT INTO cloud_device_owner VALUES ('a','alice'),('b','bob')")

            fun source(
                id: String,
                device: String?,
                writer: String? = null,
            ) {
                sql(
                    "UPDATE cloud_metadata SET origin='LOCAL', source_device_id=${device?.let { "'$it'" } ?: "NULL"}, " +
                        "writer_profile_id=${writer?.let { "'$it'" } ?: "NULL"} WHERE entity_id='$id'",
                )
            }

            fun header(
                id: String,
                device: String?,
                writer: String? = null,
            ) {
                sql(
                    """INSERT INTO match_header(id,sport,discipline,format_json,lineup_json,meta_json,created_at_ms,updated_at_ms,status)
                    VALUES ('$id','TENNIS','SINGLES','not-json','not-json','not-json',1,1,'FINISHED')""",
                )
                source(id, device, writer)
            }

            fun player(
                id: String,
                kind: String,
                device: String?,
                writer: String? = null,
            ) {
                sql("INSERT INTO player VALUES ('$id','Name','$kind',0,NULL)")
                source(id, device, writer)
            }
            header("own", "a")
            header("foreign", "b")
            header("writer", "b", "alice")
            header("writer-only", null, "alice")
            header("both", "a", "alice")
            header("hidden", "a")
            header("ack", "a")
            header("unknown", null)
            sql("UPDATE cloud_metadata SET uploaded_revision=revision WHERE entity_id='ack'")
            sql("INSERT INTO match_tombstone VALUES ('hidden',1)")
            player("guest", "GUEST", "a")
            player("archived", "ARCHIVED", "a")
            player("placeholder", "PLACEHOLDER", "a")
            player("foreign-player", "GUEST", "b")
            player("writer-player", "GUEST", "b", "alice")
            player("hidden", "GUEST", "a")
            sql(
                "INSERT INTO cloud_metadata(kind,entity_id,source_device_id) VALUES ('HEADER','missing-header','a'),('PLAYER','missing-player','a')",
            )

            fun event(
                id: String,
                device: String,
                match: String = "own",
                confirmed: Boolean = false,
            ) {
                data.events.append(StoredEvent(id, match, device, id.length.toLong(), id.length.toLong(), "{}", confirmed = confirmed))
            }
            event("pending", "a", confirmed = true)
            event("rejected", "a")
            event("uploaded-event", "a")
            event("foreign-event", "b", "foreign")
            event("hidden-event", "a", "hidden")
            event("unknown-event", "unbound", "unknown")
            sql("UPDATE match_event SET cloud_state=2, cloud_rejection='forbidden' WHERE id='rejected'")
            sql("UPDATE match_event SET cloud_state=1 WHERE id='uploaded-event'")
            val alice = data.cloudUploads.activate("alice")
            val inventory = data.cloudUploads.inventory(alice)
            assertEquals(CloudUploadInventory(1, 1, 16), inventory)
            assertEquals(legacyInventory(database, data.cloud, "alice"), inventory)
            assertEquals(5L, data.events.unconfirmedCount())
            assertFalse(
                database.cloudMetadataQueries
                    .pendingForProfile("alice", emptyList(), null, "", 100)
                    .executeAsList()
                    .any { it.kind == "PROJECTION" && it.entity_id == "own" },
            )
            val bob = data.cloudUploads.activate("bob")
            assertEquals(legacyInventory(database, data.cloud, "bob"), data.cloudUploads.inventory(bob))
            assertFailsWith<IllegalStateException> { data.cloudUploads.inventory(alice) }
            data.cloudUploads.deactivate()
            assertFailsWith<IllegalStateException> { data.cloudUploads.inventory(bob) }
        }
    }

    @Test
    fun inventoryRequiresTheOriginalRepositorySessionAndRejectsOuterTransactions() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver)
            val session = data.cloudUploads.activate("alice")
            assertEquals(CloudUploadInventory(0, 0, 0), data.cloudUploads.inventory(session))
            assertFailsWith<IllegalStateException> { data.transaction { data.cloudUploads.inventory(session) } }
            val other = LocalData(driver)
            assertFailsWith<IllegalStateException> { other.cloudUploads.inventory(session) }
            other.cloudUploads.activate("alice")
            assertFailsWith<IllegalStateException> { data.cloudUploads.inventory(session) }
        }
    }

    @Test
    fun migratedSchemaSupportsScalarInventoryAndIndependentDeviceAcknowledgements() {
        val file = File.createTempFile("advantly-upload-inventory", ".db")
        try {
            File("src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                AdvantlyDatabase.Schema.migrate(driver, 1, LocalData.SCHEMA_VERSION).value
                val data = LocalData(driver)
                data.events.append(StoredEvent("event", "match", "phone", 1, 1, "{}", confirmed = true))
                val session = data.cloudUploads.activate("alice")
                data.cloudUploads.bindOwnedDevices(session, setOf("phone"))
                assertEquals(CloudUploadInventory(1, 0, 0), data.cloudUploads.inventory(session))
                data.cloud.acknowledge(emptySet(), mapOf("event" to "forbidden"))
                assertEquals(CloudUploadInventory(0, 1, 0), data.cloudUploads.inventory(session))
                data.cloud.acknowledge(setOf("event"))
                assertEquals(CloudUploadInventory(0, 0, 0), data.cloudUploads.inventory(session))
                assertEquals(0L, data.events.unconfirmedCount())
                assertEquals(LocalData.SCHEMA_VERSION, AdvantlyDatabase.Schema.version)
            }
        } finally {
            file.delete()
        }
    }

    /** Vorherige Listen-Semantik ausschließlich als Vergleichsorakel für die kleine Fachfixture. */
    private fun legacyInventory(
        database: AdvantlyDatabase,
        cloud: CloudSyncRepository,
        profile: String,
    ): CloudUploadInventory {
        val queries = database.cloudUploadQueries
        val pending = queries.pendingForProfile(profile, null, Long.MAX_VALUE) { id, _, _, _, _, _, _, _, _, _ -> id }.executeAsList().size
        val rejected =
            cloud.rejectedEvents().keys.count { id ->
                val event = queries.eventById(id) { _, match, device, _, _, _, _, _, _, _ -> match to device }.executeAsOneOrNull()
                event != null && queries.deviceOwner(event.second).executeAsOneOrNull() == profile &&
                    database.matchTombstoneQueries.countById(event.first).executeAsOne() == 0L
            }

        fun pendingVersion(
            kind: CloudMetadataKind,
            id: String,
        ): Boolean {
            val row = database.cloudMetadataQueries.byKey(kind.name, id).executeAsOneOrNull() ?: return false
            return row.revision > row.uploaded_revision &&
                (row.writer_profile_id == profile || row.source_device_id?.let { queries.deviceOwner(it).executeAsOneOrNull() } == profile)
        }
        val players =
            database.playerQueries.selectAll().executeAsList().count {
                it.kind != "PLACEHOLDER" && pendingVersion(CloudMetadataKind.PLAYER, it.id)
            }
        val matches =
            database.matchHeaderQueries.recent(Long.MAX_VALUE).executeAsList().sumOf { header ->
                if (database.matchTombstoneQueries.countById(header.id).executeAsOne() > 0) {
                    0
                } else {
                    listOf(CloudMetadataKind.HEADER, CloudMetadataKind.PARTICIPANTS, CloudMetadataKind.PROJECTION)
                        .count { pendingVersion(it, header.id) }
                }
            }
        return CloudUploadInventory(pending.toLong(), rejected.toLong(), (players + matches).toLong())
    }
}
