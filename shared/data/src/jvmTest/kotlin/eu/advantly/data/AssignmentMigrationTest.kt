package eu.advantly.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.PlayerAssigned
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssignmentMigrationTest {
    @Test
    fun schemaOneKeepsDataAndRebuildsAssignmentsFromOutOfOrderLog() {
        val file = File.createTempFile("advantly-schema-one", ".db")
        try {
            File("src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            try {
                driver.execute(null, "INSERT INTO setting (key, value) VALUES ('keep', 'value')", 0)
                val me = PlayerId("me")
                val slot = Placeholders.id("opponent")
                val match = MatchId("old-match")
                val person = Player(PlayerId("bea"), "Bea")

                fun event(
                    n: Long,
                    payload: EventPayload,
                ) = ScoreEvent(EventId("e$n"), match, DeviceId("phone"), n + 1, n + 1, n, null, payload)
                val genesis =
                    event(0, MatchCreated(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(me), listOf(slot), me), createdBy = me))
                val assigned = event(1, PlayerAssigned(slot, person, me))
                val oldStore = SqlDelightEventStore(driver)
                oldStore.appendAll(listOf(assigned, genesis).map { it.toStoredEvent() })
                AdvantlyDatabase.Schema.migrate(driver, 1, LocalData.SCHEMA_VERSION).value
                val data = LocalData(driver)
                assertEquals("value", data.settings.get("keep"))
                assertEquals(2, data.events.count(match.value))
                assertEquals(mapOf(slot to person), data.assignments(match))
                data.rebuildAllAssignments()
                assertEquals(mapOf(slot to person), data.assignments(match))
                data.events.append(event(2, PlayerAssigned(slot, null, me)).toStoredEvent())
                assertTrue(data.assignments(match).isEmpty())
                assertTrue(LocalData(driver).assignments(match).isEmpty())
            } finally {
                driver.close()
            }
        } finally {
            file.delete()
        }
    }
}
