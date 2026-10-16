package eu.advantly.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudOwnershipMigrationTest {
    @Test
    fun schemaThreeKeepsExistingCloudStateWithoutImplicitOwnership() {
        val file = File.createTempFile("advantly-schema-three", ".db")
        try {
            File("src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
            var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            try {
                AdvantlyDatabase.Schema.migrate(driver, 1, 3).value
                val rows = (1L..3L).map { StoredEvent("e$it", "match", "phone", it, it, "payload-$it") }
                SqlDelightEventStore(driver).appendAll(rows)
                driver.execute(null, "UPDATE match_event SET cloud_state=1 WHERE id='e1'", 0)
                driver.execute(null, "UPDATE match_event SET cloud_state=2, cloud_rejection='forbidden' WHERE id='e2'", 0)
                driver.execute(null, "INSERT INTO cloud_cursor VALUES ('alice','pull',42)", 0)
                AdvantlyDatabase.Schema.migrate(driver, 3, LocalData.SCHEMA_VERSION).value
                val first = LocalData(driver)
                assertEquals(rows, first.events.eventsFor("match"))
                assertEquals(1, first.cloud.pendingCount())
                assertEquals(mapOf("e2" to "forbidden"), first.cloud.rejectedEvents())
                assertEquals(42, first.cloud.cursor("alice", "pull"))
                val session = first.cloudUploads.activate("alice")
                assertTrue(
                    first.cloudUploads
                        .capture(session, 10)
                        .events
                        .isEmpty(),
                )
                first.cloudUploads.bindOwnedDevices(session, setOf("phone"))
                val snapshot = first.cloudUploads.capture(session, 10)
                assertEquals(listOf("e3"), snapshot.events.map { it.id })
                driver.close()
                driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
                val reopened = LocalData(driver)
                assertFailsWith<IllegalStateException> { reopened.cloudUploads.acknowledge(snapshot, setOf("e3")) }
                val next = reopened.cloudUploads.activate("alice")
                assertEquals(
                    listOf("e3"),
                    reopened.cloudUploads
                        .capture(next, 10)
                        .events
                        .map { it.id },
                )
                val bob = reopened.cloudUploads.activate("bob")
                assertFailsWith<IllegalStateException> { reopened.cloudUploads.bindOwnedDevices(bob, setOf("phone")) }
                assertTrue(
                    reopened.cloudUploads
                        .capture(bob, 10)
                        .events
                        .isEmpty(),
                )
            } finally {
                driver.close()
            }
        } finally {
            file.delete()
        }
    }
}
