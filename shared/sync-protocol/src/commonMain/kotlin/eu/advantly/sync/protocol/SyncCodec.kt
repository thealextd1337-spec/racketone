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
        val identity: IdentityEnvelope? = null,
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

    fun encodeToString(message: SyncMessage): String {
        require(message !is ReplicaControlMessage) { "ReplicaControlMessage requires IdentityEnvelope" }
        return serialize(message).also { requireWireBudget(message.v, it.encodeToByteArray().size) }
    }

    fun encode(message: SyncMessage): ByteArray = encodeToString(message).encodeToByteArray()

    fun encode(
        message: SyncMessage,
        identity: IdentityEnvelope,
    ): ByteArray {
        val original = json.parseToJsonElement(serialize(message)) as JsonObject
        return JsonObject(
            original + ("identity" to json.encodeToJsonElement(IdentityEnvelope.serializer(), identity)),
        ).toString().encodeToByteArray().also { requireWireBudget(message.v, it.size) }
    }

    private fun serialize(message: SyncMessage): String = json.encodeToString(SyncMessage.serializer(), message)

    /**
     * @param accepted lesbare Versionen; Standard [ProtocolVersion.MIN_SUPPORTED]..[ProtocolVersion.CURRENT]. Ein anderer
     *   Bereich (Session-Konfiguration, Tests von Protokoll 4) ändert nie die Konstanten.
     */
    fun decode(
        bytes: ByteArray,
        accepted: IntRange = DEFAULT_ACCEPTED,
    ): DecodeResult = decodePacket(bytes.decodeToString(), bytes.size, accepted)

    fun decode(
        text: String,
        accepted: IntRange = DEFAULT_ACCEPTED,
    ): DecodeResult = decodePacket(text, text.encodeToByteArray().size, accepted)

    private val DEFAULT_ACCEPTED = ProtocolVersion.MIN_SUPPORTED..ProtocolVersion.CURRENT

    private fun decodePacket(
        text: String,
        byteCount: Int,
        accepted: IntRange,
    ): DecodeResult {
        val element =
            try {
                json.parseToJsonElement(text)
            } catch (_: IllegalArgumentException) {
                return DecodeResult.Malformed("kein JSON")
            }
        val obj = element as? JsonObject ?: return DecodeResult.Malformed("kein JSON-Objekt")
        val v = (obj["v"] as? JsonPrimitive)?.intOrNull ?: return DecodeResult.Malformed("Feld 'v' fehlt")
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        if (v >= 4 && byteCount > MAX_V4_PACKET_BYTES) return DecodeResult.Malformed("Wirepaket zu groß")
        // Hello immer lesen: Versionsprüfung braucht genau diese Nachricht
        if (type != HELLO && v !in accepted) return DecodeResult.Unsupported(v, type)
        return try {
            val message = json.decodeFromJsonElement(SyncMessage.serializer(), obj)
            val identity = obj["identity"]?.let { json.decodeFromJsonElement(IdentityEnvelope.serializer(), it) }
            // Gilt auch nach der späteren V4-Aktivierung; lokale Legacy-Daten bleiben separat zulässig.
            if (message is ReplicaControlMessage && identity == null) {
                DecodeResult.Malformed("ReplicaControlMessage requires IdentityEnvelope")
            } else {
                DecodeResult.Ok(message, identity)
            }
        } catch (_: IllegalArgumentException) {
            if (v !in accepted) DecodeResult.Unsupported(v, type) else DecodeResult.Malformed("unlesbare Nachricht")
        }
    }

    private fun requireWireBudget(
        version: Int,
        byteCount: Int,
    ) {
        require(version < 4 || byteCount <= MAX_V4_PACKET_BYTES) { "Wirepaket zu groß" }
    }

    private const val MAX_V4_PACKET_BYTES = 64 * 1024
    private const val HELLO = "Hello"
}
