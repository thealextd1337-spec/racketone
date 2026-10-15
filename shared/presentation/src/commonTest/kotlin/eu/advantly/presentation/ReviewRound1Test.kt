package eu.advantly.presentation

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.live.LiveScoreRules
import eu.advantly.presentation.live.PointLabel
import eu.advantly.presentation.live.ScoreHint
import eu.advantly.presentation.match.CountingIds
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.match.LiveRole
import eu.advantly.presentation.match.LocalDay
import eu.advantly.presentation.match.ScorerOption
import eu.advantly.presentation.match.StartOptions
import eu.advantly.presentation.match.StepClock
import eu.advantly.presentation.match.SyncInfo
import eu.advantly.presentation.match.play
import eu.advantly.presentation.match.setupFor
import eu.advantly.presentation.match.testStore
import eu.advantly.presentation.stats.MatchStatistics
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.presentation.wizard.SetsChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review-Runde 1 (PR #14): Walkover/Ballwechsel, eingefrorener Stand, TT-Doppel-Hinweise, Nettodauer, Badminton 11. */
class ReviewRound1Test {
    @Test
    fun sideOutRallyCountsAsPlayedSoWalkoverIsRejected() {
        val store = testStore()
        store.start(store.setupFor(Presets.PICKLEBALL_SINGLES_11_SIDEOUT.format()))
        // A schlägt auf, B gewinnt den Ballwechsel → Side-Out, Stand bleibt 0:0
        store.play("B")
        val snapshot = store.active.value!!
        assertEquals(SidePair(0, 0), snapshot.state.current!!.points)
        assertEquals(1, snapshot.pointsPlayed)
        assertFalse(store.endMatch(EndReason.WALKOVER, Side.B))
        assertTrue(store.endMatch(EndReason.RETIRED, Side.B))
    }

    @Test
    fun undonePointsDoNotCountAsPlayed() {
        val store = testStore()
        store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        store.play("A")
        store.undo()
        assertEquals(0, store.active.value!!.pointsPlayed)
        assertTrue(store.endMatch(EndReason.WALKOVER, Side.B))
    }

    @Test
    fun retirementKeepsTheFrozenSetInLiveAndSummary() {
        val store = testStore()
        store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        // 6:4, dann 2:1 und 30:0
        store.play("AAAABBBB".repeat(4) + "AAAA".repeat(2) + "AAAABBBB" + "AAAA" + "AA")
        assertTrue(store.endMatch(EndReason.RETIRED, Side.B))
        val state = store.active.value!!.state
        assertEquals(MatchStatus.FINISHED, state.status)
        assertEquals(PointLabel.Number("30"), LiveScoreRules.pointLabel(state, Side.A))
        val columns = LiveScoreRules.setColumns(state)
        assertEquals(listOf(SidePair(6, 4), SidePair(2, 1)), columns.map { it.values })
        val summary = store.matches.value.single()
        val frozen = assertNotNull(summary.interruptedSet)
        assertEquals(SidePair(2, 1), frozen.games)
        assertEquals(SidePair("30", "0"), frozen.display)
    }

    @Test
    fun regularFinishHasNoInterruptedSet() {
        val store = testStore()
        store.start(store.setupFor(Presets.BADMINTON_1GAME_15.format()))
        store.play("A".repeat(15))
        assertNull(
            store.matches.value
                .single()
                .interruptedSet,
        )
        assertEquals(PointLabel.Number("1"), LiveScoreRules.pointLabel(store.active.value!!.state, Side.A))
    }

    @Test
    fun tableTennisDoublesDecidingGameShowsChangeEndsAndReceiveSwap() {
        val store = testStore()
        store.start(store.setupFor(Presets.TABLE_TENNIS_DOUBLES_BO3.format(Discipline.DOUBLES)))
        store.play("A".repeat(11) + "B".repeat(11) + "A".repeat(5))
        val hint = LiveScoreRules.hint(store.active.value!!)
        assertEquals(ScoreHint(ScoreHint.Kind.ChangeEnds, also = ScoreHint.Kind.ReceiveOrderSwapped), hint)
    }

    @Test
    fun netDurationEndsPauseWithImplicitResumeAndOpenPause() {
        // Uhr ohne Selbst-Vorlauf: jede Zeit wird explizit gesetzt (Beispiel aus dem Review: 0 · 10 · 70 · 100 → 40 s)
        val clock = StepClock(now = 0, stepMs = 0)
        val store = InMemoryMatchStore(clock, CountingIds())
        val match = store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        clock.now = 10_000
        assertTrue(store.pause())
        clock.now = 70_000
        assertTrue(store.recordPoint(Side.A), "Punkt setzt die Pause implizit fort")
        clock.now = 100_000
        assertTrue(store.recordPoint(Side.A))
        assertEquals(40_000, MatchStatistics.compute(store.record(match)!!).netDurationMs)

        // Offene Pause bis zum Ende zählt nicht
        clock.now = 130_000
        assertTrue(store.pause())
        clock.now = 160_000
        assertTrue(store.endMatch(EndReason.ABANDONED))
        assertEquals(40_000 + 30_000, MatchStatistics.compute(store.record(match)!!).netDurationMs)
    }

    @Test
    fun badmintonElevenPointsStaysBestOfFive() {
        val wizard = MatchWizard(testStore(), today = { LocalDay(2026, 10, 15) })
        wizard.selectSport(Sport.BADMINTON)
        wizard.setPoints(11)
        assertEquals(listOf<SetsChoice>(SetsChoice.BestOf(5)), wizard.state.value.options.sets)
        wizard.setSets(SetsChoice.BestOf(3))
        assertEquals(21, wizard.state.value.input.pointsToWin, "andere Satzanzahl → Punkte nach Datum")
        assertEquals(SetsChoice.BestOf(3), wizard.state.value.input.sets)
        assertTrue(
            wizard.state.value.format!!
                .rally!!
                .winBy == 2,
        )
    }

    @Test
    fun inMemoryPortAlwaysScoresLocally() {
        val store = testStore()
        store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()), StartOptions(ScorerOption.MY_WATCH))
        store.play("AB")
        val snapshot = store.active.value!!
        assertEquals(SyncInfo.LOCAL_ONLY, snapshot.sync)
        assertEquals(LiveRole.SCORER, snapshot.role)
        assertEquals(0, snapshot.rejectedByRoleCount)
        assertEquals(0, snapshot.absorbedTaps)
        assertFalse(store.takeOverScoring(), "ein Gerät zählt immer selbst")
        assertFalse(store.watchAvailable.value)
    }

    @Test
    fun inMemoryRenamesOnlyGuests() {
        val store = testStore()
        store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        val guest = store.guests.value.first()
        assertFalse(store.canRename(store.self.value.id))
        assertFalse(store.renameGuest(guest.id, "  "))
        assertTrue(store.renameGuest(guest.id, " Jo "))
        assertEquals(
            "Jo",
            store.guests.value
                .first()
                .displayName,
        )
        assertEquals(
            "Jo",
            store.active.value!!
                .setup
                .player(guest.id)!!
                .displayName,
        )
    }

    @Test
    fun pauseAndResumeKeepRedoButPointsClearIt() {
        val store = testStore()
        store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        store.play("AA")
        store.undo()
        assertTrue(store.pause())
        assertTrue(store.resume())
        assertTrue(store.active.value!!.canRedo, "Pause/Fortsetzen leeren Redo nicht")
        store.play("B")
        assertFalse(store.active.value!!.canRedo)
    }

    @Test
    fun deletingTheActiveFinishedMatchClosesItFirst() {
        val store = testStore()
        val id = store.start(store.setupFor(Presets.BADMINTON_1GAME_15.format()))
        store.play("A".repeat(15))
        assertTrue(store.delete(id))
        assertNull(store.active.value)
        assertTrue(store.matches.value.isEmpty())
    }
}
