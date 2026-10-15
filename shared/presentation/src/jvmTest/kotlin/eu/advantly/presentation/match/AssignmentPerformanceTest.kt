package eu.advantly.presentation.match

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Presets
import eu.advantly.data.LocalData
import eu.advantly.data.assignments
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.data.recentPeople
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionConfig
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class ReadCountingDriver(
    private val delegate: SqlDriver,
) : SqlDriver by delegate {
    var payloadReads = 0

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        if ("FROM match_event" in sql && "payload" in sql) payloadReads++
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}

class AssignmentPerformanceTest {
    @Test
    fun scoreSnapshotsRecentsAndUnchangedHistoryNeverReadEventPayloads() {
        val driver = ReadCountingDriver(createInMemoryJvmDriver())
        val data = LocalData(driver)
        val me = data.players.ensureSelf("Alex", 1) { PlayerId("me") }.id
        var now = 10L
        val session = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), { now })
        val store = SessionMatchStore(session, WallClock { now })
        val slot = Placeholders.id("first")
        val finished = session.create(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(me), listOf(slot), me))
        repeat(15) {
            now++
            session.recordPoint(Side.A)
        }
        val person = store.addGuest("Bea")
        assertTrue(store.assignPlayer(finished, slot, person.id))
        val current = session.create(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(me), listOf(Placeholders.id("next")), me))
        driver.payloadReads = 0
        repeat(10) {
            now++
            session.recordPoint(Side.B)
        }
        repeat(5) {
            data.assignments(finished)
            data.recentPeople()
            store.recentPlayers(Sport.BADMINTON)
            store.active.value
        }
        assertEquals(0, driver.payloadReads, "snapshotOf und Personenlesepfade dürfen kein Log lesen")
        // Eine Metadatenrevision bei unverändertem updatedAt darf alte Score-Zusammenfassungen nicht neu falten.
        val before = driver.payloadReads
        assertEquals(current, store.active.value?.matchId)
        session.deleteMatch(current)
        assertEquals(before, driver.payloadReads, "dataRevision darf unveränderte Ergebnis-Caches nicht verwerfen")
        store.close()
        driver.close()
    }
}
