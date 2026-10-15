package eu.advantly.device.link

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinkFrameTest {
    @Test
    fun dataFrameRoundTrip() {
        val payload = """{"v":1,"type":"Events"}""".encodeToByteArray()
        val decoded = assertNotNull(LinkFrame.decode(LinkFrame.data("abc-ü", payload).encode()))
        assertEquals(LinkFrame.Type.DATA, decoded.type)
        assertEquals("abc-ü", decoded.messageId)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun ackFrameHasNoPayload() {
        val decoded = assertNotNull(LinkFrame.decode(LinkFrame.ack("id1").encode()))
        assertEquals(LinkFrame.Type.ACK, decoded.type)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun emptyPayloadIsAllowed() {
        val decoded = assertNotNull(LinkFrame.decode(LinkFrame.data("x", ByteArray(0)).encode()))
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun malformedFramesAreRejected() {
        val valid = LinkFrame.data("id", byteArrayOf(1, 2)).encode()
        assertNull(LinkFrame.decode(ByteArray(0)))
        assertNull(LinkFrame.decode("hello".encodeToByteArray()))
        assertNull(LinkFrame.decode(valid.copyOf().also { it[1] = 9 })) // falsche Version
        assertNull(LinkFrame.decode(valid.copyOf().also { it[2] = 7 })) // unbekannter Typ
        assertNull(LinkFrame.decode(valid.copyOf(6))) // ID abgeschnitten
        assertNull(LinkFrame.decode(LinkFrame.data("", ByteArray(0)).encode())) // leere ID
    }
}
