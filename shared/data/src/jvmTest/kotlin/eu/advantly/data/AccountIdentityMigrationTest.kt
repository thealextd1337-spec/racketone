package eu.advantly.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccountIdentityMigrationTest {
    @Test
    fun schemaFourPreservesPayloadsDeviceAndCloudReceiptsAndImmutableOwnersAfterReopen() {
        val file = File.createTempFile("advantly-identity-migration", ".db")
        try {
            File("src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
            var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            try {
                AdvantlyDatabase.Schema.migrate(driver, 1, 4).value
                val rows = (1L..3L).map { StoredEvent("event-$it", "match", "old-phone", it, it, "payload-$it") }
                SqlDelightEventStore(driver).appendAll(rows)
                driver.execute(null, "UPDATE match_event SET confirmed=1, cloud_state=1 WHERE id='event-1'", 0)
                driver.execute(null, "UPDATE match_event SET cloud_state=2, cloud_rejection='forbidden' WHERE id='event-2'", 0)
                driver.execute(null, "INSERT INTO cloud_device_owner VALUES ('old-phone','alice')", 0)
                AdvantlyDatabase.Schema.migrate(driver, 4, LocalData.SCHEMA_VERSION).value
                driver.close()
                driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
                val data = LocalData(driver)
                assertEquals(rows, data.events.eventsFor("match"))
                assertEquals(2L, data.events.unconfirmedCount())
                assertEquals(mapOf("event-2" to "forbidden"), data.cloud.rejectedEvents())
                assertTrue(data.accountIdentities.localSources().isEmpty())
                assertTrue(data.accountIdentities.adoptionSources().isEmpty())
                val alice = data.cloudUploads.activate("alice")
                assertEquals(
                    listOf("event-3"),
                    data.cloudUploads
                        .capture(alice, 10)
                        .events
                        .map { it.id },
                )
                val bob = data.cloudUploads.activate("bob")
                assertFailsWith<IllegalStateException> { data.cloudUploads.bindOwnedDevices(bob, setOf("old-phone")) }
                assertTrue(
                    data.cloudUploads
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
