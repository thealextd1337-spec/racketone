package eu.advantly.cloud.sync

import eu.advantly.core.scoring.EventPayload
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vertrag Event-Typen Kotlin ↔ Supabase: Jeder `EventPayload`-Typ steht im CHECK `match_events_type_check` der
 * jüngsten Migration, die ihn setzt – sonst lehnt `sync_push` ihn als `invalid` ab (z. B. `ResultCorrected`, 0.3.0).
 */
class EventTypeContractTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun everyPayloadTypeIsAllowedByTheServer() {
        val kotlinTypes =
            EventPayload
                .serializer()
                .descriptor
                .getElementDescriptor(1)
                .elementNames
                .toSet()
        val migration =
            File(System.getProperty("user.dir"), "../../supabase/migrations")
                .listFiles { f -> f.name.endsWith(".sql") && "match_events_type_check" in f.readText() }!!
                .maxBy { it.name }
        val check = migration.readText().substringAfter("add constraint match_events_type_check").substringBefore(";")
        val serverTypes = Regex("'([A-Za-z]+)'").findAll(check).map { it.groupValues[1] }.toSet()
        assertEquals(kotlinTypes, serverTypes, migration.name)
    }
}
