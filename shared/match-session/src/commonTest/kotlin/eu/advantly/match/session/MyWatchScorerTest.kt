package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.PeerKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * „Meine Uhr“ ohne Live-Verbindung (W1-I, Feldtest Pixel Watch): bekannte Uhr wird direkt Zähler; nie verbundene Uhr
 * übernimmt beim ersten `Hello` (Platzhalter-Zähler); „Doch am Handy zählen“ verhindert die spätere Übergabe.
 */
class MyWatchScorerTest {
    private class Rig(
        watchOnline: Boolean,
    ) {
        val scheduler = ManualScheduler()
        val network = InMemoryLinkNetwork(scheduler)
        private var ids = 0

        private fun session(
            name: String,
            kind: PeerKind,
        ) = MatchSession(
            testLocalData(),
            ReliableDeviceLink(network.transport(name), scheduler, newMessageId = { "$name-${++ids}" }),
            scheduler,
            SessionConfig(DeviceId(name), kind),
            { 1_760_000_000_000L + scheduler.nowMs() },
            RandomIdGenerator(Random(name.length)),
        )

        val phone = session("phone", PeerKind.PHONE)
        val watch = session("watch", PeerKind.WATCH)

        init {
            network.transport("watch")
            if (!watchOnline) network.setConnected("watch", false)
            phone.start()
            watch.start()
            settle()
        }

        fun settle(ms: Long = 2_000) = scheduler.advanceBy(ms)
    }

    private val format = Presets.TENNIS_SINGLES_BO3.format()

    @Test
    fun knownWatchBecomesTheScorerWhileOffline() {
        val rig = Rig(watchOnline = true)
        assertEquals(listOf(DeviceId("watch")), rig.phone.knownDevices(PeerKind.WATCH))
        rig.network.setConnected("watch", false)
        rig.settle()
        rig.phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch)
        assertEquals(
            DeviceId("watch"),
            rig.phone.state.value.match
                ?.scorerDeviceId,
        )
        assertFalse(rig.phone.recordPoint(Side.A), "Handy zeigt nur an")

        rig.network.setConnected("watch", true)
        rig.settle()
        assertTrue(
            rig.watch.state.value.match
                ?.isScorer == true,
        )
        assertTrue(rig.watch.recordPoint(Side.A))
        rig.settle()
        assertEquals(
            "15",
            rig.phone.state.value.match
                ?.state
                ?.current
                ?.display
                ?.a,
        )
    }

    @Test
    fun neverConnectedWatchTakesOverOnFirstContact() {
        val rig = Rig(watchOnline = false)
        assertTrue(rig.phone.knownDevices(PeerKind.WATCH).isEmpty())
        rig.phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch)
        assertEquals(
            MatchSession.PENDING_WATCH,
            rig.phone.state.value.match
                ?.scorerDeviceId,
        )
        assertFalse(rig.phone.recordPoint(Side.A))

        rig.network.setConnected("watch", true)
        rig.settle()
        assertEquals(
            DeviceId("watch"),
            rig.phone.state.value.match
                ?.scorerDeviceId,
        )
        assertTrue(
            rig.watch.state.value.match
                ?.isScorer == true,
        )
        assertTrue(rig.watch.recordPoint(Side.B))
        rig.settle()
        assertEquals(
            "15",
            rig.phone.state.value.match
                ?.state
                ?.current
                ?.display
                ?.b,
        )
        assertEquals(
            0,
            rig.phone.state.value.match
                ?.rejectedByRoleCount,
        )
    }

    @Test
    fun countingOnThePhoneInsteadStopsTheLaterHandOver() {
        val rig = Rig(watchOnline = false)
        rig.phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch)
        assertTrue(rig.phone.takeOverScoring())
        assertTrue(rig.phone.recordPoint(Side.A))
        rig.network.setConnected("watch", true)
        rig.settle()
        assertTrue(
            rig.phone.state.value.match
                ?.isScorer == true,
            "Handy zählt weiter",
        )
        assertFalse(
            rig.watch.state.value.match
                ?.isScorer == true,
        )
        assertEquals(
            "15",
            rig.watch.state.value.match
                ?.state
                ?.current
                ?.display
                ?.a,
        )
    }
}
