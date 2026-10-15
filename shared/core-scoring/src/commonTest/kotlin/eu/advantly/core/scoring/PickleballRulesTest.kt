package eu.advantly.core.scoring

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.End
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchSettings
import eu.advantly.core.model.PickleballFormat
import eu.advantly.core.model.PickleballScoring
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regel-Tabellen Pickleball (02 §6.6): Side-Out mit Aufschläger 1/2 und 0-0-2, Rally, Freeze, Cap, Seitenwechsel. */
class PickleballRulesTest {
    private val MatchDriver.srv get() = checkNotNull(state.serve)
    private val MatchDriver.cur get() = checkNotNull(state.current)

    private fun pbFormat(
        pb: PickleballFormat,
        discipline: Discipline = Discipline.SINGLES,
        match: MatchSettings = MatchSettings.bestOf(1),
    ) = MatchFormat(sport = Sport.PICKLEBALL, discipline = discipline, match = match, pickleball = pb)

    @Test
    fun singlesSideOutCourtFollowsOwnScore() {
        val m = MatchDriver(Presets.PICKLEBALL_SINGLES_11_SIDEOUT.format())
        assertEquals("0-0", m.state.scoreCall)
        assertNull(m.srv.serverNumber)
        assertEquals(ServeCourt.RIGHT, m.srv.court)
        m.point(Side.A)
        assertEquals(ServeCourt.LEFT, m.srv.court)
        assertEquals("1-0", m.state.scoreCall)
        m.point(Side.B)
        assertEquals(listOf(Effect.SIDE_OUT), m.state.effects, "Rückschläger punktet nicht")
        assertEquals(Side.B, m.srv.side)
        assertEquals(ServeCourt.RIGHT, m.srv.court)
        assertEquals("0-1", m.state.scoreCall)
        assertEquals(SidePair(Positions(p("A1"), null), Positions(p("B1"), null)), m.state.positions)
    }

    @Test
    fun doublesSequenceServerOneAndTwo() {
        val m = MatchDriver(Presets.PICKLEBALL_DOUBLES_11_SIDEOUT.format())

        data class Row(
            val winner: Char,
            val call: String,
            val server: String,
            val number: Int,
            val effect: Effect,
        )
        val rows =
            listOf(
                Row('B', "0-0-1", "B1", 1, Effect.SIDE_OUT), // 0-0-2: sofort Side Out
                Row('B', "1-0-1", "B1", 1, Effect.POINT),
                Row('A', "1-0-2", "B2", 2, Effect.SECOND_SERVER),
                Row('A', "0-1-1", "A1", 1, Effect.SIDE_OUT),
                Row('B', "0-1-2", "A2", 2, Effect.SECOND_SERVER),
                Row('A', "1-1-2", "A2", 2, Effect.POINT),
            )
        for (row in rows) {
            m.point(if (row.winner == 'A') Side.A else Side.B)
            assertEquals(row.call, m.state.scoreCall, row.toString())
            assertEquals(row.server, m.srv.player.value, row.toString())
            assertEquals(row.number, m.srv.serverNumber, row.toString())
            assertEquals(listOf(row.effect), m.state.effects, row.toString())
        }
    }

    @Test
    fun doublesWithoutSingleServerStart() {
        val format =
            Presets.PICKLEBALL_DOUBLES_11_SIDEOUT
                .format()
                .copy(
                    pickleball =
                        Presets.PICKLEBALL_DOUBLES_11_SIDEOUT
                            .format()
                            .pickleball!!
                            .copy(firstServiceTurnSingleServer = false),
                )
        val m = MatchDriver(format)
        assertEquals("0-0-1", m.state.scoreCall)
        m.point(Side.B)
        assertEquals(listOf(Effect.SECOND_SERVER), m.state.effects)
        assertEquals("A2", m.srv.player.value)
        assertEquals(ServeCourt.LEFT, m.srv.court, "zweiter Aufschläger schlägt von seiner Position auf")
    }

    @Test
    fun rallyScoringReceivingTeamScoresAndSwitchesPositions() {
        val m = MatchDriver(Presets.PICKLEBALL_DOUBLES_RALLY_21.format())
        assertEquals("0-0", m.state.scoreCall)
        assertNull(m.srv.serverNumber)
        m.point(Side.B)
        assertEquals(listOf(Effect.POINT, Effect.SIDE_OUT), m.state.effects)
        assertEquals(SidePair(0, 1), m.cur.points)
        assertEquals("B2", m.srv.player.value, "Team-Score 1 → Startaufschläger steht links, rechts steht B2")
        assertEquals(ServeCourt.RIGHT, m.srv.court)
        assertEquals(Positions(p("B2"), p("B1")), checkNotNull(m.state.positions).b)
        assertEquals("1-0", m.state.scoreCall)
    }

    @Test
    fun rallyFreezeGameWinningPointOnlyOnServe() {
        val m =
            MatchDriver(
                pbFormat(
                    PickleballFormat(PickleballScoring.RALLY, pointsToWin = 7, winBy = 2, gameWinningPointOnServeOnly = true),
                ),
            )
        m.points("BBBBBB")
        assertEquals(SidePair(0, 6), m.cur.points)
        m.point(Side.A)
        assertEquals(Side.A, m.srv.side)
        assertEquals(emptyList(), m.state.statusFlags, "B kann als Rückschläger den Spielpunkt nicht machen, A ist weit weg")
        m.point(Side.B)
        assertEquals(listOf(Effect.SIDE_OUT), m.state.effects, "Freeze: Side Out ohne Punkt")
        assertEquals(SidePair(1, 6), m.cur.points)
        assertEquals(listOf(StatusFlag(Side.B, FlagLevel.MATCH_POINT, 5, false)), m.state.statusFlags)
        m.point(Side.B)
        assertEquals(MatchResult(Side.B, ResultReason.COMPLETED), m.state.result)
    }

    @Test
    fun teamPlayWinByOne() {
        val m = MatchDriver(Presets.PICKLEBALL_TEAM_WIN_BY_1.format())
        m.points("AAAAAAAAAA")
        m.point(Side.B) // Side Out (0-0-2)
        m.points("BBBBBBBBBB")
        assertEquals(SidePair(10, 10), m.cur.points)
        assertEquals(listOf(StatusFlag(Side.B, FlagLevel.MATCH_POINT, 1, false)), m.state.statusFlags)
        m.point(Side.B)
        assertEquals(SetRecord(SetKind.PB_GAME, null, null, SidePair(10, 11), Side.B), m.state.sets.single())
    }

    @Test
    fun pointCapEndsGame() {
        val m = MatchDriver(pbFormat(PickleballFormat(PickleballScoring.RALLY, pointsToWin = 11, winBy = 2, pointCap = 15)))
        repeat(14) { m.points("AB") }
        assertEquals(SidePair(14, 14), m.cur.points)
        m.point(Side.A)
        assertEquals(MatchResult(Side.A, ResultReason.COMPLETED), m.state.result)
        assertEquals(
            SidePair(15, 14),
            m.state.sets
                .single()
                .points,
        )
    }

    @Test
    fun bestOfThreeMidChangeOnlyInDecidingGameAndFirstServeAlternates() {
        val m = MatchDriver(Presets.PICKLEBALL_DOUBLES_BO3_11.format())
        m.points("AAAAAA")
        assertEquals(SidePair(End.END_1, End.END_2), m.state.ends, "kein Wechsel bei 6 im 1. Spiel")
        m.points("AAAAA")
        assertEquals(listOf(Effect.POINT, Effect.SET_WON, Effect.CHANGE_ENDS, Effect.SET_STARTED), m.state.effects)
        assertEquals(SidePair(End.END_2, End.END_1), m.state.ends)
        assertEquals(Side.B, m.srv.side, "Wechsel des ersten Aufschlags (21.F.2)")
        assertEquals("0-0-2", m.state.scoreCall)
        m.send(Correction.servingOrder(Side.B, listOf(p("B2"), p("B1"))))
        assertEquals("B2", m.srv.player.value, "neuer Startaufschläger vor dem ersten Ballwechsel")
        m.points("BBBBBBBBBBB")
        assertEquals(SidePair(1, 1), m.state.setsWon)
        assertEquals(Side.A, m.srv.side)
        assertEquals(SidePair(End.END_1, End.END_2), m.state.ends)
        m.points("AAAAA")
        assertEquals(SidePair(End.END_1, End.END_2), m.state.ends)
        m.point(Side.A)
        assertEquals(SidePair(End.END_2, End.END_1), m.state.ends, "Entscheidungsspiel: Wechsel bei 6")
        assertTrue(Effect.CHANGE_ENDS in m.state.effects)
    }

    @Test
    fun servingOrderAfterFirstRallyIsIgnored() {
        val m = MatchDriver(Presets.PICKLEBALL_DOUBLES_BO3_11.format())
        m.point(Side.B) // Side Out, aber Ballwechsel gespielt
        m.send(Correction.servingOrder(Side.A, listOf(p("A2"), p("A1"))))
        assertEquals(listOf(Effect.IGNORED), m.state.effects)
    }

    @Test
    fun openModeChangesEndsInEveryGame() {
        val m = MatchDriver(Presets.PICKLEBALL_OPEN.format())
        m.points("AAAAAA")
        assertEquals(SidePair(End.END_2, End.END_1), m.state.ends)
        m.points("AAAAA")
        assertEquals(MatchStatus.AWAITING_DECISION, m.state.status)
        assertEquals(SidePair(End.END_2, End.END_1), m.state.ends, "Wechsel erst mit ContinueDecision(true)")
        assertEquals(Continuation(Side.B, p("B1")), m.state.continuation)
        m.send(ContinueDecision(true))
        assertEquals(listOf(Effect.CHANGE_ENDS, Effect.SET_STARTED), m.state.effects)
        m.send(ContinueDecision(true))
        assertEquals(listOf(Effect.IGNORED), m.state.effects)
    }

    @Test
    fun weatherFormatChangesAtFour() {
        val m = MatchDriver(Presets.PICKLEBALL_RALLY_7_WEATHER.format())
        m.points("AAA")
        assertEquals(SidePair(End.END_1, End.END_2), m.state.ends)
        m.point(Side.A)
        assertEquals(SidePair(End.END_2, End.END_1), m.state.ends)
    }

    @Test
    fun correctionServerSetsSideAndNumber() {
        val m = MatchDriver(Presets.PICKLEBALL_DOUBLES_11_SIDEOUT.format())
        m.send(Correction.server(p("B2"), serverNumber = 2))
        assertEquals(Side.B, m.srv.side)
        assertEquals("B2", m.srv.player.value)
        assertEquals(2, m.srv.serverNumber)
        assertEquals(ServeCourt.LEFT, m.srv.court)
        m.send(Correction.server(p("A1"), serverNumber = 3))
        assertEquals(listOf(Effect.IGNORED), m.state.effects)
    }
}
