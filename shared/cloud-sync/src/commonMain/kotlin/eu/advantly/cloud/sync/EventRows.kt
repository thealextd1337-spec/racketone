package eu.advantly.cloud.sync

import eu.advantly.data.StoredEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Abbildung lokales Event (`match_event`, [StoredEvent]) ↔ Cloud-Zeile (`match_events`, [EventRow]).
 *
 * - Die Payload wird **nicht** neu serialisiert, sondern als JSON-Objekt 1:1 übernommen (gleiche Form wie auf dem
 *   Draht, 01 §4.3). Strukturelle Gleichheit genügt (02 §7.3 „bitgleich“ meint data-class-Gleichheit).
 * - `confirmed` ist ein Geräte-Replikations-Flag und wird nicht übertragen. Ein Cloud-Download bestätigt
 *   nur die Cloud (`cloud_state`); die Weitergabe an gekoppelte Geräte bleibt ausstehend.
 */
object EventRows {
    /** Lokales Event → Cloud-Zeile. Fehlt [StoredEvent.type] (Alt-Daten), wird er aus der Payload gelesen. */
    fun toRow(event: StoredEvent): EventRow {
        val payload = CloudJson.parseToJsonElement(event.payload).jsonObject
        val payloadType = (payload["type"] as? JsonPrimitive)?.content
        val type = event.type.ifEmpty { payloadType.orEmpty() }
        require(type.isNotEmpty() && type == payloadType) {
            "Event ${event.id}: Typ '${event.type}' passt nicht zur Payload ('$payloadType')"
        }
        return EventRow(
            id = event.id,
            matchId = event.matchId,
            deviceId = event.deviceId,
            deviceSeq = event.deviceSeq,
            lamport = event.lamport,
            wallClockMs = event.wallClockMs,
            parentId = event.parentId,
            type = type,
            payload = payload,
        )
    }

    /** Cloud-Zeile → lokales Event (CloudSyncRepository speichert atomar samt Cloud-Markierung). */
    fun toStored(row: EventRow): StoredEvent =
        StoredEvent(
            id = row.id,
            matchId = row.matchId,
            deviceId = row.deviceId,
            deviceSeq = row.deviceSeq,
            lamport = row.lamport,
            payload = JsonObject(row.payload).toString(),
            wallClockMs = row.wallClockMs,
            parentId = row.parentId,
            type = row.type,
            confirmed = false,
        )
}

/** Lokale Werte der Engine → Cloud-Schreibweise (`TABLE_TENNIS` → `table_tennis`, `IN_PROGRESS` → `in_progress`). */
object CloudNames {
    fun of(engineName: String): String = engineName.lowercase()

    /** Cloud → Engine (`in_progress` → `IN_PROGRESS`). */
    fun toEngine(cloudName: String): String = cloudName.uppercase()
}
