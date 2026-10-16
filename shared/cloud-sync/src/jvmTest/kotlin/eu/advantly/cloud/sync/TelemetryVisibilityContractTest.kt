package eu.advantly.cloud.sync

import eu.advantly.core.model.TelemetryVisibility
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * P2-J: Die lokal gespeicherte und zwischen den Geräten synchronisierte Telemetrie-Sichtbarkeit muss später 1:1 in
 * `profile_settings.telemetry_visibility` passen (Werte des CHECK-Constraints und Standard).
 */
class TelemetryVisibilityContractTest {
    private val migration =
        File(System.getProperty("user.dir"), "../../supabase/migrations/20261015000100_profile_freunde_geraete.sql").readText()

    @Test
    fun wireValuesAndDefaultMatchTheProfileSettingsColumn() {
        val column = assertNotNull(Regex("""telemetry_visibility\s+text[^\n]*""").find(migration)).value
        val default = assertNotNull(Regex("""default '([a-z]+)'""").find(column)).groupValues[1]
        val allowed = Regex("""'([a-z]+)'""").findAll(column.substringAfter(" in ")).map { it.groupValues[1] }.toSet()
        assertEquals(TelemetryVisibility.entries.map { it.wire }.toSet(), allowed)
        assertEquals(TelemetryVisibility.DEFAULT.wire, default)
    }
}
