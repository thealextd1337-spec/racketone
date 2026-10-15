package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.scoreEvents
import eu.advantly.device.link.DeliveryResult
import eu.advantly.device.link.LinkTransport
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.PeerKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Queue-Kaltstart (Review W1-I Runde 2): Ein Frame aus der DataItem-Queue wird mit `onConsumed` übergeben. Die
 * Bestätigung – nach der das Item gelöscht wird – kommt erst, wenn die Session die Events gespeichert hat.
 */
class QueueConfirmationTest {
    /** Transport, dessen gesendete Frames der Test selbst (wie aus einer Queue) zustellt. */
    private class HandTransport(
        private val peer: String,
    ) : LinkTransport {
        var listener: LinkTransport.Listener? = null
        val outbox = mutableListOf<ByteArray>()

        override fun start(listener: LinkTransport.Listener) {
            this.listener = listener
            listener.onPeersChanged(listOf(peer), available = true)
        }

        override fun stop() {
            listener = null
        }

        override fun send(
            peer: String,
            frame: ByteArray,
            onResult: (DeliveryResult) -> Unit,
        ) {
            outbox += frame
            onResult(DeliveryResult.QUEUED)
        }
    }

    @Test
    fun queuedFramesAreConfirmedOnlyAfterTheSessionPersistedThem() {
        val scheduler = ManualScheduler()
        val watchTransport = HandTransport("phone")
        val phoneTransport = HandTransport("watch")
        val watch =
            MatchSession(
                testLocalData(),
                ReliableDeviceLink(watchTransport, scheduler, newMessageId = { "w-${Random.nextInt()}" }),
                scheduler,
                SessionConfig(DeviceId("watch"), PeerKind.WATCH),
                { 1_000 },
                RandomIdGenerator(Random(1)),
            )
        val phoneData = testLocalData()
        val phone =
            MatchSession(
                phoneData,
                ReliableDeviceLink(phoneTransport, scheduler, newMessageId = { "p-${Random.nextInt()}" }),
                scheduler,
                SessionConfig(DeviceId("phone"), PeerKind.PHONE),
                { 1_000 },
                RandomIdGenerator(Random(2)),
            )
        watch.start()
        phone.start()
        scheduler.runUntilIdle()

        val id = watch.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        watch.recordPoint(Side.A)
        scheduler.runUntilIdle()
        assertTrue(watchTransport.outbox.isNotEmpty())

        // Wie der Service beim Kaltstart: alle Queue-Frames mit Verarbeitungsbestätigung übergeben
        val storedAtConfirmation = mutableListOf<Int>()
        watchTransport.outbox.toList().forEach { frame ->
            checkNotNull(phoneTransport.listener).onFrame("watch", frame) {
                storedAtConfirmation += phoneData.events.scoreEvents(id).size
            }
        }
        assertTrue(storedAtConfirmation.isEmpty(), "Bestätigung erst nach Verarbeitung auf dem Scheduler-Thread")
        scheduler.runUntilIdle()

        assertEquals(watchTransport.outbox.size, storedAtConfirmation.size, "jedes Item bestätigt")
        assertEquals(2, phoneData.events.scoreEvents(id).size, "Genesis + Punkt gespeichert")
        // spätestens die Bestätigung des letzten Frames sieht beide Events in der Datenbank
        assertEquals(2, storedAtConfirmation.max())
        assertTrue(storedAtConfirmation.zipWithNext().all { (a, b) -> a <= b })
    }
}
