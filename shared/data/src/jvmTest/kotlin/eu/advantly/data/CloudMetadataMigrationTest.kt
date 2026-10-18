package eu.advantly.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CloudMetadataMigrationTest {
    @Test
    fun schemaFiveKeepsOldRowsOwnershipPayloadsAndSeparateReceiptsAfterReopen() {
        val file = File.createTempFile("advantly-schema-five", ".db")
        try {
            File("src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
            var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            try {
                AdvantlyDatabase.Schema.migrate(driver, 1, 5).value
                driver.execute(null, "INSERT INTO player VALUES ('old-self','Alex','SELF',1,NULL)", 0)
                driver.execute(
                    null,
                    "INSERT INTO match_header(id,sport,discipline,format_json,lineup_json,meta_json,created_at_ms,updated_at_ms,status) VALUES ('old','TENNIS','SINGLES','{}','{}','{}',1,1,'IN_PROGRESS')",
                    0,
                )
                driver.execute(null, "INSERT INTO match_assignment VALUES ('old','slot',NULL)", 0)
                driver.execute(null, "INSERT INTO setting VALUES ('assignments.projectionVersion','2')", 0)
                val events = (1L..3L).map { StoredEvent("e$it", "old", "phone", it, it, "original-$it") }
                SqlDelightEventStore(driver).appendAll(events)
                driver.execute(null, "UPDATE match_event SET confirmed=1, cloud_state=0 WHERE id='e1'", 0)
                driver.execute(null, "UPDATE match_event SET confirmed=0, cloud_state=1 WHERE id='e2'", 0)
                driver.execute(null, "UPDATE match_event SET cloud_state=2, cloud_rejection='forbidden' WHERE id='e3'", 0)
                driver.execute(null, "INSERT INTO cloud_device_owner VALUES ('phone','alice')", 0)
                driver.execute(null, "INSERT INTO cloud_cursor VALUES ('alice','pull',42)", 0)
                AdvantlyDatabase.Schema.migrate(driver, 5, LocalData.SCHEMA_VERSION).value
                driver.close()
                driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
                val data = LocalData(driver)
                assertEquals(events, data.events.eventsFor("old"))
                assertEquals(2, data.events.unconfirmedCount())
                assertEquals(1, data.cloud.pendingCount())
                assertEquals(mapOf("e3" to "forbidden"), data.cloud.rejectedEvents())
                assertEquals(42, data.cloud.cursor("alice", "pull"))
                assertEquals("Alex", data.players.get(PlayerId("old-self"))?.displayName)
                CloudMetadataKind.entries.forEach { kind ->
                    val state = assertNotNull(data.cloud.metadataState(kind, if (kind == CloudMetadataKind.PLAYER) "old-self" else "old"))
                    assertEquals(CloudMetadataOrigin.UNKNOWN, state.origin)
                    assertEquals(1, state.selected.revision)
                    assertEquals(0, state.uploadedRevision)
                }
                val session = data.cloudUploads.activate("alice")
                val selection = data.cloudUploads.capture(session, 100)
                assertEquals(listOf("e1"), selection.events.map { it.id })
                assertTrue(selection.metadataRevisions.isEmpty())
                data.matches.updateMeta(MatchId("old"), MatchMeta(venue = "Court"))
                assertEquals(
                    2,
                    data.cloud
                        .metadataState(CloudMetadataKind.HEADER, "old")
                        ?.selected
                        ?.revision,
                )
                data.cloudUploads.acknowledge(selection, setOf("e1"))
                assertEquals(2, data.events.unconfirmedCount())
                assertEquals(0, data.cloud.metadataState(CloudMetadataKind.HEADER, "old")?.uploadedRevision)
                val owned = LocalPlayer(PlayerId("cloud-owned"), "Bea", PlayerKind.GUEST, 0, null)
                data.cloud.storeDownloadedPlayer(owned, "alice", owned = true)
                data.players.rename(owned.id, "Beatrice")
                val selected = data.cloudUploads.capture(session, 100)
                data.cloudUploads.acknowledge(selected, emptySet(), metadata = selected.metadataRevisions)
                data.players.rename(owned.id, "B.")
                driver.close()
                driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
                val reopened = LocalData(driver)
                val persisted = assertNotNull(reopened.cloud.metadataState(CloudMetadataKind.PLAYER, owned.id.value))
                assertEquals(3, persisted.selected.revision)
                assertEquals(2, persisted.uploadedRevision)
                assertEquals(CloudMetadataOrigin.CLOUD, persisted.origin)
                assertEquals("alice", persisted.writerProfileId)
                assertEquals(
                    2,
                    reopened.cloud
                        .metadataState(CloudMetadataKind.HEADER, "old")
                        ?.selected
                        ?.revision,
                )
                assertEquals(2, reopened.events.unconfirmedCount())
            } finally {
                driver.close()
            }
        } finally {
            file.delete()
        }
    }
}
