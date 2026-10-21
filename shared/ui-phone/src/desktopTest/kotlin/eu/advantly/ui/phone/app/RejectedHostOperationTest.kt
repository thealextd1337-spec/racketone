package eu.advantly.ui.phone.app

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Surface
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.match.LiveMatchPort
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.match.PlayersPort
import eu.advantly.presentation.match.StartOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RejectedHostOperationTest {
    private fun setup(store: PlayersPort): MatchSetup {
        val self = store.self.value
        val other = Player(PlayerId("other"), "Other")
        return MatchSetup(
            Presets.TENNIS_SINGLES_BO3.format(),
            Lineup(listOf(self.id), listOf(other.id), self.id),
            players = listOf(self, other),
        )
    }

    @Test
    fun rejectedStartDoesNotSyncOrNavigate() {
        val raw = InMemoryMatchStore()
        var attempts = 0
        val port =
            object : LiveMatchPort by raw {
                override fun tryStart(
                    setup: MatchSetup,
                    options: StartOptions,
                ): MatchId? {
                    attempts++
                    return null
                }
            }
        val model = AdvantlyAppModel(port, raw, raw)
        model.navigator.push(Overlay.Wizard)
        model.startMatch(setup(raw), StartOptions())
        assertEquals(1, attempts)
        assertEquals(Overlay.Wizard, model.navigator.top)
        assertNull(model.controller.snapshot)
        assertNull(raw.active.value)
        assertFalse(model.startBenchmarkMatch())
        assertEquals(2, attempts)
        assertEquals(Overlay.Wizard, model.navigator.top)
    }

    @Test
    fun rejectedGuestOrFavoriteDoesNotStartAnotherOperation() {
        val raw = InMemoryMatchStore()
        var starts = 0
        val live =
            object : LiveMatchPort by raw {
                override fun tryStart(
                    setup: MatchSetup,
                    options: StartOptions,
                ): MatchId? {
                    starts++
                    return null
                }
            }
        val players =
            object : PlayersPort by raw {
                override fun tryAddGuest(name: String): Player? = null

                override fun trySaveFavorite(setup: MatchSetup): eu.advantly.presentation.match.FavoriteLineup? = null
            }
        val model = AdvantlyAppModel(live, raw, players)
        assertFalse(model.startBenchmarkMatch())
        assertEquals(0, starts)
        assertTrue(raw.guests.value.isEmpty())
        model.onPlay()
        model.wizard.selectSport(Sport.TENNIS)
        model.wizard.selectSurface(Surface.CLAY)
        model.wizard.pickPlayer(raw.addGuest("Other"))
        model.wizard.setSaveAsFavorite(true)
        model.finishWizard()
        assertEquals(0, starts)
        assertEquals(Overlay.Wizard, model.navigator.top)
        assertNull(raw.active.value)
    }
}
