package eu.advantly.presentation.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveScoreSessionTest {
    @Test
    fun undoRestoresPreviousStateAndRedoReapplies() {
        val session = LiveScoreSession()
        session.score(Team.Us)
        val afterTwo = session.score(Team.Them)!!.after

        val undo = session.undo()!!
        assertEquals(ScoreChange.Direction.Backward, undo.direction)
        assertEquals(HapticCue.Undo, undo.cue)
        assertEquals(afterTwo, undo.before)
        assertEquals(1, session.state.pointsUs)
        assertEquals(0, session.state.pointsThem)
        assertTrue(session.canRedo)

        val redo = session.redo()!!
        assertEquals(afterTwo, redo.after)
        assertFalse(session.canRedo)
    }

    @Test
    fun newPointClearsRedo() {
        val session = LiveScoreSession()
        session.score(Team.Us)
        session.undo()
        session.score(Team.Them)
        assertFalse(session.canRedo)
        assertNull(session.redo())
    }

    @Test
    fun undoOnEmptyLogIsNoop() {
        assertNull(LiveScoreSession().undo())
    }

    @Test
    fun hapticCuesFollowTheStrongestEvent() {
        val session = LiveScoreSession()
        assertEquals(HapticCue.PointUs, session.score(Team.Us)!!.cue)
        assertEquals(HapticCue.PointThem, session.score(Team.Them)!!.cue)
        session.reset()
        repeat(3) { session.score(Team.Us) }
        // 1:0 nach dem ersten Game → ungerade Summe → Seitenwechsel schlägt „Game gewonnen“
        assertEquals(HapticCue.ChangeEnds, session.score(Team.Us)!!.cue)
        repeat(3) { session.score(Team.Them) }
        // 1:1 → kein Seitenwechsel, Gegner gewinnt das Game
        assertEquals(HapticCue.GameLost, session.score(Team.Them)!!.cue)
        repeat(3) { session.score(Team.Us) }
        // 2:1 → Seitenwechsel
        assertEquals(HapticCue.ChangeEnds, session.score(Team.Us)!!.cue)
        repeat(3) { session.score(Team.Us) }
        // 3:1 → gerade Summe, wir gewinnen das Game
        assertEquals(HapticCue.GameWon, session.score(Team.Us)!!.cue)
    }

    @Test
    fun gameLostAndSetWonCues() {
        val session = LiveScoreSession()
        repeat(4) { session.score(Team.Us) }
        repeat(3) { session.score(Team.Them) }
        assertEquals(HapticCue.GameLost, session.score(Team.Them)!!.cue)

        session.demo(DemoScenario.SetPoint)
        assertEquals(HapticCue.SetWon, session.score(Team.Us)!!.cue)
    }

    @Test
    fun scoringAfterMatchEndIsRejected() {
        val session = LiveScoreSession()
        session.demo(DemoScenario.MatchPoint)
        assertEquals(ScoreHint.Kind.MatchPoint, FakeTennis.hint(session.state)?.kind)
        session.score(Team.Us)
        assertEquals(Team.Us, session.state.winner)
        assertNull(session.score(Team.Them))
        // Undo hebt das Matchende wieder auf
        session.undo()
        assertNull(session.state.winner)
    }

    @Test
    fun demoScenariosProduceTheirHints() {
        val session = LiveScoreSession()
        val expected =
            mapOf(
                DemoScenario.BreakPoint to ScoreHint(ScoreHint.Kind.BreakPoint, Team.Us),
                DemoScenario.SetPoint to ScoreHint(ScoreHint.Kind.SetPoint, Team.Us),
                DemoScenario.MatchPoint to ScoreHint(ScoreHint.Kind.MatchPoint, Team.Us),
                DemoScenario.Deuce to ScoreHint(ScoreHint.Kind.Deuce, null),
                DemoScenario.Tiebreak to ScoreHint(ScoreHint.Kind.Tiebreak, null),
            )
        expected.forEach { (scenario, hint) ->
            session.demo(scenario)
            assertEquals(hint, FakeTennis.hint(session.state), scenario.name)
        }
    }
}
