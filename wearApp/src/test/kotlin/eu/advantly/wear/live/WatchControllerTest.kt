package eu.advantly.wear.live

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.SidePair
import eu.advantly.match.session.ScorerChoice
import eu.advantly.presentation.live.PointLabel
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.wear.WatchHarness
import eu.advantly.wear.match.FormatChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uhr-Controller über echte Sessions (Handy + Uhr) mit Fake-Transport: Rollen, Sync, Übernahme, Staleness, Effekte. */
class WatchControllerTest {
    private val h = WatchHarness()
    private val c = h.controller
    private val singles = Lineup(listOf(PlayerId("me")), listOf(PlayerId("bea")), PlayerId("me"))

    private val live: LiveUi get() = assertNotNull("kein Live-Screen", c.state.value.live).let { c.state.value.live!! }

    private fun startOnWatch(
        sport: Sport = Sport.TENNIS,
        discipline: Discipline = Discipline.SINGLES,
        format: FormatChoice = FormatChoice.TENNIS_BO3,
        server: Side = Side.A,
        serverIndex: Int = 0,
    ) {
        c.newMatch()
        c.selectSport(sport)
        if (sport != Sport.PADEL) c.selectDiscipline(discipline)
        c.selectFormat(format)
        assertTrue(c.selectServer(server, serverIndex))
    }

    /** Punkte mit genug Abstand für die 600-ms-Entprellung. */
    private fun points(vararg sides: Side) =
        sides.forEach {
            assertTrue(c.point(it))
            h.scheduler.advanceBy(700)
        }

    @Test
    fun homeIsShownWithoutMatch() {
        val home = c.state.value.screen as WatchScreen.Home
        assertNull(home.last)
        assertNull(home.resume)
        assertTrue(home.workoutEnabled)
    }

    @Test
    fun watchStartsMatchAndPhoneShowsIt() {
        startOnWatch()
        assertEquals(LiveRole.SCORER, live.role)
        points(Side.A, Side.A, Side.B)
        h.settle()
        val phone = assertNotNull(h.phone.state.value.match).let { h.phone.state.value.match!! }
        assertEquals(live.matchId, phone.id)
        assertFalse("Handy zeigt nur an", phone.isScorer)
        assertEquals(SidePair(2, 1), phone.state.current?.points)
        assertEquals(SyncUi.Synced, live.sync)
        assertEquals(listOf(WatchHaptic.POINT_US, WatchHaptic.POINT_US, WatchHaptic.POINT_THEM), h.haptics.map { it.pattern })
    }

    @Test
    fun doubleTapWithinLockoutCountsOnce() {
        startOnWatch()
        assertTrue(c.point(Side.A))
        h.scheduler.advanceBy(300)
        assertFalse(c.point(Side.A))
        h.scheduler.advanceBy(300)
        assertTrue(c.point(Side.B))
        assertEquals(
            SidePair(1, 1),
            h.watch.state.value.match
                ?.state
                ?.current
                ?.points,
        )
    }

    @Test
    fun undoShowsBeforeAndAfter() {
        startOnWatch()
        points(Side.A, Side.A)
        assertTrue(c.undo())
        assertEquals(WatchToast.Undone("30:0", "15:0"), h.toasts.last())
        assertEquals(WatchHaptic.UNDO, h.haptics.last().pattern)
        assertTrue(c.redo())
        assertEquals(PointLabel.Number("30"), live.points.a)
        c.undo()
        c.undo()
        assertFalse(c.undo())
        assertEquals(WatchToast.NothingToUndo, h.toasts.last())
    }

    @Test
    fun gameAndChangeEndsProduceTheirHaptics() {
        startOnWatch()
        points(Side.A, Side.A, Side.A, Side.A) // 1:0 Spiele → Seitenwechsel nach dem 1. Spiel
        assertEquals(WatchHaptic.CHANGE_ENDS, h.haptics.last().pattern)
        points(Side.B, Side.B, Side.B, Side.B)
        assertEquals(WatchHaptic.GAME_LOST, h.haptics.last().pattern)
    }

    @Test
    fun breakPointAddsAttentionImpulse() {
        startOnWatch(server = Side.A) // wir schlagen auf: 0:40 ist Breakball für Gegner
        points(Side.B, Side.B)
        assertFalse(h.haptics.last().attention)
        points(Side.B)
        assertTrue(h.haptics.last().attention)
        assertEquals(eu.advantly.presentation.live.ScoreHint.Kind.BreakPoint, live.hint?.kind)
        assertEquals(3, live.hint?.count)
    }

    @Test
    fun phoneDirectorMakesTheWatchADisplay() {
        h.phone.create(Presets.TENNIS_SINGLES_BO3.format(), singles, scorer = ScorerChoice.ThisDevice)
        h.settle()
        assertEquals(LiveRole.DISPLAY, live.role)
        assertEquals(PeerKind.PHONE, live.scorerKind)
        assertEquals(SyncUi.Live, live.sync)
        assertFalse(c.point(Side.A))
        assertFalse(c.undo())
        h.phone.recordPoint(Side.B)
        h.settle()
        assertEquals(PointLabel.Number("15"), live.points.b)
        assertTrue("Anzeige-Uhr erzeugt keine Events", h.watchData.events.unconfirmedCount() == 0L)
    }

    @Test
    fun phoneStartedMatchWithWatchAsScorerAppearsAsScorer() {
        h.phone.create(Presets.TENNIS_SINGLES_BO3.format(), singles, scorer = ScorerChoice.Device(DeviceId("watch")))
        h.settle()
        assertEquals(LiveRole.SCORER, live.role)
        assertTrue(c.point(Side.A))
        h.settle()
        assertEquals(
            SidePair(1, 0),
            h.phone.state.value.match
                ?.state
                ?.current
                ?.points,
        )
    }

    @Test
    fun staleDisplayOffersTakeOverAfterThirtySeconds() {
        h.phone.create(Presets.TENNIS_SINGLES_BO3.format(), singles)
        h.settle()
        h.offline("phone")
        h.settle(10_000)
        assertTrue(live.sync is SyncUi.Stale)
        assertFalse(live.takeOverOffered)
        h.settle(21_000)
        assertTrue(live.takeOverOffered)
        val seconds = (live.sync as SyncUi.Stale).seconds
        assertTrue("Stand vor X s: $seconds", seconds != null && seconds >= 30)
    }

    @Test
    fun takeOverNeedsConfirmationAndSwitchesRoles() {
        h.phone.create(Presets.TENNIS_SINGLES_BO3.format(), singles)
        h.settle()
        c.requestTakeOver()
        assertEquals(LiveOverlay.CONFIRM_TAKE_OVER, live.overlay)
        c.dismissOverlay()
        assertEquals(LiveRole.DISPLAY, live.role)
        c.requestTakeOver()
        assertTrue(c.confirmTakeOver())
        h.settle()
        assertEquals(LiveRole.SCORER, live.role)
        assertNull(live.overlay)
        assertEquals(WatchToast.RoleChanged(nowScorer = true, scorerKind = PeerKind.WATCH), h.toasts.last())
        assertFalse(
            h.phone.state.value.match!!
                .isScorer,
        )
        assertTrue(c.point(Side.A))
    }

    @Test
    fun oldScorerOfflineInputsAreReportedAsNotApplied() {
        startOnWatch()
        points(Side.A)
        h.settle()
        h.offline("watch")
        h.settle(5_000)
        assertTrue(h.phone.takeOverScoring())
        points(Side.A, Side.A) // Uhr weiß noch nichts von der Übernahme
        h.online("watch")
        h.settle()
        assertEquals(LiveRole.DISPLAY, live.role)
        assertEquals(2, live.rejectedInputs)
        assertTrue(h.toasts.contains(WatchToast.RoleChanged(nowScorer = false, scorerKind = PeerKind.PHONE)))
        assertTrue(h.toasts.contains(WatchToast.InputsRejected(2)))
        assertEquals(PointLabel.Number("15"), live.points.a)
    }

    @Test
    fun offlineScorerBuffersAndDeliversImmediatelyOnReconnect() {
        startOnWatch()
        h.settle()
        h.offline("watch") // Flugmodus mitten im Satz
        points(Side.A, Side.B, Side.A)
        h.settle(1_000)
        assertEquals(SyncUi.Pending(3), live.sync)
        h.online("watch")
        h.scheduler.advanceBy(500) // sofort, nicht erst im 30-s-Takt
        c.tick()
        assertEquals(
            SidePair(2, 1),
            h.phone.state.value.match
                ?.state
                ?.current
                ?.points,
        )
        assertEquals(SyncUi.Synced, live.sync)
    }

    @Test
    fun deletingOnThePhoneRemovesTheMatchFromTheWatch() {
        startOnWatch()
        points(Side.A)
        h.settle()
        h.phone.deleteMatch(live.matchId)
        h.settle()
        assertTrue(c.state.value.screen is WatchScreen.Home)
        assertEquals(WatchToast.MatchRemoved, h.toasts.last())
    }

    @Test
    fun closingLocallyIsNotReportedAsRemoved() {
        startOnWatch()
        assertTrue(c.confirmEnd())
        c.close()
        assertTrue(c.state.value.screen is WatchScreen.Home)
        assertFalse(h.toasts.contains(WatchToast.MatchRemoved))
    }

    @Test
    fun openFormatAsksAnotherSetOnlyOnTheScorer() {
        startOnWatch(format = FormatChoice.TENNIS_OPEN)
        repeat(6 * 4) {
            c.point(Side.A)
            h.scheduler.advanceBy(700)
        }
        h.settle()
        val decision = assertNotNull(live.decision).let { live.decision!! }
        assertEquals(1, decision.setNumber)
        assertEquals(SidePair(6, 0), decision.score)
        assertEquals(listOf(NextSetKind.REGULAR, NextSetKind.TIEBREAK_7, NextSetKind.MATCH_TIEBREAK_10), decision.options)
        // Handy (Anzeige) sieht denselben Zustand, kann aber nicht entscheiden
        assertFalse(h.phone.continueDecision(anotherSet = true))
        assertTrue(c.continueDecision(anotherSet = true))
        assertNull(live.decision)
        assertEquals(MatchStatus.IN_PROGRESS, live.status)
    }

    @Test
    fun finishedBestOfOffersFollowUpOnTheScorer() {
        startOnWatch(format = FormatChoice.TENNIS_ONE_SET)
        repeat(6 * 4) {
            c.point(Side.A)
            h.scheduler.advanceBy(700)
        }
        val finished = assertNotNull(live.finished).let { live.finished!! }
        assertEquals(Side.A, finished.winner)
        assertTrue(finished.canFollowUp)
        val first = live.matchId
        assertTrue(c.followUp())
        assertTrue(live.matchId != first)
        assertEquals(MatchStatus.IN_PROGRESS, live.status)
        h.settle()
        assertEquals(
            live.matchId,
            h.phone.state.value.match
                ?.id,
        ) // Handy folgt automatisch
    }

    @Test
    fun touchLockBlocksPointsUntilUnlocked() {
        startOnWatch()
        c.lock()
        assertTrue(live.locked)
        assertFalse(c.point(Side.A))
        assertEquals(WatchToast.Locked, h.toasts.last())
        assertEquals(WatchHaptic.REJECTED, h.haptics.last().pattern)
        c.unlock()
        assertTrue(c.point(Side.A))
    }

    @Test
    fun endMatchNeedsConfirmation() {
        startOnWatch()
        c.requestEnd()
        assertEquals(LiveOverlay.CONFIRM_END, live.overlay)
        assertFalse("kein Punkt während der Rückfrage", c.point(Side.A))
        assertTrue(c.confirmEnd())
        assertEquals(MatchStatus.FINISHED, live.status)
    }

    @Test
    fun wizardBackNavigationAndMandatoryTableTennisSets() {
        c.newMatch()
        c.selectSport(Sport.TABLE_TENNIS)
        c.selectDiscipline(Discipline.DOUBLES)
        val setup = c.state.value.screen as WatchScreen.Setup
        assertEquals(SetupStep.FORMAT, setup.step)
        assertNull(setup.defaultFormat)
        c.back()
        assertEquals(SetupStep.DISCIPLINE, (c.state.value.screen as WatchScreen.Setup).step)
        c.back()
        assertEquals(SetupStep.SPORT, (c.state.value.screen as WatchScreen.Setup).step)
        c.back()
        assertTrue(c.state.value.screen is WatchScreen.Home)
        // Padel überspringt Einzel/Doppel
        c.newMatch()
        c.selectSport(Sport.PADEL)
        assertEquals(SetupStep.FORMAT, (c.state.value.screen as WatchScreen.Setup).step)
        assertEquals(Discipline.DOUBLES, (c.state.value.screen as WatchScreen.Setup).discipline)
    }

    @Test
    fun doublesShowServerAndReceiverWithCourt() {
        startOnWatch(sport = Sport.BADMINTON, discipline = Discipline.DOUBLES, format = FormatChoice.BADMINTON_BO3_21)
        val serve = assertNotNull(live.serve).let { live.serve!! }
        assertEquals(Side.A, serve.side)
        assertEquals(CourtLetter.R, serve.court)
        assertEquals(Side.A, serve.server?.side)
        assertEquals(Side.B, serve.receiver?.side)
        assertTrue(live.doubles)
    }

    @Test
    fun repeatLastStartsTheSameSetupAgain() {
        startOnWatch(sport = Sport.PICKLEBALL, discipline = Discipline.DOUBLES, format = FormatChoice.PICKLEBALL_11)
        val first = h.watch.state.value.match!!
        c.confirmEnd()
        c.close()
        val home = c.state.value.screen as WatchScreen.Home
        assertEquals(Sport.PICKLEBALL, home.last?.sport)
        assertEquals(FormatChoice.PICKLEBALL_11, home.last?.choice)
        assertEquals(1, home.recent.size)
        h.scheduler.advanceBy(1_000)
        assertTrue(c.repeatLast())
        val second = h.watch.state.value.match!!
        assertTrue(second.id != first.id)
        assertEquals(first.format, second.format)
        assertEquals(first.lineup.a.first(), second.lineup.a.first())
        assertTrue((second.lineup.a.drop(1) + second.lineup.b).all(h.watchData::isPlaceholder))
        assertTrue((second.lineup.a.drop(1) + second.lineup.b).none { it in first.lineup.a + first.lineup.b })
        assertTrue(second.isScorer)
    }

    @Test
    fun stalenessIsMeasuredMonotonicallyDespiteWallClockJumps() {
        h.phone.create(Presets.TENNIS_SINGLES_BO3.format(), singles)
        h.settle()
        h.offline("phone")
        h.settle(10_000)
        val before = (live.sync as SyncUi.Stale).seconds!!
        h.wallOffsetMs = -3_600_000 // Zeitumstellung um eine Stunde zurück
        c.tick()
        assertEquals(before, (live.sync as SyncUi.Stale).seconds)
        h.settle(21_000)
        assertTrue("Angebot nach 30 s trotz Zeitsprung", live.takeOverOffered)
    }

    @Test
    fun newSummaryContactIsSeenAfterAWallClockJumpBack() {
        h.phone.create(Presets.TENNIS_SINGLES_BO3.format(), singles)
        h.settle()
        h.phone.recordPoint(Side.A) // letztes Event mit „später“ Wanduhr
        h.settle()
        h.wallOffsetMs = -3_600_000 // Uhrzeit springt zurück, Verbindung bleibt
        h.settle(31_000) // Abgleich im 30-s-Takt (Summary) ohne neue Events: kleinere Wanduhr als das letzte Event
        h.offline("phone")
        h.settle(5_000)
        val seconds = (live.sync as SyncUi.Stale).seconds
        assertTrue("Stand seit dem letzten Summary, nicht seit dem letzten Event: $seconds", seconds != null && seconds < 30)
        assertFalse("Übernahme erst nach 30 s ohne Kontakt", live.takeOverOffered)
    }

    @Test
    fun doublesStartWithTheChosenServerOnTheRightCourt() {
        startOnWatch(sport = Sport.BADMINTON, discipline = Discipline.DOUBLES, format = FormatChoice.BADMINTON_BO3_21, serverIndex = 1)
        val serve = live.serve!!
        assertEquals(Side.A, serve.server?.side)
        assertEquals("Partner schlägt auf", 1, serve.server?.index)
        assertEquals(CourtLetter.R, serve.court)
    }

    @Test
    fun serverCorrectionIsRetroactiveInTheFirstGameOnly() {
        startOnWatch(discipline = Discipline.DOUBLES, server = Side.A)
        val corrections = live.corrections!!
        assertTrue(corrections.retroactive)
        assertEquals(4, corrections.players.size)
        val opponent2 = corrections.players.single { it.side == Side.B && it.index == 1 }
        c.requestCorrection()
        assertEquals(LiveOverlay.CORRECT_SERVER, live.overlay)
        assertTrue(c.correctServer(opponent2.id))
        assertNull(live.overlay)
        assertEquals(opponent2.id, live.serve?.server?.id)
        assertEquals(WatchToast.Corrected, h.toasts.last())
        points(Side.A, Side.A, Side.A, Side.A) // 1. Spiel vorbei → ab jetzt
        assertFalse(live.corrections!!.retroactive)
    }

    @Test
    fun badmintonDoublesCanSwapCourtPositions() {
        startOnWatch(sport = Sport.BADMINTON, discipline = Discipline.DOUBLES, format = FormatChoice.BADMINTON_BO3_21)
        val before =
            h.watch.state.value.match!!
                .state.positions!!
                .b
        assertTrue(c.swapPositions(Side.B))
        val after =
            h.watch.state.value.match!!
                .state.positions!!
                .b
        assertEquals(before.left, after.right)
        assertEquals(before.right, after.left)
    }

    @Test
    fun displayOffersNoCorrections() {
        h.phone.create(Presets.TENNIS_SINGLES_BO3.format(), singles)
        h.settle()
        assertNull(live.corrections)
        c.requestCorrection()
        assertNull(live.overlay)
        assertFalse(c.correctServer(PlayerId("me")))
    }

    @Test
    fun pickleballDoublesShowsTheScoreCall() {
        startOnWatch(sport = Sport.PICKLEBALL, discipline = Discipline.DOUBLES, format = FormatChoice.PICKLEBALL_11)
        assertEquals("0-0-2", live.scoreCall)
    }
}
