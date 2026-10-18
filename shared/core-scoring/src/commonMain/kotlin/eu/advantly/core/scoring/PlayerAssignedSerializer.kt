package eu.advantly.core.scoring

import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/** Erhält die alte Feldform und trennt sie strikt von namensfreien Referenzen. */
@OptIn(ExperimentalSerializationApi::class)
object PlayerAssignedSerializer : KSerializer<PlayerAssigned> {
    override val descriptor =
        buildClassSerialDescriptor("PlayerAssigned") {
            element("slot", PlayerId.serializer().descriptor)
            element("player", Player.serializer().nullable.descriptor, isOptional = true)
            element("assignedBy", PlayerId.serializer().descriptor)
            element<Boolean>("legacySlot", isOptional = true)
            element("creatorAlias", PlayerId.serializer().nullable.descriptor, isOptional = true)
            element<Int>("assignmentVersion", isOptional = true)
            element("playerId", PlayerId.serializer().nullable.descriptor, isOptional = true)
        }

    override fun serialize(
        encoder: Encoder,
        value: PlayerAssigned,
    ) {
        if (!value.hasValidForm()) throw SerializationException("Ungültige PlayerAssigned-Feldkombination")
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, PlayerId.serializer(), value.slot)
            if (value.assignmentVersion == 1) {
                encodeNullableSerializableElement(descriptor, 1, Player.serializer(), value.player)
            }
            encodeSerializableElement(descriptor, 2, PlayerId.serializer(), value.assignedBy)
            if (value.legacySlot || shouldEncodeElementDefault(descriptor, 3)) {
                encodeBooleanElement(descriptor, 3, value.legacySlot)
            }
            if (value.creatorAlias != null || shouldEncodeElementDefault(descriptor, 4)) {
                encodeNullableSerializableElement(descriptor, 4, PlayerId.serializer(), value.creatorAlias)
            }
            if (value.assignmentVersion == 2) {
                encodeIntElement(descriptor, 5, 2)
                // Auch bei einem Encoder mit weggelassenen optionalen Nullwerten bleibt die Aufhebung explizit.
                encodeSerializableElement(descriptor, 6, PlayerId.serializer().nullable, value.playerId)
            }
        }
    }

    override fun deserialize(decoder: Decoder): PlayerAssigned =
        decoder.decodeStructure(descriptor) {
            var slot: PlayerId? = null
            var player: Player? = null
            var assignedBy: PlayerId? = null
            var legacySlot = false
            var creatorAlias: PlayerId? = null
            var version = 1
            var playerId: PlayerId? = null
            val present = mutableSetOf<Int>()
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                if (!present.add(index)) throw SerializationException("Doppeltes PlayerAssigned-Feld: $index")
                when (index) {
                    0 -> slot = decodeSerializableElement(descriptor, index, PlayerId.serializer())
                    1 -> player = decodeNullableSerializableElement(descriptor, index, Player.serializer())
                    2 -> assignedBy = decodeSerializableElement(descriptor, index, PlayerId.serializer())
                    3 -> legacySlot = decodeBooleanElement(descriptor, index)
                    4 -> creatorAlias = decodeNullableSerializableElement(descriptor, index, PlayerId.serializer())
                    5 -> version = decodeIntElement(descriptor, index)
                    6 -> playerId = decodeNullableSerializableElement(descriptor, index, PlayerId.serializer())
                    else -> throw SerializationException("Unbekanntes PlayerAssigned-Feld: $index")
                }
            }
            if (slot == null || assignedBy == null) throw SerializationException("PlayerAssigned benötigt slot und assignedBy")
            // Legacy ohne Versionsfeld lässt nullable player aus; explizite Version 1 bleibt strenger.
            val validFields =
                when (version) {
                    1 -> 6 !in present && (1 in present || 5 !in present)
                    2 -> 5 in present && 6 in present && 1 !in present
                    else -> false
                }
            if (!validFields) throw SerializationException("Ungültige PlayerAssigned-Version oder Feldform")
            PlayerAssigned(slot, player, assignedBy, legacySlot, creatorAlias, version, playerId)
        }
}

/** Auch direkte Kotlin-Events dürfen den versionsabhängigen Feldvertrag nicht umgehen. */
internal fun PlayerAssigned.hasValidForm(): Boolean =
    when (assignmentVersion) {
        1 -> playerId == null
        2 -> player == null
        else -> false
    }
