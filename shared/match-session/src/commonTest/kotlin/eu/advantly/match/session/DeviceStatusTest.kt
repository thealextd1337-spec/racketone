package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.device.link.DeviceLink
import eu.advantly.device.link.DeviceLinkListener
import eu.advantly.device.link.LinkState
import eu.advantly.device.link.LinkStats
import eu.advantly.device.link.SendCallback
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.AppBuild
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.Summary
import eu.advantly.sync.protocol.SyncCodec
import eu.advantly.sync.protocol.SyncMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceStatusTest {
    private val build = AppBuild("0.1.0", "223dff1")

    @Test
    fun commitComparisonKeepsUnknownAndDirtyBuildsYellow() {
        val peer =
            DeviceStatus(
                "watch",
                PeerKind.WATCH,
                appBuild = build,
                compatibility = CompatibilityStatus.COMPATIBLE,
                reachable = true,
            )
        assertEquals(DeviceLight.GREEN, peer.light(build))
        assertEquals(DeviceLight.YELLOW, peer.light(build.copy(commit = "223dff1-dirty")))
        assertEquals(
            DeviceLight.YELLOW,
            peer.copy(appBuild = build.copy(commit = "223dff1-dirty")).light(build.copy(commit = "223dff1-dirty")),
        )
        assertEquals(DeviceLight.YELLOW, peer.copy(appBuild = null).light(build))
        assertEquals(DeviceLight.YELLOW, peer.copy(appBuild = build.copy(commit = "unknown")).light(build.copy(commit = "unknown")))
        assertEquals(DeviceLight.YELLOW, peer.light(build.copy(commit = "a48fa00")))
        assertEquals(DeviceLight.RED, peer.copy(reachable = false).light(build))
        for (status in listOf(CompatibilityStatus.PEER_TOO_OLD, CompatibilityStatus.PEER_TOO_NEW)) {
            assertEquals(DeviceLight.RED, peer.copy(compatibility = status).light(build))
        }
    }

    @Test
    fun idleConnectedPeersStayReachableWithoutTrafficForFiveMinutesAndSurviveRestartOffline() {
        val scheduler = ManualScheduler()
        val link = StatusLink()
        val data = testLocalData()

        fun session() =
            MatchSession(
                data,
                link,
                scheduler,
                SessionConfig(DeviceId("phone"), PeerKind.PHONE, appBuild = build),
                clock = { 1_760_000_000_000L + scheduler.nowMs() },
            )
        val phone = session().also { it.start() }
        assertTrue(link.sent.filterIsInstance<Hello>().all { it.appBuild == build })

        fun devices() = phone.state.value.devices
        link.deliver("pixel", Hello(DeviceId("pixel"), PeerKind.WATCH, appBuild = build))
        link.deliver("galaxy", Hello(DeviceId("galaxy"), PeerKind.WATCH))
        assertEquals(listOf(DeviceLight.YELLOW, DeviceLight.GREEN), devices().map { it.light(build) })
        link.sent.clear()
        scheduler.advanceBy(300_000)
        assertTrue(link.sent.isEmpty(), "Ohne Match/Rückstand darf der Timer nichts senden")
        assertTrue(devices().all { it.reachable }, "Plattform-Erreichbarkeit braucht keinen Hello-Keepalive")
        link.deliver("pixel", Summary(emptyMap()))
        assertEquals(DeviceLight.GREEN, devices().first { it.peer == "pixel" }.light(build))
        assertEquals(DeviceLight.YELLOW, devices().first { it.peer == "galaxy" }.light(build))
        link.online = emptyList()
        link.sink!!.onStateChanged(LinkState.DISCONNECTED, emptyList())
        assertTrue(devices().none { it.reachable }, "Node nicht erreichbar wirkt sofort")
        phone.close()
        val restarted = session().also { it.start() }
        assertEquals(2, restarted.state.value.devices.size)
        assertTrue(
            restarted.state.value.devices
                .none { it.reachable },
        )
        assertEquals(
            1_760_000_300_000L,
            restarted.state.value.devices
                .first { it.peer == "pixel" }
                .lastSeenAtMs,
        )
        restarted.close()
    }

    @Test
    fun repeatedHelloOnlyUpdatesStatusAndReconnectOrIdentityOrProtocolChangeSyncsAgain() {
        val scheduler = ManualScheduler()
        val link = StatusLink()
        val phone = MatchSession(testLocalData(), link, scheduler, SessionConfig(DeviceId("phone"), PeerKind.PHONE))
        phone.start()
        val hello = Hello(DeviceId("pixel"), PeerKind.WATCH, appBuild = build)
        link.sent.clear()
        link.deliver("pixel", hello)
        assertTrue(link.sent.isNotEmpty(), "Erstes Hello gleicht ab")
        link.sent.clear()
        link.deliver("pixel", hello.copy(appBuild = build.copy(commit = "a48fa00")))
        assertTrue(link.sent.isEmpty(), "Wiederholtes Hello darf keinen Antwort-Burst erzeugen")
        assertEquals(
            "a48fa00",
            phone.state.value.devices
                .first { it.peer == "pixel" }
                .appBuild
                ?.commit,
        )
        link.deliver("pixel", hello.copy(deviceId = DeviceId("reinstalled")))
        assertTrue(link.sent.isNotEmpty(), "Neue Installation gleicht erneut ab")
        link.deliver("pixel", hello.copy(v = 99, minV = 99))
        link.sent.clear()
        link.deliver("pixel", hello)
        assertTrue(link.sent.isNotEmpty(), "Wieder kompatibles Protokoll gleicht erneut ab")
        link.online = emptyList()
        link.sink!!.onStateChanged(LinkState.DISCONNECTED, emptyList())
        link.online = listOf("pixel")
        link.sink!!.onStateChanged(LinkState.CONNECTED, link.online)
        link.sent.clear()
        link.deliver("pixel", hello)
        assertTrue(link.sent.isNotEmpty(), "Reconnect muss Archive und Tombstones erneut abgleichen")
        phone.close()
    }

    @Test
    fun lastSeenIsPersistedAtMostEveryFiveMinutesUnlessStatusChanges() {
        val scheduler = ManualScheduler()
        val link = StatusLink()
        val data = testLocalData()
        val phone = MatchSession(data, link, scheduler, SessionConfig(DeviceId("phone"), PeerKind.PHONE), clock = { scheduler.nowMs() })
        phone.start()
        val hello = Hello(DeviceId("pixel"), PeerKind.WATCH, appBuild = build)
        link.deliver("pixel", hello)
        val saved = data.settings.all()
        repeat(9) {
            scheduler.advanceBy(30_000)
            link.deliver("pixel", hello)
            link.deliver("pixel", Summary(emptyMap()))
            assertEquals(saved, data.settings.all())
        }
        scheduler.advanceBy(30_000)
        link.deliver("pixel", Summary(emptyMap()))
        assertFalse(saved == data.settings.all())
        phone.close()
    }

    @Test
    fun contactAgeUsesMonotonicTimeAndRejectsUnknownOrNegativeAge() {
        assertTrue(contactIsFresh(90_000, 0))
        assertFalse(contactIsFresh(90_001, 0))
        assertFalse(contactIsFresh(0, null))
        assertFalse(contactIsFresh(0, 1))
    }

    @Test
    fun storedStatusHistoryIsBoundedAndRetainsCurrentPeers() {
        val scheduler = ManualScheduler()
        val data = testLocalData()
        val link = StatusLink()
        val phone = MatchSession(data, link, scheduler, SessionConfig(DeviceId("phone"), PeerKind.PHONE), clock = { scheduler.nowMs() })
        phone.start()
        link.deliver("pixel", Hello(DeviceId("pixel"), PeerKind.WATCH))
        repeat(30) {
            scheduler.advanceBy(1)
            link.deliver("old-$it", Hello(DeviceId("old-$it"), PeerKind.WATCH))
        }
        assertEquals(16, phone.state.value.devices.size)
        assertTrue(
            phone.state.value.devices
                .any { it.peer == "pixel" },
        )
        phone.retainDeviceStatuses(emptySet())
        assertEquals(
            listOf("pixel"),
            phone.state.value.devices
                .map { it.peer },
        )
        phone.close()
        val restarted = MatchSession(data, null, scheduler, SessionConfig(DeviceId("phone"), PeerKind.PHONE))
        assertEquals(
            listOf("pixel"),
            restarted.state.value.devices
                .map { it.peer },
        )
    }

    private class StatusLink : DeviceLink {
        val sent = mutableListOf<SyncMessage>()
        var online = listOf("pixel", "galaxy")
        var sink: DeviceLinkListener? = null
        override val state get() = if (online.isEmpty()) LinkState.DISCONNECTED else LinkState.CONNECTED
        override val peers get() = online
        override val stats = LinkStats()

        override fun start() = Unit

        override fun stop() = Unit

        override fun setListener(listener: DeviceLinkListener?) {
            sink = listener
        }

        override fun send(
            peer: String,
            payload: ByteArray,
            callback: SendCallback?,
        ): String {
            sent += (SyncCodec.decode(payload) as eu.advantly.sync.protocol.DecodeResult.Ok).message
            return "sent"
        }

        fun deliver(
            peer: String,
            message: SyncMessage,
        ) {
            sink!!.onMessage(peer, "incoming", SyncCodec.encode(message))
        }
    }
}
