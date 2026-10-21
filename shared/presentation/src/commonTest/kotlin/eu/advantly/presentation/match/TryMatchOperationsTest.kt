package eu.advantly.presentation.match

import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Surface
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.presentation.wizard.PlayerPlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TryMatchOperationsTest {
    @Test
    fun defaultsDelegateExactlyOneHealthyOperation() {
        val store = testStore()
        val guest = assertNotNull(store.tryAddGuest("Guest"))
        assertEquals(listOf(guest), store.guests.value)
        val setup = store.setupFor(Presets.TENNIS_SINGLES_BO3.format())
        val favorite = assertNotNull(store.trySaveFavorite(setup))
        assertEquals(listOf(favorite), store.favorites.value)
        val id = assertNotNull(store.tryStart(setup))
        assertEquals(id, store.active.value?.matchId)
        assertEquals(1, store.openMatches.value.size)
        assertFailsWith<IllegalArgumentException> { store.tryAddGuest(" ") }
    }

    @Test
    fun rejectedGuestDoesNotMutateWizardSlots() {
        val raw = testStore()
        val players =
            object : PlayersPort by raw {
                override fun tryAddGuest(name: String): eu.advantly.core.model.Player? = null
            }
        val wizard = MatchWizard(players)
        wizard.selectSport(Sport.TENNIS)
        wizard.selectSurface(Surface.CLAY)
        val before = wizard.state.value.input
        assertNull(wizard.addGuest("Guest"))
        assertNull(wizard.addGuestAt("Guest", PlayerPlace(Side.B, 0)))
        assertEquals(before, wizard.state.value.input)
        assertTrue(raw.guests.value.isEmpty())
    }

    @Test
    fun requestedFavoriteFailureAbortsFinish() {
        val raw = testStore()
        var attempts = 0
        val players =
            object : PlayersPort by raw {
                override fun trySaveFavorite(setup: MatchSetup): FavoriteLineup? {
                    attempts++
                    return null
                }
            }
        val wizard = MatchWizard(players)
        wizard.selectSport(Sport.TENNIS)
        wizard.selectSurface(Surface.CLAY)
        wizard.pickPlayer(raw.addGuest("Opponent"))
        wizard.setSaveAsFavorite(true)
        assertNotNull(wizard.state.value.setup)
        assertNull(wizard.finish())
        assertEquals(1, attempts)
        assertTrue(raw.favorites.value.isEmpty())
    }
}
