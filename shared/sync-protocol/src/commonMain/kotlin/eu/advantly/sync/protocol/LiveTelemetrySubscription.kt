package eu.advantly.sync.protocol

import eu.advantly.core.model.MatchId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Flüchtiges Interesse einer sichtbaren Handy-Live-Ansicht; kein Ack, keine Queue, keine Persistenz. */
@Serializable
data class LiveTelemetrySubscription(
    val v: Int,
    val matchId: MatchId,
) {
    override fun toString(): String = "LiveTelemetrySubscription(<redacted>)"

    companion object {
        /** Nur bei inkompatiblen Änderungen erhöhen. */
        const val CURRENT_VERSION: Int = 1
        const val MIN_VERSION: Int = 1
        const val MESSAGE_PATH: String = "/advantly/telemetry/subscribe"
        const val KEEPALIVE_MS: Long = 20_000
        const val FRESH_FOR_MS: Long = 30_000
    }
}

object LiveTelemetrySubscriptionCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(value: LiveTelemetrySubscription): ByteArray =
        json.encodeToString(LiveTelemetrySubscription.serializer(), value).encodeToByteArray()

    fun decode(bytes: ByteArray): LiveTelemetrySubscription? {
        if (bytes.size > 1_024) return null
        return try {
            json.decodeFromString(LiveTelemetrySubscription.serializer(), bytes.decodeToString()).takeIf {
                it.v in LiveTelemetrySubscription.MIN_VERSION..LiveTelemetrySubscription.CURRENT_VERSION && it.matchId.value.isNotBlank()
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
