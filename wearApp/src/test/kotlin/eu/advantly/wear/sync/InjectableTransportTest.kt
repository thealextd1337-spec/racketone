package eu.advantly.wear.sync

import eu.advantly.device.link.DeliveryResult
import eu.advantly.device.link.DeviceLinkListener
import eu.advantly.device.link.LinkTransport
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hintergrund-Empfang: ein vom Listener-Service übergebener Frame landet einmal im Link und wird bestätigt. */
class InjectableTransportTest {
    private class RecordingTransport : LinkTransport {
        val sent = mutableListOf<Pair<String, ByteArray>>()
        var started = false

        override fun start(listener: LinkTransport.Listener) {
            started = true
        }

        override fun stop() {
            started = false
        }

        override fun send(
            peer: String,
            frame: ByteArray,
            onResult: (DeliveryResult) -> Unit,
        ) {
            sent += peer to frame
            onResult(DeliveryResult.SENT)
        }
    }

    @Test
    fun injectedFramesAreDeliveredOnceAndAcked() {
        val scheduler = ManualScheduler()
        val platform = RecordingTransport()
        val transport = InjectableTransport(platform)
        val link = ReliableDeviceLink(transport, scheduler)
        val received = mutableListOf<String>()
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
        // Frame so erzeugen, wie das Handy ihn sendet (eigener Link über einen aufzeichnenden Transport)
        val phoneTransport = RecordingTransport()
        ReliableDeviceLink(phoneTransport, scheduler).apply { start() }.send("watch", "hallo".encodeToByteArray())
        val frame = phoneTransport.sent.single().second
        assertFalse("Link noch nicht gestartet", transport.inject("phone", frame))
        link.start()
        assertTrue(platform.started)
        assertTrue(transport.inject("phone", frame))
        assertTrue(transport.inject("phone", frame)) // Live-Listener + Service: Duplikat
        scheduler.runUntilIdle()
        assertEquals(listOf("hallo"), received)
        assertEquals(2, link.stats.duplicatesDropped + link.stats.delivered)
        // Jede Zustellung wird bestätigt (auch das Duplikat)
        assertEquals(2, platform.sent.count { it.first == "phone" })
        link.stop()
        assertFalse(transport.inject("phone", frame))
    }
}
