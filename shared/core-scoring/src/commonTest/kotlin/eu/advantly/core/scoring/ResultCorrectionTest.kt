package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ergebnis-Korrektur nach Matchende (`ResultCorrected`, `GameServersCorrected`): Plausibilität, Fold, Statistik. */
class ResultCorrectionTest {
    private fun reg(
        a: Int,
        b: Int,
        tbA: Int? = null,
        tbB: Int? = null,
    ) = CorrectedSet(SetKind.REGULAR, SidePair(a, b), if (tbA != null && tbB != null) SidePair(tbA, tbB) else null)

    private fun tb(
        a: Int,
        b: Int,
    ) = CorrectedSet(SetKind.MATCH_TIEBREAK, SidePair(a, b))

    private fun rally(
        a: Int,
        b: Int,
        kind: SetKind = SetKind.RALLY_GAME,
    ) = CorrectedSet(kind, SidePair(a, b))

    private val completed = MatchResult(Side.A, ResultReason.COMPLETED)

    private fun issue(
        format: MatchFormat,
        vararg sets: CorrectedSet,
        recorded: MatchResult = completed,
    ): CorrectionIssueKind? = ResultCorrection.check(format, lineupFor(format.discipline), sets.toList(), recorded).issue?.kind

    private fun result(
        format: MatchFormat,
        vararg sets: CorrectedSet,
        recorded: MatchResult = completed,
    ): MatchResult? = ResultCorrection.check(format, lineupFor(format.discipline), sets.toList(), recorded).result

    @Test
    fun tennisSetScoresFollowTheFormat() {
        val bo3 = Presets.TENNIS_SINGLES_BO3.format()
        assertNull(issue(bo3, reg(6, 4), reg(7, 5)))
        assertNull(issue(bo3, reg(6, 0), reg(6, 7, 3, 7), reg(7, 6, 10, 8)))
        assertEquals(CorrectionIssueKind.SET_SCORE, issue(bo3, reg(8, 4)))
        assertEquals(CorrectionIssueKind.SET_SCORE, issue(bo3, reg(6, 5)))
        assertEquals(CorrectionIssueKind.SET_SCORE, issue(bo3, reg(6, 6)))
        assertEquals(CorrectionIssueKind.SET_SCORE, issue(bo3, reg(8, 6)), "Tiebreak bei 6:6, kein Vorteilssatz")
        assertEquals(CorrectionIssueKind.TIEBREAK_REQUIRED, issue(bo3, reg(7, 6)))
        assertEquals(CorrectionIssueKind.TIEBREAK_SCORE, issue(bo3, reg(7, 6, 7, 6)))
        assertEquals(CorrectionIssueKind.TIEBREAK_SCORE, issue(bo3, reg(7, 6, 5, 7)), "Tiebreak-Sieger = Satzsieger")
        assertEquals(CorrectionIssueKind.TIEBREAK_SCORE, issue(bo3, reg(6, 4, 7, 3)))
        assertEquals(CorrectionIssueKind.SET_KIND, issue(bo3, tb(10, 8)))
        assertEquals(CorrectionIssueKind.AFTER_MATCH_END, issue(bo3, reg(6, 4), reg(6, 4), reg(6, 4)))
        assertEquals(CorrectionIssueKind.NO_SETS, issue(bo3))
        assertEquals(CorrectionIssueKind.SET_KIND, issue(bo3, rally(21, 10)))
    }

    @Test
    fun specialTennisFormatsAreAllowed() {
        // Match-Tiebreak im Entscheidungssatz
        val mtb = Presets.TENNIS_BO3_MTB10.format()
        assertNull(issue(mtb, reg(6, 4), reg(3, 6), tb(10, 8)))
        assertNull(issue(mtb, reg(6, 4), reg(3, 6), tb(12, 10)))
        assertEquals(CorrectionIssueKind.SET_SCORE, issue(mtb, reg(6, 4), reg(3, 6), tb(10, 9)))
        assertEquals(CorrectionIssueKind.SET_KIND, issue(mtb, reg(6, 4), reg(3, 6), reg(6, 4)))
        // Vorteilssatz im Entscheidungssatz
        val adv = Presets.TENNIS_BO3_ADV_FINAL.format()
        assertNull(issue(adv, reg(6, 4), reg(3, 6), reg(12, 10)))
        assertEquals(CorrectionIssueKind.SET_SCORE, issue(adv, reg(6, 4), reg(3, 6), reg(7, 6, 7, 2)))
        // Kurzsätze bis 4 mit Tiebreak bei 4:4
        val short = Presets.TENNIS_SHORT_SETS.format()
        assertNull(issue(short, reg(4, 2), reg(5, 4, 7, 5)))
        assertEquals(CorrectionIssueKind.SET_SCORE, issue(short, reg(6, 4)))
        // offener Modus: Tiebreak-Satz bis 7 oder 10 nach einem normalen Satz
        val open = Presets.TENNIS_OPEN.format()
        assertNull(issue(open, reg(6, 3), tb(7, 5)))
        assertNull(issue(open, reg(6, 3), tb(10, 4)))
        assertEquals(CorrectionIssueKind.SET_KIND, issue(open, tb(7, 5)), "erster Satz ist immer ein normaler Satz")
    }

    @Test
    fun rallySportsUsePointsWinByTwoAndCaps() {
        val badminton = Presets.BADMINTON_SINGLES_BO3_21.format()
        assertNull(issue(badminton, rally(21, 19), rally(30, 29)))
        assertNull(issue(badminton, rally(25, 23), rally(21, 0)))
        assertEquals(CorrectionIssueKind.SET_SCORE, issue(badminton, rally(21, 20)))
        assertEquals(CorrectionIssueKind.SET_SCORE, issue(badminton, rally(31, 29)))
        assertEquals(CorrectionIssueKind.SET_SCORE, issue(badminton, rally(22, 19)))
        val tt = Presets.TABLE_TENNIS_SINGLES_BO5.format()
        assertNull(issue(tt, rally(11, 9), rally(14, 12), rally(11, 0)))
        assertEquals(CorrectionIssueKind.SET_SCORE, issue(tt, rally(11, 10)))
        assertEquals(CorrectionIssueKind.AFTER_MATCH_END, issue(tt, rally(11, 9), rally(11, 9), rally(11, 9), rally(11, 9)))
        // Pickleball mit Side-Out-Zählung (Doppel, zweiter Aufschläger)
        val pickleball = Presets.PICKLEBALL_DOUBLES_BO3_11.format()
        assertNull(issue(pickleball, rally(11, 9, SetKind.PB_GAME), rally(13, 11, SetKind.PB_GAME)))
        assertEquals(CorrectionIssueKind.SET_SCORE, issue(pickleball, rally(11, 10, SetKind.PB_GAME)))
        assertEquals(CorrectionIssueKind.SET_KIND, issue(pickleball, rally(11, 9)))
    }

    @Test
    fun winnerAndReasonFollowTheSets() {
        val bo3 = Presets.TENNIS_SINGLES_BO3.format()
        assertEquals(MatchResult(Side.B, ResultReason.COMPLETED, true), result(bo3, reg(4, 6), reg(6, 3), reg(2, 6)))
        // unvollständig: wie „Beenden, wie es steht“
        assertEquals(MatchResult(Side.A, ResultReason.STOPPED, true), result(bo3, reg(6, 4)))
        assertEquals(MatchResult(null, ResultReason.STOPPED, true), result(bo3, reg(6, 4), reg(4, 6)))
        // Aufgabe bleibt Aufgabe, solange das Match nicht regulär entschieden ist
        val retired = MatchResult(Side.A, ResultReason.RETIRED)
        assertEquals(retired.copy(corrected = true), result(bo3, reg(4, 6), recorded = retired))
        assertEquals(MatchResult(Side.B, ResultReason.COMPLETED, true), result(bo3, reg(4, 6), reg(4, 6), recorded = retired))
        // offener Modus: mehr Sätze gewinnt, Gleichstand unentschieden
        val open = Presets.TABLE_TENNIS_OPEN.format()
        assertEquals(MatchResult(null, ResultReason.DRAW, true), result(open, rally(11, 5), rally(5, 11)))
        assertEquals(MatchResult(Side.B, ResultReason.COMPLETED, true), result(open, rally(5, 11)))
    }

    @Test
    fun correctionOnlyAppliesAfterTheEndAndTheLastValidOneWins() {
        val d = MatchDriver(Presets.TENNIS_SINGLES_BO3.format())
        val early = d.send(ResultCorrected(listOf(reg(6, 4), reg(6, 4))))
        assertTrue(early in d.state.ignoredEvents, "vor Matchende ungültig")
        d.points("AAAA".repeat(12))
        assertEquals(MatchStatus.FINISHED, d.state.status)
        val undoTarget = d.engineState.undoTarget
        d.send(ResultCorrected(listOf(reg(6, 4), reg(3, 6), reg(7, 5))))
        assertEquals(SidePair(2, 1), d.state.setsWon)
        assertEquals(MatchResult(Side.A, ResultReason.COMPLETED, true), d.state.result)
        assertEquals(listOf(SidePair(6, 4), SidePair(3, 6), SidePair(7, 5)), d.state.sets.map { it.games })
        assertEquals(undoTarget, d.engineState.undoTarget, "Korrektur ist kein UI-Undo-Ziel")
        assertEquals(emptyList(), d.state.effects)
        val invalid = d.send(ResultCorrected(listOf(reg(9, 0))))
        assertTrue(invalid in d.state.ignoredEvents)
        assertEquals(SidePair(2, 1), d.state.setsWon, "ungültige Korrektur ändert nichts")
        d.send(ResultCorrected(listOf(reg(2, 6), reg(4, 6))))
        assertEquals(MatchResult(Side.B, ResultReason.COMPLETED, true), d.state.result)
        // leere Liste = gezähltes Ergebnis
        d.send(ResultCorrected(emptyList()))
        assertEquals(MatchResult(Side.A, ResultReason.COMPLETED), d.state.result)
        assertEquals(listOf(SidePair(6, 0), SidePair(6, 0)), d.state.sets.map { it.games })
    }

    @Test
    fun undoOfTheEndDropsTheCorrectionAndUndoOfTheCorrectionRestoresThePrevious() {
        val d = MatchDriver(Presets.TENNIS_SINGLES_BO3.format())
        d.points("AAAA".repeat(12))
        val first = d.send(ResultCorrected(listOf(reg(6, 4), reg(6, 4))))
        val second = d.send(ResultCorrected(listOf(reg(6, 2), reg(6, 2))))
        d.send(Undo(second))
        assertEquals(listOf(SidePair(6, 4), SidePair(6, 4)), d.state.sets.map { it.games })
        assertFalse(first in d.state.ignoredEvents)
        // Undo des Matchendes: Match läuft wieder, die Korrektur davor verfällt
        d.undo()
        assertEquals(MatchStatus.IN_PROGRESS, d.state.status)
        assertTrue(first in d.state.ignoredEvents)
        assertNull(d.state.result)
    }

    @Test
    fun interruptedSetIsDroppedAndOnlyTheScorerMayCorrect() {
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val d = MatchDriver(format)
        d.points("AAAA".repeat(6) + "BBBB")
        d.send(MatchEnded(EndReason.RETIRED, Side.B))
        d.send(ResultCorrected(listOf(reg(6, 4))))
        assertNull(d.state.current)
        assertEquals(MatchResult(Side.A, ResultReason.RETIRED, true), d.state.result)
        // Rollenregel: fremdes Gerät
        val genesis = ScoreEvent(EventId("g"), MatchId("m"), DeviceId("d"), 0, 0, 0, null, MatchCreated(format, d.lineup))
        val foreign =
            ScoreEvent(EventId("x"), MatchId("m"), DeviceId("other"), 1, 999, 0, null, ResultCorrected(listOf(reg(6, 0))))
        val state = ScoringEngine.replay(listOf(genesis) + d.events + foreign).state
        assertTrue(EventId("x") in state.rejectedByRole)
        assertEquals(listOf(SidePair(6, 4)), state.sets.map { it.games })
    }

    @Test
    fun gameServersReassignServeStatisticsAndBreakPoints() {
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val d = MatchDriver(format)
        // Spiel 1: A schlägt laut Zählung auf und verliert zu null → laut Zählung ein Break für B
        d.points("BBBB" + "AAAA".repeat(12))
        val before = ScoringEngine.pointHistory(format, d.lineup, d.events, null)
        assertEquals(p("A1"), before.first().server)
        assertTrue(
            before[3]
                .before.statusFlags
                .single()
                .breakPoint,
        )
        d.send(GameServersCorrected(listOf(GameServer(0, 0, p("B1")))))
        val after = ScoringEngine.pointHistory(format, d.lineup, d.events, null)
        assertEquals(List(4) { p("B1") }, after.take(4).map { it.server })
        assertEquals(
            Side.B,
            after
                .first()
                .before.serve
                ?.side,
        )
        assertFalse(
            after[3]
                .before.statusFlags
                .single()
                .breakPoint,
            "Spielball des Aufschlägers, kein Breakball",
        )
        assertEquals(before.drop(4), after.drop(4), "andere Spiele unverändert")
    }

    @Test
    fun invalidGameServersAreIgnored() {
        val d = MatchDriver(Presets.TENNIS_SINGLES_BO3.format())
        val early = d.send(GameServersCorrected(listOf(GameServer(0, 0, p("B1")))))
        d.points("AAAA".repeat(12))
        val missingGame = d.send(GameServersCorrected(listOf(GameServer(0, 6, p("B1")))))
        val duplicate = d.send(GameServersCorrected(listOf(GameServer(0, 1, p("B1")), GameServer(0, 1, p("A1")))))
        val stranger = d.send(GameServersCorrected(listOf(GameServer(0, 1, p("Z9")))))
        assertTrue(d.state.ignoredEvents.containsAll(listOf(early, missingGame, duplicate, stranger)))
        assertEquals(emptyList(), d.engineState.gameServers)
        val pickleball = MatchDriver(Presets.PICKLEBALL_SINGLES_BO3_11.format(), lineupFor(Discipline.SINGLES))
        pickleball.points("A".repeat(60))
        val pb = pickleball.send(GameServersCorrected(listOf(GameServer(0, 0, p("B1")))))
        assertEquals(MatchStatus.FINISHED, pickleball.state.status)
        assertTrue(pb in pickleball.state.ignoredEvents, "nur Tennis/Padel")
    }

    @Test
    fun serviceGamesCoverCorrectedGamesWithoutPoints() {
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val d = MatchDriver(format)
        // gezählt 6:0 6:0, korrigiert 6:0 7:5: Satz 2 hat 6 Spiele mit Punkten (A) und 6 ohne
        d.points("AAAA".repeat(12))
        d.send(ResultCorrected(listOf(reg(6, 0), reg(7, 5))))
        val genesis = ScoreEvent(EventId("g"), MatchId("m"), DeviceId("d"), 0, 0, 0, null, MatchCreated(format, d.lineup))
        val log = listOf(genesis) + d.events
        var games = ScoringEngine.serviceGames(log)
        assertEquals(6 + 12, games.size)
        val set2 = games.filter { it.set == 1 }
        assertEquals(6, set2.count { it.recorded })
        // offene Spiele: A braucht noch 1, B 5 → nicht eindeutig zuzuordnen
        assertTrue(set2.filterNot { it.recorded }.all { it.winner == null && it.server == null })
        d.send(GameServersCorrected(listOf(GameServer(1, 11, p("B1")))))
        games = ScoringEngine.serviceGames(listOf(genesis) + d.events)
        assertEquals(p("B1"), games.last().server)
        // gezählt 6:0 6:0, korrigiert 6:0 7:5 ist mehrdeutig; 6:0 6:0 → 6:0 und 7:5 nach vorherigem 5:5 wäre eindeutig:
        val e = MatchDriver(format)
        e.points("AAAA".repeat(6) + "AAAABBBB".repeat(5))
        e.send(MatchEnded(EndReason.STOPPED))
        e.send(ResultCorrected(listOf(reg(6, 0), reg(7, 5))))
        val inferred = ScoringEngine.serviceGames(listOf(genesis.copy(payload = MatchCreated(format, e.lineup))) + e.events)
        assertEquals(listOf(Side.A, Side.A), inferred.filter { it.set == 1 && !it.recorded }.map { it.winner })
    }
}
