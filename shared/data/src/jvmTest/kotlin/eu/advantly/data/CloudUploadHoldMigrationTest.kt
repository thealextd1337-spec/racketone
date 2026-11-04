package eu.advantly.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudUploadHoldMigrationTest {
    /** Plan 0.6.3 §3.2: Schema 16 → 17 behält ausstehende Events und legt die leere Zurückstellungstabelle an. */
    @Test
    fun schemaSixteenToSeventeenKeepsPendingEventsAndStartsWithoutHolds() {
        val file = File.createTempFile("upload-hold-schema-16", ".db")
        try {
            File("src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                AdvantlyDatabase.Schema.migrate(driver, 1, 16).value
                val data = LocalData(driver)
                data.events.append(StoredEvent("a", "match", "phone", 1, 1, """{"type":"PointWon","side":"A"}""", type = "PointWon"))
                // Ohne activate(): das liest schon die neue Tabelle.
                driver.execute(null, "INSERT INTO cloud_device_owner VALUES ('phone', 'alice')", 0)
                AdvantlyDatabase.Schema.migrate(driver, 16, LocalData.SCHEMA_VERSION).value
            }
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val data = LocalData(driver) { 1_000 }
                val session = data.cloudUploads.activate("alice")
                val snapshot = data.cloudUploads.capture(session, 10)
                assertEquals(listOf("a"), snapshot.events.map { it.id })
                data.cloudUploads.acknowledge(snapshot, emptySet(), held = setOf("match"))
                assertEquals(emptyList(), data.cloudUploads.capture(session, 10).events)
                assertEquals(1, data.cloud.pendingCount())
            }
        } finally {
            file.delete()
        }
    }
}
