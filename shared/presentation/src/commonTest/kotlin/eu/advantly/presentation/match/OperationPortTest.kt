package eu.advantly.presentation.match

import eu.advantly.core.model.EventId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.PointDetail
import eu.advantly.core.scoring.PointDetailKind
import eu.advantly.core.scoring.PointDetailRules
import eu.advantly.core.scoring.PointDetailSet
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.SetKind
import eu.advantly.core.scoring.SidePair
import eu.advantly.match.session.ServerChoicePhase
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
    fun expectedChoiceContextIsSafeAcrossPhaseMatchAndCorrection() {
        val store = testStore()
        val setup = store.setupFor(Presets.PADEL_GOLDEN_BO3.format())
        val id = store.start(setup)
        val first = assertNotNull(store.snap.serverChoiceContext)
        assertEquals(ServerChoicePhase.FIRST, first.phase)
        assertEquals(setup.lineup.firstServer, first.defaultServer)
        val opponent = setup.lineup.b.last()
        val count = assertNotNull(store.record(id)).events.size
        assertTrue(store.chooseServer(opponent, first))
        assertTrue(store.chooseServer(opponent, first), "Doppeltap trotz geändertem eröffnendem Team")
        assertEquals(count + 1, assertNotNull(store.record(id)).events.size)
        assertFalse(store.snap.canCorrectServerRetroactively)
        assertFalse(store.correctServer(setup.lineup.a.first(), retroactive = true))
        assertEquals(count + 1, assertNotNull(store.record(id)).events.size)
        val chosen = assertNotNull(store.snap.serverChoiceContext)
        assertTrue(store.correctServer(setup.lineup.b.first(), retroactive = false))
        assertNull(store.snap.serverChoiceContext?.chosen)
        val correctedCount = assertNotNull(store.record(id)).events.size
        assertFalse(store.chooseServer(opponent, chosen))
        assertEquals(correctedCount, assertNotNull(store.record(id)).events.size)

        store.play("BBBB")
        val second = assertNotNull(store.snap.serverChoiceContext)
        assertEquals(ServerChoicePhase.SECOND, second.phase)
        val secondPlayer = setup.lineup.a.last()
        val secondCount = assertNotNull(store.record(id)).events.size
        assertFalse(store.chooseServer(secondPlayer, first), "Spieler in beiden Fenstern zulässig")
        assertEquals(secondCount, assertNotNull(store.record(id)).events.size)
        assertTrue(store.chooseServer(secondPlayer, second))
        val nextId = store.start(setup)
        val nextCount = assertNotNull(store.record(nextId)).events.size
        assertFalse(store.chooseServer(secondPlayer, first), "gleiches Lineup, anderes Match")
        assertEquals(nextCount, assertNotNull(store.record(nextId)).events.size)
    }

    @Test
    fun forwardCorrectionAfterChoicePreservesPointServersAndUndoRedo() {
        val store = testStore()
        val setup = store.setupFor(Presets.PADEL_GOLDEN_BO3.format())
        val id = store.start(setup)
        val selected = setup.lineup.a.last()
        val corrected = setup.lineup.b.last()
        assertTrue(store.chooseServer(selected))
        store.play("A")
        val point = assertNotNull(store.snap.lastPoint)
        assertTrue(store.correctServer(corrected, retroactive = false))
        assertEquals(
            corrected,
            store.snap.state.serve
                ?.player,
        )
        assertEquals(selected, store.snap.lastPoint?.server)
        val count = assertNotNull(store.record(id)).events.size
        assertFalse(store.correctServer(corrected, retroactive = true))
        assertEquals(count, assertNotNull(store.record(id)).events.size)
        assertTrue(store.undo(), "Undo nimmt Vorwärtskorrektur zurück")
        assertEquals(
            selected,
            store.snap.state.serve
                ?.player,
        )
        assertTrue(store.redo())
        assertEquals(
            corrected,
            store.snap.state.serve
                ?.player,
        )
        assertEquals(point, store.snap.lastPoint)
    }

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

    /** Review R1: Doppelfehler auf einen Punkt mit `kind = WINNER` zählt in der Statistik (Doppelfehler und 2.-Aufschlag-Nenner). */
    @Test
    fun doubleFaultOnWinnerPointIsCounted() {
        val store = testStore()
        val setup = store.setupFor(Presets.TENNIS_SINGLES_BO3.format())
        val id = store.start(setup)
        store.play("B")
        val base = assertNotNull(store.record(id))
        val pointEvent = base.events.last()
        // Rückschläger-Punkt trägt bereits ein Winner-Detail (z. B. am Handy nachgetragen)
        val winner = PointDetail(PointDetailKind.WINNER, setup.lineup.b.single())
        val withWinner = pointEvent.copy(payload = PointWon(Side.B, winner))
        for (detail in listOf(
            PointDetailRules.withServe(winner, ServeOutcome.DOUBLE_FAULT), // Aufschlag-Leiste (bereinigt)
            winner.copy(serve = ServeOutcome.DOUBLE_FAULT), // älterer Erzeuger ohne Bereinigung (Vorrangregel)
        )) {
            val set =
                pointEvent.copy(
                    id = EventId("detail"),
                    lamport = pointEvent.lamport + 1,
                    deviceSeq = pointEvent.deviceSeq + 1,
                    parentId = pointEvent.id,
                    payload = PointDetailSet(pointEvent.id, detail),
                )
            val events = base.events.dropLast(1) + withWinner + set
            val record = base.copy(events = events, finalState = ScoringEngine.replay(events).state)
            assertEquals(emptyList(), record.finalState.ignoredEvents, "$detail")
            val stats = MatchStatistics.compute(record)
            assertEquals(1, stats.serve.a.doubleFaults, "$detail")
            assertEquals(Ratio(0, 1), stats.serve.a.secondServeWon, "$detail")
            assertEquals(Ratio(0, 1), stats.serve.a.firstServeIn, "$detail")
        }
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
