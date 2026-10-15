package eu.advantly.presentation.live

import androidx.lifecycle.SavedStateHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wiederherstellung nach Prozesstod: neuer ViewModel mit den gesicherten SavedState-Werten. */
class LiveScoreViewModelTest {
    private fun SavedStateHandle.copy(): SavedStateHandle = SavedStateHandle(keys().associateWith { get<Any?>(it) })

    @Test
    fun scoreUndoHistoryAndSettingsSurviveRecreation() {
        val handle = SavedStateHandle()
        val first = LiveScoreViewModel(handle)
        first.demo(DemoScenario.Deuce)
        first.score(Team.Us)
        first.score(Team.Them)
        first.score(Team.Them)
        first.undo()
        first.outdoorContrast = true
        first.showLatencyOverlay = false

        val restored = LiveScoreViewModel(handle.copy())
        assertEquals(first.state, restored.state)
        assertTrue(restored.canUndo)
        assertTrue(restored.canRedo)
        assertTrue(restored.outdoorContrast)
        assertFalse(restored.showLatencyOverlay)
        assertTrue(restored.keepScreenOn)

        // Redo und weiteres Undo arbeiten auf der wiederhergestellten Historie
        assertEquals(PointLabel.Advantage, FakeTennis.pointLabel(restored.redo()!!.after, Team.Them))
        repeat(3) { restored.undo() }
        assertEquals(DemoScenario.Deuce.let { LiveScoreSession().apply { demo(it) }.state }, restored.state)
    }

    @Test
    fun freshHandleStartsEmpty() {
        val vm = LiveScoreViewModel(SavedStateHandle())
        assertEquals(FakeTennisState(), vm.state)
        assertFalse(vm.canUndo)
    }

    @Test
    fun corruptLogIsIgnoredGracefully() {
        val vm = LiveScoreViewModel(SavedStateHandle(mapOf("live.events" to "U;?;D:Nope;T", "live.redo" to "")))
        assertEquals(1, vm.state.pointsUs)
        assertEquals(1, vm.state.pointsThem)
    }
}
