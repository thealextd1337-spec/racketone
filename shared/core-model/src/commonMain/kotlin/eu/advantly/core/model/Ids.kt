package eu.advantly.core.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * UUID Version 7 (RFC 9562) als reiner Wert-Typ in kanonischer Textform (`xxxxxxxx-xxxx-7xxx-[89ab]xxx-xxxxxxxxxxxx`, klein).
 *
 * Die Engine erzeugt **keine** UUIDs: Zeit und Zufall kommen von außen (Plattform-Schicht), damit die Pure-Module
 * deterministisch bleiben. [fromParts] baut aus den gelieferten Bestandteilen eine gültige UUIDv7, [parse] prüft eine
 * vorhandene. Die Textform ist so gewählt, dass der lexikografische Vergleich der Strings der Bytereihenfolge entspricht.
 */
@Serializable
@JvmInline
value class Uuid7 private constructor(
    val value: String,
) : Comparable<Uuid7> {
    override fun compareTo(other: Uuid7): Int = value.compareTo(other.value)

    override fun toString(): String = value

    /** Millisekunden-Zeitstempel (48 Bit) aus den ersten 12 Hex-Ziffern. */
    val unixTimeMs: Long
        get() = value.substring(0, 8).toLong(16).shl(16) or value.substring(9, 13).toLong(16)

    companion object {
        private val PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

        /** Prüft [text] auf kanonische UUIDv7-Form; `null`, wenn ungültig. */
        fun parseOrNull(text: String): Uuid7? = if (PATTERN.matches(text)) Uuid7(text) else null

        /** Wie [parseOrNull], wirft aber [IllegalArgumentException] bei ungültiger Eingabe. */
        fun parse(text: String): Uuid7 = requireNotNull(parseOrNull(text)) { "Keine kanonische UUIDv7: '$text'" }

        /**
         * Baut eine UUIDv7 aus Zeitstempel und Zufallsbits, die der Aufrufer liefert.
         *
         * @param unixTimeMs 48-Bit-Zeitstempel in Millisekunden (0 ≤ t < 2^48)
         * @param randA 12 Zufallsbits (nur die unteren 12 Bit werden verwendet)
         * @param randB 62 Zufallsbits (nur die unteren 62 Bit werden verwendet)
         */
        fun fromParts(
            unixTimeMs: Long,
            randA: Int,
            randB: Long,
        ): Uuid7 {
            require(unixTimeMs in 0 until (1L shl 48)) { "unixTimeMs außerhalb von 48 Bit: $unixTimeMs" }
            val ts = hex(unixTimeMs, 12)
            val a = hex((0x7000 or (randA and 0x0FFF)).toLong(), 4)
            val b = randB and 0x3FFF_FFFF_FFFF_FFFFL
            val variantAndHigh = hex(0x8000L or (b ushr 48), 4)
            val low = hex(b and 0xFFFF_FFFF_FFFFL, 12)
            return Uuid7("${ts.substring(0, 8)}-${ts.substring(8, 12)}-$a-$variantAndHigh-$low")
        }

        private fun hex(
            v: Long,
            digits: Int,
        ): String = v.toString(16).padStart(digits, '0')
    }
}

/**
 * ID eines Events (01 §3.2). Im Betrieb eine UUIDv7 ([Uuid7.value]), in Testvektoren ein beliebiger stabiler String
 * (z. B. `e17`). Für die Ordnung wird der String lexikografisch verglichen.
 */
@Serializable
@JvmInline
value class EventId(
    val value: String,
) : Comparable<EventId> {
    override fun compareTo(other: EventId): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        /** Event-ID aus einer extern erzeugten UUIDv7. */
        fun of(uuid: Uuid7): EventId = EventId(uuid.value)
    }
}

/** ID eines Matches (UUIDv7-Text, extern erzeugt). */
@Serializable
@JvmInline
value class MatchId(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        /** Match-ID aus einer extern erzeugten UUIDv7. */
        fun of(uuid: Uuid7): MatchId = MatchId(uuid.value)
    }
}

/** Stabile Installations-ID eines Geräts (Teil der Event-Ordnung `(lamport, deviceId, id)`). */
@Serializable
@JvmInline
value class DeviceId(
    val value: String,
) : Comparable<DeviceId> {
    override fun compareTo(other: DeviceId): Int = value.compareTo(other.value)

    override fun toString(): String = value
}

/** ID eines Spielers. Im Lineup und in allen Zuständen wird nur diese ID referenziert. */
@Serializable
@JvmInline
value class PlayerId(
    val value: String,
) {
    override fun toString(): String = value
}
