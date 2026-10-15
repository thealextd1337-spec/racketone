package eu.advantly.presentation.match

import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.PendingQuestion
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ResultReason
import eu.advantly.core.scoring.SetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryMatchStoreTest {
    private val tennisBo3 = Presets.TENNIS_SINGLES_BO3.format()

    @Test
    fun pointsUndoAndRedoGoThroughTheEngine() {
        val store = testStore()
        store.start(store.setupFor(tennisBo3))
        store.play("AAB")
        assertEquals(
            "30",
            store.active.value!!
                .state.current!!
                .display.a,
        )
        assertEquals(
            "15",
            store.active.value!!
                .state.current!!
                .display.b,
        )

        assertTrue(store.undo())
        assertEquals(
            "0",
            store.active.value!!
                .state.current!!
                .display.b,
        )
        assertTrue(store.active.value!!.canRedo)
        assertTrue(store.undo())
        assertEquals(
            "15",
            store.active.value!!
                .state.current!!
                .display.a,
        )

        assertTrue(store.redo())
        assertEquals(
            "30",
            store.active.value!!
                .state.current!!
                .display.a,
        )
        assertEquals(
            "0",
            store.active.value!!
                .state.current!!
                .display.b,
        )
        assertTrue(store.redo())
        assertEquals(
            "15",
            store.active.value!!
                .state.current!!
                .display.b,
        )
        assertFalse(store.redo())

        store.undo()
        store.recordPoint(Side.A)
        assertFalse(store.active.value!!.canRedo, "neuer Punkt leert den Redo-Stapel")
    }

    @Test
    fun undoOnFreshMatchIsRejected() {
        val store = testStore()
        store.start(store.setupFor(tennisBo3))
        assertFalse(store.active.value!!.canUndo)
        assertFalse(store.undo())
    }

    @Test
    fun bestOfFinishesAutomaticallyAndLandsInHistory() {
        val store = testStore()
        val id = store.start(store.setupFor(Presets.PICKLEBALL_SINGLES_11_SIDEOUT.format()))
        // Side-Out, Einzel: A schlägt auf und macht 11 Punkte in Folge
        store.play("A".repeat(11))
        val snapshot = store.active.value!!
        assertEquals(MatchStatus.FINISHED, snapshot.state.status)
        assertEquals(Side.A, snapshot.state.result!!.winner)
        assertNotNull(snapshot.finishedAtMs)
        assertEquals(listOf(id), store.matches.value.map { it.matchId })
        assertFalse(store.recordPoint(Side.A), "Punkt nach Matchende wird abgelehnt")

        // Undo öffnet das Match wieder – es verschwindet aus dem Verlauf
        assertTrue(store.undo())
        assertEquals(
            MatchStatus.IN_PROGRESS,
            store.active.value!!
                .state.status,
        )
        assertTrue(store.matches.value.isEmpty())
    }

    @Test
    fun openModeAsksAfterEverySetAndOffersMatchTiebreak() {
        val store = testStore()
        store.start(store.setupFor(Presets.TENNIS_OPEN.format()))
        store.play("AAAA".repeat(6))
        val awaiting = store.active.value!!.state
        assertEquals(MatchStatus.AWAITING_DECISION, awaiting.status)
        assertEquals(PendingQuestion.ASK_ANOTHER_SET, awaiting.pending)
        assertFalse(store.recordPoint(Side.A), "Punkte während des Dialogs werden nicht geloggt")

        assertTrue(store.continueDecision(anotherSet = true, matchTiebreak = true))
        assertEquals(
            SetKind.MATCH_TIEBREAK,
            store.active.value!!
                .state.current!!
                .setKind,
        )
        assertTrue(store.undo(), "Undo führt zurück zur Satzende-Frage")
        assertEquals(
            MatchStatus.AWAITING_DECISION,
            store.active.value!!
                .state.status,
        )

        assertTrue(store.continueDecision(anotherSet = false))
        val finished = store.active.value!!.state
        assertEquals(MatchStatus.FINISHED, finished.status)
        assertEquals(Side.A, finished.result!!.winner)
        assertNull(store.createFollowUp(), "kein Folge-Match im Modus Offen")
    }

    @Test
    fun matchTiebreakIsRejectedWithoutConfiguredFormat() {
        val store = testStore()
        store.start(store.setupFor(Presets.TABLE_TENNIS_OPEN.format()))
        store.play("A".repeat(11))
        assertFalse(store.continueDecision(anotherSet = true, matchTiebreak = true))
        assertTrue(store.continueDecision(anotherSet = true))
    }

    @Test
    fun followUpIsLinkedOpenMatchWithSameLineup() {
        val store = testStore()
        val first = store.start(store.setupFor(Presets.BADMINTON_1GAME_15.format()))
        store.play("A".repeat(15))
        val finished = store.active.value!!
        assertEquals(ResultReason.COMPLETED, finished.state.result!!.reason)

        val follow = assertNotNull(store.createFollowUp())
        val active = store.active.value!!
        assertEquals(follow, active.matchId)
        assertEquals(first, active.setup.previousMatchId)
        assertEquals(MatchMode.OPEN, active.setup.format.match.mode)
        assertEquals(finished.setup.lineup.a, active.setup.lineup.a)
        assertEquals(finished.state.continuation!!.player, active.setup.lineup.firstServer)
        assertEquals(1, store.matches.value.size, "Vor-Match bleibt im Verlauf")
    }

    @Test
    fun retirementAndAbandonEndTheMatch() {
        val store = testStore()
        store.start(store.setupFor(tennisBo3))
        assertFalse(store.endMatch(EndReason.RETIRED, side = null), "Aufgabe braucht eine Side")
        store.play("AB")
        assertFalse(store.endMatch(EndReason.WALKOVER, Side.B), "Walkover nur ohne Punkte")
        assertTrue(store.endMatch(EndReason.RETIRED, Side.B))
        val state = store.active.value!!.state
        assertEquals(ResultReason.RETIRED, state.result!!.reason)
        assertEquals(Side.A, state.result!!.winner)
        assertNotNull(state.current, "Stand bleibt eingefroren")
        assertTrue(store.dismiss())
        assertNull(store.active.value)
        assertEquals(1, store.matches.value.size)
    }

    @Test
    fun startingANewMatchAbandonsTheRunningOne() {
        val store = testStore()
        val first = store.start(store.setupFor(tennisBo3))
        store.play("A")
        assertFalse(store.dismiss(), "laufendes Match lässt sich nicht schließen")
        store.start(store.setupFor(tennisBo3))
        assertEquals(
            ResultReason.ABANDONED,
            store
                .record(first)!!
                .finalState.result!!
                .reason,
        )
    }

    @Test
    fun serverCorrectionRetroactiveAndFromNow() {
        val store = testStore()
        val setup = store.setupFor(tennisBo3)
        store.start(setup)
        val opponent = setup.lineup.b.first()
        assertTrue(store.correctServer(opponent, retroactive = true))
        assertEquals(
            opponent,
            store.active.value!!
                .state.serve!!
                .player,
        )
        assertTrue(store.correctServer(setup.lineup.a.first(), retroactive = false))
        assertEquals(
            Side.A,
            store.active.value!!
                .state.serve!!
                .side,
        )
        assertFalse(store.correctServer(PlayerId("fremd"), retroactive = false))
    }

    @Test
    fun pauseAndResume() {
        val store = testStore()
        store.start(store.setupFor(tennisBo3))
        assertFalse(store.resume())
        assertTrue(store.pause())
        assertEquals(
            MatchStatus.PAUSED,
            store.active.value!!
                .state.status,
        )
        assertTrue(store.recordPoint(Side.A), "Punkt setzt automatisch fort")
        assertEquals(
            MatchStatus.IN_PROGRESS,
            store.active.value!!
                .state.status,
        )
    }

    @Test
    fun invalidSetupIsRejected() {
        val store = testStore()
        val setup = store.setupFor(tennisBo3)
        assertFailsWith<IllegalArgumentException> {
            store.start(setup.copy(lineup = setup.lineup.copy(b = setup.lineup.a)))
        }
        assertNull(store.active.value)
    }

    @Test
    fun playersPortRemembersGuestsLastSetupAndFavorites() {
        val store = testStore()
        assertNull(store.lastSetup.value)
        val setup = store.setupFor(Presets.PADEL_GOLDEN_BO3.format())
        store.start(setup)
        assertEquals(setup, store.lastSetup.value)
        assertEquals(3, store.guests.value.size)

        val favorite = store.saveFavorite(setup)
        assertEquals(listOf(favorite), store.favorites.value)
        store.removeFavorite(favorite.id)
        assertTrue(store.favorites.value.isEmpty())
        assertFailsWith<IllegalArgumentException> { store.addGuest("  ") }
    }

    @Test
    fun recordContainsGenesisAndAllEvents() {
        val store = testStore()
        val id = store.start(store.setupFor(tennisBo3))
        store.play("AB")
        store.undo()
        val record = assertNotNull(store.record(id))
        assertEquals(4, record.events.size)
        assertTrue(record.events.zipWithNext().all { (a, b) -> a.lamport < b.lamport })
    }
}
