package eu.advantly.wear.live

import eu.advantly.core.model.EventId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.wear.scoring.ScoringGesture
import eu.advantly.wear.scoring.StripHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Gesten des Score-Screens: Ziel beim Aufsetzen, nie Zählen/Abziehen von der Leiste, kein Rückfall beim Ablauf. */
class TouchRoutingTest {
    private val options = listOf(ServeOutcome.FIRST_SERVE_IN, ServeOutcome.SECOND_SERVE_IN)
    private val serveP1 = StripAction.Serve(ServeBarUi(EventId("p1"), options, null))
    private val serveP2 = StripAction.Serve(ServeBarUi(EventId("p2"), options, null))

    private fun decide(
        target: TouchTarget,
        gesture: ScoringGesture,
        now: StripAction?,
        locked: Boolean = false,
        scorer: Boolean = true,
    ) = TouchRouting.decide(target, gesture, now, locked, scorer)

    @Test
    fun targetIsFixedFromTheDownPosition() {
        assertEquals(TouchTarget.Strip(serveP1), TouchRouting.target(serveP1, StripHit.STRIP))
        assertEquals(TouchTarget.Guard, TouchRouting.target(serveP1, StripHit.GUARD))
        assertEquals(TouchTarget.Score, TouchRouting.target(serveP1, StripHit.OUTSIDE))
        assertEquals("ohne Leiste zählt alles wie bisher", TouchTarget.Score, TouchRouting.target(null, StripHit.STRIP))
    }

    @Test
    fun touchesStartingOnTheStripNeverScoreOrRemove() {
        val strip = TouchTarget.Strip(StripAction.UndoRemoval)
        assertEquals(
            "Halten auf der Leiste zieht nicht ab",
            TouchDecision.None,
            decide(strip, ScoringGesture.LONG_PRESS, StripAction.UndoRemoval),
        )
        assertEquals(TouchDecision.None, decide(strip, ScoringGesture.SWIPE_DOWN, StripAction.UndoRemoval))
        assertEquals(TouchDecision.Strip(StripAction.UndoRemoval), decide(strip, ScoringGesture.TAP, StripAction.UndoRemoval))
        val choose = TouchTarget.Strip(StripAction.ChooseServer)
        assertEquals(TouchDecision.None, decide(choose, ScoringGesture.LONG_PRESS, StripAction.ChooseServer))
        assertEquals(TouchDecision.None, decide(TouchTarget.Guard, ScoringGesture.TAP, serveP1))
        assertEquals(TouchDecision.None, decide(TouchTarget.Guard, ScoringGesture.LONG_PRESS, serveP1))
        assertEquals("Navigation bleibt", TouchDecision.OpenControls, decide(TouchTarget.Guard, ScoringGesture.SWIPE_LEFT, serveP1))
    }

    @Test
    fun expiredOrReplacedStripDiscardsTheTapInsteadOfScoring() {
        val strip = TouchTarget.Strip(serveP1)
        assertEquals("Leiste während des Taps abgelaufen", TouchDecision.None, decide(strip, ScoringGesture.TAP, null))
        assertEquals("andere Punkt-ID", TouchDecision.None, decide(strip, ScoringGesture.TAP, serveP2))
        assertEquals("andere Leiste", TouchDecision.None, decide(strip, ScoringGesture.TAP, StripAction.ChooseServer))
        val marked = StripAction.Serve(ServeBarUi(EventId("p1"), options, ServeOutcome.FIRST_SERVE_IN))
        assertEquals("gleicher Punkt, neue Markierung", TouchDecision.Strip(marked), decide(strip, ScoringGesture.TAP, marked))
        assertNull(TouchRouting.stillShown(StripAction.UndoRemoval, StripAction.ChooseServer))
        assertNull(TouchRouting.stillShown(StripAction.ChooseServer, serveP1))
    }

    @Test
    fun scoringAreaKeepsTheExistingGestures() {
        val score = TouchTarget.Score
        assertEquals(TouchDecision.Point, decide(score, ScoringGesture.TAP, serveP1))
        assertEquals(TouchDecision.RemovePoint, decide(score, ScoringGesture.LONG_PRESS, null))
        assertEquals("Entsperren zieht nicht ab", TouchDecision.Unlock, decide(score, ScoringGesture.LONG_PRESS, null, locked = true))
        assertEquals(TouchDecision.Undo, decide(score, ScoringGesture.SWIPE_DOWN, null))
        assertEquals(TouchDecision.OpenControls, decide(score, ScoringGesture.SWIPE_LEFT, null))
        assertEquals(TouchDecision.None, decide(score, ScoringGesture.SWIPE_LEFT, null, locked = true))
        assertEquals(TouchDecision.None, decide(score, ScoringGesture.NONE, null))
        // Anzeige: keine Zähl-Gesten
        assertEquals(TouchDecision.None, decide(score, ScoringGesture.TAP, null, scorer = false))
        assertEquals(TouchDecision.None, decide(score, ScoringGesture.LONG_PRESS, null, scorer = false))
        assertEquals(TouchDecision.None, decide(score, ScoringGesture.SWIPE_DOWN, null, scorer = false))
    }

    /** Halte-Fortschritt nur auf Zählflächen, wo Halten etwas bewirkt (Abziehen am Zähler bzw. Entsperren). */
    @Test
    fun holdProgressOnlyWhereHoldingDoesSomething() {
        val score = TouchTarget.Score
        assertEquals(Side.B, TouchRouting.holdSide(score, Side.B, locked = false, scorer = true, canRemove = true))
        assertNull("nichts abzuziehen", TouchRouting.holdSide(score, Side.B, locked = false, scorer = true, canRemove = false))
        assertNull("Anzeige zieht nie ab", TouchRouting.holdSide(score, Side.A, locked = false, scorer = false, canRemove = true))
        assertEquals(
            "gesperrt: Halten entsperrt",
            Side.A,
            TouchRouting.holdSide(score, Side.A, locked = true, scorer = true, canRemove = false),
        )
        assertNull("Totzone/Mittelband", TouchRouting.holdSide(score, null, locked = false, scorer = true, canRemove = true))
        assertNull(
            "Aktionsleiste",
            TouchRouting.holdSide(TouchTarget.Strip(serveP1), Side.A, locked = false, scorer = true, canRemove = true),
        )
        assertNull("Schutzstreifen", TouchRouting.holdSide(TouchTarget.Guard, Side.A, locked = false, scorer = true, canRemove = true))
    }
}
