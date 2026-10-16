package eu.advantly.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudOutboxMigrationTest {
    @Test
    fun schemaTwoKeepsExistingEventsAndSettingsWhileStartingAnIndependentOutbox() {
        val file = File.createTempFile("advantly-schema-two", ".db")
        try {
            File("src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            try {
                AdvantlyDatabase.Schema.migrate(driver, 1, 2).value
                driver.execute(null, "INSERT INTO setting (key, value) VALUES ('keep', 'value')", 0)
                val old = SqlDelightEventStore(driver)
                val rows =
                    listOf(
                        StoredEvent("one", "match", "phone", 1, 1, "first"),
                        StoredEvent("two", "match", "phone", 2, 2, "second", confirmed = true),
                    )
                old.appendAll(rows)
                val before = old.eventsFor("match")
                AdvantlyDatabase.Schema.migrate(driver, 2, LocalData.SCHEMA_VERSION).value
                val data = LocalData(driver)
                assertEquals(before, data.events.eventsFor("match"))
                assertEquals("value", data.settings.get("keep"))
                assertEquals(1, data.events.unconfirmedCount())
                assertEquals(rows, data.cloud.pendingEvents(10))
                assertEquals(0, data.cloud.cursor("owner", "pull"))
                assertEquals(LocalData.SCHEMA_VERSION, AdvantlyDatabase.Schema.version)
            } finally {
                driver.close()
            }
        } finally {
            file.delete()
        }
    }
}
