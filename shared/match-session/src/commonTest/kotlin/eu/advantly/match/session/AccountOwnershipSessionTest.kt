package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.LocalData
import eu.advantly.data.scoreEvents
import eu.advantly.device.link.DeviceLink
import eu.advantly.device.link.DeviceLinkListener
import eu.advantly.device.link.LinkTransport
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.SendCallback
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.DecodeResult
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.SyncCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountOwnershipSessionTest {
    private class CapturedLink(
        private val delegate: DeviceLink,
    ) : DeviceLink by delegate {
        val sent = mutableListOf<ByteArray>()
        var receiver: DeviceLinkListener? = null
        var durableCallbacks = 0

        override fun setListener(listener: DeviceLinkListener?) {
            receiver = listener
            delegate.setListener(listener)
        }

        override fun send(
            peer: String,
            payload: ByteArray,
            callback: SendCallback?,
        ): String {
            sent += payload
            if (callback != null) durableCallbacks++
            return delegate.send(peer, payload, callback)
        }

        fun replay(
            peer: String,
            payload: ByteArray,
        ) = receiver!!.onMessage(peer, "replay", payload)
    }

    private class Pairing {
        val scheduler = ManualScheduler()
        val network = InMemoryLinkNetwork(scheduler, queueWhenDisconnected = true)
        val phoneData = testLocalData()
        val watchData = testLocalData()
        private var seed = 0

        private fun link(name: String): CapturedLink {
            val transport = network.transport(name)
            val trusted =
                object : LinkTransport by transport {
                    override fun isTrustedCounterpart(peer: String): Boolean = true
                }
            return CapturedLink(ReliableDeviceLink(trusted, scheduler))
        }

        val phoneLink = link("phone")
        val watchLink = link("watch")

        fun session(
            data: LocalData,
            link: DeviceLink,
            kind: PeerKind,
        ): MatchSession =
            MatchSession(
                data,
                link,
                scheduler,
                SessionConfig(MatchSessions.deviceId(data), kind),
                WallClock { 1_760_000_000_000 + scheduler.nowMs() },
                RandomIdGenerator(Random(++seed)),
            )

        var phone = session(phoneData, phoneLink, PeerKind.PHONE)
        var watch = session(watchData, watchLink, PeerKind.WATCH)

        init {
            phoneData.players.ensureSelf("Alex", 0) { PlayerId("phone-self") }
            watchData.players.ensureSelf("Alex", 0) { PlayerId("watch-self") }
            phone.start()
            watch.start()
            settle()
        }

        fun settle() = scheduler.advanceBy(2_000)

        fun activate(profile: String) {
            phone.activateAccount(profile, phoneData.cloudUploads.activate(profile))
            settle()
        }

        fun restartWatch() {
            watch.close()
            watch = session(watchData, watchLink, PeerKind.WATCH)
            watch.start()
            settle()
        }

        fun restartPhone() {
            phone.close()
            phone = session(phoneData, phoneLink, PeerKind.PHONE)
            phone.start()
            settle()
        }
    }

    @Test
    fun scopedActivationFailsClosedForOldAndUntrustedPeersWithoutPrivatePayloads() {
        val node = WireNode("phone", 8, identifyPeer = false)
        node.data.players.ensureSelf("Alex", 0) { PlayerId("self") }
        node.session.activateAccount("alice", node.data.cloudUploads.activate("alice"))
        node.link.sent.clear()
        node.link.deliver(Hello(DeviceId("old-watch"), PeerKind.WATCH))
        assertTrue(node.link.sent.all { it is Hello })
        assertEquals(
            eu.advantly.sync.protocol.CompatibilityStatus.PEER_TOO_OLD,
            node.session.state.value.sync.peers
                .single()
                .compatibility,
        )
        node.link.deliver(
            Hello(
                DeviceId("untrusted-watch"),
                PeerKind.WATCH,
                identityProtocol = 1,
                session = "fake-session",
            ),
        )
        assertTrue(node.link.sent.all { it is Hello })
    }

    @Test
    fun pairedHelloCannotBindMismatchedKindsOrImportedSources() {
        val pair = Pairing()
        pair.activate("alice")
        val token = pair.phoneData.cloudUploads.activate("alice")
        pair.phone.activateAccount("alice", token)
        pair.phoneData.events.append(
            eu.advantly.data.StoredEvent("foreign-event", "foreign-match", "foreign-device", 1, 1, "foreign-payload"),
        )
        val hello =
            pair.watchLink.sent
                .mapNotNull { (SyncCodec.decode(it) as? DecodeResult.Ok)?.message as? Hello }
                .last()
        val poisoned =
            hello.copy(
                localSources =
                    hello.localSources +
                        eu.advantly.sync.protocol.OwnSource(
                            "foreign-device",
                            "foreign-self",
                            pair.phoneData.accountIdentities.scope!!,
                            "PHONE",
                            1,
                        ),
            )
        pair.phoneLink.replay("watch", SyncCodec.encode(poisoned))
        assertNull(pair.phoneData.accountIdentities.sourceScope("foreign-device"))
        assertFalse(pair.phoneData.isSelf(PlayerId("foreign-self")))
        assertTrue(
            pair.phoneData.cloudUploads
                .capture(token, 10)
                .events
                .isEmpty(),
        )
    }

    @Test
    fun adoptedWatchGuestSourceForcesFreshGuestIdentitiesAfterLogout() {
        val pair = Pairing()
        pair.activate("alice")
        pair.phone.leaveAccount()
        pair.settle()
        val guestPhone = pair.phone.deviceId
        val guestWatch = pair.watch.deviceId
        val match = pair.watch.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.watch.recordPoint(Side.A)
        pair.settle()
        val historical = pair.watchData.events.eventsFor(match.value)
        pair.activate("bob")
        val token = pair.phoneData.cloudUploads.activate("bob")
        pair.phone.activateAccount("bob", token)
        pair.phone.adoptSources(token, setOf(guestWatch.value))
        pair.settle()
        pair.phone.leaveAccount()
        pair.settle()
        assertNotEquals(guestPhone, pair.phone.deviceId)
        assertNotEquals(guestWatch, pair.watch.deviceId)
        assertEquals(historical, pair.watchData.events.eventsFor(match.value))
        val fresh = pair.watch.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.settle()
        pair.activate("alice")
        assertTrue(pair.phone.adoptionSources().any { it.deviceId != guestWatch.value && it.kind == "WATCH" })
        assertTrue(pair.watchData.events.count(fresh.value) > 0)
    }

    @Test
    fun detachedCloudAuthorizationDoesNotBreakLocalPairSynchronization() {
        val pair = Pairing()
        pair.activate("alice")
        pair.phoneData.cloudUploads.deactivate()
        pair.phone.detachAccountSession()
        pair.network.setConnected("watch", false)
        val match = pair.watch.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.watch.recordPoint(Side.A)
        pair.network.setConnected("watch", true)
        pair.settle()
        assertEquals(2L, pair.phoneData.events.count(match.value))
    }

    @Test
    fun telemetryOffSurvivesFirstActivationNewGuestAndSavedAccountRestoration() {
        val pair = Pairing()
        pair.phone.setTelemetryVisibility(eu.advantly.core.model.TelemetryVisibility.OFF)
        pair.settle()
        pair.activate("alice")
        assertEquals(eu.advantly.core.model.TelemetryVisibility.OFF, pair.phone.state.value.telemetry.visibility)
        assertEquals(eu.advantly.core.model.TelemetryVisibility.OFF, pair.watch.state.value.telemetry.visibility)
        pair.phone.leaveAccount()
        pair.settle()
        assertEquals(eu.advantly.core.model.TelemetryVisibility.OFF, pair.phone.state.value.telemetry.visibility)
        pair.activate("bob")
        pair.phone.setTelemetryVisibility(eu.advantly.core.model.TelemetryVisibility.PRIVATE)
        pair.settle()
        pair.activate("alice")
        assertEquals(eu.advantly.core.model.TelemetryVisibility.OFF, pair.phone.state.value.telemetry.visibility)
        pair.activate("bob")
        assertEquals(eu.advantly.core.model.TelemetryVisibility.PRIVATE, pair.phone.state.value.telemetry.visibility)
        pair.restartWatch()
        assertEquals(eu.advantly.core.model.TelemetryVisibility.PRIVATE, pair.watch.state.value.telemetry.visibility)
    }

    @Test
    fun pairedScopesRotateAndRestoreWithoutRewritingOldEvents() {
        val pair = Pairing()
        val oldPhone = pair.phone.deviceId
        val oldWatch = pair.watch.deviceId
        pair.activate("alice")
        val alicePhone = pair.phone.deviceId
        val aliceWatch = pair.watch.deviceId
        assertNotEquals(oldPhone, alicePhone)
        assertNotEquals(oldWatch, aliceWatch)
        val match = pair.watch.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.watch.recordPoint(Side.A)
        pair.settle()
        assertEquals(pair.watchData.events.scoreEvents(match), pair.phoneData.events.scoreEvents(match))
        assertEquals(0, pair.watchLink.durableCallbacks)
        val oldEvents = pair.watchData.events.eventsFor(match.value)
        val delayed = pair.watchLink.sent.toList()
        pair.activate("bob")
        assertNotEquals(alicePhone, pair.phone.deviceId)
        assertNotEquals(aliceWatch, pair.watch.deviceId)
        assertNull(pair.phone.state.value.match)
        delayed.forEach { pair.phoneLink.replay("watch", it) }
        pair.settle()
        assertNull(pair.phone.state.value.match)
        assertEquals(oldEvents, pair.watchData.events.eventsFor(match.value))
        pair.activate("alice")
        assertEquals(alicePhone, pair.phone.deviceId)
        assertEquals(aliceWatch, pair.watch.deviceId)
        pair.phone.leaveAccount()
        pair.settle()
        assertNotEquals(alicePhone, pair.phone.deviceId)
        assertNotEquals(aliceWatch, pair.watch.deviceId)
        pair.activate("alice")
        assertEquals(alicePhone, pair.phone.deviceId)
        assertEquals(aliceWatch, pair.watch.deviceId)
    }

    @Test
    fun oneSidedRestartsAndUnknownStaleHelloPreserveNormalSynchronization() {
        val pair = Pairing()
        pair.activate("alice")
        pair.restartWatch()
        pair.restartPhone()
        val hello =
            pair.watchLink.sent
                .mapNotNull { (SyncCodec.decode(it) as? DecodeResult.Ok)?.message as? Hello }
                .last()
        pair.phoneLink.replay("watch", SyncCodec.encode(hello.copy(session = "unseen-stale", recipientSession = null)))
        pair.settle()
        val match = pair.watch.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.watch.recordPoint(Side.B)
        pair.settle()
        assertEquals(2L, pair.phoneData.events.count(match.value))
        assertEquals(pair.watchData.events.scoreEvents(match), pair.phoneData.events.scoreEvents(match))
        assertTrue(pair.phoneLink.sent.size + pair.watchLink.sent.size < 200)
    }

    @Test
    fun offlineOldAccountEventsRemainUnconfirmedUntilItsReturn() {
        val pair = Pairing()
        pair.activate("alice")
        pair.network.setConnected("watch", false)
        val match = pair.watch.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.watch.recordPoint(Side.A)
        pair.activate("bob")
        pair.network.setConnected("watch", true)
        pair.settle()
        assertEquals(0L, pair.phoneData.events.count(match.value))
        assertTrue(pair.watchData.events.unconfirmedCount() >= 2)
        pair.activate("alice")
        pair.settle()
        assertEquals(2L, pair.phoneData.events.count(match.value))
        assertEquals(0L, pair.watchData.events.unconfirmedCount())
    }

    @Test
    fun legacyWatchOnlyDataRequiresExplicitConsentAndDeletionSurvivesRestart() {
        val pair = Pairing()
        pair.network.setConnected("watch", false)
        val legacy = pair.watch.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.watch.recordPoint(Side.A)
        pair.activate("alice")
        pair.network.setConnected("watch", true)
        pair.settle()
        assertEquals(0L, pair.phoneData.events.count(legacy.value))
        val source = pair.phone.adoptionSources().single { it.kind == "WATCH" }
        assertEquals(2L, source.eventCount)
        val token = pair.phoneData.cloudUploads.activate("alice")
        pair.phone.activateAccount("alice", token)
        pair.phone.adoptSources(token, setOf(source.deviceId))
        pair.settle()
        pair.scheduler.advanceBy(31_000)
        assertEquals(2L, pair.phoneData.events.count(legacy.value))
        assertTrue(pair.watchData.isSelf(PlayerId("watch-self")))
        pair.phone.deleteMatch(legacy)
        pair.settle()
        assertTrue(pair.watchData.matches.isDeleted(legacy))
        pair.restartPhone()
        pair.restartWatch()
        pair.settle()
        assertTrue(pair.watchData.matches.isDeleted(legacy))
        assertFalse(
            pair.phoneData.accountIdentities
                .currentSources()
                .isEmpty(),
        )
    }
}
