package eu.advantly.sync.protocol

import eu.advantly.core.model.MatchId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Flüchtige Workout-Werte des eigenen Gerätepaars; kein SyncMessage, Match-Event oder gespeicherter Datensatz. */
@Serializable
data class LiveTelemetry(
    val v: Int,
    val matchId: MatchId,
    val heartRateBpm: Int? = null,
    val steps: Long? = null,
) {
    override fun toString(): String = "LiveTelemetry(<redacted>)"

    companion object {
        /** Nur bei inkompatiblen Änderungen erhöhen; additive Felder bleiben in derselben Version. */
        const val CURRENT_VERSION: Int = 1
        const val MIN_VERSION: Int = 1
        const val VERSION: Int = CURRENT_VERSION
        const val MESSAGE_PATH: String = "/advantly/telemetry/live"
    }
}

/** Eigener, kleiner Wire-Codec; kaputte oder unbekannte Versionen werden ohne Payload-Diagnose verworfen. */
object LiveTelemetryCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_BYTES = 1_024

    fun encode(value: LiveTelemetry): ByteArray = json.encodeToString(LiveTelemetry.serializer(), value).encodeToByteArray()

    fun decode(bytes: ByteArray): LiveTelemetry? {
        if (bytes.size > MAX_BYTES) return null
        return try {
            json.decodeFromString(LiveTelemetry.serializer(), bytes.decodeToString()).takeIf {
                it.v in LiveTelemetry.MIN_VERSION..LiveTelemetry.CURRENT_VERSION && it.matchId.value.isNotBlank() &&
                    (it.heartRateBpm == null || it.heartRateBpm > 0) && (it.steps == null || it.steps >= 0)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
