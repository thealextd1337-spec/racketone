package eu.advantly.core.scoring

import eu.advantly.core.model.DeuceMode
import eu.advantly.core.model.GameFormat
import eu.advantly.core.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals

/** Regel-Tabellen Padel: Advantage, Golden Point, Star Point mit verschiedenen `maxAdvantages` (02 §6.1). */
class PadelRulesTest {
    private fun starPoint(maxAdvantages: Int) =
        MatchDriver(Presets.PADEL_PREMIER_2026.format().copy(game = GameFormat(DeuceMode.STAR_POINT, maxAdvantages)))

    private val MatchDriver.cur get() = checkNotNull(state.current)

    /** Zeile: Rohpunkte nach der Folge → erwarteter Callout, deuceCount, decidingPoint. */
    private data class Row(
        val seq: String,
        val callout: Callout?,
        val deuceCount: Int,
        val deciding: Boolean,
    )

    private fun check(
        m: MatchDriver,
        rows: List<Row>,
    ) {
        for (row in rows) {
            m.points(row.seq)
            assertEquals(row.callout, m.cur.callout, "nach ${row.seq}: callout")
            assertEquals(row.deuceCount, m.cur.deuceCount, "nach ${row.seq}: deuceCount")
            assertEquals(row.deciding, m.cur.decidingPoint, "nach ${row.seq}: decidingPoint")
        }
    }

    @Test
    fun starPointAfterTwoAdvantages() =
        check(
            MatchDriver(Presets.PADEL_PREMIER_2026.format()),
            listOf(
                Row("AAABBB", Callout.DEUCE, 1, false),
                Row("A", Callout.ADVANTAGE_SERVER, 1, false),
                Row("B", Callout.DEUCE, 2, false),
                Row("B", Callout.ADVANTAGE_RECEIVER, 2, false),
                Row("A", Callout.STAR_POINT, 3, true),
            ),
        )

    @Test
    fun starPointWithOneAdvantage() =
        check(
            starPoint(1),
            listOf(
                Row("AAABBB", Callout.DEUCE, 1, false),
                Row("A", Callout.ADVANTAGE_SERVER, 1, false),
                Row("B", Callout.STAR_POINT, 2, true),
            ),
        )

    @Test
    fun starPointWithThreeAdvantages() {
        val m = starPoint(3)
        check(m, listOf(Row("AAABBB", Callout.DEUCE, 1, false), Row("ABAB", Callout.DEUCE, 3, false)))
        // 6:6 = Einstand 4 > 3 Vorteile → Star Point
        m.points("AB")
        assertEquals(Callout.STAR_POINT, m.cur.callout)
        assertEquals(4, m.cur.deuceCount)
        m.point(Side.B)
        assertEquals(SidePair(0, 1), m.cur.games, "Star Point entscheidet ohne Vorsprung")
    }

    @Test
    fun goldenPointAtFirstDeuce() {
        val m = MatchDriver(Presets.PADEL_GOLDEN_BO3.format()).points("ABABAB")
        assertEquals(Callout.DECIDING_POINT, m.cur.callout)
        assertEquals(ServeCourt.RECEIVER_CHOICE, checkNotNull(m.state.serve).court)
        m.point(Side.A)
        assertEquals(SidePair(1, 0), m.cur.games)
    }

    @Test
    fun advantageNeverDecides() {
        val m = MatchDriver(Presets.PADEL_ADVANTAGE_BO3.format()).points("AAABBB")
        repeat(10) { m.points("AB") }
        assertEquals(Callout.DEUCE, m.cur.callout)
        assertEquals(false, m.cur.decidingPoint)
        assertEquals(11, m.cur.deuceCount)
        m.points("AA")
        assertEquals(SidePair(1, 0), m.cur.games)
    }

    @Test
    fun miniSetsTiebreakAtFourAll() {
        val m = MatchDriver(Presets.PADEL_MINI_SETS.format()).holdGames(8)
        assertEquals(GameKind.TIEBREAK, m.cur.gameKind)
        assertEquals(SidePair(4, 4), m.cur.games)
    }

    @Test
    fun padelThirdSetWithoutTiebreak() {
        val m = MatchDriver(Presets.PADEL_ADV_FINAL_NO_TB.format())
        repeat(6) { m.points("AAAA") }
        repeat(6) { m.points("BBBB") }
        m.holdGames(14)
        assertEquals(GameKind.STANDARD, m.cur.gameKind)
        assertEquals(SidePair(7, 7), m.cur.games)
    }
}
