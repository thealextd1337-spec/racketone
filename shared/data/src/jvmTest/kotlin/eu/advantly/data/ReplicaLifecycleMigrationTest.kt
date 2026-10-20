package eu.advantly.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplicaLifecycleMigrationTest {
    @Test
    fun schemaSevenToEightPreservesRowsPayloadsProvenanceSourcesAndIndependentAcksAfterCloseReopen() {
        val file = File.createTempFile("replica-schema-seven", ".db")
        File("src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
        var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")

        fun sql(statement: String) {
            driver.execute(null, statement, 0)
        }

        fun query(
            statement: String,
            columns: Int,
        ): List<List<String?>> =
            driver
                .executeQuery(null, statement, { c ->
                    val result = mutableListOf<List<String?>>()
                    while (c.next().value) result += (0 until columns).map(c::getString)
                    QueryResult.Value(result)
                }, 0)
                .value
        try {
            AdvantlyDatabase.Schema.migrate(driver, 1, 7).value
            sql("INSERT INTO setting VALUES ('assignments.projectionVersion','2')")
            sql("INSERT INTO setting VALUES ('identity.scope','scope-A')")
            sql("INSERT INTO setting VALUES ('identity.profile','A')")
            sql("INSERT INTO local_identity VALUES ('scope-A','phone','self')")
            sql("INSERT INTO local_source VALUES ('phone','self','PHONE','scope-A',1,17)")
            sql("INSERT INTO local_scope_source VALUES ('watch','scope-A')")
            sql("INSERT INTO local_match_source VALUES ('old','phone')")
            sql("INSERT INTO cloud_device_owner VALUES ('phone','A')")
            sql("INSERT INTO player VALUES ('self','Original name','SELF',1,NULL)")
            sql("INSERT INTO cloud_cursor VALUES ('A','pull',17)")
            sql("INSERT INTO cloud_cursor VALUES ('B','pull',29)")
            sql("INSERT INTO cloud_pull_profile(profile_id,visibility_epoch) VALUES ('A',7)")
            sql(
                "INSERT INTO cloud_pull_snapshot(profile_id,kind,entity_id,server_version,snapshot_json,access) VALUES ('A','MATCH','snapshot-match',5,'{}','member')",
            )
            sql("INSERT INTO cloud_pull_receipt VALUES ('A','receipt-match','receipt',9,'phone',9,0)")
            sql(
                "INSERT INTO cloud_metadata(kind,entity_id,origin,source_device_id,revision,uploaded_revision) VALUES ('HEADER','local-match','LOCAL','phone',4,0)",
            )
            sql("INSERT INTO cloud_metadata(kind,entity_id,origin,revision,uploaded_revision) VALUES ('HEADER','cloud-match','CLOUD',3,3)")
            sql(
                "INSERT INTO cloud_metadata(kind,entity_id,origin,source_device_id,revision,uploaded_revision) VALUES ('HEADER','uploaded-match','PEER','watch',5,2)",
            )
            sql("""INSERT INTO match_assignment VALUES ('local-match','slot','{"id":"guest","displayName":"Legacy"}')""")
            for ((id, ack, cloud) in listOf(Triple("pending", 1, 0), Triple("uploaded", 0, 1), Triple("rejected", 1, 2))) {
                sql(
                    "INSERT INTO match_event(id,match_id,device_id,device_seq,lamport,payload,confirmed,cloud_state,cloud_rejection) VALUES ('$id','$id-match','phone',1,1,'exact-$id',$ack,$cloud,${if (cloud == 2) "'denied'" else "NULL"})",
                )
            }
            val columns =
                query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", 1).associate { row ->
                    val table = row.single()!!
                    table to query("PRAGMA table_info($table)", 2).map { it[1]!! }
                }

            fun snapshot() =
                columns.mapValues { (table, names) -> query("SELECT ${names.joinToString(",")} FROM $table ORDER BY rowid", names.size) }
            val before = snapshot()
            AdvantlyDatabase.Schema.migrate(driver, 7, LocalData.SCHEMA_VERSION).value
            assertEquals(before, snapshot())
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            assertEquals(before, snapshot())
            val data = LocalData(driver)
            assertEquals(LocalData.SCHEMA_VERSION, AdvantlyDatabase.Schema.version)
            assertEquals(
                setOf(
                    "snapshot-match",
                    "receipt-match",
                    "cloud-match",
                    "uploaded-match",
                ),
                query("SELECT match_id FROM replica_cloud_seen", 1)
                    .map {
                        it.single()
                    }.toSet(),
            )
            assertTrue(!data.replicas.isCloudSeen("local-match"))
            assertEquals(listOf(listOf(null)), query("SELECT player_id FROM match_assignment", 1))
            assertEquals(0, data.replicas.captureContext().contextRevision)
            assertEquals(before, snapshot())
        } finally {
            driver.close()
            file.delete()
        }
    }
}
