package eu.advantly.core.scoring

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.End
import eu.advantly.core.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regel-Tabellen Tischtennis (02 §6.9): Aufschlagwechsel, ab 10:10 jeder Punkt, Doppel-Zyklus, Rückschlagtausch. */
class TableTennisRulesTest {
    private val MatchDriver.srv get() = checkNotNull(state.serve)
    private val MatchDriver.cur get() = checkNotNull(state.current)

    private fun MatchDriver.winSet(side: Side) = points(if (side == Side.A) "AAAAAAAAAAA" else "BBBBBBBBBBB")

    @Test
    fun serveAlternatesEveryTwoPointsThenEveryPointFromTenAll() {
        val m = MatchDriver(Presets.TABLE_TENNIS_SINGLES_BO5.format())
        val servers = mutableListOf<Pair<Side, Int>>()
        repeat(20) {
            servers += m.srv.side to checkNotNull(m.srv.servesRemaining)
            m.point(if (it % 2 == 0) Side.A else Side.B)
        }
        val expected = (0 until 20).map { k -> (if ((k / 2) % 2 == 0) Side.A else Side.B) to (2 - k % 2) }
        assertEquals(expected, servers)
        assertEquals(SidePair(10, 10), m.cur.points)
        assertEquals(Callout.DEUCE, m.cur.callout)
        assertNull(m.srv.court)
        assertNull(m.state.positions)
        assertEquals(Side.A to 1, m.srv.side to m.srv.servesRemaining)
        m.point(Side.B)
        assertEquals(Side.B to 1, m.srv.side to m.srv.servesRemaining)
        assertEquals(listOf(StatusFlag(Side.B, FlagLevel.SET_POINT, 1, false)), m.state.statusFlags)
    }

    @Test
    fun bestOfSevenDecidingGameChangesEndsAtFiveWithoutReceiverSwapInSingles() {
        val m = MatchDriver(Presets.TABLE_TENNIS_SINGLES_BO7.format())
        repeat(3) {
            m.winSet(Side.A)
            m.winSet(Side.B)
        }
        assertEquals(SidePair(3, 3), m.state.setsWon)
        assertEquals(Side.A, m.srv.side, "Satz 7 eröffnet wie Satz 1, 3, 5 die Side A")
        val before = m.state.ends
        m.points("AAAA")
        assertEquals(before, m.state.ends)
        m.point(Side.A)
        assertEquals(before.map { _, e -> e.other }, m.state.ends)
        assertEquals(listOf(Effect.POINT, Effect.CHANGE_ENDS), m.state.effects)
    }

    @Test
    fun noMidChangeOutsideDecidingGame() {
        val m = MatchDriver(Presets.TABLE_TENNIS_SINGLES_BO3.format())
        m.points("AAAAA")
        assertEquals(SidePair(End.END_1, End.END_2), m.state.ends)
    }

    @Test
    fun leisureTwentyOneServesFiveAndChangesAtTenInDecidingGame() {
        val m = MatchDriver(Presets.TABLE_TENNIS_LEISURE_21.format())
        assertEquals(5, m.srv.servesRemaining)
        m.points("AAAA")
        assertEquals(Side.A to 1, m.srv.side to m.srv.servesRemaining)
        m.point(Side.A)
        assertEquals(Side.B to 5, m.srv.side to m.srv.servesRemaining)
        m.points("BBBBB")
        repeat(15) { m.points("AB") }
        assertEquals(SidePair(20, 20), m.cur.points)
        assertEquals(1, m.srv.servesRemaining)
        m.points("AA")
        assertEquals(1, m.state.setsWon.a)
        m.points("BBBBBBBBBBBBBBBBBBBBB")
        assertEquals(SidePair(1, 1), m.state.setsWon)
        val before = m.state.ends
        m.points("AAAAAAAAA")
        assertEquals(before, m.state.ends)
        m.point(Side.A)
        assertEquals(before.map { _, e -> e.other }, m.state.ends, "alte Zählweise: Wechsel bei 10 (U18)")
    }

    @Test
    fun doublesServingOrderFirstGameBothPairsLaterOnlyServingPair() {
        val m = MatchDriver(Presets.TABLE_TENNIS_DOUBLES_BO3.format())
        m.send(Correction.servingOrder(Side.B, listOf(p("B2"), p("B1"))))
        assertEquals("B2", m.srv.receiver?.value, "Erst-Rückschläger im 1. Satz wählbar")
        m.send(Correction.servingOrder(Side.A, listOf(p("A2"), p("A1"))))
        assertEquals("A2" to "B2", m.srv.player.value to m.srv.receiver?.value)
        m.points("AA")
        assertEquals("B2" to "A1", m.srv.player.value to m.srv.receiver?.value)
        m.points("AAAAAAAAA")
        // Satz 2: B eröffnet; Rückschlagfolge durch 2.13.4 festgelegt
        assertEquals(Side.B, m.srv.side)
        m.send(Correction.servingOrder(Side.A, listOf(p("A1"), p("A2"))))
        assertEquals(listOf(Effect.IGNORED), m.state.effects)
        m.send(Correction.servingOrder(Side.B, listOf(p("B1"), p("B2"))))
        assertEquals(emptyList(), m.state.effects)
        // Zyklus Satz 1: [A2, B2, A1, B1]; Vorgänger von B1 ist A1
        assertEquals("B1" to "A1", m.srv.player.value to m.srv.receiver?.value)
    }

    @Test
    fun correctionServerWithReceiverRebuildsCycle() {
        val m = MatchDriver(Presets.TABLE_TENNIS_DOUBLES_BO5.format())
        m.send(Correction.server(p("B2"), receiver = p("A2")))
        assertEquals("B2" to "A2", m.srv.player.value to m.srv.receiver?.value)
        m.points("AB")
        assertEquals("A2" to "B1", m.srv.player.value to m.srv.receiver?.value)
        m.send(Correction.server(p("A1"), receiver = p("A2")))
        assertEquals(listOf(Effect.IGNORED), m.state.effects, "Rückschläger muss zur Gegenseite gehören")
    }

    @Test
    fun receiverSwapOnlyInDoublesDecidingGame() {
        val m = MatchDriver(Presets.TABLE_TENNIS_DOUBLES_BO5.format())
        m.points("AAAAA")
        assertTrue(Effect.RECEIVE_ORDER_SWAPPED !in m.state.effects)
        assertEquals(SidePair(End.END_1, End.END_2), m.state.ends)
    }

    @Test
    fun openModeHasNoMidChangeAndNoSwap() {
        val m = MatchDriver(Presets.TABLE_TENNIS_OPEN.format(Discipline.DOUBLES))
        m.points("AAAAAAAAAAA")
        assertEquals(MatchStatus.AWAITING_DECISION, m.state.status)
        assertEquals(Continuation(Side.B, p("B1")), m.state.continuation)
        m.send(ContinueDecision(true))
        m.points("AAAAA")
        assertEquals(SidePair(End.END_2, End.END_1), m.state.ends)
        assertEquals(listOf(Effect.POINT), m.state.effects)
    }
}
