package eu.advantly.presentation.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeTennisTest {
    private fun FakeTennisState.points(vararg teams: Team): FakeTennisState = teams.fold(this, FakeTennis::point)

    private fun FakeTennisState.games(
        team: Team,
        count: Int,
    ): FakeTennisState = (1..count).fold(this) { s, _ -> s.points(team, team, team, team) }

    private fun label(
        state: FakeTennisState,
        team: Team,
    ) = FakeTennis.pointLabel(state, team)

    @Test
    fun pointsCountZeroFifteenThirtyForty() {
        var s = FakeTennisState()
        val expected = listOf(0, 15, 30, 40)
        expected.forEach { value ->
            assertEquals(PointLabel.Number(value), label(s, Team.Us))
            s = FakeTennis.point(s, Team.Us)
        }
        assertEquals(1, s.gamesUs)
        assertEquals(PointLabel.Number(0), label(s, Team.Us))
    }

    @Test
    fun deuceAndAdvantage() {
        val deuce = FakeTennisState().points(Team.Us, Team.Us, Team.Us, Team.Them, Team.Them, Team.Them)
        assertEquals(PointLabel.Number(40), label(deuce, Team.Us))
        assertEquals(PointLabel.Number(40), label(deuce, Team.Them))
        assertEquals(ScoreHint.Kind.Deuce, FakeTennis.hint(deuce)?.kind)

        val advantageThem = FakeTennis.point(deuce, Team.Them)
        assertEquals(PointLabel.Advantage, label(advantageThem, Team.Them))
        assertEquals(PointLabel.Number(40), label(advantageThem, Team.Us))

        val backToDeuce = FakeTennis.point(advantageThem, Team.Us)
        assertEquals(PointLabel.Number(40), label(backToDeuce, Team.Them))
        assertEquals(0, backToDeuce.gamesThem)
    }

    @Test
    fun serverAlternatesEachGame() {
        val s = FakeTennisState().games(Team.Us, 1)
        assertEquals(Team.Them, s.server)
        assertEquals(Team.Us, s.games(Team.Them, 1).server)
    }

    @Test
    fun breakPointOnlyForReceiver() {
        // Wir schlagen auf: 40:0 ist Spielball (kein Hinweis), 0:40 ist Breakball Gegner
        val serving = FakeTennisState().points(Team.Us, Team.Us, Team.Us)
        assertNull(FakeTennis.hint(serving))
        val receiving = FakeTennisState().points(Team.Them, Team.Them, Team.Them)
        assertEquals(ScoreHint(ScoreHint.Kind.BreakPoint, Team.Them), FakeTennis.hint(receiving))
    }

    @Test
    fun setPointAndMatchPoint() {
        // 5:0, wir schlagen nach dem 5. Game NICHT auf (Gegner schlägt auf) → 40:0 ist Satzball (vor Breakball)
        val setPoint = FakeTennisState().games(Team.Us, 5).points(Team.Us, Team.Us, Team.Us)
        assertEquals(ScoreHint(ScoreHint.Kind.SetPoint, Team.Us), FakeTennis.hint(setPoint))

        val afterSet = FakeTennis.point(setPoint, Team.Us)
        assertEquals(listOf(SetResult(6, 0)), afterSet.completedSets)

        val matchPoint = afterSet.games(Team.Us, 5).points(Team.Us, Team.Us, Team.Us)
        assertEquals(ScoreHint(ScoreHint.Kind.MatchPoint, Team.Us), FakeTennis.hint(matchPoint))

        val finished = FakeTennis.point(matchPoint, Team.Us)
        assertEquals(Team.Us, finished.winner)
        assertEquals(ScoreHint.Kind.MatchWon, FakeTennis.hint(finished)?.kind)
        assertEquals(finished, FakeTennis.point(finished, Team.Them), "nach Matchende zählt nichts mehr")
    }

    @Test
    fun tiebreakAtSixAllWithNumericPointsAndServerRotation() {
        var s = FakeTennisState()
        repeat(5) { s = s.games(Team.Us, 1).games(Team.Them, 1) }
        s = s.games(Team.Us, 1).games(Team.Them, 1)
        assertTrue(s.tiebreak)
        assertEquals(6, s.gamesUs)
        val first = s.server
        assertEquals(ScoreHint.Kind.Tiebreak, FakeTennis.hint(s)?.kind)

        s = FakeTennis.point(s, Team.Us)
        assertEquals(PointLabel.Number(1), label(s, Team.Us))
        assertEquals(first.other, s.server)
        s = FakeTennis.point(s, Team.Them)
        assertEquals(first.other, s.server)
        s = FakeTennis.point(s, Team.Them)
        assertEquals(first, s.server)

        repeat(6) { s = FakeTennis.point(s, Team.Us) }
        assertFalse(s.tiebreak)
        assertEquals(SetResult(7, 6, tiebreakLoserPoints = 2), s.completedSets.single())
        assertEquals(first.other, s.server, "im neuen Satz schlägt der Rückschläger des Tiebreak-Starts auf")
    }

    @Test
    fun changeEndsAfterOddGames() {
        val afterFirstGame = FakeTennisState().games(Team.Us, 1)
        assertTrue(FakeTennis.changeEnds(afterFirstGame))
        assertEquals(ScoreHint.Kind.ChangeEnds, FakeTennis.hint(afterFirstGame)?.kind)
        assertFalse(FakeTennis.changeEnds(afterFirstGame.games(Team.Them, 1)))
        assertFalse(FakeTennis.changeEnds(FakeTennis.point(afterFirstGame, Team.Us)))
    }
}
