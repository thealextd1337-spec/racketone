package eu.advantly.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.Presets
import eu.advantly.data.db.AdvantlyDatabase
import eu.advantly.health.HealthQuality
import eu.advantly.health.HealthSeries
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthSeriesMigrationTest {
    @Test
    fun schemaFourteenToFifteenKeepsMatchesAndAddsAnEmptyHealthTableWithCleanup() {
        val file = File.createTempFile("health-schema-14", ".db")
        try {
            File("src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                AdvantlyDatabase.Schema.migrate(driver, 1, 14).value
                val data = LocalData(driver)
                val self = data.players.ensureSelf("Alex", 1) { PlayerId("self") }.id
                data.matches.insertIfAbsent(
                    MatchId("m"),
                    Presets.BADMINTON_1GAME_15.format(),
                    Lineup(listOf(self), listOf(PlayerId("x")), self),
                    MatchMeta(),
                    1,
                )
                AdvantlyDatabase.Schema.migrate(driver, 14, LocalData.SCHEMA_VERSION).value
            }
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val data = LocalData(driver)
                val id = MatchId("m")
                assertTrue(data.health.allSeries().isEmpty())
                val stored =
                    StoredHealthSeries(id, DeviceId("watch"), 1, HealthSeries(0, 5_000, listOf(99), listOf(3), 0, HealthQuality.OK))
                assertTrue(data.health.upsert(stored))
                assertEquals(listOf(stored), data.health.forMatch(id))
                data.deleteMatch(id)
                assertTrue(data.health.allSeries().isEmpty())
            }
        } finally {
            file.delete()
        }
    }
}
