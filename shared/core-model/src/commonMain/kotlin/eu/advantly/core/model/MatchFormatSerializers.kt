package eu.advantly.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * Serializer für [MatchFormat]: schreibt sportfremde Felder nicht und lehnt verbotene explizite `null`-Werte ab
 * (02 §3.1 Validierung 1, Schema §3.2).
 */
object MatchFormatSerializer : KSerializer<MatchFormat> {
    private val names =
        listOf(
            "formatVersion",
            "presetId",
            "sport",
            "discipline",
            "match",
            "game",
            "set",
            "decidingSet",
            "matchTiebreak",
            "pickleball",
            "rally",
        )

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("eu.advantly.core.model.MatchFormat") {
            element("formatVersion", Int.serializer().descriptor)
            element("presetId", String.serializer().nullable.descriptor, isOptional = true)
            element("sport", Sport.serializer().descriptor)
            element("discipline", Discipline.serializer().descriptor)
            element("match", MatchSettings.serializer().descriptor)
            element("game", GameFormat.serializer().nullable.descriptor, isOptional = true)
            element("set", RegularSetSerializer.nullable.descriptor, isOptional = true)
            element("decidingSet", SetFormatSerializer.nullable.descriptor, isOptional = true)
            element("matchTiebreak", MatchTiebreakSerializer.nullable.descriptor, isOptional = true)
            element("pickleball", PickleballFormat.serializer().nullable.descriptor, isOptional = true)
            element("rally", RallyFormat.serializer().nullable.descriptor, isOptional = true)
        }

    override fun serialize(
        encoder: Encoder,
        value: MatchFormat,
    ) {
        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, 0, value.formatVersion)
            encodeNullableSerializableElement(descriptor, 1, String.serializer(), value.presetId)
            encodeSerializableElement(descriptor, 2, Sport.serializer(), value.sport)
            encodeSerializableElement(descriptor, 3, Discipline.serializer(), value.discipline)
            encodeSerializableElement(descriptor, 4, MatchSettings.serializer(), value.match)
            value.game?.let { encodeSerializableElement(descriptor, 5, GameFormat.serializer(), it) }
            value.set?.let { encodeSerializableElement(descriptor, 6, RegularSetSerializer, it) }
            if (value.sport.isTennisLike) {
                // Bei Tennis/Padel sind decidingSet und matchTiebreak erlaubt und werden explizit (auch als null) geschrieben.
                encodeNullableSerializableElement(descriptor, 7, SetFormatSerializer, value.decidingSet)
                encodeNullableSerializableElement(descriptor, 8, MatchTiebreakSerializer, value.matchTiebreak)
            } else {
                value.decidingSet?.let { encodeSerializableElement(descriptor, 7, SetFormatSerializer, it) }
                value.matchTiebreak?.let { encodeSerializableElement(descriptor, 8, MatchTiebreakSerializer, it) }
            }
            value.pickleball?.let { encodeSerializableElement(descriptor, 9, PickleballFormat.serializer(), it) }
            value.rally?.let { encodeSerializableElement(descriptor, 10, RallyFormat.serializer(), it) }
        }
    }

    override fun deserialize(decoder: Decoder): MatchFormat =
        decoder.decodeStructure(descriptor) {
            val present = BooleanArray(names.size)
            var formatVersion: Int? = null
            var presetId: String? = null
            var sport: Sport? = null
            var discipline: Discipline? = null
            var match: MatchSettings? = null
            var game: GameFormat? = null
            var set: RegularSet? = null
            var decidingSet: SetFormat? = null
            var matchTiebreak: MatchTiebreakFormat? = null
            var pickleball: PickleballFormat? = null
            var rally: RallyFormat? = null
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                present[index] = true
                when (index) {
                    0 -> formatVersion = decodeIntElement(descriptor, 0)
                    1 -> presetId = decodeSerializableElement(descriptor, 1, String.serializer().nullable)
                    2 -> sport = decodeSerializableElement(descriptor, 2, Sport.serializer())
                    3 -> discipline = decodeSerializableElement(descriptor, 3, Discipline.serializer())
                    4 -> match = decodeSerializableElement(descriptor, 4, MatchSettings.serializer())
                    5 -> game = decodeSerializableElement(descriptor, 5, GameFormat.serializer().nullable)
                    6 -> set = decodeSerializableElement(descriptor, 6, RegularSetSerializer.nullable)
                    7 -> decidingSet = decodeSerializableElement(descriptor, 7, SetFormatSerializer.nullable)
                    8 -> matchTiebreak = decodeSerializableElement(descriptor, 8, MatchTiebreakSerializer.nullable)
                    9 -> pickleball = decodeSerializableElement(descriptor, 9, PickleballFormat.serializer().nullable)
                    10 -> rally = decodeSerializableElement(descriptor, 10, RallyFormat.serializer().nullable)
                    else -> throw SerializationException("Unbekannter Index $index in MatchFormat")
                }
            }
            val s = sport ?: throw SerializationException("MatchFormat: Pflichtfeld 'sport' fehlt")

            fun explicitNull(
                index: Int,
                value: Any?,
            ) = present[index] && value == null
            for ((index, value) in listOf(5 to game, 6 to set, 9 to pickleball, 10 to rally)) {
                if (explicitNull(index, value)) {
                    throw SerializationException("MatchFormat: '${names[index]}' darf nicht explizit null sein (weglassen)")
                }
            }
            if (!s.isTennisLike) {
                for ((index, value) in listOf(7 to decidingSet, 8 to matchTiebreak)) {
                    if (explicitNull(index, value)) {
                        throw SerializationException("MatchFormat: '${names[index]}' ist bei $s sportfremd und muss fehlen")
                    }
                }
            }
            MatchFormat(
                formatVersion = formatVersion ?: throw SerializationException("MatchFormat: Pflichtfeld 'formatVersion' fehlt"),
                presetId = presetId,
                sport = s,
                discipline = discipline ?: throw SerializationException("MatchFormat: Pflichtfeld 'discipline' fehlt"),
                match = match ?: throw SerializationException("MatchFormat: Pflichtfeld 'match' fehlt"),
                game = game,
                set = set,
                decidingSet = decidingSet,
                matchTiebreak = matchTiebreak,
                pickleball = pickleball,
                rally = rally,
            )
        }
}

/** Gemeinsame Kodierung der flachen Satz-Objekte (`regularSet` / `matchTiebreak`, unterschieden über `kind`). */
private object SetObjectCodec {
    fun descriptor(name: String): SerialDescriptor =
        buildClassSerialDescriptor(name) {
            element("kind", SetFormatKind.serializer().descriptor, isOptional = true)
            element("gamesToWin", Int.serializer().descriptor, isOptional = true)
            element("tiebreakAt", Int.serializer().nullable.descriptor, isOptional = true)
            element("tiebreak", TiebreakFormat.serializer().descriptor, isOptional = true)
            element("pointsToWin", Int.serializer().descriptor, isOptional = true)
            element("winBy", Int.serializer().descriptor, isOptional = true)
            element("endChange", EndChange.serializer().descriptor, isOptional = true)
        }

    fun decode(
        decoder: Decoder,
        descriptor: SerialDescriptor,
    ): SetFormat =
        decoder.decodeStructure(descriptor) {
            var kind: SetFormatKind? = null
            var gamesToWin: Int? = null
            var tiebreakAtPresent = false
            var tiebreakAt: Int? = null
            var tiebreak: TiebreakFormat? = null
            var pointsToWin: Int? = null
            var winBy: Int? = null
            var endChange: EndChange? = null
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> {
                        break
                    }

                    0 -> {
                        kind = decodeSerializableElement(descriptor, 0, SetFormatKind.serializer())
                    }

                    1 -> {
                        gamesToWin = decodeIntElement(descriptor, 1)
                    }

                    2 -> {
                        tiebreakAtPresent = true
                        tiebreakAt = decodeSerializableElement(descriptor, 2, Int.serializer().nullable)
                    }

                    3 -> {
                        tiebreak = decodeSerializableElement(descriptor, 3, TiebreakFormat.serializer())
                    }

                    4 -> {
                        pointsToWin = decodeIntElement(descriptor, 4)
                    }

                    5 -> {
                        winBy = decodeIntElement(descriptor, 5)
                    }

                    6 -> {
                        endChange = decodeSerializableElement(descriptor, 6, EndChange.serializer())
                    }

                    else -> {
                        throw SerializationException("Unbekannter Index $index in Satz-Objekt")
                    }
                }
            }
            if (kind == SetFormatKind.MATCH_TIEBREAK) {
                MatchTiebreakFormat(
                    pointsToWin = pointsToWin ?: missing("pointsToWin"),
                    winBy = winBy ?: missing("winBy"),
                    endChange = endChange ?: missing("endChange"),
                )
            } else {
                if (!tiebreakAtPresent) missing("tiebreakAt")
                RegularSet(
                    gamesToWin = gamesToWin ?: missing("gamesToWin"),
                    tiebreakAt = tiebreakAt,
                    tiebreak = tiebreak ?: TiebreakFormat.TB7,
                )
            }
        }

    fun encode(
        encoder: Encoder,
        descriptor: SerialDescriptor,
        value: SetFormat,
        writeRegularKind: Boolean,
    ) {
        encoder.encodeStructure(descriptor) {
            when (value) {
                is RegularSet -> {
                    if (writeRegularKind) encodeKind(descriptor, SetFormatKind.REGULAR)
                    encodeIntElement(descriptor, 1, value.gamesToWin)
                    encodeNullableSerializableElement(descriptor, 2, Int.serializer(), value.tiebreakAt)
                    encodeSerializableElement(descriptor, 3, TiebreakFormat.serializer(), value.tiebreak)
                }

                is MatchTiebreakFormat -> {
                    encodeKind(descriptor, SetFormatKind.MATCH_TIEBREAK)
                    encodeIntElement(descriptor, 4, value.pointsToWin)
                    encodeIntElement(descriptor, 5, value.winBy)
                    encodeSerializableElement(descriptor, 6, EndChange.serializer(), value.endChange)
                }
            }
        }
    }

    private fun CompositeEncoder.encodeKind(
        descriptor: SerialDescriptor,
        kind: SetFormatKind,
    ) = encodeSerializableElement(descriptor, 0, SetFormatKind.serializer(), kind)

    private fun missing(field: String): Nothing = throw SerializationException("Satz-Objekt: Pflichtfeld '$field' fehlt")
}

/** `decidingSet`: regulärer Satz oder Match-Tiebreak, unterschieden über `kind` (Default `REGULAR`). */
object SetFormatSerializer : KSerializer<SetFormat> {
    override val descriptor: SerialDescriptor = SetObjectCodec.descriptor("eu.advantly.core.model.SetFormat")

    override fun serialize(
        encoder: Encoder,
        value: SetFormat,
    ) = SetObjectCodec.encode(encoder, descriptor, value, writeRegularKind = true)

    override fun deserialize(decoder: Decoder): SetFormat = SetObjectCodec.decode(decoder, descriptor)
}

/** `set`: immer regulärer Satz; `kind` darf fehlen oder `REGULAR` sein. */
object RegularSetSerializer : KSerializer<RegularSet> {
    override val descriptor: SerialDescriptor = SetObjectCodec.descriptor("eu.advantly.core.model.RegularSet")

    override fun serialize(
        encoder: Encoder,
        value: RegularSet,
    ) = SetObjectCodec.encode(encoder, descriptor, value, writeRegularKind = false)

    override fun deserialize(decoder: Decoder): RegularSet =
        SetObjectCodec.decode(decoder, descriptor) as? RegularSet
            ?: throw SerializationException("'set' muss ein regulärer Satz sein (kind = REGULAR)")
}

/** `matchTiebreak` bzw. Match-Tiebreak-Objekt: `kind = MATCH_TIEBREAK` ist Pflicht. */
object MatchTiebreakSerializer : KSerializer<MatchTiebreakFormat> {
    override val descriptor: SerialDescriptor = SetObjectCodec.descriptor("eu.advantly.core.model.MatchTiebreakFormat")

    override fun serialize(
        encoder: Encoder,
        value: MatchTiebreakFormat,
    ) = SetObjectCodec.encode(encoder, descriptor, value, writeRegularKind = false)

    override fun deserialize(decoder: Decoder): MatchTiebreakFormat =
        SetObjectCodec.decode(decoder, descriptor) as? MatchTiebreakFormat
            ?: throw SerializationException("Match-Tiebreak-Objekt braucht kind = MATCH_TIEBREAK")
}
