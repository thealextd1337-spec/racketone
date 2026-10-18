package eu.advantly.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudPullMigrationTest {
    @Test
    fun schemaSixToSevenPreservesAllExistingRowsOwnersPayloadsCursorsAndIndependentAcksAfterReopen() {
        val file = File.createTempFile("pull-schema-six", ".db")
        try {
            File("src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
            var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            try {
                AdvantlyDatabase.Schema.migrate(driver, 1, 6).value
                driver.execute(null, "INSERT INTO setting VALUES ('assignments.projectionVersion','2')", 0)
                driver.execute(null, "INSERT INTO player VALUES ('self','Alex','SELF',1,NULL)", 0)
                driver.execute(null, "INSERT INTO cloud_device_owner VALUES ('phone','alice')", 0)
                driver.execute(null, "INSERT INTO cloud_cursor VALUES ('alice','pull',42)", 0)
                driver.execute(null, "INSERT INTO cloud_cursor VALUES ('bob','pull',77)", 0)
                driver.execute(null, "INSERT INTO cloud_cursor VALUES ('alice','pull:events:old',15)", 0)
                for ((id, confirmed, cloud) in listOf(Triple("pending", 1, 0), Triple("uploaded", 0, 1), Triple("rejected", 1, 2))) {
                    driver.execute(
                        null,
                        """INSERT INTO match_event(id,match_id,device_id,device_seq,lamport,payload,confirmed,cloud_state,cloud_rejection)
                        VALUES ('$id','old','phone',${cloud + 1},${cloud + 1},'original-$id',
                            $confirmed,$cloud,${if (cloud == 2) "'forbidden'" else "NULL"})""",
                        0,
                    )
                }
                val oldTables =
                    driver
                        .executeQuery(null, "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", { cursor ->
                            val names = mutableListOf<String>()
                            while (cursor.next().value) names += requireNotNull(cursor.getString(0))
                            app.cash.sqldelight.db.QueryResult
                                .Value(names)
                        }, 0)
                        .value

                fun rows(): Map<String, List<List<String?>>> =
                    oldTables.associateWith { table ->
                        val count =
                            driver
                                .executeQuery(null, "PRAGMA table_info($table)", { c ->
                                    var columns = 0
                                    while (c.next().value) columns++
                                    app.cash.sqldelight.db.QueryResult
                                        .Value(columns)
                                }, 0)
                                .value
                        driver
                            .executeQuery(null, "SELECT * FROM $table ORDER BY rowid", { cursor ->
                                val result = mutableListOf<List<String?>>()
                                while (cursor.next().value) result += (0 until count).map(cursor::getString)
                                app.cash.sqldelight.db.QueryResult
                                    .Value(result)
                            }, 0)
                            .value
                    }
                val before = rows()
                AdvantlyDatabase.Schema.migrate(driver, 6, 7).value
                assertEquals(before, rows())
                driver.close()
                driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
                assertEquals(before, rows())
                val data = LocalData(driver)
                assertEquals(7, AdvantlyDatabase.Schema.version)
                assertEquals(42, data.cloudPulls.progress("alice").cursor)
                assertEquals(77, data.cloudPulls.progress("bob").cursor)
                assertEquals(0, data.cloudPulls.progress("alice").checkRound)
                assertEquals(0, data.cloudPulls.progress("alice").eventRequestsSinceMetadata)
                assertTrue(!data.cloudPulls.progress("alice").roundCatalogReady)
                assertTrue(!data.cloudPulls.progress("alice").roundActive)
                assertEquals(0, data.cloudPulls.failedEventJobs("alice"))
                assertEquals(0, data.cloudPulls.pendingMaterializations("alice"))
                assertEquals(mapOf("rejected" to "forbidden"), data.cloud.rejectedEvents())
                assertEquals(1, data.events.unconfirmedCount())
                assertEquals(1, data.cloud.pendingCount())
                assertEquals(
                    listOf("pending"),
                    data.cloudUploads
                        .capture(data.cloudUploads.activate("alice"), 10)
                        .events
                        .map { it.id },
                )
                assertTrue(data.cloudPulls.nextEventWork("alice", 1) == null)
            } finally {
                driver.close()
            }
        } finally {
            file.delete()
        }
    }
}
