package eu.advantly.wear.live

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.SetKind
import eu.advantly.core.scoring.SidePair
import eu.advantly.match.session.ScorerChoice
import eu.advantly.presentation.live.PointLabel
import eu.advantly.presentation.match.LocalDay
import eu.advantly.wear.WatchHarness
import eu.advantly.wear.match.FormatChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W2-Bedienung am Uhr-Controller über echte Sessions (Handy + Uhr): Punkt abziehen, Aufschlag-Leiste,
 * Aufschlägerwahl im Doppel, Satzende-Optionen und Setup (Games pro Satz, Nur Tiebreak, Aufschlag-Statistik).
 */
class WatchControllerOperationTest {
    private val today = LocalDay(2026, 10, 15)
    private val h = WatchHarness(today)
    private val c = h.controller

    private val live: LiveUi get() = assertNotNull("kein Live-Screen", c.state.value.live).let { c.state.value.live!! }
    private val match get() = h.watch.state.value.match!!

    private fun startOnWatch(
        sport: Sport = Sport.TENNIS,
        discipline: Discipline = Discipline.SINGLES,
        format: FormatChoice = FormatChoice.TENNIS_BO3,
        server: Side = Side.A,
        games: Int? = null,
        serveStats: Boolean? = null,
    ) {
        c.newMatch()
        c.selectSport(sport)
        if (sport != Sport.PADEL) c.selectDiscipline(discipline)
        c.selectFormat(format)
        games?.let(c::selectGamesPerSet)
        serveStats?.let(c::setServeStats)
        assertTrue(c.selectServer(server, 0))
    }

    /** Punkte mit genug Abstand für die 600-ms-Entprellung. */
    private fun points(vararg sides: Side) =
        sides.forEach {
            if (live.overlay == LiveOverlay.SERVER_CHOICE) c.dismissOverlay()
            h.scheduler.advanceBy(300)
            assertTrue(c.point(it))
            h.scheduler.advanceBy(700)
        }

    // --- Punkt abziehen ---------------------------------------------------------------------------

    @Test
    fun removingTheLastPointOfASideKeepsLaterPointsOfTheOtherSide() {
        startOnWatch()
        points(Side.A, Side.B, Side.A) // 30:15
        assertEquals(SidePair(true, true), live.canRemovePoint)
        assertTrue(c.removePoint(Side.B))
        assertEquals(PointLabel.Number("30"), live.points.a)
        assertEquals(PointLabel.Number("0"), live.points.b)
        assertEquals(WatchToast.PointRemoved(Side.B), h.toasts.last())
        assertEquals(WatchHaptic.UNDO, h.haptics.last().pattern)
        assertFalse(live.canRemovePoint.b)
        // „Rückgängig“ im Hinweis = Redo
        assertTrue(live.canRedo)
        assertTrue(c.redo())
        assertEquals(PointLabel.Number("15"), live.points.b)
        h.settle()
        assertEquals(
            SidePair(2, 1),
            h.phone.state.value.match
                ?.state
                ?.current
                ?.points,
        )
    }

    @Test
    fun removingWithoutAPointIsRejected() {
        startOnWatch()
        points(Side.A)
        assertFalse(live.canRemovePoint.b)
        assertFalse(c.removePoint(Side.B))
        assertEquals(WatchToast.NothingToRemove, h.toasts.last())
        assertEquals(WatchHaptic.REJECTED, h.haptics.last().pattern)
        assertEquals(PointLabel.Number("15"), live.points.a)
    }

    @Test
    fun removingAPointReopensAFinishedSet() {
        startOnWatch(format = FormatChoice.TENNIS_OPEN)
        repeat(6 * 4) { points(Side.A) }
        assertNotNull(live.decision)
        assertTrue(c.removePoint(Side.A))
        assertNull(live.decision)
        assertEquals(MatchStatus.IN_PROGRESS, live.status)
        assertEquals(SidePair(5, 0), match.state.current?.games)
        assertEquals(PointLabel.Number("40"), live.points.a)
    }

    @Test
    fun lockedOrDisplayCannotRemovePoints() {
        startOnWatch()
        points(Side.A)
        c.lock()
        assertFalse(c.removePoint(Side.A))
        assertEquals(WatchToast.Locked, h.toasts.last())
        c.unlock()
        assertTrue(c.confirmEnd())
        c.close()
        h.settle() // F22: erst wenn das Handy das Ende kennt, ist die Uhr frei für ein neues Director-Match
        h.phone.create(
            Presets.TENNIS_SINGLES_BO3.format(),
            Lineup(listOf(PlayerId("me")), listOf(PlayerId("bea")), PlayerId("me")),
            scorer = ScorerChoice.ThisDevice,
        )
        h.phone.recordPoint(Side.A)
        h.settle()
        assertEquals(LiveRole.DISPLAY, live.role)
        assertEquals(SidePair(false, false), live.canRemovePoint)
        assertFalse(c.removePoint(Side.A))
    }

    // --- Aufschlag-Leiste -------------------------------------------------------------------------

    @Test
    fun serveBarAppearsAfterEachPointAndOffersDoubleFaultOnlyForReceiverPoints() {
        startOnWatch(server = Side.A)
        assertNull(live.serveBar)
        points(Side.A) // Aufschläger gewinnt
        assertEquals(listOf(ServeOutcome.FIRST_SERVE_IN, ServeOutcome.SECOND_SERVE_IN), live.serveBar?.options)
        assertFalse(c.setServe(ServeOutcome.DOUBLE_FAULT))
        points(Side.B) // Rückschläger gewinnt → neue Leiste für den neuen Punkt
        val bar = live.serveBar!!
        assertEquals(match.lastPoint?.id, bar.pointId)
        assertEquals(ServeOutcome.entries.toList(), bar.options)
        assertTrue(c.setServe(ServeOutcome.DOUBLE_FAULT))
        assertEquals(WatchHaptic.CONFIRM, h.haptics.last().pattern)
        assertEquals(ServeOutcome.DOUBLE_FAULT, match.lastPoint?.serve)
        assertEquals("markiert", ServeOutcome.DOUBLE_FAULT, live.serveBar?.selected)
        // Korrektur, solange sichtbar
        assertTrue(c.setServe(ServeOutcome.SECOND_SERVE_IN))
        assertEquals(ServeOutcome.SECOND_SERVE_IN, match.lastPoint?.serve)
        // kurz nach der Wahl blendet sie aus
        h.scheduler.advanceBy(ServeBarTimer.AFTER_CHOICE_MILLIS)
        c.tick()
        assertNull(live.serveBar)
        assertFalse(c.setServe(ServeOutcome.FIRST_SERVE_IN))
    }

    /**
     * Feldtest 2026-10-16: Die Leiste muss nach JEDEM Punkt stehen (Aufschlag-Statistik ist Default an) – über Spiel- und
     * Satzgrenzen, im Tiebreak, im Doppel trotz offener Aufschlägerwahl und im Padel; sie hat in der Aktionsleiste Vorrang.
     */
    @Test
    fun serveBarFollowsEveryPointThroughGamesSetsAndTiebreaks() {
        for ((sport, discipline, format) in listOf(
            Triple(Sport.TENNIS, Discipline.SINGLES, FormatChoice.TENNIS_BO3),
            Triple(Sport.TENNIS, Discipline.DOUBLES, FormatChoice.TENNIS_BO3_MTB),
            Triple(Sport.PADEL, Discipline.DOUBLES, FormatChoice.PADEL_GOLDEN),
        )) {
            startOnWatch(sport = sport, discipline = discipline, format = format)
            assertTrue("$sport: Aufschlag-Statistik ist Default an", match.meta.serveStats)
            // 6:6 über abwechselnd gewonnene Spiele, dann Tiebreak bis 7:5 – und der erste Punkt des 2. Satzes
            val rallies =
                List(6) { List(4) { Side.A } + List(4) { Side.B } }.flatten() + List(5) { listOf(Side.A, Side.B) }.flatten() +
                    listOf(Side.A, Side.A, Side.B)
            rallies.forEachIndexed { i, side ->
                val servingBefore = match.state.serve?.side
                points(side)
                val bar = live.serveBar
                assertNotNull("$sport $discipline: Leiste nach Punkt ${i + 1}", bar)
                assertEquals("$sport: für genau diesen Punkt", match.lastPoint?.id, bar!!.pointId)
                assertEquals(
                    "$sport: Doppelfehler nur bei Punkt der Rückschlagseite",
                    side != servingBefore,
                    ServeOutcome.DOUBLE_FAULT in bar.options,
                )
                assertEquals(
                    "$sport: Overlay schützt die Eingabe",
                    if (live.overlay ==
                        null
                    ) {
                        StripAction.Serve(bar)
                    } else {
                        null
                    },
                    OperationRules.stripAction(live, null),
                )
            }
            assertEquals("$sport: im 2. Satz angekommen", 1, match.state.sets.size)
            c.confirmEnd()
            c.close()
        }
    }

    @Test
    fun serveBarHidesAutomaticallyAndOnUndoOrWake() {
        startOnWatch()
        points(Side.A)
        assertNotNull(live.serveBar)
        h.scheduler.advanceBy(ServeBarTimer.VISIBLE_MILLIS)
        c.tick()
        assertNull("ohne Wahl nach wenigen Sekunden weg", live.serveBar)
        points(Side.A)
        assertNotNull(live.serveBar)
        assertTrue(c.undo())
        assertNull(live.serveBar)
        assertTrue(c.redo())
        assertNull("Redo zeigt keine Leiste", live.serveBar)
        points(Side.B)
        c.onWake()
        assertNull(live.serveBar)
    }

    @Test
    fun serveBarRespectsTheServeStatsOptionAndTheSport() {
        startOnWatch(serveStats = false)
        assertFalse(match.meta.serveStats)
        points(Side.A)
        assertNull(live.serveBar)
        c.confirmEnd()
        c.close()
        startOnWatch(sport = Sport.BADMINTON, format = FormatChoice.BADMINTON_ONE_21)
        points(Side.A)
        assertNull(live.serveBar)
    }

    @Test
    fun explicitOpeningChoiceOnlyOffersForwardCorrection() {
        startOnWatch(discipline = Discipline.DOUBLES)
        c.openServerChoice()
        val partner = live.teams.a[1].id
        assertTrue(c.chooseServer(partner))
        assertFalse(live.corrections!!.retroactive)
        c.requestCorrection()
        assertTrue(c.correctServer(live.teams.b[1].id))
        assertEquals(live.teams.b[1].id, match.state.serve?.player)
        assertEquals(LiveOverlay.SERVER_CHOICE, live.overlay)
    }

    @Test
    fun automaticChoiceIsLocalAndRejectsStaleTaps() {
        startOnWatch(discipline = Discipline.DOUBLES)
        assertEquals(LiveOverlay.SERVER_CHOICE, live.overlay)
        val first = match.serverChoiceContext!!
        val count = match.eventCount
        assertFalse(c.point(Side.A))
        assertFalse(c.undo())
        c.dismissOverlay()
        c.tick()
        c.onWake()
        assertNull(live.overlay)
        assertEquals(count, match.eventCount)
        points(Side.A, Side.A, Side.A, Side.A)
        assertEquals(LiveOverlay.SERVER_CHOICE, live.overlay)
        val second = match.serverChoiceContext!!
        assertFalse(c.chooseServer(second.players.first(), first))
        assertEquals(second, match.serverChoiceContext)
        assertTrue(c.chooseServer(second.players.last(), second))
        assertNull(live.overlay)
        assertFalse("Auswahltap zählt keinen Punkt", c.point(Side.A))
        h.scheduler.advanceBy(300)
        assertTrue(c.point(Side.A))
    }

    @Test
    fun backgroundAndLockDeferAutomaticChoiceAndUndoReopensMarkedChoice() {
        c.setInteractive(false)
        startOnWatch(discipline = Discipline.DOUBLES, format = FormatChoice.TENNIS_TIEBREAK_7)
        assertNull(live.overlay)
        c.lock()
        c.setInteractive(true)
        assertNull("Gesperrt noch keine Auswahl", live.overlay)
        c.unlock()
        assertEquals(LiveOverlay.SERVER_CHOICE, live.overlay)
        c.chooseServer(match.serverChoiceContext!!.players.last())
        points(Side.A)
        assertEquals(LiveOverlay.SERVER_CHOICE, live.overlay)
        c.dismissOverlay()
        h.scheduler.advanceBy(300)
        assertTrue(c.undo())
        assertEquals(LiveOverlay.SERVER_CHOICE, live.overlay)
        assertNotNull(live.serverChoice?.chosen)
    }

    // --- Aufschlägerwahl im Doppel ----------------------------------------------------------------

    @Test
    fun undoAtTheStartOfALaterSetReviewsTheMarkedChoice() {
        startOnWatch(discipline = Discipline.DOUBLES, format = FormatChoice.TENNIS_OPEN)
        repeat(24) { points(Side.A) }
        assertNotNull(live.decision)
        assertTrue(c.startNextSet(NextSetKind.REGULAR))
        assertEquals(LiveOverlay.SERVER_CHOICE, live.overlay)
        assertTrue(
            c.chooseServer(
                live.serverChoice!!
                    .players
                    .last()
                    .id,
            ),
        )
        points(Side.A)
        assertTrue(c.undo())
        assertEquals(1, match.state.current?.setIndex)
        assertEquals(LiveOverlay.SERVER_CHOICE, live.overlay)
        assertNotNull(live.serverChoice?.chosen)
    }

    @Test
    fun doublesAsksTheOpponentsServerBeforeTheirFirstServiceGame() {
        startOnWatch(discipline = Discipline.DOUBLES)
        assertTrue("Erste Wahl erfolgt live", live.serverChoice!!.prompt)
        points(Side.A)
        assertNull("Game 1 läuft", live.serverChoice)
        points(Side.A, Side.A, Side.A)
        val choice = live.serverChoice!!
        assertEquals(2, choice.game)
        assertEquals(Side.B, choice.side)
        assertTrue(choice.prompt)
        h.scheduler.advanceBy(ServeBarTimer.VISIBLE_MILLIS + 1)
        c.tick()
        c.dismissOverlay()
        assertNotNull("Aufschlag-Info bleibt nach längerer Auswahl erreichbar", live.serveBar)
        c.openServerChoice()
        assertEquals(LiveOverlay.SERVER_CHOICE, live.overlay)
        assertFalse("Auswahl offen: kein Punkt", c.point(Side.A))
        c.dismissOverlay() // „Später“
        assertNull(live.overlay)
        c.tick()
        assertNull("Später öffnet nicht erneut", live.overlay)
        c.openServerChoice()
        val opponent2 = choice.players.single { it.index == 1 }
        assertTrue(c.chooseServer(opponent2.id))
        assertNull(live.overlay)
        assertEquals(opponent2.id, live.serverChoice?.chosen)
        assertFalse(live.serverChoice!!.prompt)
        assertEquals("Gegner 2 schlägt Game 2 auf", opponent2.id, live.serve?.server?.id)
        points(Side.B)
        assertNull("Fenster zu", live.serverChoice)
    }

    @Test
    fun serverChoiceRejectsPlayersOutsideTheWindowAndClosesOnRoleChange() {
        startOnWatch(discipline = Discipline.DOUBLES)
        points(Side.A, Side.A, Side.A, Side.A)
        val us = live.teams.a.first()
        assertFalse("Game 2: nur das andere Team", c.chooseServer(us.id))
        c.openServerChoice()
        assertEquals(LiveOverlay.SERVER_CHOICE, live.overlay)
        h.settle()
        assertTrue(h.phone.takeOverScoring())
        h.settle()
        assertEquals(LiveRole.DISPLAY, live.role)
        assertNull(live.overlay)
        assertNull(live.serverChoice)
    }

    @Test
    fun liftedChoiceInTheFirstGameIsAskedAgain() {
        startOnWatch(discipline = Discipline.DOUBLES)
        val partner = live.teams.a[1]
        assertTrue(live.serverChoice!!.prompt)
        assertTrue(c.chooseServer(partner.id))
        assertEquals(partner.id, live.serverChoice?.chosen)
        // Korrektur der Reihenfolge hebt die Wahl auf (Engine, Review R1) → Hinweis erscheint wieder
        assertTrue(h.watch.correct(Correction.servingOrder(Side.A, listOf(live.teams.a[0].id, partner.id))))
        assertNull(live.serverChoice?.chosen)
        assertTrue("aufgehobene Wahl wird wieder erfragt", live.serverChoice!!.prompt)
        // neuer Controller (Prozessverlust/Activity neu): Zustand aus dem Event-Log, Hinweis bleibt
        val fresh = WatchController(h.watch, h.directory, h.prefs, h.clock::nowMs, h.scheduler::nowMs, h.scheduler::nowMs, { today })
        fresh.start()
        assertTrue(
            fresh.state.value.live!!
                .serverChoice!!
                .prompt,
        )
        fresh.dispose()
        assertTrue(c.chooseServer(partner.id))
        assertEquals(partner.id, live.serve?.server?.id)
        assertFalse(live.serverChoice!!.prompt)
    }

    @Test
    fun singlesHasNoServerChoice() {
        startOnWatch()
        assertNull(live.serverChoice)
        c.openServerChoice()
        assertNull(live.overlay)
    }

    // --- Satzende ---------------------------------------------------------------------------------

    @Test
    fun setEndOffersTiebreakSetsInTennis() {
        startOnWatch(format = FormatChoice.TENNIS_OPEN)
        repeat(6 * 4) { points(Side.A) }
        assertEquals(listOf(NextSetKind.REGULAR, NextSetKind.TIEBREAK_7, NextSetKind.MATCH_TIEBREAK_10), live.decision?.options)
        assertTrue(c.startNextSet(NextSetKind.TIEBREAK_7))
        assertNull(live.decision)
        assertEquals(SetKind.MATCH_TIEBREAK, match.state.current?.setKind)
        repeat(7) { points(Side.B) }
        val decision = live.decision!!
        assertEquals(2, decision.setNumber)
        assertEquals(Side.B, decision.winner)
        assertEquals(SidePair(0, 7), decision.score)
    }

    /** „{n}. Satz?“ zählt nach dem beendeten Satz – auch nachdem Undo den Satzball zurückgenommen hat. */
    @Test
    fun decisionNumbersFollowTheFinishedSetAlsoAfterUndo() {
        startOnWatch(format = FormatChoice.TENNIS_OPEN)
        repeat(6 * 4) { points(Side.A) }
        assertEquals("1. Satz beendet → „2. Satz?“", 1, live.decision?.setNumber)
        assertTrue(c.startNextSet(NextSetKind.REGULAR))
        repeat(6 * 4) { points(Side.B) }
        assertEquals(2, live.decision?.setNumber)
        assertTrue(c.undo()) // Satzball zurück: wieder im 2. Satz
        assertNull(live.decision)
        assertEquals(1, match.state.sets.size)
        points(Side.B)
        assertEquals("erneut 2. Satz beendet → „3. Satz?“", 2, live.decision?.setNumber)
    }

    @Test
    fun otherSportsOnlyOfferARegularSet() {
        startOnWatch(sport = Sport.TABLE_TENNIS, format = FormatChoice.TABLE_TENNIS_OPEN)
        repeat(11) { points(Side.A) }
        assertEquals(listOf(NextSetKind.REGULAR), live.decision?.options)
        assertFalse(c.startNextSet(NextSetKind.TIEBREAK_7))
        assertTrue(c.startNextSet(NextSetKind.REGULAR))
        assertEquals(MatchStatus.IN_PROGRESS, live.status)
    }

    // --- Setup ------------------------------------------------------------------------------------

    @Test
    fun setupOffersGamesPerSetAndServeStatsForTennis() {
        c.newMatch()
        c.selectSport(Sport.TENNIS)
        c.selectDiscipline(Discipline.SINGLES)
        c.selectFormat(FormatChoice.TENNIS_SHORT_SETS)
        var setup = c.state.value.screen as WatchScreen.Setup
        assertEquals(listOf(1, 2, 3, 4, 5, 6), setup.gamesOptions)
        assertEquals("Standard des Formats", 4, setup.gamesPerSet)
        assertTrue(setup.serveStatsAvailable)
        assertTrue(setup.serveStats)
        c.selectGamesPerSet(9) // nicht wählbar
        c.selectGamesPerSet(3)
        c.setServeStats(false)
        setup = c.state.value.screen as WatchScreen.Setup
        assertEquals(3, setup.gamesPerSet)
        assertFalse(setup.serveStats)
        // anderes Format → wieder dessen Standard
        c.back()
        c.selectFormat(FormatChoice.TENNIS_BO3)
        assertEquals(6, (c.state.value.screen as WatchScreen.Setup).gamesPerSet)
        c.selectGamesPerSet(1)
        assertTrue(c.selectServer(Side.A))
        assertEquals(1, match.format.set?.gamesToWin)
        assertEquals(1, match.format.set?.tiebreakAt)
        assertFalse(match.meta.serveStats)
    }

    @Test
    fun tiebreakOnlyHasNoGamesAndRepeatsWithTheSameFormat() {
        startOnWatch(format = FormatChoice.TENNIS_TIEBREAK_10)
        assertEquals("TENNIS_TIEBREAK_ONLY_10", match.format.presetId)
        assertEquals(SetKind.MATCH_TIEBREAK, match.state.current?.setKind)
        c.confirmEnd()
        c.close()
        val home = c.state.value.screen as WatchScreen.Home
        assertEquals(FormatChoice.TENNIS_TIEBREAK_10, home.last?.choice)
        assertNull(home.last?.gamesPerSet)
        c.newMatch()
        c.selectSport(Sport.PADEL)
        c.selectFormat(FormatChoice.PADEL_TIEBREAK_7)
        val setup = c.state.value.screen as WatchScreen.Setup
        assertEquals(emptyList<Int>(), setup.gamesOptions)
        assertNull(setup.gamesPerSet)
        assertTrue(setup.serveStatsAvailable)
    }

    @Test
    fun homeShowsChangedGamesOfTheLastMatch() {
        startOnWatch(games = 4)
        c.confirmEnd()
        c.close()
        val last = (c.state.value.screen as WatchScreen.Home).last!!
        assertEquals(FormatChoice.TENNIS_BO3, last.choice)
        assertEquals(4, last.gamesPerSet)
    }

    @Test
    fun serveStatsSwitchIsOnlyForTennisAndPadel() {
        c.newMatch()
        c.selectSport(Sport.PICKLEBALL)
        c.selectDiscipline(Discipline.DOUBLES)
        c.selectFormat(FormatChoice.PICKLEBALL_11)
        val setup = c.state.value.screen as WatchScreen.Setup
        assertFalse(setup.serveStatsAvailable)
        assertEquals(emptyList<Int>(), setup.gamesOptions)
        c.setServeStats(false)
        assertTrue((c.state.value.screen as WatchScreen.Setup).serveStats)
    }

    @Test
    fun noUpdateHintWhileVersionsMatch() {
        startOnWatch()
        h.settle()
        assertNull(live.updateRequired)
        c.confirmEnd()
        c.close()
        assertNull((c.state.value.screen as WatchScreen.Home).updateRequired)
    }
}
