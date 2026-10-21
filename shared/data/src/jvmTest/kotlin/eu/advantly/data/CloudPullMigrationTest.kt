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
                AdvantlyDatabase.Schema.migrate(driver, 7, LocalData.SCHEMA_VERSION).value
                val data = LocalData(driver)
                assertEquals(LocalData.SCHEMA_VERSION, AdvantlyDatabase.Schema.version)
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

    @Test
    fun schemaTenToElevenAddsEmptyReadinessMarksKeepsPullRowsAndForcesFullCatalogAtSameGeneration() {
        val file = File.createTempFile("pull-schema-ten", ".db")
        try {
            File("src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                AdvantlyDatabase.Schema.migrate(driver, 1, 10).value
                driver.execute(null, "INSERT INTO cloud_pull_profile(profile_id,visibility_epoch,check_round) VALUES ('alice',3,2)", 0)
                driver.execute(null, "INSERT INTO cloud_pull_event_work(profile_id,match_id,last_checked) VALUES ('alice','m',2)", 0)
                driver.execute(null, "INSERT INTO cloud_pull_receipt VALUES ('alice','m','e',7,'d',1,0)", 0)
                driver.execute(null, "UPDATE cloud_pull_profile SET metadata_complete = 1, round_catalog_ready = 1, scan_run = 4", 0)
                driver.execute(null, "INSERT INTO cloud_cursor VALUES ('alice','pull',42)", 0)
                driver.execute(null, "INSERT INTO cloud_cursor VALUES ('alice','pull:events:m',7)", 0)
                AdvantlyDatabase.Schema.migrate(driver, 10, LocalData.SCHEMA_VERSION).value
                val db = AdvantlyDatabase(driver)
                assertEquals(
                    3,
                    db.cloudPullQueries
                        .profile("alice")
                        .executeAsOne()
                        .visibility_epoch,
                )
                assertEquals(
                    2,
                    db.cloudPullQueries
                        .eventWork("alice", "m")
                        .executeAsOne()
                        .last_checked,
                )
                assertEquals(
                    7,
                    db.cloudPullQueries
                        .receipt("alice", "m", "e")
                        .executeAsOne()
                        .server_seq,
                )
                // Alte Pullzeilen ohne Access: Vollkatalog ab null bei gleichem G als Neuscan, Eventcursor bleiben.
                val profile = db.cloudPullQueries.profile("alice").executeAsOne()
                assertEquals(3, profile.target_epoch)
                assertEquals(5, profile.scan_run)
                assertEquals(0, profile.metadata_complete)
                assertEquals(0, profile.round_catalog_ready)
                assertEquals(null, db.cloudSyncQueries.cursor("alice", "pull").executeAsOneOrNull())
                assertEquals(7, db.cloudSyncQueries.cursor("alice", "pull:events:m").executeAsOne())
                assertEquals(null, db.cloudPullQueries.readinessMark("alice", "m").executeAsOneOrNull())
                db.cloudPullQueries.ensureReadinessMark("alice", "m", 3, 0, 7)
                assertEquals(
                    7,
                    db.cloudPullQueries
                        .readinessMark("alice", "m")
                        .executeAsOne()
                        .high_watermark,
                )
            }
        } finally {
            file.delete()
        }
    }
}
