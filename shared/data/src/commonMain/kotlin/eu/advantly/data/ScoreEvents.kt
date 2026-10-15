package eu.advantly.data

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.ScoreEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON der Datenschicht – dieselbe Konfiguration wie der Wire-Codec in `sync-protocol` (`SyncCodec.json`), damit
 * gespeicherte Payloads und Payloads auf dem Draht identisch aussehen (P2: Upload ohne Umkodieren).
 */
internal val DataJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = true
        classDiscriminator = "type"
    }

/** Speicherform eines Engine-Events (Payload als JSON, Typ separat). */
fun ScoreEvent.toStoredEvent(): StoredEvent {
    val json = DataJson.encodeToJsonElement(EventPayload.serializer(), payload).jsonObject
    return StoredEvent(
        id = id.value,
        matchId = matchId.value,
        deviceId = deviceId.value,
        deviceSeq = deviceSeq,
        lamport = lamport,
        payload = json.toString(),
        wallClockMs = wallClockMs,
        parentId = parentId?.value,
        type = json["type"]?.jsonPrimitive?.content.orEmpty(),
    )
}

/**
 * Engine-Event aus der Speicherform.
 *
 * @throws IllegalArgumentException wenn die Payload nicht lesbar ist (z. B. unbekannter Typ einer neueren Version)
 */
fun StoredEvent.toScoreEvent(): ScoreEvent =
    ScoreEvent(
        id = EventId(id),
        matchId = MatchId(matchId),
        deviceId = DeviceId(deviceId),
        deviceSeq = deviceSeq,
        lamport = lamport,
        wallClockMs = wallClockMs,
        parentId = parentId?.let { EventId(it) },
        payload = DataJson.decodeFromString(EventPayload.serializer(), payload),
    )

/** Wie [toScoreEvent], aber `null` bei unlesbarer Payload. */
fun StoredEvent.toScoreEventOrNull(): ScoreEvent? =
    try {
        toScoreEvent()
    } catch (e: IllegalArgumentException) {
        null
    }

/** Alle lesbaren Events eines Matches in globaler Ordnung (unlesbare werden übersprungen). */
fun EventStore.scoreEvents(matchId: MatchId): List<ScoreEvent> = eventsFor(matchId.value).mapNotNull { it.toScoreEventOrNull() }
