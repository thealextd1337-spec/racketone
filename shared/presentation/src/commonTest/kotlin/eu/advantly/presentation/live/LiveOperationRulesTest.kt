package eu.advantly.presentation.live

import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.presentation.match.LiveRole
import eu.advantly.presentation.match.play
import eu.advantly.presentation.match.setupFor
import eu.advantly.presentation.match.testStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveOperationRulesTest {
    @Test
    fun removeAvailabilityComesFromEachSideAndIsScorerOnly() {
        val store = testStore()
        store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        assertFalse(LiveOperationRules.canRemove(store.active.value!!, Side.A))
        store.play("A")
        val snapshot = store.active.value!!
        assertTrue(LiveOperationRules.canRemove(snapshot, Side.A))
        assertFalse(LiveOperationRules.canRemove(snapshot, Side.B))
        assertFalse(LiveOperationRules.canRemove(snapshot.copy(role = LiveRole.DISPLAY), Side.A))
    }

    @Test
    fun serveOutcomesFollowTheLastPointContractAndOption() {
        val store = testStore()
        val setup = store.setupFor(Presets.TENNIS_SINGLES_BO3.format())
        store.start(setup)
        assertTrue(LiveOperationRules.serveOptions(store.active.value!!).isEmpty())
        store.play("A")
        assertEquals(
            listOf(ServeOutcome.FIRST_SERVE_IN, ServeOutcome.SECOND_SERVE_IN),
            LiveOperationRules.serveOptions(store.active.value!!),
        )
        store.play("B")
        val snapshot = store.active.value!!
        assertEquals(ServeOutcome.entries.toList(), LiveOperationRules.serveOptions(snapshot))
        assertTrue(LiveOperationRules.serveOptions(snapshot.copy(role = LiveRole.DISPLAY)).isEmpty())
        store.start(setup.copy(meta = MatchMeta(serveStats = false)))
        store.play("B")
        assertTrue(LiveOperationRules.serveOptions(store.active.value!!).isEmpty())
        store.start(store.setupFor(Presets.BADMINTON_1GAME_21.format()))
        store.play("A")
        assertTrue(LiveOperationRules.serveOptions(store.active.value!!).isEmpty())
    }

    @Test
    fun tiebreakColumnsUsePointsAndBracketsMarker() {
        val store = testStore()
        store.start(store.setupFor(Presets.TENNIS_TIEBREAK_ONLY_7.format()))
        store.play("AB")
        assertTrue(LiveScoreRules.setColumns(store.active.value!!.state).single().isTiebreakSet)
        assertEquals(
            1,
            LiveScoreRules
                .setColumns(store.active.value!!.state)
                .single()
                .values.a,
        )
        assertEquals(
            1,
            LiveScoreRules
                .setColumns(store.active.value!!.state)
                .single()
                .values.b,
        )
        store.play("AAAAAA")
        val column = LiveScoreRules.setColumns(store.active.value!!.state).single()
        assertTrue(column.isTiebreakSet)
        assertEquals(7, column.values.a)
        assertEquals(1, column.values.b)
    }
}
