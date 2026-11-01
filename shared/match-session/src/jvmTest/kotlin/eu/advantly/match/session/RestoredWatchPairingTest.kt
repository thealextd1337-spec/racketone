package eu.advantly.match.session

import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.device.link.DeviceLink
import eu.advantly.device.link.LinkTransport
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.PeerKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Feldfehler 0.4.1: Wear-App neu installiert, Android spielt das Cloud-Backup (Datenbank mit Kopplung) zurück, der Guard
 * stellt das Abbild per [rekeyRestoredInstallation] um. Ein danach am Handy gestartetes Match „Uhr zählt“ muss auf der Uhr
 * aufgehen.
 */
class RestoredWatchPairingTest {
    private val scheduler = ManualScheduler()
    private val network = InMemoryLinkNetwork(scheduler, queueWhenDisconnected = true)
    private var seed = 0

    private fun link(name: String): DeviceLink {
        val transport = network.transport(name)
        val trusted =
            object : LinkTransport by transport {
                override fun isTrustedCounterpart(peer: String): Boolean = true
            }
        return ReliableDeviceLink(trusted, scheduler)
    }

    private fun session(
        data: LocalData,
        link: DeviceLink,
        kind: PeerKind,
    ) = MatchSession(
        data,
        link,
        scheduler,
        SessionConfig(MatchSessions.deviceId(data), kind),
        WallClock { 1_760_000_000_000 + scheduler.nowMs() },
        RandomIdGenerator(Random(++seed)),
    )

    private fun settle() = scheduler.advanceBy(2_000)

    private val phoneData = LocalData(createInMemoryJvmDriver())
    private val phone = session(phoneData, link("phone"), PeerKind.PHONE)
    private val watchDriver = createInMemoryJvmDriver()
    private var watchData = LocalData(watchDriver)
    private var watch = session(watchData, link("watch"), PeerKind.WATCH)

    @Test
    fun phoneStartedWatchScoredMatchOpensOnARestoredWatch() {
        phoneData.players.ensureSelf("Alex", 0) { PlayerId("phone-self") }
        watchData.players.ensureSelf("Alex", 0) { PlayerId("watch-self") }
        phone.start()
        watch.start()
        settle()
        phone.activateAccount("alice", phoneData.cloudUploads.activate("alice"))
        phoneData.replicas.activate(phoneData.replicas.captureContext()) {}
        settle()
        phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES, scorer = ScorerChoice.MyWatch)
        watch.recordPoint(Side.A)
        settle()

        // Neuinstallation mit Restore: dasselbe Abbild (Stand des Backups), umgestellt vor der ersten Session
        watch.close()
        network.setConnected("watch", false)
        watchData = LocalData(watchDriver)
        rekeyRestoredInstallation(watchData, PeerKind.WATCH, "restored", releaseUnbound = false, ids = RandomIdGenerator(Random(++seed)))
        watchData = LocalData(watchDriver)
        watch = session(watchData, link("watch"), PeerKind.WATCH)
        watch.start()
        network.setConnected("watch", true)
        settle()

        val match = phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES, scorer = ScorerChoice.MyWatch, takeOverWatchMatch = true)
        settle()
        scheduler.advanceBy(31_000)
        assertEquals(
            watch.deviceId,
            phone.state.value.match
                ?.scorerDeviceId,
            "Handy hält die Uhr für den Zähler",
        )
        assertEquals(
            match,
            watch.state.value.match
                ?.id,
            "Uhr öffnet das Match",
        )
    }
}
