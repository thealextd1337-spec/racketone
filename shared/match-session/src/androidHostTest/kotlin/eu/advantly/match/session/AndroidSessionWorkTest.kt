package eu.advantly.match.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.data.LocalData
import eu.advantly.data.db.AdvantlyDatabase
import eu.advantly.device.link.Cancellable
import eu.advantly.device.link.DeliveryResult
import eu.advantly.device.link.DeviceLinkListener
import eu.advantly.device.link.LinkScheduler
import eu.advantly.device.link.LinkTransport
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.ManualScheduler
import java.io.IOException
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidSessionWorkTest {
    private fun discardBetweenPrecheckAndTransition(hold: Boolean) {
        Fixture().use { f ->
            val checked = java.util.concurrent.CountDownLatch(1)
            val discarded = java.util.concurrent.CountDownLatch(1)
            val original = IOException("background discard after successful precheck")
            var actions = 0
            var outputs = 0
            f.work.post {
                actions++
                f.work.output { outputs++ }
            }
            val background =
                Thread {
                    check(checked.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    f.work.discard(original)
                    discarded.countDown()
                }
            background.start()
            f.work.throwIfFailed()
            checked.countDown()
            assertTrue(discarded.await(5, java.util.concurrent.TimeUnit.SECONDS))
            background.join(5_000)
            assertFalse(background.isAlive)
            val escaped = runCatching { if (hold) f.work.hold() else f.work.release() }.exceptionOrNull()
            assertSame(original, escaped)
            assertSame(original, assertFailsWith<IOException> { f.work.throwIfFailed() })
            f.run()
            assertEquals(0, actions)
            assertEquals(0, outputs)
        }
    }

    @Test
    fun releaseKeepsConcurrentDiscardFailureAfterSuccessfulPrecheck() = discardBetweenPrecheckAndTransition(hold = false)

    @Test
    fun holdKeepsConcurrentDiscardFailureAfterSuccessfulPrecheck() = discardBetweenPrecheckAndTransition(hold = true)

    @Test
    fun sendAfterHoldIsDroppedSilentlyAndHealthySendStillTransmits() {
        Fixture().use { f ->
            f.work.release()
            f.link.hold()
            assertEquals("", f.link.send("peer", byteArrayOf(1)))
            f.run()
            assertTrue(f.raw.frames.isEmpty())
            f.link.activate()
            assertTrue(f.link.send("peer", byteArrayOf(1)).isNotEmpty())
            f.run()
            assertEquals(1, f.raw.frames.size)
        }
    }

    @Test
    fun readingPeersAfterIdentityChangeRebindsTransportWithoutSending() {
        Fixture().use { f ->
            val old = f.raw
            f.data.settings.put("identity.epoch", "2")
            f.link.peers
            // Ohne Senden: der neue Transport muss Eingänge annehmen, sonst bleibt der Link taub (Uhr ohne Antwort).
            assertEquals(2, f.raws.size)
            assertEquals(1, old.stops)
            f.link.peers
            assertEquals(2, f.raws.size)
        }
    }

    @Test
    fun discardWithoutFailureRemainsProgrammingMisuse() {
        Fixture().use { f ->
            f.work.discard()
            assertFailsWith<IllegalStateException> { f.work.release() }
            assertFailsWith<IllegalStateException> { f.work.hold() }
        }
    }

    @Test
    fun healthyHoldReleaseKeepsSingleQueuedActionAndOutput() {
        Fixture().use { f ->
            var actions = 0
            var outputs = 0
            f.work.post {
                actions++
                f.work.output { outputs++ }
            }
            f.work.hold()
            f.run()
            assertEquals(0, actions)
            f.work.release()
            f.run()
            assertEquals(1, actions)
            assertEquals(1, outputs)
            f.work.hold()
            f.work.release()
            f.run()
            assertEquals(1, actions)
            assertEquals(1, outputs)
        }
    }

    private class Raw(
        val frames: MutableList<ByteArray> = mutableListOf(),
    ) : LinkTransport {
        override fun isTrustedCounterpart(peer: String) = true

        lateinit var listener: LinkTransport.Listener
        var stops = 0

        override fun start(listener: LinkTransport.Listener) {
            this.listener = listener
        }

        override fun stop() {
            stops++
        }

        override fun send(
            peer: String,
            frame: ByteArray,
            onResult: (DeliveryResult) -> Unit,
        ) {
            frames += frame
            onResult(DeliveryResult.SENT)
        }
    }

    @Test
    fun realScopeHelloMustNotSerializeOpenAnonymousMatchOrFavorites() {
        Fixture().use { f ->
            val session =
                MatchSession(
                    f.data,
                    f.link,
                    f.work,
                    SessionConfig(
                        eu.advantly.core.model
                            .DeviceId("anonymous-watch"),
                        eu.advantly.sync.protocol.PeerKind.WATCH,
                    ),
                    WallClock { 1_760_000_000_000 },
                    RandomIdGenerator(),
                )
            session.start()
            f.data.players.ensureSelf("Private owner", 0) {
                eu.advantly.core.model
                    .PlayerId("private-self")
            }
            val self =
                f.data.players
                    .self()!!
                    .id
            val old =
                session.create(
                    eu.advantly.core.scoring.Presets.TENNIS_SINGLES_BO3
                        .format(),
                    eu.advantly.core.model
                        .Lineup(
                            listOf(self),
                            listOf(
                                eu.advantly.core.model
                                    .PlayerId("private-opponent"),
                            ),
                            self,
                        ),
                )
            f.data.favorites.merge(
                eu.advantly.data.FavoriteLineup(
                    "private-favorite",
                    eu.advantly.core.model.Sport.TENNIS,
                    eu.advantly.core.model.Discipline.SINGLES,
                    listOf(self),
                    listOf(
                        eu.advantly.core.model
                            .PlayerId("private-opponent"),
                    ),
                    "private-name",
                    1,
                    1,
                ),
            )
            f.work.release()
            f.run()

            fun messages() =
                f.raw.frames.filter { it[2].toInt() == 1 }.map {
                    val end = 5 + ((it[3].toInt() and 255) shl 8) + (it[4].toInt() and 255)
                    it.copyOfRange(end, it.size).decodeToString()
                }

            // Ein unadressiertes Angebot fordert zuerst die echte aktuelle Watch-Challenge an.
            fun receive(
                hello: eu.advantly.sync.protocol.Hello,
                id: String,
            ) {
                val bytes =
                    eu.advantly.sync.protocol.SyncCodec
                        .encode(hello)
                val key = id.encodeToByteArray()
                f.raw.listener.onFrame("peer", byteArrayOf(0xad.toByte(), 1, 1, 0, key.size.toByte()) + key + bytes)
                f.run()
            }
            val offer =
                eu.advantly.sync.protocol.Hello(
                    eu.advantly.core.model
                        .DeviceId("phone"),
                    eu.advantly.sync.protocol.PeerKind.PHONE,
                    session = "phone-session",
                    identityProtocol = 1,
                    ownerContext =
                        eu.advantly.sync.protocol
                            .OwnerContext("authority", 1, "account-scope"),
                    localSources =
                        listOf(
                            eu.advantly.sync.protocol
                                .OwnSource("phone", "phone-self", "account-scope", "PHONE", 0),
                        ),
                )
            receive(offer, "offer")
            val response =
                messages()
                    .mapNotNull {
                        (
                            eu.advantly.sync.protocol.SyncCodec.decode(
                                it.encodeToByteArray(),
                            ) as? eu.advantly.sync.protocol.DecodeResult.Ok
                        )?.message
                    }.filterIsInstance<eu.advantly.sync.protocol.Hello>()
                    .last()
            f.raw.frames.clear()
            val oldRaw = f.raw
            val oldListener = oldRaw.listener
            var stopped = 0
            f.work.post {
                // Bereits serialisierte alte Bytes gehören auch bei reentrantem Hello zur alten Linkinstanz.
                f.link.send(
                    "peer",
                    "private-before-scope-change".encodeToByteArray(),
                    object : eu.advantly.device.link.SendCallback {
                        override fun onAcked(
                            messageId: String,
                            attempts: Int,
                            latencyMs: Long,
                        ) = error("old output acknowledged")

                        override fun onFailed(
                            messageId: String,
                            attempts: Int,
                            reason: eu.advantly.device.link.SendFailure,
                        ) {
                            assertEquals(eu.advantly.device.link.SendFailure.STOPPED, reason)
                            stopped++
                        }
                    },
                )
                receive(offer.copy(recipientSession = response.session), "accepted")
            }
            f.run()
            assertTrue(f.data.accountIdentities.managed)
            assertEquals("account-scope", f.data.accountIdentities.scope)
            assertEquals(1, stopped)
            assertEquals(1, oldRaw.stops)
            assertTrue(f.raw !== oldRaw)
            var staleConsumed = 0
            oldListener.onFrame("peer", byteArrayOf(1)) { staleConsumed++ }
            // Der neue Listener nimmt den gültig adressierten Folgesync weiter an.
            val fresh =
                messages()
                    .mapNotNull {
                        (
                            eu.advantly.sync.protocol.SyncCodec.decode(
                                it.encodeToByteArray(),
                            ) as? eu.advantly.sync.protocol.DecodeResult.Ok
                        )?.message
                    }.filterIsInstance<eu.advantly.sync.protocol.Hello>()
                    .last()
            var consumed = 0
            val payload =
                eu.advantly.sync.protocol.SyncCodec
                    .encode(offer.copy(recipientSession = fresh.session))
            f.raw.listener.onFrame("peer", byteArrayOf(0xad.toByte(), 1, 1, 0, 4) + "sync".encodeToByteArray() + payload) { consumed++ }
            f.run()
            assertEquals(1, consumed)
            assertEquals(0, staleConsumed)
            (f.scheduler as ManualScheduler).advanceBy(5_000)
            assertTrue(messages().isNotEmpty())
            assertTrue(
                messages().none {
                    old.value in it || "private-opponent" in it || "private-favorite" in it || "private-name" in it ||
                        "private-before-scope-change" in it
                },
                messages().joinToString("\n"),
            )
            assertTrue(f.data.events.count(old.value) > 0)
            assertTrue(f.data.favorites.get("private-favorite") != null)
        }
    }

    private class Fixture(
        val scheduler: LinkScheduler = ManualScheduler(),
        val driver: app.cash.sqldelight.db.SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), AdvantlyDatabase.Schema),
        private val closeDriver: Boolean = true,
    ) : AutoCloseable {
        val data = LocalData(driver)
        private val frames = mutableListOf<ByteArray>()
        val raws = mutableListOf<Raw>()
        val raw: Raw get() = raws.last()
        var allowed = true
        var onMessage: () -> Unit = {}
        var cause: Exception? = null
        lateinit var work: AndroidSessionWork
        val protected: AndroidProtectedTransport get() = transports.last()
        private val transports = mutableListOf<AndroidProtectedTransport>()
        val link: AndroidScopedLink

        init {
            work =
                AndroidSessionWork(
                    scheduler,
                    storage = { action ->
                        try {
                            action()
                            true
                        } catch (error: IOException) {
                            cause = error
                            allowed = false
                            work.discard(error)
                            raw.stop()
                            false
                        }
                    },
                    available = { allowed },
                    failed = {
                        cause = it
                        allowed = false
                        raw.stop()
                    },
                )
            link =
                AndroidScopedLink({ androidSessionIdentity(data) }) { current ->
                    val raw = Raw(frames).also { raws += it }
                    val protected = AndroidProtectedTransport(raw, work) { allowed && current() }.also { transports += it }
                    ReliableDeviceLink(protected, work)
                }

            link.setListener(
                object : DeviceLinkListener {
                    override fun onMessage(
                        peer: String,
                        messageId: String,
                        payload: ByteArray,
                    ) = onMessage()
                },
            )
            link.start()
            link.activate()
        }

        fun receive(consumed: () -> Unit = {}) = raw.listener.onFrame("peer", byteArrayOf(0xad.toByte(), 1, 1, 0, 1, 109, 9), consumed)

        fun run() = (scheduler as ManualScheduler).runUntilIdle()

        override fun close() {
            work.discard()
            protected.stop()
            if (closeDriver) driver.close()
        }
    }

    @Test
    fun realCommittedEventsSurviveFailedTaskAndReplayAfterRetryIsIdempotent() {
        val sender = WireNode("sender", 18, identifyPeer = false)
        val self =
            eu.advantly.core.model
                .PlayerId("sender-self")
        sender.data.players.ensureSelf("Sender", 0) { self }
        val match =
            sender.session.create(
                eu.advantly.core.scoring.Presets.TENNIS_SINGLES_BO3
                    .format(),
                eu.advantly.core.model
                    .Lineup(
                        listOf(self),
                        listOf(
                            eu.advantly.core.model
                                .PlayerId("opponent"),
                        ),
                        self,
                    ),
            )
        sender.session.recordPoint(eu.advantly.core.model.Side.A)
        val events = sender.events(match)
        val payload =
            eu.advantly.sync.protocol.SyncCodec
                .encode(
                    eu.advantly.sync.protocol
                        .Events(match, events),
                )
        val id = "durable-events".encodeToByteArray()
        val frame = byteArrayOf(0xad.toByte(), 1, 1, 0, id.size.toByte()) + id + payload

        fun receiver(
            f: Fixture,
            failAfter: Boolean,
        ): MatchSession {
            val link =
                object : eu.advantly.device.link.DeviceLink by f.link {
                    override fun setListener(listener: DeviceLinkListener?) {
                        f.link.setListener(
                            listener?.let { real ->
                                object : DeviceLinkListener by real {
                                    override fun onMessage(
                                        peer: String,
                                        messageId: String,
                                        payload: ByteArray,
                                    ) {
                                        real.onMessage(peer, messageId, payload)
                                        if (failAfter &&
                                            (
                                                eu.advantly.sync.protocol.SyncCodec.decode(
                                                    payload,
                                                ) as? eu.advantly.sync.protocol.DecodeResult.Ok
                                            )?.message is eu.advantly.sync.protocol.Events
                                        ) {
                                            throw IOException("storage read failed after committed events")
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            return MatchSessions.create(f.data, link, f.work, eu.advantly.sync.protocol.PeerKind.PHONE).also {
                it.start()
                f.link.activate()
            }
        }
        Fixture().use { first ->
            val old = receiver(first, true)
            first.work.release()
            var consumed = 0
            first.raw.listener.onFrame("peer", frame) { consumed++ }
            first.run()
            assertEquals(events.size.toLong(), first.data.events.count(match.value))
            assertEquals(0, consumed)
            assertTrue(first.raw.frames.isEmpty())
            assertFalse(first.allowed)
            Fixture(driver = first.driver, closeDriver = false).use { retry ->
                val fresh = receiver(retry, false)
                assertEquals(old.deviceId, fresh.deviceId)
                retry.work.release()
                retry.raw.listener.onFrame("peer", frame) { consumed++ }
                retry.run()
                assertEquals(1, consumed)
                assertEquals(events.size.toLong(), retry.data.events.count(match.value))
                assertEquals(
                    events.map { it.id.value },
                    retry.data.events
                        .eventsFor(match.value)
                        .map { it.id },
                )
                assertTrue(retry.raw.frames.any { it[2].toInt() == 2 })
            }
        }
    }

    @Test
    fun sendsAndQueueConsumptionWaitForSuccessfulSqlCommit() {
        Fixture().use { f ->
            var consumed = 0
            f.onMessage = {
                assertTrue(f.raw.frames.isEmpty())
                assertEquals(0, consumed)
                f.data.settings.put("task", "committed")
            }
            f.receive {
                assertNull(f.driver.currentTransaction())
                consumed++
            }
            f.run()
            assertTrue(f.raw.frames.isEmpty())
            f.work.release()
            f.run()
            assertEquals("committed", f.data.settings.get("task"))
            assertEquals(1, f.raw.frames.size)
            assertEquals(
                2,
                f.raw.frames
                    .single()[2]
                    .toInt(),
            )
            assertEquals(1, consumed)
        }
    }

    @Test
    fun failedTaskDiscardsOutputsButPreservesAlreadyCommittedLocalTransaction() {
        Fixture().use { f ->
            var consumed = 0
            f.onMessage = {
                f.data.settings.put("task", "already committed")
                throw IOException("storage failed")
            }
            f.work.release()
            f.receive { consumed++ }
            f.run()
            assertTrue(f.raw.frames.isEmpty())
            assertEquals(0, consumed)
            assertEquals("already committed", f.data.settings.get("task"))
            assertFalse(f.allowed)
            assertEquals("storage failed", f.cause?.message)
        }
    }

    @Test
    fun failedExplicitLocalTransactionCannotFlushOutputs() {
        Fixture().use { f ->
            var consumed = 0
            f.onMessage = {
                f.data.transaction {
                    f.data.settings.put("task", "not committed")
                    throw IOException("local transaction failed before commit")
                }
            }
            f.work.release()
            f.receive { consumed++ }
            f.run()
            assertTrue(f.raw.frames.isEmpty())
            assertEquals(0, consumed)
            assertNull(f.data.settings.get("task"))
        }
    }

    @Test
    fun cancelledOldGenerationNeverRunsOrConsumesAfterRetry() {
        val scheduler = ManualScheduler()
        Fixture(scheduler).use { old ->
            var oldCalls = 0
            old.onMessage = { oldCalls++ }
            old.work.release()
            old.receive { oldCalls++ }
            val staleListener = old.raw.listener
            old.work.discard(IOException("old generation failed"))
            old.protected.stop()
            Fixture(scheduler).use { fresh ->
                var freshCalls = 0
                fresh.work.release()
                fresh.onMessage = { freshCalls++ }
                fresh.receive { freshCalls++ }
                staleListener.onFrame("peer", byteArrayOf(1)) { oldCalls++ }
                scheduler.runUntilIdle()
                assertEquals(0, oldCalls)
                assertTrue(old.raw.frames.isEmpty())
                assertEquals(2, freshCalls)
                assertEquals(1, fresh.raw.frames.size)
            }
        }
    }

    @Test
    fun cancelledJobHasNoActionOrOutput() {
        Fixture().use { f ->
            var calls = 0
            val task =
                f.work.postDelayed(0) {
                    calls++
                    f.work.output { calls++ }
                }
            task.cancel()
            f.work.release()
            f.run()
            assertEquals(0, calls)
        }
    }

    @Test
    fun reentrantNestedFailureDiscardsOutputsAndPreservesPriorLocalCommit() {
        val immediate =
            object : LinkScheduler {
                override fun nowMs() = 0L

                override fun post(action: () -> Unit) = action()

                override fun postDelayed(
                    delayMs: Long,
                    action: () -> Unit,
                ): Cancellable {
                    if (delayMs == 0L) action()
                    return Cancellable {}
                }
            }
        Fixture(immediate).use { f ->
            var outputs = 0
            f.work.release()
            f.work.post {
                f.data.settings.put("outer", "committed before nested failure")
                f.work.output { outputs++ }
                f.work.post {
                    f.work.output { outputs++ }
                    throw IOException("nested failed")
                }
            }
            assertEquals(0, outputs)
            assertEquals("committed before nested failure", f.data.settings.get("outer"))
            assertFalse(f.allowed)
        }
    }

    @Test
    fun programmingFailureIsPropagatedAndCannotLeaveOutputForNextTask() {
        Fixture().use { f ->
            val error = IllegalArgumentException("programming error")
            var outputs = 0
            f.work.release()
            f.work.post {
                f.work.output { outputs++ }
                throw error
            }
            assertSame(error, assertFailsWith<IllegalArgumentException> { f.run() })
            f.work.post { f.work.output { outputs++ } }
            f.run()
            assertEquals(0, outputs)
            assertSame(error, f.cause)
        }
    }

    @Test
    fun successfulTasksKeepExistingReliableRetryAndAckBehavior() {
        Fixture().use { f ->
            f.work.release()
            var acked = 0
            val message =
                f.link.send(
                    "peer",
                    byteArrayOf(7),
                    object : eu.advantly.device.link.SendCallback {
                        override fun onAcked(
                            messageId: String,
                            attempts: Int,
                            latencyMs: Long,
                        ) {
                            acked++
                        }

                        override fun onFailed(
                            messageId: String,
                            attempts: Int,
                            reason: eu.advantly.device.link.SendFailure,
                        ) = error("unexpected failure")
                    },
                )
            f.run()
            assertEquals(1, f.raw.frames.size)
            (f.scheduler as ManualScheduler).advanceBy(1_500)
            assertEquals(2, f.raw.frames.size)
            assertTrue(f.raw.frames[0].contentEquals(f.raw.frames[1]))
            val id = message.encodeToByteArray()
            val ack = byteArrayOf(0xad.toByte(), 1, 2, (id.size ushr 8).toByte(), id.size.toByte()) + id
            f.raw.listener.onFrame("peer", ack)
            f.run()
            assertEquals(1, acked)
            assertEquals(1, f.link.stats.retries)
            f.scheduler.advanceBy(20_000)
            assertEquals(2, f.raw.frames.size)
        }
    }

    @Test
    fun stagingIsBoundedAndOverflowFailsClosed() {
        Fixture().use { f ->
            var calls = 0
            assertFailsWith<IllegalStateException> { repeat(513) { f.work.post { calls++ } } }
            f.run()
            assertEquals(0, calls)
            assertFalse(f.allowed)
        }
    }
}
