package eu.advantly.umbrella.watch

import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.RandomIdGenerator
import eu.advantly.match.session.WallClock
import eu.advantly.sync.protocol.PeerKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P2-J: Telemetrie-Sichtbarkeit über die flache Swift-Facade (Strings statt Enums, kein Match nötig). */
class WatchTelemetryVisibilityTest {
    private val scheduler = ManualScheduler()
    private val network = InMemoryLinkNetwork(scheduler, latencyMs = 20)
    private val clock = WallClock { 1_760_000_000_000L + scheduler.nowMs() }
    private var notifications = 0

    private fun session(
        name: String,
        kind: PeerKind,
        seed: Int,
    ): MatchSession {
        val link =
            ReliableDeviceLink(network.transport(name), scheduler, newMessageId = { "$name-${scheduler.nowMs()}-${++notifications}" })
        return MatchSessions.create(testLocalData(), link, scheduler, kind, clock = clock, ids = RandomIdGenerator(Random(seed))).also {
            it.start()
        }
    }

    private val watchSession = session("watch", PeerKind.WATCH, 1)
    private val phone = session("phone", PeerKind.PHONE, 2)
    private var snapshots = 0
    private val watch =
        WatchScoring({ WatchScoring.Backend(watchSession, watchSession.data) }, clock).apply {
            setListener(
                object : WatchScoringListener {
                    override fun onSnapshot(snapshot: WatchScoreSnapshot?) {
                        snapshots++
                    }
                },
            )
            isAvailable()
        }

    @Test
    fun defaultChangesFromPhoneAndWatchAndInvalidInput() {
        scheduler.advanceBy(2_000)
        assertEquals("private", watch.telemetryVisibility())
        // vom iPhone geändert: Listener meldet sich, Swift liest neu
        val before = snapshots
        phone.setTelemetryVisibility(TelemetryVisibility.OFF)
        scheduler.advanceBy(2_000)
        assertTrue(snapshots > before)
        assertEquals("off", watch.telemetryVisibility())
        // auf der Uhr geändert: gilt sofort und erreicht das iPhone
        assertTrue(watch.setTelemetryVisibility("share"))
        assertEquals("", watch.lastError())
        assertEquals("share", watch.telemetryVisibility())
        scheduler.advanceBy(2_000)
        assertEquals(TelemetryVisibility.SHARE, phone.state.value.telemetryVisibility)
        // unbekannter Wert ändert nichts
        assertFalse(watch.setTelemetryVisibility("friends"))
        assertEquals("INVALID_INPUT", watch.lastError())
        assertEquals("share", watch.telemetryVisibility())
    }

    @Test
    fun brokenStoreFallsBackToTheDefault() {
        val broken = WatchScoring({ error("disk I/O error") })
        assertEquals("private", broken.telemetryVisibility())
        assertFalse(broken.setTelemetryVisibility("off"))
        assertEquals("STORE_READ", broken.lastError())
    }
}
