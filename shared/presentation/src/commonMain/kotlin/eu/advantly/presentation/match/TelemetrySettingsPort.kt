package eu.advantly.presentation.match

import eu.advantly.core.model.TelemetryVisibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Telemetrie-Sichtbarkeit für Puls und Schritte (P2-J, F19/F29): „Teilen“, „Nur für mich“ (Standard) oder „Gar nicht“.
 * Wirkt auf Uhr und Handy; die App-Implementierung speichert über die Match-Session und gleicht mit der eigenen Uhr ab.
 * Nur vom UI-Thread benutzen.
 */
interface TelemetrySettingsPort {
    val telemetryVisibility: StateFlow<TelemetryVisibility>

    fun setTelemetryVisibility(visibility: TelemetryVisibility)
}

/** Nur im Speicher (Tests, Previews). */
class InMemoryTelemetrySettings(
    initial: TelemetryVisibility = TelemetryVisibility.DEFAULT,
) : TelemetrySettingsPort {
    private val mutable = MutableStateFlow(initial)
    override val telemetryVisibility: StateFlow<TelemetryVisibility> = mutable.asStateFlow()

    override fun setTelemetryVisibility(visibility: TelemetryVisibility) {
        mutable.value = visibility
    }
}
