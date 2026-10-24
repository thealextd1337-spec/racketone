package eu.advantly.wear.match

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Presets
import eu.advantly.wear.WatchHarness
import eu.advantly.wear.live.WatchScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerChoiceTest {
    /** Redigierte Personen (leerer Name) erscheinen weder als Vorschlag noch mit leerem Namen im Live-Bildschirm. */
    @Test
    fun blankNamesAreNeitherSuggestedNorShown() {
        val h = WatchHarness()
        val bea =
            h.watchData.players
                .addGuest(PlayerId("bea"), "Bea", 1)
                .id
        val caro =
            h.watchData.players
                .addGuest(PlayerId("caro"), "Caro", 1)
                .id
        val self = h.directory.self()
        val slot = Placeholders.id("opponent")
        h.watch.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(self), listOf(slot), self))
        assertTrue(
            h.watch.assignPlayer(
                h.watch.state.value.match!!
                    .id,
                slot,
                bea,
            ),
        )
        h.settle()
        h.watchData.settings.put("players.recentRemote", "bea,caro")

        fun opponent() =
            (h.controller.state.value.screen as WatchScreen.Live)
                .live.teams.b
                .single()
                .name
        assertEquals("Bea", opponent())
        assertEquals(listOf(bea, caro), h.directory.recentPlayers().map { it.id })

        h.watchData.players.rename(bea, "")
        h.watch.recordPoint(Side.A)
        h.settle()
        assertNull(opponent())
        assertEquals(listOf(caro), h.directory.recentPlayers().map { it.id })
    }

    @Test
    fun phoneDirectorySuppliesNamesAndUnselectedPlacesStayNeutral() {
        val h = WatchHarness()
        val owner =
            h.phoneData.players
                .ensureSelf("Alex", 1) { PlayerId("owner") }
                .id
        val bea =
            h.phoneData.players
                .addGuest(PlayerId("bea"), "Bea", 1)
                .id
        h.phone.create(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(owner), listOf(bea), owner))
        repeat(15) { h.phone.recordPoint(Side.A) }
        h.settle()
        h.phone.syncNow()
        h.settle()
        assertEquals(listOf("Bea"), h.directory.recentPlayers().map { it.displayName })
        val c = h.controller
        c.close()
        c.newMatch()
        c.selectSport(Sport.TENNIS)
        c.selectDiscipline(Discipline.DOUBLES)
        c.selectFormat(FormatChoice.TENNIS_BO3)
        c.selectPlayer(PlayerSlot.OPPONENT_1, bea)
        c.selectPlayer(PlayerSlot.PARTNER, bea)
        assertEquals(mapOf(PlayerSlot.OPPONENT_1 to bea), (c.state.value.screen as WatchScreen.Setup).selectedPlayers)
        assertTrue(c.selectServer(Side.A))
        val match = h.watch.state.value.match!!
        assertEquals(bea, match.lineup.b[0])
        assertTrue(h.watchData.isPlaceholder(match.lineup.a[1]))
        assertTrue(h.watchData.isPlaceholder(match.lineup.b[1]))
        assertFalse(
            h.watchData.players
                .all()
                .any { it.displayName.startsWith("Opponent") },
        )
        h.settle()
        assertFalse(
            h.phoneData.players
                .all()
                .any { h.phoneData.isPlaceholder(it.id) },
        )
        val repeated = h.directory.lastSetup()!!.lineup
        assertEquals(match.lineup.a[0], repeated.a[0])
        assertEquals(bea, repeated.b[0])
        assertTrue(h.watchData.isPlaceholder(repeated.a[1]))
        assertTrue(h.watchData.isPlaceholder(repeated.b[1]))
        assertFalse(repeated.a[1] in match.lineup.a)
        assertFalse(repeated.b[1] in match.lineup.b)
        c.close()
        c.newMatch()
        c.selectSport(Sport.TENNIS)
        c.selectDiscipline(Discipline.DOUBLES)
        c.selectFormat(FormatChoice.TENNIS_BO3)
        c.selectPlayer(PlayerSlot.PARTNER, bea)
        c.selectDiscipline(Discipline.SINGLES)
        c.selectFormat(FormatChoice.TENNIS_BO3)
        c.selectPlayer(PlayerSlot.OPPONENT_1, bea)
        assertEquals(mapOf(PlayerSlot.OPPONENT_1 to bea), (c.state.value.screen as WatchScreen.Setup).selectedPlayers)
    }
}
