package eu.advantly.core.scoring

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.End
import eu.advantly.core.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regel-Tabellen Badminton (02 §6.8): Deckel 30/21, 5×11, Doppel-Feldwechsel, Seitenwechsel, Korrekturen. */
class BadmintonRulesTest {
    private val MatchDriver.srv get() = checkNotNull(state.serve)
    private val MatchDriver.cur get() = checkNotNull(state.current)

    @Test
    fun capTwentyOneInThreeByFifteenAndMidChangeInOnlyGame() {
        val m = MatchDriver(Presets.BADMINTON_1GAME_15.format())
        m.points("ABABABA")
        assertEquals(SidePair(End.END_1, End.END_2), m.state.ends)
        m.point(Side.B)
        assertEquals(SidePair(4, 4), m.cur.points)
        m.points("ABABABA")
        assertEquals(SidePair(8, 7), m.cur.points)
        assertEquals(SidePair(End.END_2, End.END_1), m.state.ends, "Best of 1 = Entscheidungssatz: Wechsel bei 8")
        repeat(6) { m.points("BA") }
        m.point(Side.B)
        assertEquals(SidePair(14, 14), m.cur.points)
        assertEquals(Callout.DEUCE, m.cur.callout)
        repeat(6) { m.points("AB") }
        assertEquals(SidePair(20, 20), m.cur.points)
        assertEquals(Callout.DECIDING_POINT, m.cur.callout)
        assertEquals(true, m.cur.decidingPoint)
        assertEquals(
            listOf(StatusFlag(Side.A, FlagLevel.MATCH_POINT, 1, false), StatusFlag(Side.B, FlagLevel.MATCH_POINT, 1, false)),
            m.state.statusFlags,
        )
        m.point(Side.A)
        assertEquals(
            SidePair(21, 20),
            m.state.sets
                .single()
                .points,
        )
        assertEquals(SidePair(End.END_2, End.END_1), m.state.ends, "kein Wechsel nach Matchende")
    }

    @Test
    fun bestOfFiveToElevenWinByOneHasDecidingPointAtTenAll() {
        val m = MatchDriver(Presets.BADMINTON_BO5_11.format())
        repeat(10) { m.points("AB") }
        assertEquals(true, m.cur.decidingPoint)
        assertEquals(Callout.DECIDING_POINT, m.cur.callout)
        m.point(Side.B)
        assertEquals(
            SidePair(10, 11),
            m.state.sets
                .single()
                .points,
        )
        assertEquals(Side.B, m.srv.side, "Satzsieger eröffnet")
    }

    @Test
    fun bestOfFiveMidChangeOnlyInFifthGameAtSix() {
        val m = MatchDriver(Presets.BADMINTON_BO5_11.format())
        m.points("AAAAAA")
        assertEquals(SidePair(End.END_1, End.END_2), m.state.ends)
        m.points("AAAAA")
        m.points("BBBBBBBBBBB")
        m.points("AAAAAAAAAAA")
        m.points("BBBBBBBBBBB")
        assertEquals(SidePair(2, 2), m.state.setsWon)
        val before = m.state.ends
        m.points("BBBBB")
        assertEquals(before, m.state.ends)
        m.point(Side.B)
        assertEquals(before.map { _, e -> e.other }, m.state.ends)
    }

    @Test
    fun doublesStartPositionsCorrectionsAndDiagonalReceiver() {
        val m = MatchDriver(Presets.BADMINTON_DOUBLES_BO3_15.format())
        m.send(Correction.servingOrder(Side.A, listOf(p("A2"), p("A1"))))
        assertEquals("A2", m.srv.player.value)
        assertEquals("B1", m.srv.receiver?.value)
        m.point(Side.A)
        assertEquals("A2", m.srv.player.value)
        assertEquals(ServeCourt.LEFT, m.srv.court)
        assertEquals("B2", m.srv.receiver?.value)
        m.send(Correction.servingOrder(Side.B, listOf(p("B2"), p("B1"))))
        assertEquals(listOf(Effect.IGNORED), m.state.effects, "nach dem ersten Ballwechsel")
        m.send(Correction.receivePositions(Side.B, p("B2")))
        assertEquals(Positions(p("B2"), p("B1")), checkNotNull(m.state.positions).b)
        assertEquals("B1", m.srv.receiver?.value, "Law 12: sofort wirksam, links steht jetzt B1")
        m.send(Correction.server(p("A1")))
        assertEquals("A1", m.srv.player.value)
        assertEquals(Positions(p("A2"), p("A1")), checkNotNull(m.state.positions).a, "A1 wechselt ins Feld zum Score (links)")
    }

    @Test
    fun correctionServerSwapsPositionsWhenNeeded() {
        val m = MatchDriver(Presets.BADMINTON_DOUBLES_BO3_21.format())
        m.send(Correction.server(p("A2")))
        assertEquals("A2", m.srv.player.value)
        assertEquals(ServeCourt.RIGHT, m.srv.court)
        assertEquals(Positions(p("A2"), p("A1")), checkNotNull(m.state.positions).a)
    }

    @Test
    fun openModeNeverChangesMidGameAndAsksAfterEachGame() {
        val m = MatchDriver(Presets.BADMINTON_OPEN.format())
        m.points("AAAAAAAA")
        assertEquals(SidePair(End.END_1, End.END_2), m.state.ends)
        m.points("AAAAAAA")
        assertEquals(MatchStatus.AWAITING_DECISION, m.state.status)
        assertEquals(listOf(Effect.POINT, Effect.SET_WON, Effect.ASK_ANOTHER_SET), m.state.effects)
        assertEquals(Continuation(Side.A, p("A1")), m.state.continuation)
        m.send(ContinueDecision(true, NextSetKind.MATCH_TIEBREAK))
        assertEquals(listOf(Effect.IGNORED), m.state.effects, "Match-Tiebreak gibt es bei Badminton nicht")
        m.send(ContinueDecision(true))
        assertEquals(SidePair(End.END_2, End.END_1), m.state.ends)
    }

    @Test
    fun retirementFreezesScore() {
        val m = MatchDriver(Presets.BADMINTON_SINGLES_BO3_21.format()).points("AAB")
        m.send(MatchEnded(EndReason.RETIRED, Side.A))
        assertEquals(MatchResult(Side.B, ResultReason.RETIRED), m.state.result)
        assertEquals(SidePair(2, 1), m.cur.points)
        assertNull(m.state.serve)
        assertNull(m.state.scoreCall)
        assertNull(m.state.continuation)
    }

    @Test
    fun singlesServeCourtFollowsServerScoreAcrossSideOut() {
        val m = MatchDriver(Presets.BADMINTON_SINGLES_BO3_15.format(Discipline.SINGLES))
        m.points("AAA")
        assertEquals(ServeCourt.LEFT, m.srv.court)
        m.point(Side.B)
        assertEquals(ServeCourt.LEFT, m.srv.court)
        assertTrue(Effect.SIDE_OUT in m.state.effects)
        assertEquals("1-3", m.state.scoreCall)
    }
}
