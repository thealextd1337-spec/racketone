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
    fun legacyHeaderWithoutAnEventLogStillProvidesHistoryAndH2h() {
        val driver = ReadCountingDriver(createInMemoryJvmDriver())
        val data = LocalData(driver)
        val me = data.players.ensureSelf("Alex", 1) { PlayerId("me") }.id
        val guest = data.players.addGuest(PlayerId("guest"), "Bea", 1).id
        val id =
            eu.advantly.core.model
                .MatchId("legacy")
        data.matches.insertIfAbsent(
            id,
            Presets.TENNIS_ONE_SET.format(),
            Lineup(listOf(me), listOf(guest), me),
            eu.advantly.core.model
                .MatchMeta(),
            1,
        )
        data.matches.updateProgress(
            id,
            eu.advantly.core.scoring.MatchStatus.FINISHED,
            eu.advantly.data.ScoreSummary(
                winner = Side.A,
                reason = eu.advantly.core.scoring.ResultReason.STOPPED,
                setsWonA = 1,
                sets = listOf(eu.advantly.data.SetScore(6, 4)),
                current = eu.advantly.data.SetScore(2, 1),
            ),
            100,
        )
        val session = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), { 100L })
        driver.payloadReads = 0
        val store = SessionMatchStore(session, WallClock { 100 })
        val summary = store.matches.value.single()
        assertEquals(Side.A, summary.result.winner)
        assertEquals(
            eu.advantly.core.scoring
                .SidePair(6, 4),
            summary.sets.single().games,
        )
        assertEquals(
            eu.advantly.core.scoring
                .SidePair(2, 1),
            summary.interruptedSet?.games,
        )
        assertEquals(
            1,
            eu.advantly.presentation.stats
                .headToHead(
                    store.matches.value,
                    guest,
                    eu.advantly.presentation.stats.HistoryRole.OPPONENT,
                    setOf(me),
                ).wins,
        )
        assertEquals(0, driver.payloadReads)
        store.close()
        session.close()
        driver.close()
    }

    @Test
    fun coldHistoryAndMetadataRefreshReadHeadersWithoutEventPayloads() {
        val driver = ReadCountingDriver(createInMemoryJvmDriver())
        val data = LocalData(driver)
        val me = data.players.ensureSelf("Alex", 1) { PlayerId("me") }.id
        val guest = data.players.addGuest(PlayerId("guest"), "Bea", 1).id
        val slot = Placeholders.id("slot")
        var now = 10L
        val session = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), { now })
        val store = SessionMatchStore(session, WallClock { now })
        val finished = session.create(Presets.TENNIS_ONE_SET.format(), Lineup(listOf(me), listOf(slot), me))
        repeat(5) {
            now++
            session.recordPoint(Side.A)
        }
        now++
        store.endMatch(eu.advantly.core.scoring.EndReason.STOPPED)
        val expected = store.record(finished)!!.summary
        store.dismiss()
        store.close()
        driver.payloadReads = 0
        val cold = SessionMatchStore(session, WallClock { now })
        assertEquals(0, driver.payloadReads, "Kaltstart darf keine Event-Payloads lesen")
        assertEquals(expected, cold.matches.value.single())
        val unchanged = cold.matches.value.single()
        val other = session.create(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(me), listOf(guest), me))
        driver.payloadReads = 0
        session.deleteMatch(other)
        assertEquals(0, driver.payloadReads)
        kotlin.test.assertSame(unchanged, cold.matches.value.single(), "Unveränderter Kopf behält sein Setup")
        now += 1000
        assertTrue(cold.assignPlayer(finished, slot, guest))
        assertEquals(
            expected.finishedAtMs,
            cold.matches.value
                .single()
                .finishedAtMs,
        )
        assertEquals(
            "Bea",
            cold.matches.value
                .single()
                .setup
                .team(Side.B)
                .single()
                .displayName,
        )
        // Das Zuweisungskommando lädt seine Arbeitskopie aus dem Log; der folgende reine Refresh nicht.
        driver.payloadReads = 0
        cold.renameGuest(guest, "Beatrice")
        assertEquals(
            "Beatrice",
            cold.matches.value
                .single()
                .setup
                .team(Side.B)
                .single()
                .displayName,
        )
        assertEquals(0, driver.payloadReads, "Umbenennung und Verlauf-Refresh lesen keine Payloads")
        cold.close()
        session.close()
        driver.close()
    }

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
