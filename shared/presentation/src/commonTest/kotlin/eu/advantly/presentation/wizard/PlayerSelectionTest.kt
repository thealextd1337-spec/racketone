package eu.advantly.presentation.wizard

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.match.setupFor
import eu.advantly.presentation.match.testStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerSelectionTest {
    private val self = Player(PlayerId("self"), "Alex")
    private val a = Player(PlayerId("a"), "Robin")
    private val b = Player(PlayerId("b"), "Robin")
    private val c = Player(PlayerId("c"), "Sam")
    private val a0 = PlayerPlace(Side.A, 0)
    private val a1 = PlayerPlace(Side.A, 1)
    private val b0 = PlayerPlace(Side.B, 0)
    private val b1 = PlayerPlace(Side.B, 1)
    private val input = WizardInput(discipline = Discipline.DOUBLES, teamA = listOf(self, a), teamB = listOf(b, null), firstServer = a.id)

    @Test
    fun suggestionsPreferCreatedAndExcludeAssignedSelfAndDuplicateIdsButNotNames() {
        val unused = Player(PlayerId("unused"), "Kim")
        val empty = input.copy(teamA = listOf(self, null), teamB = listOf(null, null))
        assertEquals(listOf(c, a, b), PlayerSelection.suggestions(listOf(c, a, c), listOf(self, b, a), listOf(b, c, self), empty, self.id))
        assertEquals(
            listOf(c, a, b, unused),
            PlayerSelection.suggestions(listOf(c), listOf(a, b), listOf(b, unused, self, c), empty, self.id),
        )
        assertEquals(listOf(c), PlayerSelection.suggestions(listOf(a, c), listOf(b, c), listOf(a, b), input, self.id))
    }

    @Test
    fun moveAndSwapAcrossSidesPreservePlayersAndFirstServer() {
        val moved = PlayerSelection.exchange(input, a1, b1, self.id)
        assertNull(moved.teamA[1])
        assertEquals(a, moved.teamB[1])
        assertEquals(a.id, moved.firstServer)
        val swapped = PlayerSelection.exchange(input, a1, b0, self.id)
        assertEquals(b, swapped.teamA[1])
        assertEquals(a, swapped.teamB[0])
        assertEquals(input.players.map { it.id }.toSet(), swapped.players.map { it.id }.toSet())
        assertEquals(a.id, swapped.firstServer)
    }

    @Test
    fun invalidPlacesEmptySourcesAndSelfAcrossSidesAreNoOps() {
        for ((from, to) in listOf(a1 to a1, b1 to a1, a1 to PlayerPlace(Side.B, 5), PlayerPlace(Side.A, -1) to b0, a0 to b0, b0 to a0)) {
            assertEquals(input, PlayerSelection.exchange(input, from, to, self.id))
        }
        val singles = WizardInput(teamA = listOf(self), teamB = listOf(a))
        assertEquals(singles, PlayerSelection.exchange(singles, b0, a1, self.id))
        assertEquals(singles, PlayerSelection.exchange(singles, a0, b0, self.id))
    }

    @Test
    fun validTargetsExcludeSinglesDeadEndsEmptySourcesAndOpponentsMovingSelf() {
        val singles = WizardInput(teamA = listOf(self), teamB = listOf(a))
        assertTrue(PlayerSelection.validTargets(singles, a0, self.id).isEmpty())
        assertTrue(PlayerSelection.validTargets(singles, b0, self.id).isEmpty())
        assertTrue(PlayerSelection.validTargets(input, b1, self.id).isEmpty())
        assertEquals(listOf(a1), PlayerSelection.validTargets(input, a0, self.id))
        assertEquals(listOf(a1, b1), PlayerSelection.validTargets(input, b0, self.id))
    }

    @Test
    fun selfCanChangeOrderButCannotBeRemovedAndDuplicateAssignmentsAreRejected() {
        val reordered = PlayerSelection.exchange(input, a0, a1, self.id)
        assertEquals(self, reordered.teamA[1])
        assertEquals(reordered, PlayerSelection.remove(reordered, a1, self.id))
        assertEquals(input, PlayerSelection.assign(input, b1, a, self.id))
        assertEquals(input, PlayerSelection.assign(input, b0, c, self.id))
        assertEquals(input, PlayerSelection.assign(input, b1, self, self.id))
        assertEquals(c, PlayerSelection.assign(input, b1, c, self.id).teamB[1])
        assertNull(PlayerSelection.remove(input, a1, self.id).firstServer)
    }

    @Test
    fun wizardTargetsExactPlaceRetainsCreatedSuggestionsAndResetsTheirLifetime() {
        val store = testStore()
        val wizard = MatchWizard(store)
        wizard.selectSport(Sport.PADEL)
        assertNull(wizard.addGuestAt("  ", a1))
        val added = assertNotNull(wizard.addGuestAt("  Robin  ", a1))
        assertEquals("Robin", added.displayName)
        assertNull(wizard.state.value.input.teamB[0])
        wizard.clearSlot(Side.A, 1)
        assertEquals(listOf(added), wizard.playerSuggestions())
        wizard.assignPlayer(b1, added)
        assertTrue(wizard.playerSuggestions().isEmpty())
        assertFalse(wizard.state.value.canContinue)
        wizard.reset()
        wizard.selectSport(Sport.PADEL)
        assertEquals(listOf(added), wizard.playerSuggestions(), "Unbenutzte Gäste bleiben nach einem Neustart des Assistenten erreichbar")
    }

    @Test
    fun shrinkingAfterSelfReorderKeepsSelfAndLineupValidation() {
        val store = testStore()
        val wizard = MatchWizard(store)
        wizard.selectSport(Sport.PICKLEBALL)
        wizard.addGuest("Robin")
        wizard.addGuest("Sam")
        wizard.exchangePlayers(a0, a1)
        wizard.setDiscipline(Discipline.SINGLES)
        assertEquals(
            store.self.value,
            wizard.state.value.input.teamA
                .single(),
        )
        assertTrue(wizard.state.value.canContinue)
        assertNotNull(wizard.state.value.setup)
    }

    @Test
    fun recentPlayersPreferSportThenRecencyAndIgnoreUnplayedProfiles() {
        val store = testStore()
        val tennis = store.setupFor(Presets.TENNIS_SINGLES_BO3.format())
        store.start(tennis)
        store.endMatch(EndReason.STOPPED)
        store.dismiss()
        val badminton = store.setupFor(Presets.BADMINTON_1GAME_15.format())
        store.start(badminton)
        store.endMatch(EndReason.STOPPED)
        store.dismiss()
        val unused = store.addGuest("Unbenutzt")
        val tennisGuest = tennis.players.last()
        val badmintonGuest = badminton.players.last()
        assertEquals(listOf(tennisGuest, badmintonGuest), store.recentPlayers(Sport.TENNIS))
        assertEquals(listOf(badmintonGuest, tennisGuest), store.recentPlayers(Sport.BADMINTON))
        val wizard = MatchWizard(store)
        wizard.selectSport(Sport.TENNIS)
        assertEquals(listOf(tennisGuest, badmintonGuest, unused), wizard.playerSuggestions())
        wizard.assignPlayer(b0, tennisGuest)
        assertEquals(listOf(badmintonGuest, unused), wizard.playerSuggestions())
    }
}
