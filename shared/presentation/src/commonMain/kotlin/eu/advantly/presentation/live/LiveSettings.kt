package eu.advantly.presentation.live

import androidx.lifecycle.SavedStateHandle
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Einstellungen des Live-Screens (Outdoor-Kontrast, Bildschirm wach, Debug-Werkzeuge). Liegen im
 * [SavedStateHandle] des App-ViewModels und überstehen damit Rotation und Prozesstod.
 */
class LiveSettings(
    private val savedState: SavedStateHandle = SavedStateHandle(),
) {
    var outdoorContrast: Boolean by flag("live.outdoor", default = false)
    var keepScreenOn: Boolean by flag("live.keepScreenOn", default = true)

    /** Latenz-Overlay (nur Debug-Builds sichtbar, Spike S4). */
    var showLatencyOverlay: Boolean by flag("live.latencyOverlay", default = true)

    /** Optionale Entprellung 600 ms (04 §4.3.3). */
    var debounceEnabled: Boolean by flag("live.debounce", default = false)

    private fun flag(
        key: String,
        default: Boolean,
    ) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(
            thisRef: Any?,
            property: KProperty<*>,
        ): Boolean = savedState.get<Boolean>(key) ?: default

        override fun setValue(
            thisRef: Any?,
            property: KProperty<*>,
            value: Boolean,
        ) {
            savedState[key] = value
        }
    }
}
