package eu.advantly.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {
    @Test
    fun uuid7IsBuiltFromExternalPartsAndSortsByTime() {
        val early = Uuid7.fromParts(1_760_000_000_000L, 0xFFF, -1L)
        val late = Uuid7.fromParts(1_760_000_000_001L, 0, 0)
        assertTrue(early < late)
        assertEquals('7', early.value[14])
        assertTrue(early.value[19] in "89ab")
        assertEquals(1_760_000_000_000L, early.unixTimeMs)
        assertEquals(early, Uuid7.parse(early.value))
        assertNull(Uuid7.parseOrNull("not-a-uuid"))
        assertFailsWith<IllegalArgumentException> { Uuid7.fromParts(-1, 0, 0) }
        assertEquals(EventId(early.value), EventId.of(early))
    }

    @Test
    fun sidesEndsAndLineup() {
        assertEquals(Side.B, Side.A.other)
        assertEquals(End.END_1, End.END_2.other)
        val lineup = Lineup(listOf(PlayerId("A1"), PlayerId("A2")), listOf(PlayerId("B1"), PlayerId("B2")), PlayerId("B2"))
        assertEquals(Side.B, lineup.sideOf(PlayerId("B2")))
        assertNull(lineup.sideOf(PlayerId("X")))
        assertEquals(End.END_1, lineup.startEndA)
        assertEquals(2, Discipline.DOUBLES.playersPerSide)
        assertTrue(Sport.PADEL.isTennisLike && Sport.TABLE_TENNIS.isRallySport && !Sport.PICKLEBALL.isRallySport)
    }

    @Test
    fun matchFormatDefaults() {
        val rally =
            RallyFormat(
                pointsToWin = 15,
                winBy = 2,
                cap = 21,
                serveRotation = ServeRotation.WINNER_SERVES,
                nextGameFirstServer = NextGameFirstServer.GAME_WINNER,
                midGameEndChange = MidGameEndChange.DECIDING_GAME_ONLY,
            )
        assertEquals(8, rally.effectiveMidGameEndChangeAt)
        assertEquals(6, PickleballFormat(PickleballScoring.SIDE_OUT, 11, 2).effectiveMidGameEndChangeAt)
        assertEquals(2, GameFormat(DeuceMode.STAR_POINT).effectiveMaxAdvantages)
        val format = MatchFormat(sport = Sport.BADMINTON, discipline = Discipline.SINGLES, match = MatchSettings.bestOf(3), rally = rally)
        assertEquals(2, format.setsToWin)
        assertNull(format.copy(match = MatchSettings.OPEN).setsToWin)
    }
}
