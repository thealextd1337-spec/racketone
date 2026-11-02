package eu.advantly.core.scoring

import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MomentKind.BREAK
import eu.advantly.core.scoring.MomentKind.BREAK_POINT
import eu.advantly.core.scoring.MomentKind.COMEBACK
import eu.advantly.core.scoring.MomentKind.DECIDING_POINT
import eu.advantly.core.scoring.MomentKind.MATCH_POINT
import eu.advantly.core.scoring.MomentKind.MATCH_WON
import eu.advantly.core.scoring.MomentKind.SET_POINT
import eu.advantly.core.scoring.MomentKind.SET_WON
import eu.advantly.core.scoring.MomentKind.TIEBREAK_STARTED
import eu.advantly.core.scoring.MomentOutcome.CONVERTED
import eu.advantly.core.scoring.MomentOutcome.SAVED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regel-Tabellen der Highlights (Plan Puls-Highlights §2) für alle fünf Sportarten. */
class MatchMomentsTest {
    /** Erwartete Zeile: Punktindex, Arten, Ausgang, Seite. */
    private data class Row(
        val index: Int,
        val kinds: Set<MomentKind>,
        val outcome: MomentOutcome?,
        val side: Side,
    )

    private fun MatchDriver.moments(): List<MatchMoment> = MatchMoments.derive(ScoringEngine.pointHistory(format, lineup, events, null))

    private fun MatchDriver.rows(): List<Row> = moments().map { Row(it.pointIndex, it.kinds, it.outcome, it.side) }

    private fun row(
        index: Int,
        outcome: MomentOutcome?,
        side: Side,
        vararg kinds: MomentKind,
    ) = Row(index, kinds.toSet(), outcome, side)

    @Test
    fun tennisBreakAtThirtyForty() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("AABBBB")
        assertEquals(listOf(row(5, CONVERTED, Side.B, BREAK_POINT, BREAK)), m.rows())
    }

    @Test
    fun tennisSavedBreakPoint() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("AABBB" + "AAA")
        assertEquals(listOf(row(5, SAVED, Side.B, BREAK_POINT)), m.rows())
    }

    @Test
    fun padelGoldenPointSavedByServer() {
        val m = MatchDriver(Presets.PADEL_GOLDEN_BO3.format()).points("ABABAB" + "A")
        assertEquals(listOf(row(6, SAVED, Side.B, BREAK_POINT, DECIDING_POINT)), m.rows())
    }

    @Test
    fun padelStarPointConvertedByReceiver() {
        val m = MatchDriver(Presets.PADEL_PREMIER_2026.format()).points("AAABBB" + "ABBA")
        assertEquals(Callout.STAR_POINT, checkNotNull(m.state.current).callout)
        m.point(Side.B)
        assertEquals(
            listOf(row(9, SAVED, Side.B, BREAK_POINT), row(10, CONVERTED, Side.B, BREAK_POINT, DECIDING_POINT, BREAK)),
            m.rows(),
        )
    }

    @Test
    fun tennisTiebreakAtSixAll() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).holdGames(12)
        assertEquals(SidePair(6, 6), checkNotNull(m.state.current).games)
        assertEquals(listOf(row(47, null, Side.B, TIEBREAK_STARTED)), m.rows())
    }

    @Test
    fun matchTiebreakMarkedAtItsFirstPoint() {
        val m = MatchDriver(Presets.TENNIS_BO3_MTB10.format()).points("A".repeat(24) + "B".repeat(24))
        assertEquals(GameKind.MATCH_TIEBREAK, checkNotNull(m.state.current).gameKind)
        m.point(Side.A)
        assertEquals(listOf(48), m.moments().filter { TIEBREAK_STARTED in it.kinds }.map { it.pointIndex })
    }

    @Test
    fun pickleballGamePointSavedAndConverted() {
        val m = MatchDriver(Presets.PICKLEBALL_RALLY_BO3_15.format()).points("A".repeat(14) + "B" + "A")
        assertEquals(
            listOf(row(14, SAVED, Side.A, SET_POINT), row(15, CONVERTED, Side.A, SET_POINT, SET_WON)),
            m.rows(),
        )
    }

    @Test
    fun pickleballNeverHasBreakPoints() {
        val m = MatchDriver(Presets.PICKLEBALL_SINGLES_11_SIDEOUT.format()).points("ABBA".repeat(10))
        assertTrue(m.moments().none { BREAK_POINT in it.kinds || BREAK in it.kinds || DECIDING_POINT in it.kinds })
    }

    @Test
    fun badmintonDecidingPointAtTwentyNineAll() {
        val m = MatchDriver(Presets.BADMINTON_1GAME_21.format()).points("AB".repeat(29))
        assertEquals(true, checkNotNull(m.state.current).decidingPoint)
        m.point(Side.A)
        assertEquals(row(58, CONVERTED, Side.A, MATCH_POINT, DECIDING_POINT, SET_WON, MATCH_WON), m.rows().last())
    }

    @Test
    fun tableTennisTenAllIsNoDecidingPoint() {
        val m = MatchDriver(Presets.TABLE_TENNIS_SINGLES_BO5.format()).points("AB".repeat(9) + "AB")
        assertEquals(false, checkNotNull(m.state.current).decidingPoint, "TT-Presets: 2 Punkte Vorsprung, kein Deckel")
        m.points("AA")
        assertEquals(
            listOf(row(19, SAVED, Side.A, SET_POINT), row(21, CONVERTED, Side.A, SET_POINT, SET_WON)),
            m.rows().filter { it.index >= 19 },
        )
    }

    @Test
    fun comebackAfterSavedMatchPoint() {
        val m = MatchDriver(Presets.BADMINTON_1GAME_15.format()).points("B".repeat(14) + "A".repeat(16))
        val moments = m.rows()
        assertEquals(row(14, SAVED, Side.B, MATCH_POINT), moments.first())
        assertEquals(row(29, CONVERTED, Side.A, MATCH_POINT, SET_WON, MATCH_WON, COMEBACK), moments.last())
    }

    @Test
    fun comebackFromTwoGamesDownButNotFromOne() {
        val twoDown = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("BBBB".repeat(2) + "AAAA".repeat(6))
        assertTrue(COMEBACK in twoDown.moments().single { SET_WON in it.kinds }.kinds)
        val oneDown = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("BBBB" + "AAAA".repeat(6))
        assertFalse(COMEBACK in oneDown.moments().single { SET_WON in it.kinds }.kinds)
    }

    @Test
    fun comebackWithinTheTiebreakFromFivePointsDown() {
        fun setWonAfterTiebreak(points: String): Set<MomentKind> {
            val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).holdGames(12)
            assertEquals(GameKind.TIEBREAK, checkNotNull(m.state.current).gameKind)
            return m
                .points(points)
                .moments()
                .single { SET_WON in it.kinds }
                .kinds
        }
        // 0:5 → 7:5: B hatte nie einen Satzball, die Spiele standen nie 2 auseinander
        assertTrue(COMEBACK in setWonAfterTiebreak("BBBBB" + "AAAAAAA"))
        assertFalse(COMEBACK in setWonAfterTiebreak("BBBB" + "AAAAAAA"), "0:4 → 7:4 reicht nicht")
    }

    @Test
    fun matchComebackAfterLosingFirstSet() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("B".repeat(24) + "A".repeat(48))
        val sets = m.moments().filter { SET_WON in it.kinds }
        assertEquals(3, sets.size)
        assertFalse(COMEBACK in sets[1].kinds, "Satz 2 ohne Rückstand ist kein Comeback")
        assertTrue(sets[2].kinds.containsAll(setOf(MATCH_POINT, MATCH_WON, COMEBACK)), "Satz 1 verloren, Match gewonnen")
    }

    @Test
    fun undoOfBreakPointRemovesMoment() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("AABBBB")
        assertEquals(1, m.moments().size)
        m.undo()
        assertEquals(emptyList(), m.moments())
        assertEquals(
            ScoringEngine.moments(listOf(genesis(m)) + m.events),
            m.moments(),
            "ScoringEngine.moments über das vollständige Log",
        )
    }

    private fun genesis(m: MatchDriver): ScoreEvent =
        ScoreEvent(
            id =
                eu.advantly.core.model
                    .EventId("g"),
            matchId =
                eu.advantly.core.model
                    .MatchId("m"),
            deviceId =
                eu.advantly.core.model
                    .DeviceId("d"),
            deviceSeq = 0,
            lamport = 0,
            wallClockMs = -1,
            parentId = null,
            payload = MatchCreated(m.format, m.lineup),
        )
}
