package eu.advantly.sync.protocol

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.HealthConsent
import eu.advantly.core.model.MatchId
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Gesundheitsdaten zwischen den eigenen Geräten (Plan Puls §3.5, Protokoll 6): nur an Gegenstellen, die sie im `Hello`
 * ankündigen ([Hello.healthProtocol]); ältere Geräte bekommen nichts. Nie über Anti-Entropie, Outbox oder Cloud.
 */
@Serializable
sealed interface HealthMessage : SyncMessage {
    val matchId: MatchId
    val sourceDeviceId: DeviceId
    val stamp: MatchDataStamp?
}

/**
 * Puls und kumulierte Schritte eines Matches von einem Gerät ([sourceDeviceId]) im Raster ab [startMs] (Uhr der Quelle).
 * Der Empfang ist idempotent über `(matchId, sourceDeviceId, revision)`: Eine höhere [revision] ersetzt die gespeicherte
 * Serie, eine gleiche oder niedrigere wird nur bestätigt ([HealthAck]).
 *
 * @property deleted Löschung statt Serie (Pulsverlauf gelöscht, Stufe „Gar nicht“): Der Empfänger löscht die Serie dieser
 *   Quelle und zeichnet zu dem Match nichts mehr auf; [bpm]/[steps] sind dann leer
 * @property clockOffsetMs Uhr der Quelle minus Uhr des Handys (Plan Puls §3.6), 0 = gleiche Zeitbasis
 * @property quality Name einer `HealthQuality`; ein unbekannter Wert wird beim Empfang neu berechnet
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("HealthSeries")
data class HealthSeriesMessage(
    override val matchId: MatchId,
    override val sourceDeviceId: DeviceId,
    val revision: Long,
    val startMs: Long = 0,
    val intervalMs: Long = 5_000,
    val bpm: List<Int?> = emptyList(),
    val steps: List<Long?> = emptyList(),
    val clockOffsetMs: Long = 0,
    val quality: String = "",
    val deleted: Boolean = false,
    @EncodeDefault override val v: Int = ProtocolVersion.MIN_SUPPORTED,
    override val stamp: MatchDataStamp? = null,
) : HealthMessage {
    init {
        requireWireId(matchId.value)
        requireWireId(sourceDeviceId.value)
        require(revision >= 0 && intervalMs > 0 && bpm.size == steps.size && bpm.size <= MAX_BUCKETS)
        require(!deleted || bpm.isEmpty())
        require(v >= 4 || stamp == null)
    }

    // Gesundheitsdaten nie in Logs (Plan Puls §3.4).
    override fun toString(): String = "HealthSeriesMessage(<redacted>)"

    companion object {
        /** 12 h im 5-s-Raster (Obergrenze des Recorders). */
        const val MAX_BUCKETS: Int = 8_640
    }
}

/** Bestätigung des Handys: [revision] der Serie von [sourceDeviceId] ist gespeichert; die Quelle darf sie löschen. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("HealthAck")
data class HealthAck(
    override val matchId: MatchId,
    override val sourceDeviceId: DeviceId,
    val revision: Long,
    @EncodeDefault override val v: Int = ProtocolVersion.MIN_SUPPORTED,
    override val stamp: MatchDataStamp? = null,
) : HealthMessage {
    init {
        requireWireId(matchId.value)
        requireWireId(sourceDeviceId.value)
        require(v >= 4 || stamp == null)
    }
}

/**
 * Einwilligung „Pulsverlauf aufzeichnen“ auf dem Draht (additiv in `MatchMeta`, Muster [TelemetrySetting]).
 *
 * @property counter Lamport-Zähler (> 0), Konfliktregel [HealthConsent.merge]
 * @property origin Installations-ID des ändernden Geräts
 */
@Serializable
data class HealthConsentSetting(
    val enabled: Boolean,
    val counter: Long = 0,
    val origin: String = "",
) {
    /** Gültige Einwilligung oder `null` (kein Zähler, leere/zu lange Herkunft). */
    fun toConsent(): HealthConsent? =
        if (counter > 0 && origin.isNotBlank() && origin.length <= TelemetrySetting.MAX_ORIGIN) {
            HealthConsent(enabled, counter, origin)
        } else {
            null
        }

    companion object {
        /** Nur geänderte Einwilligungen gehen auf den Draht. */
        fun of(consent: HealthConsent): HealthConsentSetting? =
            consent.origin?.takeIf { !consent.isDefault }?.let { HealthConsentSetting(consent.enabled, consent.counter, it) }
    }
}
