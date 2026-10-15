package eu.advantly.device.link

import eu.advantly.device.link.testing.ManualScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regressionstests Review S3: Wear-DataItems werden nur nach bestätigter Verarbeitung gelöscht. */
class WearQueueInboxTest {
    private val deleted = mutableListOf<String>()
    private val inbox = WearQueueInbox { deleted += it }
    private val scheduler = ManualScheduler()
    private val received = mutableListOf<String>()
    private lateinit var linkListener: LinkTransport.Listener

    /** Echter ReliableDeviceLink; der Fake-Transport reicht nur dessen Listener heraus. */
    private val link =
        ReliableDeviceLink(
            object : LinkTransport {
                override fun start(listener: LinkTransport.Listener) {
                    linkListener = listener
                }

                override fun stop() = Unit

                override fun send(
                    peer: String,
                    frame: ByteArray,
                    onResult: (DeliveryResult) -> Unit,
                ) = onResult(DeliveryResult.SENT)
            },
            scheduler,
        )

    init {
        link.setListener(
            object : DeviceLinkListener {
                override fun onMessage(
                    peer: String,
                    messageId: String,
                    payload: ByteArray,
                ) {
                    received += payload.decodeToString()
                }
            },
        )
        link.start()
    }

    private fun frame(text: String) = LinkFrame.data("id-$text", text.encodeToByteArray()).encode()

    private fun segments(target: String) = listOf("advantly", "link", "q", target, "42")

    @Test
    fun itemIsDeletedOnlyAfterProcessing() {
        val gen = inbox.start(linkListener)
        inbox.setLocalNode(gen, "me")
        assertTrue(inbox.offer(gen, "wear://other/advantly/link/q/me/42", "other", segments("me"), frame("a")))
        assertTrue(deleted.isEmpty(), "Übergabe allein löscht nicht")
        scheduler.runUntilIdle()
        assertEquals(listOf("a"), received)
        assertEquals(listOf("wear://other/advantly/link/q/me/42"), deleted)
    }

    @Test
    fun lateGetDataItemsResultAfterStopIsIgnored() {
        // start → getDataItems unterwegs → stop → Ergebnis kommt an
        val gen = inbox.start(linkListener)
        inbox.setLocalNode(gen, "me")
        inbox.stop()
        assertFalse(inbox.offer(gen, "k", "other", segments("me"), frame("a")))
        scheduler.runUntilIdle()
        assertTrue(received.isEmpty())
        assertTrue(deleted.isEmpty(), "Item bleibt für den nächsten Start liegen")
    }

    @Test
    fun resultOfPreviousStartIsIgnoredAfterRestart() {
        val old = inbox.start(linkListener)
        inbox.setLocalNode(old, "me")
        val current = inbox.start(linkListener)
        inbox.setLocalNode(old, "stale") // verspätet → ignoriert
        inbox.setLocalNode(current, "me")
        assertFalse(inbox.offer(old, "k1", "other", segments("me"), frame("a")))
        assertTrue(inbox.offer(current, "k2", "other", segments("me"), frame("b")))
        scheduler.runUntilIdle()
        assertEquals(listOf("b"), received)
        assertEquals(listOf("k2"), deleted)
    }

    @Test
    fun linkStoppedBetweenHandOverAndProcessingKeepsItem() {
        val gen = inbox.start(linkListener)
        inbox.setLocalNode(gen, "me")
        assertTrue(inbox.offer(gen, "k", "other", segments("me"), frame("a")))
        link.stop()
        scheduler.runUntilIdle()
        assertTrue(received.isEmpty())
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun foreignOrMalformedItemsAreNotTouched() {
        val gen = inbox.start(linkListener)
        assertFalse(inbox.offer(gen, "k", "other", segments("me"), frame("a")), "eigene Node-ID noch unbekannt")
        inbox.setLocalNode(gen, "me")
        assertFalse(inbox.offer(gen, "k", "other", segments("someone-else"), frame("a")))
        assertFalse(inbox.offer(gen, "k", "other", listOf("other", "path"), frame("a")))
        assertFalse(inbox.offer(gen, "k", null, segments("me"), frame("a")))
        scheduler.runUntilIdle()
        assertTrue(received.isEmpty())
        assertTrue(deleted.isEmpty())
    }
}
