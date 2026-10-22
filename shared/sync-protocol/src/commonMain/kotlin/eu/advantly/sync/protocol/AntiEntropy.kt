package eu.advantly.sync.protocol

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.MatchId
import eu.advantly.core.scoring.EventOrdering
import eu.advantly.core.scoring.ScoreEvent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Ein `(deviceId, deviceSeq)`-Paar eines vorhandenen Events. */
data class DeviceSeq(
    val deviceId: DeviceId,
    val seq: Long,
)

/** Geschlossener Bereich `first..last` der `deviceSeq` eines Geräts. */
data class SeqRange(
    val deviceId: DeviceId,
    val first: Long,
    val last: Long,
) {
    init {
        require(first in 1..last) { "ungültiger Bereich $first..$last" }
    }

    /** Anzahl der Sequenznummern im Bereich. */
    val size: Long get() = last - first + 1

    operator fun contains(seq: Long): Boolean = seq in first..last
}

/**
 * Versionsvektor eines Matches (01 §4.3 `Summary`): je Gerät die **höchste lückenlos vorhandene** `deviceSeq`
 * (Wasserstand). Damit bedeutet „Gerät X hat 5“ immer „X hat 1..5“ – Events, die außer der Reihe angekommen sind
 * (z. B. 7 ohne 6), erhöhen den Wasserstand erst, wenn die Lücke geschlossen ist; sie werden höchstens doppelt
 * gesendet (idempotent), aber nie übersprungen.
 *
 * JSON: einfaches Objekt `{"<deviceId>": <seq>}`; Geräte mit Wasserstand 0 werden weggelassen.
 */
@Serializable(with = VersionVectorSerializer::class)
class VersionVector private constructor(
    /** Wasserstand je Gerät, nur Einträge > 0. */
    val watermarks: Map<DeviceId, Long>,
) {
    /** Wasserstand für [device] (0 = nichts vorhanden). */
    operator fun get(device: DeviceId): Long = watermarks[device] ?: 0L

    /** Punktweises Maximum – Wissen nach einem vollständigen Abgleich. */
    fun merge(other: VersionVector): VersionVector =
        of((watermarks.keys + other.watermarks.keys).associateWith { maxOf(get(it), other[it]) })

    /** `true`, wenn dieser Vektor jedes Event abdeckt, das [other] abdeckt. */
    fun dominates(other: VersionVector): Boolean = other.watermarks.all { (device, seq) -> get(device) >= seq }

    override fun equals(other: Any?): Boolean = other is VersionVector && other.watermarks == watermarks

    override fun hashCode(): Int = watermarks.hashCode()

    override fun toString(): String =
        watermarks.entries
            .sortedBy {
                it.key
            }.joinToString(prefix = "{", postfix = "}") { "${it.key}:${it.value}" }

    companion object {
        val EMPTY: VersionVector = VersionVector(emptyMap())

        /** Vektor aus Wasserständen; Einträge ≤ 0 werden verworfen. */
        fun of(watermarks: Map<DeviceId, Long>): VersionVector = VersionVector(watermarks.filterValues { it > 0 })

        /** Wasserstände aus den vorhandenen `(deviceId, deviceSeq)`-Paaren eines Matches. */
        fun fromSequences(sequences: Iterable<DeviceSeq>): VersionVector =
            of(
                sequences.groupBy({ it.deviceId }, { it.seq }).mapValues { (_, seqs) ->
                    val present = seqs.toHashSet()
                    var mark = 0L
                    while ((mark + 1) in present) mark++
                    mark
                },
            )
    }
}

/** JSON-Form von [VersionVector]: `{"<deviceId>": <seq>}`. */
internal object VersionVectorSerializer : KSerializer<VersionVector> {
    private val delegate = MapSerializer(String.serializer(), Long.serializer())

    override val descriptor: SerialDescriptor = SerialDescriptor("eu.advantly.sync.protocol.VersionVector", delegate.descriptor)

    override fun serialize(
        encoder: Encoder,
        value: VersionVector,
    ) {
        val sorted =
            value.watermarks.entries
                .sortedBy { it.key }
                .associate { it.key.value to it.value }
        encoder.encodeSerializableValue(delegate, sorted)
    }

    override fun deserialize(decoder: Decoder): VersionVector =
        VersionVector.of(decoder.decodeSerializableValue(delegate).mapKeys { DeviceId(it.key) })
}

/**
 * Anti-Entropie als reine Funktionen (01 §4.3 Regel 3): Abgleich bei jedem (Wieder-)Verbinden und alle 30 s.
 *
 * Ablauf zwischen zwei Geräten X und Y:
 * 1. beide senden `Summary` (ihre [VersionVector]en),
 * 2. jeder sendet die Events aus [missingRanges] (lokal vorhanden, vom Gegenüber nicht abgedeckt),
 * 3. wer laut [isBehind] etwas nicht hat, antwortet einmal mit `Summary(reply = true)`.
 * Nach einem Durchlauf ohne Verlust gilt: beide Vektoren sind gleich (Konvergenz, per Property-Test belegt).
 */
object AntiEntropy {
    /** Höchstzahl Events je `Events`-Nachricht: ~250 B je Event → < 64 KB (Grenze von WatchConnectivity `sendMessageData`). */
    const val MAX_EVENTS_PER_MESSAGE: Int = 200

    /** Abgleich-Intervall während eines aktiven Matches (01 §4.3, PLAN K4). */
    const val INTERVAL_MS: Long = 30_000L

    /**
     * Bereiche, die lokal vorhanden sind ([local]), die das Gegenüber laut [remote] aber nicht sicher hat
     * (`seq > remote[device]`). Sortiert nach Gerät und Sequenz, disjunkt, maximal zusammengefasst.
     */
    fun missingRanges(
        local: Iterable<DeviceSeq>,
        remote: VersionVector,
    ): List<SeqRange> = compress(local.filter { it.seq > remote[it.deviceId] })

    /** `true`, wenn [remote] für mindestens ein Gerät mehr abdeckt als [local] – dann lohnt sich ein `Summary(reply)`. */
    fun isBehind(
        local: VersionVector,
        remote: VersionVector,
    ): Boolean = !local.dominates(remote)

    /** Lücken unterhalb der jeweils höchsten vorhandenen Sequenz (Diagnose: „Events außer der Reihe“). */
    fun gaps(local: Iterable<DeviceSeq>): List<SeqRange> =
        local
            .groupBy({ it.deviceId }, { it.seq })
            .entries
            .sortedBy { it.key }
            .flatMap { (device, seqs) ->
                val present = seqs.toHashSet()
                val holes = (1..seqs.max()).filter { it !in present }.map { DeviceSeq(device, it) }
                compress(holes)
            }

    /** Fasst Sequenzen je Gerät zu disjunkten, sortierten Bereichen zusammen (Duplikate erlaubt). */
    fun compress(sequences: Iterable<DeviceSeq>): List<SeqRange> {
        val result = mutableListOf<SeqRange>()
        sequences
            .filter { it.seq >= 1 }
            .groupBy({ it.deviceId }, { it.seq })
            .entries
            .sortedBy { it.key }
            .forEach { (device, seqs) ->
                var start = -1L
                var end = -1L
                for (seq in seqs.distinct().sorted()) {
                    if (start < 0) {
                        start = seq
                    } else if (seq != end + 1) {
                        result += SeqRange(device, start, end)
                        start = seq
                    }
                    end = seq
                }
                if (start > 0) result += SeqRange(device, start, end)
            }
        return result
    }

    /** Events aus [events], deren `(deviceId, deviceSeq)` in einem der [ranges] liegt – in Event-Ordnung. */
    fun select(
        events: Collection<ScoreEvent>,
        ranges: List<SeqRange>,
    ): List<ScoreEvent> {
        val byDevice = ranges.groupBy { it.deviceId }
        return events
            .filter { e -> byDevice[e.deviceId]?.any { e.deviceSeq in it } == true }
            .sortedWith(EventOrdering)
    }

    /** Teilt [events] in Nachrichten zu höchstens [maxPerMessage] Events (Reihenfolge bleibt). */
    fun batches(
        events: List<ScoreEvent>,
        maxPerMessage: Int = MAX_EVENTS_PER_MESSAGE,
    ): List<List<ScoreEvent>> {
        require(maxPerMessage >= 1) { "maxPerMessage ≥ 1" }
        return events.chunked(maxPerMessage)
    }

    /**
     * Wie [batches], zusätzlich nach Bytes: Eine Gruppe wird halbiert, bis die Probe [fits] (Startposition in [items],
     * Gruppe; z. B. kodierte Größe samt Envelope) sie annimmt. Deterministisch, Reihenfolge bleibt. Ein einzelnes
     * Element, das nie passt, bildet eine eigene Gruppe; der Aufrufer darf es nicht senden.
     */
    fun <T> batchesBy(
        items: List<T>,
        maxPerMessage: Int = MAX_EVENTS_PER_MESSAGE,
        fits: (start: Int, batch: List<T>) -> Boolean,
    ): List<List<T>> {
        require(maxPerMessage >= 1) { "maxPerMessage ≥ 1" }
        val result = mutableListOf<List<T>>()
        var start = 0
        while (start < items.size) {
            var size = minOf(maxPerMessage, items.size - start)
            while (size > 1 && !fits(start, items.subList(start, start + size))) size /= 2
            result += items.subList(start, start + size).toList()
            start += size
        }
        return result
    }

    /**
     * Plausibilitätsprüfung empfangener Events vor dem Speichern: richtige Match-ID, `deviceSeq ≥ 1`, `lamport ≥ 1`,
     * keine leeren IDs. Unplausible Events verwirft der Empfänger (sie würden die Anti-Entropie stören).
     */
    fun isPlausible(
        event: ScoreEvent,
        matchId: MatchId,
    ): Boolean =
        event.matchId == matchId &&
            event.deviceSeq >= 1 &&
            event.lamport >= 1 &&
            event.id.value.isNotEmpty() &&
            event.deviceId.value.isNotEmpty()
}
