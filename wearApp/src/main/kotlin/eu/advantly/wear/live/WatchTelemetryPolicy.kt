package eu.advantly.wear.live

import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.wear.workout.WorkoutUiState

/**
 * Wirkung der Telemetrie-Sichtbarkeit (P2-J, F19/F29) auf der Uhr – rein, ohne Android-Abhängigkeiten.
 *
 * - „Teilen“ und „Nur für mich“: wie bisher (Anzeige auf der Uhr, flüchtiger Versand an das eigene Handy). „Teilen“ wirkt
 *   erst mit Freunden/Live-Ansicht anders.
 * - „Gar nicht“: Messwerte (Puls, Schritte, Kalorien) werden aus dem Zustand entfernt und nicht mehr übernommen, die
 *   Live-Ansicht zeigt keine Telemetrie-Spalten, es wird nichts gesendet und vorhandene Abos werden verworfen. Die
 *   Exercise selbst darf weiterlaufen (Laufzeitanker bei Display aus); ihre Datentypen sind ab dem Start fest
 *   (Health Services erlaubt keinen Wechsel während der Exercise), die App verwirft die Werte im Callback.
 */
object WatchTelemetryPolicy {
    /** Zustand unter [visibility]; liefert dieselbe Instanz, wenn nichts zu ändern ist (keine unnötigen Emissionen). */
    fun apply(
        state: WorkoutUiState,
        visibility: TelemetryVisibility,
    ): WorkoutUiState =
        when {
            visibility.allowsLive -> if (state.telemetryAllowed) state else state.copy(telemetryAllowed = true)
            !state.telemetryAllowed && state.heartRateBpm == null && state.steps == null && state.caloriesKcal == null -> state
            else -> state.copy(telemetryAllowed = false, heartRateBpm = null, steps = null, caloriesKcal = null)
        }
}
