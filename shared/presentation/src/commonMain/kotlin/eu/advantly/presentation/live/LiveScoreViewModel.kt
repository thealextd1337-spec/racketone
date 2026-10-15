package eu.advantly.presentation.live

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/**
 * ViewModel des Live-Scores (Spike S4). Hält die [LiveScoreSession] über Konfigurationswechsel (Rotation) und
 * sichert Ereignis-Log, Redo-Stapel und Live-Einstellungen im [SavedStateHandle] – damit überstehen Score und
 * Undo-Historie auch den Prozesstod (Android). Nur vom UI-Thread benutzen.
 */
class LiveScoreViewModel(
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val session =
        LiveScoreSession().apply {
            val events = savedState.get<String>(KEY_EVENTS)
            if (events != null) restore(LiveScoreSession.Log(events, savedState.get<String>(KEY_REDO).orEmpty()))
        }

    val state: FakeTennisState get() = session.state
    val canUndo: Boolean get() = session.canUndo
    val canRedo: Boolean get() = session.canRedo

    var outdoorContrast: Boolean by flag(KEY_OUTDOOR, default = false)
    var keepScreenOn: Boolean by flag(KEY_KEEP_SCREEN_ON, default = true)
    var showLatencyOverlay: Boolean by flag(KEY_OVERLAY, default = true)
    var debounceEnabled: Boolean by flag(KEY_DEBOUNCE, default = false)

    fun score(team: Team): ScoreChange? = session.score(team).also { persist() }

    fun undo(): ScoreChange? = session.undo().also { persist() }

    fun redo(): ScoreChange? = session.redo().also { persist() }

    fun demo(scenario: DemoScenario): ScoreChange = session.demo(scenario).also { persist() }

    fun reset() {
        session.reset()
        persist()
    }

    private fun persist() {
        val log = session.exportLog()
        savedState[KEY_EVENTS] = log.events
        savedState[KEY_REDO] = log.redo
    }

    private fun flag(
        key: String,
        default: Boolean,
    ) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(
            thisRef: Any?,
            property: kotlin.reflect.KProperty<*>,
        ): Boolean = savedState.get<Boolean>(key) ?: default

        override fun setValue(
            thisRef: Any?,
            property: kotlin.reflect.KProperty<*>,
            value: Boolean,
        ) {
            savedState[key] = value
        }
    }

    private companion object {
        const val KEY_EVENTS = "live.events"
        const val KEY_REDO = "live.redo"
        const val KEY_OUTDOOR = "live.outdoor"
        const val KEY_KEEP_SCREEN_ON = "live.keepScreenOn"
        const val KEY_OVERLAY = "live.latencyOverlay"
        const val KEY_DEBOUNCE = "live.debounce"
    }
}
