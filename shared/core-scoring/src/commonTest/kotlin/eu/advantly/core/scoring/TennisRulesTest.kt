package eu.advantly.core.scoring

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.End
import eu.advantly.core.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regel-Tabellen Tennis (02 §6.1–§6.5, §6.7). */
class TennisRulesTest {
    private fun MatchDriver.winGame(side: Side) = points(if (side == Side.A) "AAAA" else "BBBB")

    private fun MatchDriver.winGames(
        side: Side,
        n: Int,
    ) = apply { repeat(n) { winGame(side) } }

    private val MatchDriver.cur get() = checkNotNull(state.current)
    private val MatchDriver.srv get() = checkNotNull(state.serve)

    @Test
    fun standardGameDisplayDeuceAndAdvantage() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format())

        data class Row(
            val seq: String,
            val a: String,
            val b: String,
            val call: String,
            val callout: Callout?,
            val deuce: Int,
        )
        val rows =
            listOf(
                Row("A", "15", "0", "15-0", null, 0),
                Row("A", "30", "0", "30-0", null, 0),
                Row("B", "30", "15", "30-15", null, 0),
                Row("B", "30", "30", "30-30", null, 0),
                Row("A", "40", "30", "40-30", null, 0),
                Row("B", "40", "40", "40-40", Callout.DEUCE, 1),
                Row("B", "40", "AD", "40-AD", Callout.ADVANTAGE_RECEIVER, 1),
                Row("A", "40", "40", "40-40", Callout.DEUCE, 2),
                Row("A", "AD", "40", "AD-40", Callout.ADVANTAGE_SERVER, 2),
            )
        for (row in rows) {
            m.points(row.seq)
            assertEquals(SidePair(row.a, row.b), m.cur.display, row.toString())
            assertEquals(row.call, m.state.scoreCall, row.toString())
            assertEquals(row.callout, m.cur.callout, row.toString())
            assertEquals(row.deuce, m.cur.deuceCount, row.toString())
            assertEquals(false, m.cur.decidingPoint)
        }
        assertEquals(listOf(StatusFlag(Side.A, FlagLevel.GAME_POINT, 1, false)), m.state.statusFlags)
        m.point(Side.A)
        assertEquals(SidePair(1, 0), m.cur.games)
        assertEquals(listOf(Effect.POINT, Effect.GAME_WON, Effect.CHANGE_ENDS), m.state.effects)
        assertEquals(Side.B, m.srv.side)
    }

    @Test
    fun breakPointsCountByLead() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("BBB")
        assertEquals(listOf(StatusFlag(Side.B, FlagLevel.GAME_POINT, 3, true)), m.state.statusFlags)
        m.points("A")
        assertEquals(listOf(StatusFlag(Side.B, FlagLevel.GAME_POINT, 2, true)), m.state.statusFlags)
    }

    @Test
    fun noAdDecidingPointReceiverChoosesAndBothHaveGamePoint() {
        val m = MatchDriver(Presets.TENNIS_DOUBLES_MTB10.format()).points("AAABBB")
        assertEquals(true, m.cur.decidingPoint)
        assertEquals(Callout.DECIDING_POINT, m.cur.callout)
        assertEquals(ServeCourt.RECEIVER_CHOICE, m.srv.court)
        assertNull(m.srv.receiver)
        assertEquals(
            listOf(StatusFlag(Side.A, FlagLevel.GAME_POINT, 1, false), StatusFlag(Side.B, FlagLevel.GAME_POINT, 1, true)),
            m.state.statusFlags,
        )
        m.point(Side.B)
        assertEquals(SidePair(0, 1), m.cur.games)
    }

    @Test
    fun tiebreakServeOrderCourtsAndEndChanges() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).holdGames(12)
        assertEquals(GameKind.TIEBREAK, m.cur.gameKind)
        assertEquals(SidePair(End.END_1, End.END_2), m.state.ends)
        // k: 0 A rechts, 1 B links, 2 B rechts, 3 A links, 4 A rechts, 5 B links, 6 B rechts
        val expected =
            listOf(
                Side.A to ServeCourt.RIGHT,
                Side.B to ServeCourt.LEFT,
                Side.B to ServeCourt.RIGHT,
                Side.A to ServeCourt.LEFT,
                Side.A to ServeCourt.RIGHT,
                Side.B to ServeCourt.LEFT,
                Side.B to ServeCourt.RIGHT,
            )
        for ((k, pair) in expected.withIndex()) {
            assertEquals(pair, m.srv.side to m.srv.court, "TB-Punkt $k")
            if (k < expected.lastIndex) m.point(if (k % 2 == 0) Side.A else Side.B)
        }
        assertEquals(SidePair(3, 3), m.cur.points)
        assertEquals(SidePair(End.END_2, End.END_1), m.state.ends, "Wechsel nach 6 Punkten")
        assertEquals(listOf(Effect.POINT, Effect.CHANGE_ENDS), m.state.effects)
        m.points("ABABAB")
        assertEquals(SidePair(End.END_1, End.END_2), m.state.ends, "Wechsel nach 12 Punkten")
        m.points("AA")
        assertEquals(SetRecord(SetKind.REGULAR, SidePair(7, 6), SidePair(8, 6), null, Side.A), m.state.sets.single())
        // 13 Spiele ungerade → Wechsel beim Start von Satz 2; Satz 2 eröffnet other(tbFirst) = B
        assertEquals(SidePair(End.END_2, End.END_1), m.state.ends)
        assertEquals(Side.B, m.srv.side)
        assertTrue(Effect.SET_STARTED in m.state.effects && Effect.CHANGE_ENDS in m.state.effects)
    }

    @Test
    fun doublesRotationAndTiebreakRotation() {
        val m = MatchDriver(Presets.TENNIS_BO3_MTB10.format(Discipline.DOUBLES))
        val servers = mutableListOf<String>()
        repeat(5) {
            servers += m.srv.player.value
            m.holdGames(1)
        }
        assertEquals(listOf("A1", "B1", "A2", "B2", "A1"), servers)
        m.holdGames(7)
        assertEquals(GameKind.TIEBREAK, m.cur.gameKind)
        val tbServers = mutableListOf<String>()
        repeat(9) {
            tbServers += m.srv.player.value
            m.point(if (it % 2 == 0) Side.A else Side.B)
        }
        assertEquals(listOf("A1", "B1", "B1", "A2", "A2", "B2", "B2", "A1", "A1"), tbServers)
    }

    @Test
    fun doublesReceiverFollowsReceiveRight() {
        val m = MatchDriver(Presets.TENNIS_BO3_MTB10.format(Discipline.DOUBLES))
        assertEquals("B1", m.srv.receiver?.value)
        m.point(Side.A)
        assertEquals("B2", m.srv.receiver?.value)
    }

    @Test
    fun matchTiebreakReplacesDecidingSet() {
        val m = MatchDriver(Presets.TENNIS_BO3_MTB10.format()).winGames(Side.A, 6).winGames(Side.B, 6)
        assertEquals(SetKind.MATCH_TIEBREAK, m.cur.setKind)
        assertEquals(GameKind.MATCH_TIEBREAK, m.cur.gameKind)
        assertEquals(SidePair(0, 0), m.cur.games)
        assertEquals(Side.A, m.srv.side, "Satz 2 endete mit Aufschlagspiel von B → A eröffnet")
        assertEquals(SidePair(End.END_1, End.END_2), m.state.ends)
        m.points("ABABAB")
        assertEquals(SidePair(End.END_2, End.END_1), m.state.ends)
        m.points("ABABABABAB")
        assertEquals(SidePair(8, 8), m.cur.points)
        assertEquals(SidePair(End.END_1, End.END_2), m.state.ends)
        m.points("AA")
        assertEquals(MatchStatus.FINISHED, m.state.status)
        assertEquals(SetRecord(SetKind.MATCH_TIEBREAK, SidePair(1, 0), SidePair(10, 8), null, Side.A), m.state.sets.last())
        assertEquals(listOf(Effect.POINT, Effect.GAME_WON, Effect.SET_WON, Effect.MATCH_WON), m.state.effects)
        assertEquals(SidePair(End.END_1, End.END_2), m.state.ends, "kein Seitenwechsel nach Matchende")
    }

    @Test
    fun matchPointFlagsInMatchTiebreak() {
        val m = MatchDriver(Presets.TENNIS_BO3_MTB10.format()).winGames(Side.A, 6).winGames(Side.B, 6)
        m.points("AAAAAAAAA")
        assertEquals(listOf(StatusFlag(Side.A, FlagLevel.MATCH_POINT, 9, false)), m.state.statusFlags)
    }

    @Test
    fun finalSetTiebreakToTen() {
        val m =
            MatchDriver(Presets.TENNIS_SINGLES_BO5_FS10.format())
                .winGames(Side.A, 6)
                .winGames(Side.B, 6)
                .winGames(Side.A, 6)
                .winGames(Side.B, 6)
                .holdGames(12)
        assertEquals(GameKind.TIEBREAK, m.cur.gameKind)
        m.points("AAAAAAA")
        assertEquals(MatchStatus.IN_PROGRESS, m.state.status, "7:0 reicht beim TB bis 10 nicht")
        m.points("AAA")
        assertEquals(MatchStatus.FINISHED, m.state.status)
        assertEquals(
            SidePair(10, 0),
            m.state.sets
                .last()
                .tiebreak,
        )
    }

    @Test
    fun advantageFinalSetHasNoTiebreak() {
        val m =
            MatchDriver(Presets.TENNIS_BO3_ADV_FINAL.format())
                .winGames(Side.A, 6)
                .winGames(Side.B, 6)
                .holdGames(12)
        assertEquals(GameKind.STANDARD, m.cur.gameKind)
        assertEquals(SidePair(6, 6), m.cur.games)
        m.winGames(Side.A, 2)
        assertEquals(MatchStatus.FINISHED, m.state.status)
        assertEquals(SetRecord(SetKind.REGULAR, SidePair(8, 6), null, null, Side.A), m.state.sets.last())
    }

    @Test
    fun shortSetTiebreakToFiveWinByOneSingleEndChange() {
        val m = MatchDriver(Presets.TENNIS_SHORT_SETS_3ALL.format()).holdGames(6)
        assertEquals(GameKind.TIEBREAK, m.cur.gameKind)
        val endsBefore = m.state.ends
        m.points("ABAB")
        assertEquals(endsBefore.map { _, e -> e.other }, m.state.ends, "Wechsel nach 4 Punkten")
        m.points("ABAB")
        assertEquals(endsBefore.map { _, e -> e.other }, m.state.ends, "kein weiterer Wechsel (every = null)")
        assertEquals(
            listOf(StatusFlag(Side.A, FlagLevel.SET_POINT, 1, false), StatusFlag(Side.B, FlagLevel.SET_POINT, 1, false)),
            m.state.statusFlags,
        )
        m.point(Side.B)
        assertEquals(SetRecord(SetKind.REGULAR, SidePair(3, 4), SidePair(4, 5), null, Side.B), m.state.sets.single())
    }

    @Test
    fun matchTiebreakOnlyPresetStartsWithMatchTiebreak() {
        val m = MatchDriver(Presets.TENNIS_MATCH_TB_ONLY.format())
        assertEquals(SetKind.MATCH_TIEBREAK, m.cur.setKind)
        m.points("AAAAAAAAAA")
        assertEquals(MatchResult(Side.A, ResultReason.COMPLETED), m.state.result)
    }

    @Test
    fun noEndChangeAfterMatchEndEvenWithOddGameTotal() {
        val m = MatchDriver(Presets.TENNIS_ONE_SET.format()).winGames(Side.B, 1).winGames(Side.A, 6)
        assertEquals(MatchStatus.FINISHED, m.state.status)
        assertEquals(
            SidePair(6, 1),
            m.state.sets
                .single()
                .games,
        )
        assertTrue(Effect.CHANGE_ENDS !in m.state.effects)
        assertNull(m.state.current)
        assertEquals(Continuation(Side.B, p("B1")), m.state.continuation)
    }

    @Test
    fun proSetToEightTiebreakAtEightAll() {
        val m = MatchDriver(Presets.TENNIS_PRO_SET_8.format()).holdGames(16)
        assertEquals(GameKind.TIEBREAK, m.cur.gameKind)
        assertEquals(SidePair(8, 8), m.cur.games)
    }

    @Test
    fun correctionServerInStandardGameAndTiebreak() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format())
        m.send(Correction.server(p("B1")))
        assertEquals(Side.B, m.srv.side)
        m.winGame(Side.B)
        assertEquals(Side.A, m.srv.side, "Rotation läuft von dort weiter")

        val t = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).holdGames(12)
        t.point(Side.A) // k=1: B schlägt auf
        assertEquals(Side.B, t.srv.side)
        t.send(Correction.server(p("A1")))
        assertEquals(Side.A, t.srv.side)
        t.point(Side.A)
        assertEquals(Side.A, t.srv.side, "Rhythmus bleibt an k gebunden: k=2 gehört zum selben Slot")
        t.point(Side.A)
        assertEquals(Side.B, t.srv.side)
    }

    @Test
    fun correctionServingOrderOnlyBeforeTeamServes() {
        val m = MatchDriver(Presets.TENNIS_BO3_MTB10.format(Discipline.DOUBLES))
        m.send(Correction.servingOrder(Side.A, listOf(p("A2"), p("A1"))))
        assertEquals("A2", m.srv.player.value)
        m.point(Side.A)
        m.send(Correction.servingOrder(Side.A, listOf(p("A1"), p("A2"))))
        assertEquals(listOf(Effect.IGNORED), m.state.effects, "A hat im Satz schon aufgeschlagen")
        m.send(Correction.servingOrder(Side.B, listOf(p("B2"), p("B1"))))
        assertEquals(emptyList(), m.state.effects)
        m.points("AAA")
        assertEquals("B2", m.srv.player.value)
    }

    @Test
    fun correctionInitialServerIsRetroactive() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("AA")
        m.send(Correction.initialServer(p("B1")))
        assertEquals(Side.B, m.srv.side)
        assertEquals("0-30", m.state.scoreCall)
        assertEquals(SidePair(2, 0), checkNotNull(m.state.current).points)
    }

    @Test
    fun correctionReceivePositionsFromNextGame() {
        val m = MatchDriver(Presets.TENNIS_BO3_MTB10.format(Discipline.DOUBLES)).points("A")
        m.send(Correction.receivePositions(Side.B, p("B2")))
        m.point(Side.A)
        assertEquals("B1", m.srv.receiver?.value, "laufendes Spiel unverändert (R27e)")
        m.points("AA")
        // Spiel 2: B schlägt auf, vor dem ersten Punkt wirkt die Korrektur sofort
        m.send(Correction.receivePositions(Side.A, p("A2")))
        assertEquals("A2", m.srv.receiver?.value)
        m.holdGames(1)
        assertEquals("B2", m.srv.receiver?.value, "Spiel 3: neue Rückschlagposition von B")
    }

    @Test
    fun openModeContinueWithMatchTiebreakAndDraw() {
        val m = MatchDriver(Presets.TENNIS_OPEN.format()).winGames(Side.A, 6)
        assertEquals(MatchStatus.AWAITING_DECISION, m.state.status)
        assertEquals(PendingQuestion.ASK_ANOTHER_SET, m.state.pending)
        assertEquals(Continuation(Side.A, p("A1")), m.state.continuation)
        assertEquals(emptyList(), m.state.statusFlags)
        m.send(ContinueDecision(true, NextSetKind.MATCH_TIEBREAK))
        assertEquals(SetKind.MATCH_TIEBREAK, m.cur.setKind)
        m.send(ContinueDecision(true))
        assertEquals(listOf(Effect.IGNORED), m.state.effects, "keine offene Frage")
        m.points("BBBBBBBBBB")
        assertEquals(MatchStatus.AWAITING_DECISION, m.state.status)
        m.send(ContinueDecision(false))
        assertEquals(MatchResult(null, ResultReason.DRAW), m.state.result)
        assertEquals(listOf(Effect.MATCH_DRAWN), m.state.effects)
        assertNull(m.state.continuation)
    }

    @Test
    fun openModeMatchTiebreakRequiresConfig() {
        val m = MatchDriver(Presets.TENNIS_OPEN.format().copy(matchTiebreak = null)).winGames(Side.A, 6)
        m.send(ContinueDecision(true, NextSetKind.MATCH_TIEBREAK))
        assertEquals(listOf(Effect.IGNORED), m.state.effects)
        m.send(ContinueDecision(false))
        assertEquals(MatchResult(Side.A, ResultReason.COMPLETED), m.state.result)
        assertEquals(listOf(Effect.MATCH_WON), m.state.effects)
    }

    @Test
    fun walkoverOnlyWithoutPoints() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format())
        m.send(MatchEnded(EndReason.WALKOVER, Side.B))
        assertEquals(MatchResult(Side.A, ResultReason.WALKOVER), m.state.result)

        val n = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("A")
        n.send(MatchEnded(EndReason.WALKOVER, Side.B))
        assertEquals(listOf(Effect.IGNORED), n.state.effects)
        n.send(MatchEnded(EndReason.RETIRED))
        assertEquals(listOf(Effect.IGNORED), n.state.effects, "RETIRED braucht side")
        n.send(MatchEnded(EndReason.RETIRED, Side.A))
        assertEquals(MatchResult(Side.B, ResultReason.RETIRED), n.state.result)
        assertEquals("15", checkNotNull(n.state.current).display.a, "Stand eingefroren")
        n.point(Side.A)
        assertEquals(listOf(Effect.IGNORED), n.state.effects)
    }
}
