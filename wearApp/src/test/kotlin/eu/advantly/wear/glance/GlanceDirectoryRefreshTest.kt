package eu.advantly.wear.glance

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Presets
import eu.advantly.wear.WatchHarness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlanceDirectoryRefreshTest {
    private val h = WatchHarness()
    private val refresh = GlanceDirectoryRefresh()

    @Test
    fun unrelatedDataRevisionsAndSyncCallbacksDoNotReloadDirectory() {
        val state = h.watch.state.value
        assertTrue(refresh.shouldRefresh(state))
        repeat(10) { assertFalse(refresh.shouldRefresh(state.copy(dataRevision = it.toLong() + 1))) }
        assertFalse(refresh.shouldRefresh(state))
    }

    @Test
    fun directoryInvalidationIsLazyAndCoalescesUntilSessionPublishesRevision() {
        val state = h.watch.state.value
        assertTrue(refresh.shouldRefresh(state))
        val remove = h.watchData.observeDirectoryChanges(refresh::invalidate)
        h.watchData.players.addGuest(PlayerId("bea"), "Bea", 1)
        assertFalse(refresh.shouldRefresh(state))
        assertTrue(refresh.shouldRefresh(state.copy(dataRevision = state.dataRevision + 1)))
        assertFalse(refresh.shouldRefresh(state.copy(dataRevision = state.dataRevision + 2)))
        remove()
    }

    @Test
    fun scoreChangesUseSessionStateAndMatchSwitchReloadsDirectory() {
        assertTrue(refresh.shouldRefresh(h.watch.state.value))
        val me = PlayerId("me")
        val other = PlayerId("other")
        h.watch.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(me), listOf(other), me), MatchMeta())
        assertTrue(refresh.shouldRefresh(h.watch.state.value))
        h.watch.recordPoint(Side.A)
        assertFalse(refresh.shouldRefresh(h.watch.state.value))
        h.watch.closeMatch()
        assertTrue(refresh.shouldRefresh(h.watch.state.value))
    }

    @Test
    fun directorySubscriptionCanBeRemoved() {
        var changed = false
        val remove = h.watchData.observeDirectoryChanges { changed = true }
        remove()
        h.watchData.players.addGuest(PlayerId("bea"), "Bea", 1)
        assertFalse(changed)
    }

    @Test
    fun favoritesAndRepeatedRemoteMetadataDoNotInvalidateDirectory() {
        val id = PlayerId("bea")
        val guest =
            h.watchData.players
                .addGuest(id, "Bea", 1)
                .toPlayer()
        val state = h.watch.state.value
        assertTrue(refresh.shouldRefresh(state))
        val remove = h.watchData.observeDirectoryChanges(refresh::invalidate)
        h.watchData.players.upsertRemote(guest, 2)
        h.watchData.settings.put("players.recentRemote", id.value)
        h.watchData.favorites.recordUse(Sport.TENNIS, Discipline.SINGLES, listOf(PlayerId("me")), listOf(id), 2)
        assertFalse(refresh.shouldRefresh(state.copy(dataRevision = state.dataRevision + 1)))
        remove()
    }

    @Test
    fun renamedPlayersAndNewSelfAliasesInvalidateDirectory() {
        val id = PlayerId("bea")
        h.watchData.players.addGuest(id, "Bea", 1)
        val state = h.watch.state.value
        assertTrue(refresh.shouldRefresh(state))
        val remove = h.watchData.observeDirectoryChanges(refresh::invalidate)
        h.watchData.players.rename(id, "Beatrice")
        assertTrue(refresh.shouldRefresh(state.copy(dataRevision = state.dataRevision + 1)))
        h.watchData.addSelfAliases(listOf(id))
        assertTrue(refresh.shouldRefresh(state.copy(dataRevision = state.dataRevision + 2)))
        assertFalse(h.watchData.addSelfAliases(listOf(id)))
        assertFalse(refresh.shouldRefresh(state.copy(dataRevision = state.dataRevision + 3)))
        remove()
    }
}
