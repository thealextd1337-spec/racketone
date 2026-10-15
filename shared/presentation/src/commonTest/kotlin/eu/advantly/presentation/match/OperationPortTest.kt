package eu.advantly.presentation.match

import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.SetKind
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.stats.MatchStatistics
import eu.advantly.presentation.stats.Ratio
import eu.advantly.presentation.stats.ServeStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port-Kommandos „Bedienung & Statistik“ (Feldtest 2026-10-15) über die engine-gestützte In-Memory-Implementierung. */
class OperationPortTest {
    private val InMemoryMatchStore.snap get() = assertNotNull(active.value)

    @Test
    fun removeLastPointOfSideAndRedo() {
        val store = testStore()
        store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        assertEquals(SidePair(false, false), store.snap.canRemovePoint)
        store.play("ABB")
        assertEquals(SidePair(true, true), store.snap.canRemovePoint)
        assertTrue(store.removeLastPointOf(Side.A), "auch nach Punkten von B")
        assertEquals(
            SidePair(0, 2),
            store.snap.state.current
                ?.points,
        )
        assertEquals(SidePair(false, true), store.snap.canRemovePoint)
        assertFalse(store.removeLastPointOf(Side.A))
        assertTrue(store.redo())
        assertEquals(
            SidePair(1, 2),
            store.snap.state.current
                ?.points,
        )
    }

    @Test
    fun setEndOffersTiebreakSets() {
        val store = testStore()
        store.start(store.setupFor(Presets.PADEL_OPEN.format()))
        assertEquals(emptyList(), store.snap.nextSetOptions)
        store.play("AAAA".repeat(6))
        assertEquals(listOf(NextSetKind.REGULAR, NextSetKind.TIEBREAK_7, NextSetKind.MATCH_TIEBREAK_10), store.snap.nextSetOptions)
        assertTrue(store.startNextSet(NextSetKind.TIEBREAK_7))
        assertEquals(
            SetKind.MATCH_TIEBREAK,
            store.snap.state.current
                ?.setKind,
        )
        assertFalse(store.startNextSet(NextSetKind.REGULAR), "kein Dialog offen")
        store.play("B".repeat(7))
        assertEquals(MatchStatus.AWAITING_DECISION, store.snap.state.status)
    }

    @Test
    fun serverChoiceAndServeStatisticsPerTeamAndPlayer() {
        val store = testStore()
        val setup = store.setupFor(Presets.PADEL_GOLDEN_BO3.format())
        val (me, mia) = setup.lineup.a
        val (jonas, tom) = setup.lineup.b
        val id = store.start(setup)
        assertTrue(store.snap.awaitingServerChoice)
        assertTrue(store.chooseServer(mia))
        assertFalse(store.snap.awaitingServerChoice)

        fun point(
            side: Char,
            serve: ServeOutcome?,
        ) {
            store.play(side.toString())
            val last = assertNotNull(store.snap.lastPoint)
            if (serve != null) assertTrue(store.setServeInfo(last.id, serve), "$side $serve")
        }
        point('A', ServeOutcome.FIRST_SERVE_IN)
        assertFalse(store.setServeInfo(assertNotNull(store.snap.lastPoint).id, ServeOutcome.DOUBLE_FAULT), "Punkt des Aufschlägers")
        point('B', ServeOutcome.DOUBLE_FAULT)
        assertTrue(assertNotNull(store.snap.lastPoint).doubleFaultAllowed)
        point('A', ServeOutcome.SECOND_SERVE_IN)
        point('A', null)
        point('A', null)
        assertEquals(mia, store.snap.lastPoint?.server)
        assertEquals(
            tom,
            store.snap.state.serverChoice
                ?.players
                ?.get(1),
        )
        assertTrue(store.chooseServer(tom))
        point('B', ServeOutcome.FIRST_SERVE_IN)
        store.play("BBB")

        val stats = MatchStatistics.compute(assertNotNull(store.record(id)))
        assertTrue(stats.serveInfoRecorded)
        val teamA = ServeStats(Ratio(4, 5), Ratio(1, 1), Ratio(1, 3), Ratio(1, 1), Ratio(1, 2), doubleFaults = 1, recorded = 3)
        assertEquals(teamA, stats.serve.a)
        assertEquals(listOf(mia, tom), stats.servers.map { it.player }, "nur Spieler mit Aufschlag, Lineup-Reihenfolge")
        assertEquals(teamA, stats.servers[0].stats)
        val tomStats = stats.servers[1].stats
        assertEquals(Ratio(4, 4), tomStats.servicePoints)
        assertEquals(Ratio(1, 1), tomStats.firstServeIn)
        assertEquals(Ratio(0, 0), tomStats.secondServeWon)
        assertNull(tomStats.secondServeWon.percent, "nicht erfasst → „—“")
        assertTrue(me != jonas)
    }

    @Test
    fun serveStatisticsWithoutServeInfo() {
        val store = testStore()
        val id = store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        store.play("AAAB")
        val stats = MatchStatistics.compute(assertNotNull(store.record(id)))
        assertFalse(stats.serveInfoRecorded)
        assertEquals(Ratio(3, 4), stats.serve.a.servicePoints)
        assertNull(stats.serve.a.firstServeIn.percent)
        assertEquals(ServeStats.EMPTY, stats.serve.b)
    }

    @Test
    fun serveInfoDisabledByOptionOrSport() {
        val store = testStore()
        val tennis = store.setupFor(Presets.TENNIS_SINGLES_BO3.format())
        store.start(tennis.copy(meta = tennis.meta.copy(serveStats = false)))
        store.play("A")
        assertFalse(store.snap.serveInfoEnabled)
        assertFalse(store.setServeInfo(assertNotNull(store.snap.lastPoint).id, ServeOutcome.FIRST_SERVE_IN))
        store.start(store.setupFor(Presets.TABLE_TENNIS_SINGLES_BO5.format()))
        store.play("A")
        assertFalse(store.snap.serveInfoEnabled)
        assertNull(store.snap.state.serverChoice)
    }
}
