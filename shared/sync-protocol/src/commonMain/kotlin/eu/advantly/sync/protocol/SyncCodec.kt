package eu.advantly.sync.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Ergebnis von [SyncCodec.decode]. */
sealed interface DecodeResult {
    /** Nachricht gelesen. */
    data class Ok(
        val message: SyncMessage,
    ) : DecodeResult

    /** Gültiges JSON, aber Protokollversion [v] wird nicht unterstützt (siehe [ProtocolVersion.accepts]). */
    data class Unsupported(
        val v: Int,
        val type: String?,
    ) : DecodeResult

    /** Kein lesbares Protokoll-JSON (kaputt, fremd, unbekannter Typ oder Pflichtfeld fehlt). */
    data class Malformed(
        val reason: String,
    ) : DecodeResult
}

/**
 * JSON-Codec des Geräte-Syncs (01 §4.3). UTF-8-JSON, Diskriminator `type`, Protokollversion `v`.
 *
 * - Unbekannte Felder werden ignoriert (Vorwärtskompatibilität innerhalb einer Version).
 * - Payloads der Events haben dieselbe Form wie in den Testvektoren (`{"type":"PointWon","side":"A"}`).
 * - [decode] wirft nie: Fehler kommen als [DecodeResult.Malformed] bzw. [DecodeResult.Unsupported] zurück.
 */
object SyncCodec {
    /** Gemeinsame JSON-Konfiguration für Nachrichten und gespeicherte Event-Payloads. */
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = true
            classDiscriminator = "type"
        }

    fun encodeToString(message: SyncMessage): String = json.encodeToString(SyncMessage.serializer(), message)

    fun encode(message: SyncMessage): ByteArray = encodeToString(message).encodeToByteArray()

    fun decode(bytes: ByteArray): DecodeResult = decode(bytes.decodeToString())

    fun decode(text: String): DecodeResult {
        val element =
            try {
                json.parseToJsonElement(text)
            } catch (e: IllegalArgumentException) {
                return DecodeResult.Malformed("kein JSON: ${e.message}")
            }
        val obj = element as? JsonObject ?: return DecodeResult.Malformed("kein JSON-Objekt")
        val v = (obj["v"] as? JsonPrimitive)?.intOrNull ?: return DecodeResult.Malformed("Feld 'v' fehlt")
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        // Hello immer lesen: Versionsprüfung braucht genau diese Nachricht
        if (type != HELLO && !ProtocolVersion.accepts(v)) return DecodeResult.Unsupported(v, type)
        return try {
            DecodeResult.Ok(json.decodeFromJsonElement(SyncMessage.serializer(), obj))
        } catch (e: IllegalArgumentException) {
            if (!ProtocolVersion.accepts(v)) DecodeResult.Unsupported(v, type) else DecodeResult.Malformed(e.message ?: "unlesbar")
        }
    }

    private const val HELLO = "Hello"
}
