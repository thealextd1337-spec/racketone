package eu.advantly.presentation.stats

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.CorrectedSet
import eu.advantly.core.scoring.CorrectionIssueKind
import eu.advantly.core.scoring.GameServer
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.SetKind
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.match.play
import eu.advantly.presentation.match.setupFor
import eu.advantly.presentation.match.testStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Statistik nach „Ergebnis bearbeiten“: korrigierte Aufschläger und Spiele ohne Punkte (0.3.0). */
class ResultCorrectionStatisticsTest {
    @Test
    fun correctedServersReassignServeStatisticsBreaksAndServeInfo() {
        val store = testStore()
        val setup = store.setupFor(Presets.TENNIS_SINGLES_BO3.format())
        val id = store.start(setup)
        // Spiel 1 laut Zählung: A schlägt auf, B gewinnt zu null (Break); mit Doppelfehler-Info am ersten Punkt
        store.play("B")
        assertTrue(store.setServeInfo(assertNotNull(store.active.value!!.lastPoint).id, ServeOutcome.DOUBLE_FAULT))
        store.play("BBB" + "AAAA".repeat(12))
        val before = MatchStatistics.compute(assertNotNull(store.record(id)))
        // A schlägt in 7 Spielen auf (eins an B verloren), B in 6 (alle verloren)
        assertEquals(Ratio(1, 7), before.breaks.b)
        assertEquals(Ratio(0, 6), before.serve.b.serviceGames)
        assertEquals(1, before.serve.a.doubleFaults)

        val b = setup.lineup.b.single()
        assertTrue(store.correctGameServers(id, listOf(GameServer(0, 0, b))))
        val after = MatchStatistics.compute(assertNotNull(store.record(id)))
        assertEquals(Ratio(0, 6), after.breaks.b, "B hat sein eigenes Aufschlagspiel gewonnen")
        assertEquals(Ratio(1, 7), after.serve.b.serviceGames)
        assertEquals(Ratio(4, 28), after.serve.b.servicePoints)
        assertEquals(0, after.serve.a.doubleFaults + after.serve.b.doubleFaults, "Doppelfehler passt nicht mehr zur Aufschlagseite")
        assertEquals(before.pointsWon, after.pointsWon, "Punkte bleiben erhalten")
    }

    @Test
    fun gamesWithoutPointsCountOnlyWithAKnownWinner() {
        val store = testStore()
        val setup = store.setupFor(Presets.TENNIS_SINGLES_BO3.format())
        val id = store.start(setup)
        // gezählt 6:0 und 5:5 – dann „Beenden“, korrigiert auf 6:0 7:5: die zwei fehlenden Spiele gewann A
        store.play("AAAA".repeat(6) + "AAAABBBB".repeat(5))
        assertTrue(store.stopMatch())
        val editing = ResultEditing(assertNotNull(store.record(id)))
        val sets = listOf(CorrectedSet(SetKind.REGULAR, SidePair(6, 0)), CorrectedSet(SetKind.REGULAR, SidePair(7, 5)))
        assertEquals(null, editing.check(sets).issue)
        assertEquals(CorrectionIssueKind.SET_SCORE, editing.check(sets.take(1) + CorrectedSet(SetKind.REGULAR, SidePair(7, 4))).issue?.kind)
        val preview = editing.serviceGames(sets)
        assertEquals(listOf(Side.A, Side.A), preview.filter { !it.recorded }.map { it.winner })
        assertTrue(store.correctResult(id, sets))
        val b = setup.lineup.b.single()
        val chosen = editing.servers(mapOf((1 to 10) to b, (1 to 11) to b, (5 to 0) to b), preview)
        assertEquals(listOf(GameServer(1, 10, b), GameServer(1, 11, b)), chosen, "nur vorhandene Spiele")
        val before =
            MatchStatistics
                .compute(assertNotNull(store.record(id)))
                .serve.b.serviceGames
        assertTrue(store.correctGameServers(id, chosen))
        val after =
            MatchStatistics
                .compute(assertNotNull(store.record(id)))
                .serve.b.serviceGames
        assertEquals(Ratio(before.won, before.total + 2), after, "zwei verlorene Aufschlagspiele ohne Punkte")
        assertFalse(store.correctGameServers(id, listOf(GameServer(1, 12, b))), "Spiel gibt es nicht")
        val record = assertNotNull(store.record(id))
        assertTrue(record.summary.result.corrected)
        assertEquals(Discipline.SINGLES, record.summary.setup.format.discipline)
    }
}
