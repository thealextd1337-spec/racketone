package eu.advantly.cloud.sync

import eu.advantly.core.model.TelemetryPreference
import eu.advantly.core.model.TelemetryVisibility
import kotlinx.serialization.Serializable

/**
 * Kontoweite Telemetrie-Sichtbarkeit (H0, F19/F29) über `cloud_set_telemetry_visibility_v1`
 * (supabase/migrations/20261027000300_telemetry_visibility_sync.sql). Der Server führt mit derselben Konfliktregel wie
 * [TelemetryPreference.merge] zusammen; Gesundheitsdaten selbst gehen nicht in die Cloud.
 */
interface CloudTelemetryPort {
    /**
     * Sendet den lokalen Stand (Standard = nur lesen) und liefert den Kontostand danach; `null`, solange das Konto den
     * Standard hält oder der Wert unbekannt ist. Gilt die Sitzung nicht mehr für [profileId], wird nichts gesendet.
     */
    suspend fun syncTelemetry(
        profileId: String,
        local: TelemetryPreference,
    ): TelemetryPreference?
}

/** Serverantwort `{visibility, counter, origin}`; counter 0 = Standard ohne origin. */
@Serializable
internal data class CloudTelemetrySetting(
    val visibility: String,
    val counter: Long,
    val origin: String? = null,
) {
    fun toPreference(): TelemetryPreference? {
        val value = TelemetryVisibility.fromWire(visibility) ?: return null
        val source = origin?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
        return if (counter in 1 until MAX_COUNTER) TelemetryPreference(value, counter, source) else null
    }

    companion object {
        /** Obergrenze wie `cloud_set_telemetry_visibility_v1` (2^53, verlustfrei in JSON). */
        const val MAX_COUNTER: Long = 1L shl 53
    }
}
