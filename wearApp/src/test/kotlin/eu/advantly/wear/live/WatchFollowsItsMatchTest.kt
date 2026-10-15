package eu.advantly.wear.live

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ResultReason
import eu.advantly.match.session.ScorerChoice
import eu.advantly.wear.WatchHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F22 auf der Wear-Uhr: Das Handy führt mehrere Matches parallel, die Uhr zeigt und zählt nur ihr Uhr-Match; F21: Das
 * Ende (Beenden, wie es steht) kommt vom Handy und wird auf der Uhr korrekt angezeigt.
 */
class WatchFollowsItsMatchTest {
    private val h = WatchHarness()
    private val c = h.controller
    private val singles = Lineup(listOf(PlayerId("me")), listOf(PlayerId("bea")), PlayerId("me"))
    private val format = Presets.TENNIS_SINGLES_BO3.format()

    private val live: LiveUi get() = (c.state.value.screen as WatchScreen.Live).live

    @Test
    fun watchStaysOnItsMatchWhilePhoneRunsNeighbourCourts() {
        val onWatch = h.phone.create(format, singles, scorer = ScorerChoice.MyWatch)
        h.settle()
        assertEquals(onWatch, live.matchId)
        assertTrue(live.isScorer)
        // Nachbarplätze: das Handy zählt als Director, die Uhr bleibt bei ihrem Match
        val court2 = h.phone.create(Presets.PADEL_GOLDEN_BO3.format(), singlesPadel())
        h.settle()
        assertTrue(h.phone.recordPoint(Side.A))
        h.phone.create(format, singles)
        h.settle()
        assertEquals(onWatch, live.matchId)
        assertTrue(c.point(Side.A))
        h.settle()
        assertEquals(
            1,
            h.phone.state.value
                .match(onWatch)!!
                .state.current!!
                .points.a,
        )
        // Beenden eines Nicht-Uhr-Matches am Handy lässt die Uhr in Ruhe
        assertTrue(h.phone.resume(court2))
        assertTrue(h.phone.endMatch(EndReason.ABANDONED))
        h.settle()
        assertEquals(onWatch, live.matchId)
        assertNull(live.finished)
    }

    @Test
    fun phoneStopsTheWatchMatchAndTheWatchShowsTheResult() {
        val onWatch = h.phone.create(format, singles, scorer = ScorerChoice.MyWatch)
        h.settle()
        assertTrue(c.point(Side.A))
        h.settle()
        assertTrue(h.phone.resume(onWatch))
        assertTrue(h.phone.takeOverScoring())
        assertTrue(h.phone.endMatch(EndReason.STOPPED))
        h.settle()
        val finished = live.finished!!
        assertEquals(ResultReason.STOPPED, finished.reason)
        assertEquals(Side.A, finished.winner)
        c.close()
        val home = c.state.value.screen as WatchScreen.Home
        assertNull("beendetes Uhr-Match öffnet sich nicht wieder", home.resume)
    }

    @Test
    fun homeOffersOnlyTheWatchMatch() {
        val onWatch = h.phone.create(format, singles, scorer = ScorerChoice.MyWatch)
        h.settle()
        h.phone.create(format, singles) // Director-Match auf dem Nachbarplatz
        h.settle()
        h.offline("watch")
        h.settle()
        // Uhr-App „neu gestartet“: nichts offen, die Startliste bietet nur das Uhr-Match an
        h.watch.closeMatch()
        val home = c.state.value.screen as WatchScreen.Home
        assertEquals(onWatch, home.resume?.matchId)
    }

    @Test
    fun switchingTheWatchMatchMovesTheWatch() {
        val first = h.phone.create(format, singles, scorer = ScorerChoice.MyWatch)
        h.settle()
        assertTrue(c.point(Side.B))
        h.settle()
        val second = h.phone.create(format, singles, scorer = ScorerChoice.MyWatch)
        h.settle()
        assertEquals(second, live.matchId)
        assertTrue(live.isScorer)
        assertTrue(
            "altes Match zählt jetzt das Handy",
            h.phone.state.value
                .match(first)!!
                .isScorer,
        )
    }

    /** Review W3-M #5: Online-Wechsel verlustfrei – die Uhr übergibt ihr altes Match nach ihrer letzten Eingabe. */
    @Test
    fun onlineSwitchKeepsTheWatchPoints() {
        val first = h.phone.create(format, singles, scorer = ScorerChoice.MyWatch)
        h.settle()
        assertTrue(c.point(Side.A))
        h.phone.create(format, singles, scorer = ScorerChoice.MyWatch)
        // Zuweisung noch unterwegs: die Uhr zählt im alten Match weiter
        assertTrue(h.watch.recordPoint(Side.A))
        h.settle()
        val old =
            h.phone.state.value
                .match(first)!!
        assertEquals(0, old.rejectedByRoleCount)
        assertEquals(
            "30",
            old.state.current!!
                .display.a,
        )
        assertTrue(old.isScorer)
    }

    /** Review W3-M #5: Offline bestätigte Übernahme – die Uhr meldet nach der Wiederverbindung verworfene Eingaben. */
    @Test
    fun offlineTakeOverShowsDiscardedInputsOnTheWatch() {
        val first = h.phone.create(format, singles, scorer = ScorerChoice.MyWatch)
        h.settle()
        assertTrue(c.point(Side.A))
        h.settle()
        h.offline("watch")
        h.settle()
        val second = h.phone.create(format, singles, scorer = ScorerChoice.MyWatch, takeOverWatchMatch = true)
        assertTrue(c.point(Side.A))
        h.scheduler.advanceBy(700)
        assertTrue(c.point(Side.B))
        h.online("watch")
        h.settle(5_000)
        assertEquals(second, live.matchId)
        assertEquals(
            2,
            h.phone.state.value
                .match(first)!!
                .rejectedByRoleCount,
        )
        assertTrue(h.toasts.any { it == WatchToast.InputsRejected(2) })
    }

    private fun singlesPadel() = Lineup(listOf(PlayerId("me"), PlayerId("ann")), listOf(PlayerId("bea"), PlayerId("cid")), PlayerId("me"))
}
