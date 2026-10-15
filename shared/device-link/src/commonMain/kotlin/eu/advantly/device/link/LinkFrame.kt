package eu.advantly.device.link

/**
 * Binäres Rahmenformat auf dem Draht (klein, ohne Serialisierungs-Bibliothek):
 * ```
 * [0]    0xAD  Magic
 * [1]    1     Rahmen-Version
 * [2]    Typ   1 = DATA, 2 = ACK
 * [3..4] Länge der Nachrichten-ID (uint16, big endian)
 * [..]   Nachrichten-ID (UTF-8)
 * [..]   Nutzdaten (nur DATA)
 * ```
 */
internal class LinkFrame(
    val type: Type,
    val messageId: String,
    val payload: ByteArray,
) {
    enum class Type(
        val code: Byte,
    ) {
        DATA(1),
        ACK(2),
    }

    fun encode(): ByteArray {
        val id = messageId.encodeToByteArray()
        require(id.size <= 0xFFFF) { "Nachrichten-ID zu lang" }
        val out = ByteArray(HEADER_SIZE + id.size + payload.size)
        out[0] = MAGIC
        out[1] = VERSION
        out[2] = type.code
        out[3] = (id.size ushr 8).toByte()
        out[4] = id.size.toByte()
        id.copyInto(out, HEADER_SIZE)
        payload.copyInto(out, HEADER_SIZE + id.size)
        return out
    }

    companion object {
        private const val MAGIC: Byte = 0xAD.toByte()
        private const val VERSION: Byte = 1
        private const val HEADER_SIZE = 5

        fun data(
            messageId: String,
            payload: ByteArray,
        ) = LinkFrame(Type.DATA, messageId, payload)

        fun ack(messageId: String) = LinkFrame(Type.ACK, messageId, ByteArray(0))

        /** `null` bei fremden/kaputten Rahmen (werden gezählt und verworfen). */
        fun decode(bytes: ByteArray): LinkFrame? {
            if (bytes.size < HEADER_SIZE || bytes[0] != MAGIC || bytes[1] != VERSION) return null
            val type = Type.entries.firstOrNull { it.code == bytes[2] } ?: return null
            val idLength = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
            val idEnd = HEADER_SIZE + idLength
            if (idLength == 0 || idEnd > bytes.size) return null
            val id = bytes.decodeToString(HEADER_SIZE, idEnd)
            return LinkFrame(type, id, bytes.copyOfRange(idEnd, bytes.size))
        }
    }
}
