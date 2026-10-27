package eu.advantly.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.core.model.PlayerId
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class FriendPlayerMigrationTest {
    @Test
    fun schemaThirteenToFourteenKeepsPlayersAndAddsEmptyFriendTables() {
        val file = File.createTempFile("friend-schema-13", ".db")
        try {
            File("src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                AdvantlyDatabase.Schema.migrate(driver, 1, 13).value
                driver.execute(null, "INSERT INTO player VALUES ('self','Alex','SELF',1,NULL)", 0)
                driver.execute(null, "INSERT INTO player VALUES ('guest','Sam','GUEST',1,NULL)", 0)
                AdvantlyDatabase.Schema.migrate(driver, 13, LocalData.SCHEMA_VERSION).value
            }
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val data = LocalData(driver)
                assertEquals(listOf("Alex", "Sam"), data.players.all().map { it.displayName })
                val bob = data.players.rememberFriend("alice", "bob-profile", "Bob", 2) { PlayerId("bob-player") }
                assertEquals("bob-profile", data.players.friendProfile("alice", bob!!.id))
            }
        } finally {
            file.delete()
        }
    }
}
