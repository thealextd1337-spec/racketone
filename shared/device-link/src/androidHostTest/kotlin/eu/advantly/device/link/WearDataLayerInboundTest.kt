package eu.advantly.device.link

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Weiterleitung vom WearableListenerService an den laufenden Transport (Hintergrund-Empfang, W1-I). */
class WearDataLayerInboundTest {
    private val received = mutableListOf<Pair<String, String>>()
    private var consumedCallbacks = mutableListOf<() -> Unit>()
    private val sink: (String, ByteArray, () -> Unit) -> Unit = { node, frame, onConsumed ->
        received += node to frame.decodeToString()
        consumedCallbacks += onConsumed
    }

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
        val newer: (String, ByteArray, () -> Unit) -> Unit = { _, _, _ -> received += "new" to "" }
        WearDataLayerInbound.attach(sink)
        WearDataLayerInbound.attach(newer)
        WearDataLayerInbound.detach(sink)
        assertTrue(WearDataLayerInbound.deliverMessage("w", WearDataLayerTransport.MESSAGE_PATH, ByteArray(0)))
        assertEquals(listOf("new" to ""), received)
        WearDataLayerInbound.detach(newer)
    }

    /** Queue-Kaltstart (Review W1-I Runde 2): nur Items für dieses Gerät, Bestätigung erst nach der Verarbeitung. */
    @Test
    fun queuedItemsForThisNodeAreDeliveredWithProcessingConfirmation() {
        WearDataLayerInbound.attach(sink)
        val path = listOf("advantly", "link", "q", "phone-node", "17")
        var confirmed = 0
        assertFalse(WearDataLayerInbound.deliverQueuedItem("other-node", "watch", path, "a".encodeToByteArray()) { confirmed++ })
        assertFalse(
            WearDataLayerInbound.deliverQueuedItem("phone-node", "watch", listOf("x", "y"), "a".encodeToByteArray()) { confirmed++ },
        )
        assertFalse(WearDataLayerInbound.deliverQueuedItem("phone-node", null, path, "a".encodeToByteArray()) { confirmed++ })
        assertFalse(WearDataLayerInbound.deliverQueuedItem("phone-node", "watch", path, null) { confirmed++ })
        assertTrue(WearDataLayerInbound.deliverQueuedItem("phone-node", "watch", path, "frame".encodeToByteArray()) { confirmed++ })
        assertEquals(listOf("watch" to "frame"), received)
        assertEquals(0, confirmed, "erst nach Verarbeitung bestätigen")
        consumedCallbacks.single().invoke()
        assertEquals(1, confirmed)
    }
}
