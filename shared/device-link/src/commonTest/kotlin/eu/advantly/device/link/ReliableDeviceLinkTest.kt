package eu.advantly.device.link

import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReliableDeviceLinkTest {
    private val scheduler = ManualScheduler()
    private val network = InMemoryLinkNetwork(scheduler, latencyMs = 20)
    private val policy =
        RetryPolicy(ackTimeoutMs = 100, backoffFactor = 2, maxAckTimeoutMs = 400, maxAttempts = 4, queuedAckTimeoutMs = 10_000)
    private var ids = 0

    private fun link(node: String) = ReliableDeviceLink(network.transport(node), scheduler, policy) { "$node-${++ids}" }

    private val phone = link("phone")
    private val watch = link("watch")
    private val received = mutableListOf<String>()
    private val results = mutableListOf<String>()

    private val recorder =
        object : SendCallback {
            override fun onAcked(
                messageId: String,
                attempts: Int,
                latencyMs: Long,
            ) {
                results += "acked $messageId attempts=$attempts latency=$latencyMs"
            }

            override fun onQueued(messageId: String) {
                results += "queued $messageId"
            }

            override fun onFailed(
                messageId: String,
                attempts: Int,
                reason: SendFailure,
            ) {
                results += "failed $messageId attempts=$attempts $reason"
            }
        }

    init {
        watch.setListener(
            object : DeviceLinkListener {
                override fun onMessage(
                    peer: String,
                    messageId: String,
                    payload: ByteArray,
                ) {
                    received += "$peer:${payload.decodeToString()}"
                }
            },
        )
        phone.start()
        watch.start()
        scheduler.runUntilIdle()
    }

    private fun isData(frame: ByteArray) = LinkFrame.decode(frame)?.type == LinkFrame.Type.DATA

    @Test
    fun messageIsDeliveredAndAcked() {
        val id = phone.send("watch", "hello".encodeToByteArray(), recorder)
        scheduler.advanceBy(1_000)
        assertEquals(listOf("phone:hello"), received)
        assertEquals(listOf("acked $id attempts=1 latency=40"), results)
        assertEquals(LinkStats(framesSent = 1, acked = 1), phone.stats)
        assertEquals(1, watch.stats.delivered)
    }

    @Test
    fun lostDataFrameIsRetried() {
        var dropped = 0
        network.dropFilter = { _, _, frame -> !(isData(frame) && dropped++ == 0) }
        val id = phone.send("watch", "x".encodeToByteArray(), recorder)
        scheduler.advanceBy(1_000)
        assertEquals(listOf("phone:x"), received)
        // erster Versuch verloren, Timeout 100 ms, zweiter Versuch + 40 ms Roundtrip
        assertEquals(listOf("acked $id attempts=2 latency=140"), results)
        assertEquals(1, phone.stats.retries)
    }

    @Test
    fun lostAckLeadsToDuplicateThatIsDroppedButAckedAgain() {
        var acksDropped = 0
        network.dropFilter = { from, _, frame -> !(from == "watch" && !isData(frame) && acksDropped++ == 0) }
        val id = phone.send("watch", "x".encodeToByteArray(), recorder)
        scheduler.advanceBy(1_000)
        assertEquals(listOf("phone:x"), received, "genau einmal ausgeliefert")
        assertEquals(listOf("acked $id attempts=2 latency=140"), results)
        assertEquals(1, watch.stats.duplicatesDropped)
    }

    @Test
    fun networkDuplicatesAreDeliveredOnce() {
        network.duplicateRate = 1.0
        repeat(5) { phone.send("watch", "m$it".encodeToByteArray(), recorder) }
        scheduler.advanceBy(1_000)
        assertEquals((0 until 5).map { "phone:m$it" }, received)
        assertEquals(5, watch.stats.duplicatesDropped)
        assertEquals(5, phone.stats.acked)
        // Jede DATA-Kopie wird bestätigt und jedes Ack ebenfalls verdoppelt: 4 Acks je Nachricht, 1 gilt, 3 sind spät
        assertEquals(15, phone.stats.lateAcks, "doppelte Acks werden gezählt und ignoriert")
    }

    @Test
    fun givesUpAfterMaxAttemptsWithoutQueue() {
        network.setConnected("watch", false)
        scheduler.runUntilIdle()
        val id = phone.send("watch", "x".encodeToByteArray(), recorder)
        scheduler.advanceBy(10_000)
        assertEquals(listOf("failed $id attempts=4 NO_ACK"), results)
        assertEquals(4, phone.stats.framesSent)
        assertEquals(3, phone.stats.retries)
        assertEquals(1, phone.stats.failed)
        assertTrue(received.isEmpty())
    }

    @Test
    fun queuedMessageIsDeliveredAfterReconnectWithoutLiveRetries() {
        network.queueWhenDisconnected = true
        network.setConnected("watch", false)
        scheduler.runUntilIdle()
        val id = phone.send("watch", "offline".encodeToByteArray(), recorder)
        scheduler.advanceBy(5_000)
        assertEquals(listOf("queued $id"), results)
        assertEquals(1, phone.stats.framesSent, "keine Live-Wiederholungen, solange die OS-Queue zuständig ist")

        network.setConnected("watch", true)
        scheduler.advanceBy(1_000)
        assertEquals(listOf("phone:offline"), received)
        assertEquals("acked $id attempts=1 latency=5040", results.last())
    }

    @Test
    fun queuedMessageFailsAfterQueuedAckTimeout() {
        network.queueWhenDisconnected = true
        network.setConnected("watch", false)
        scheduler.runUntilIdle()
        val id = phone.send("watch", "x".encodeToByteArray(), recorder)
        scheduler.advanceBy(10_001)
        assertEquals(listOf("queued $id", "failed $id attempts=1 QUEUED_NO_ACK"), results)
    }

    @Test
    fun reportsConnectionState() {
        val states = mutableListOf<String>()
        phone.setListener(
            object : DeviceLinkListener {
                override fun onMessage(
                    peer: String,
                    messageId: String,
                    payload: ByteArray,
                ) = Unit

                override fun onStateChanged(
                    state: LinkState,
                    peers: List<String>,
                ) {
                    states += "$state$peers"
                }
            },
        )
        assertEquals(LinkState.CONNECTED, phone.state)
        assertEquals(listOf("watch"), phone.peers)
        network.setConnected("watch", false)
        scheduler.runUntilIdle()
        network.setConnected("watch", true)
        scheduler.runUntilIdle()
        assertEquals(listOf("DISCONNECTED[]", "CONNECTED[watch]"), states)
    }

    @Test
    fun reconnectRetransmitsImmediately() {
        network.setConnected("watch", false)
        scheduler.runUntilIdle()
        val id = phone.send("watch", "x".encodeToByteArray(), recorder) // Versuch 1 scheitert, Timer 100 ms
        scheduler.advanceBy(50)
        network.setConnected("watch", true) // sofortiger Versuch 2 statt bei t=100
        scheduler.advanceBy(41)
        assertEquals(listOf("acked $id attempts=2 latency=90"), results)
    }

    @Test
    fun stopFailsPendingMessages() {
        network.setConnected("watch", false)
        scheduler.runUntilIdle()
        val id = phone.send("watch", "x".encodeToByteArray(), recorder)
        phone.stop()
        assertEquals(listOf("failed $id attempts=1 STOPPED"), results)
        assertEquals(LinkState.DISCONNECTED, phone.state)
        scheduler.advanceBy(10_000)
        assertEquals(1, results.size, "keine Timer nach stop()")
    }

    @Test
    fun malformedFramesAreCounted() {
        val raw = network.transport("raw")
        raw.start(
            object : LinkTransport.Listener {
                override fun onFrame(
                    peer: String,
                    frame: ByteArray,
                    onConsumed: () -> Unit,
                ) = Unit

                override fun onPeersChanged(
                    peers: List<String>,
                    available: Boolean,
                ) = Unit
            },
        )
        raw.send("watch", "garbage".encodeToByteArray()) {}
        scheduler.advanceBy(100)
        assertEquals(1, watch.stats.malformedFrames)
        assertTrue(received.isEmpty())
    }

    private fun collector(into: MutableList<String>) =
        object : DeviceLinkListener {
            override fun onMessage(
                peer: String,
                messageId: String,
                payload: ByteArray,
            ) {
                into += payload.decodeToString()
            }
        }

    @Test
    fun dedupWindowIsBoundedSoEvictedIdsAreDeliveredAgain() {
        // Dokumentierte Grenze: nur die letzten dedupCapacity IDs je Gegenstelle werden erkannt (at-least-once)
        val small = policy.copy(dedupCapacity = 2)
        val sender = ReliableDeviceLink(network.transport("p"), scheduler, small) { "p-${++ids}" }
        val receiver = ReliableDeviceLink(network.transport("w"), scheduler, small)
        val got = mutableListOf<String>()
        receiver.setListener(collector(got))
        sender.start()
        receiver.start()
        scheduler.runUntilIdle()
        var acksDropped = 0
        network.dropFilter = { from, _, frame -> !(from == "w" && !isData(frame) && acksDropped++ == 0) }
        sender.send("w", "m0".encodeToByteArray(), recorder) // Ack geht verloren → Wiederholung nach 100 ms
        sender.send("w", "m1".encodeToByteArray(), recorder)
        sender.send("w", "m2".encodeToByteArray(), recorder) // verdrängt m0 aus dem Fenster
        scheduler.advanceBy(1_000)
        assertEquals(listOf("m0", "m1", "m2", "m0"), got)
        assertEquals(3, sender.stats.acked)
    }

    @Test
    fun dedupHistoryDoesNotSurviveRestart() {
        var acksDropped = 0
        network.dropFilter = { from, _, frame -> !(from == "watch" && !isData(frame) && acksDropped++ == 0) }
        phone.send("watch", "x".encodeToByteArray(), recorder)
        scheduler.advanceBy(30) // zugestellt, Ack verloren
        assertEquals(listOf("phone:x"), received)
        watch.stop() // App-Neustart der Uhr: Dedup-Gedächtnis ist weg
        val restarted = mutableListOf<String>()
        val watchAfterRestart = ReliableDeviceLink(network.transport("watch"), scheduler, policy)
        watchAfterRestart.setListener(collector(restarted))
        watchAfterRestart.start()
        scheduler.advanceBy(1_000)
        assertEquals(listOf("x"), restarted, "nach Neustart erneut ausgeliefert – Empfänger muss idempotent sein")
        assertTrue(results.single().startsWith("acked"))
    }

    @Test
    fun framesAreConsumedOnlyAfterProcessingAndNeverAfterStop() {
        val consumed = mutableListOf<String>()
        val got = mutableListOf<String>()
        val captured = mutableListOf<LinkTransport.Listener>()
        val probe =
            ReliableDeviceLink(
                object : LinkTransport {
                    override fun start(listener: LinkTransport.Listener) {
                        captured += listener
                    }

                    override fun stop() = Unit

                    override fun send(
                        peer: String,
                        frame: ByteArray,
                        onResult: (DeliveryResult) -> Unit,
                    ) = onResult(DeliveryResult.SENT)
                },
                scheduler,
                policy,
            )
        probe.setListener(collector(got))
        probe.start()
        captured.single().onFrame("peer", LinkFrame.data("a", "1".encodeToByteArray()).encode()) { consumed += "a" }
        assertTrue(consumed.isEmpty(), "noch nicht verarbeitet")
        scheduler.runUntilIdle()
        assertEquals(listOf("a"), consumed)
        captured.single().onFrame("peer", LinkFrame.data("b", "2".encodeToByteArray()).encode()) { consumed += "b" }
        probe.stop()
        scheduler.runUntilIdle()
        assertEquals(listOf("a"), consumed, "nach stop() nie als verarbeitet melden")
        assertEquals(listOf("1"), got)
    }

    @Test
    fun lossyNetworkDeliversEverythingExactlyOnce() {
        network.lossRate = 0.3
        network.duplicateRate = 0.1
        val lossy = ReliableDeviceLink(network.transport("phone2"), scheduler, policy.copy(maxAttempts = 12)) { "p2-${++ids}" }
        lossy.start()
        scheduler.runUntilIdle()
        repeat(200) { lossy.send("watch", "m$it".encodeToByteArray(), recorder) }
        scheduler.advanceBy(60_000)
        assertEquals((0 until 200).map { "phone2:m$it" }.toSet(), received.toSet())
        assertEquals(200, received.size, "keine Doppelauslieferung")
        assertEquals(200, lossy.stats.acked)
        assertEquals(0, lossy.stats.failed)
        assertTrue(lossy.stats.retries > 0)
    }
}
