package eu.advantly.device.link

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Weiterleitung vom WearableListenerService an den laufenden Transport (Hintergrund-Empfang, W1-I). */
class WearDataLayerInboundTest {
    private val received = mutableListOf<Pair<String, String>>()
    private val sink: (String, ByteArray) -> Unit = { node, frame -> received += node to frame.decodeToString() }

    @AfterTest
    fun tearDown() = WearDataLayerInbound.detach(sink)

    @Test
    fun deliversOnlyLinkMessagesWhileATransportIsAttached() {
        assertFalse(WearDataLayerInbound.deliverMessage("watch", WearDataLayerTransport.MESSAGE_PATH, "x".encodeToByteArray()))
        WearDataLayerInbound.attach(sink)
        assertTrue(WearDataLayerInbound.isAttached)
        assertFalse(WearDataLayerInbound.deliverMessage("watch", "/andere/app", "y".encodeToByteArray()))
        assertTrue(WearDataLayerInbound.deliverMessage("watch", WearDataLayerTransport.MESSAGE_PATH, "frame".encodeToByteArray()))
        assertEquals(listOf("watch" to "frame"), received)
    }

    @Test
    fun detachingAnOldTransportKeepsTheNewOne() {
        val newer: (String, ByteArray) -> Unit = { _, _ -> received += "new" to "" }
        WearDataLayerInbound.attach(sink)
        WearDataLayerInbound.attach(newer)
        WearDataLayerInbound.detach(sink)
        assertTrue(WearDataLayerInbound.deliverMessage("w", WearDataLayerTransport.MESSAGE_PATH, ByteArray(0)))
        assertEquals(listOf("new" to ""), received)
        WearDataLayerInbound.detach(newer)
    }
}
