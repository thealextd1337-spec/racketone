package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.ServeTracking
import eu.advantly.core.scoring.MatchState
import eu.advantly.data.LocalData
import eu.advantly.data.scoreEvents
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.PeerKind
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

val SINGLES: Lineup = Lineup(listOf(PlayerId("me")), listOf(PlayerId("bea")), PlayerId("me"))

/** Für Tests, die Events zählen: „Aufschläge: Nein“, also keine automatische Aufschlag-Info („1. Aufschlag“) je Punkt. */
val NO_SERVE_TRACKING: MatchMeta = MatchMeta(serveTracking = ServeTracking.OFF)

/** Ein simuliertes Gerät: eigene Datenbank, eigener Link, eigene Session. */
class Device(
    val name: String,
    val data: LocalData,
    val link: ReliableDeviceLink,
    var session: MatchSession,
) {
    val match: ActiveMatch get() = assertNotNull(session.state.value.match, "$name hat kein offenes Match")
    val score: MatchState get() = match.state
}

/**
 * Mehrere Geräte in einem [InMemoryLinkNetwork] mit virtueller Zeit (01 §8 Sync-Simulation). Die Wanduhr ist an die
 * virtuelle Zeit gekoppelt, IDs sind je Gerät deterministisch.
 */
class Cluster(
    vararg devices: Pair<String, PeerKind>,
    latencyMs: Long = 20,
    seed: Int = 7,
) {
    val scheduler = ManualScheduler()
    val network = InMemoryLinkNetwork(scheduler, latencyMs = latencyMs, seed = seed)
    val clock = WallClock { 1_760_000_000_000L + scheduler.nowMs() }
    private var messageIds = 0
    val devices: Map<String, Device> =
        devices
            .mapIndexed { i, (name, kind) ->
                val data = testLocalData()
                val link = ReliableDeviceLink(network.transport(name), scheduler, newMessageId = { "$name-${++messageIds}" })
                val ids = RandomIdGenerator(Random(seed * 31 + i))
                val session = MatchSession(data, link, scheduler, SessionConfig(DeviceId(name), kind), clock, ids)
                name to Device(name, data, link, session)
            }.toMap()

    init {
        this.devices.values.forEach { it.session.start() }
        settle()
    }

    operator fun get(name: String): Device = devices.getValue(name)

    /** Lässt die virtuelle Zeit laufen (Zustellung, Acks, Wiederholungen, Anti-Entropie-Takt). */
    fun settle(ms: Long = 2_000) = scheduler.advanceBy(ms)

    fun offline(name: String) = network.setConnected(name, false)

    fun online(name: String) = network.setConnected(name, true)

    /** Alle Geräte haben dasselbe Match offen, denselben Zustand und dieselbe Event-Menge. */
    fun assertConverged(names: Collection<String> = devices.keys): MatchId {
        val reference = this[names.first()]
        val id = reference.match.id
        val events = eventsOf(reference, id)
        for (name in names) {
            val device = this[name]
            assertEquals(id, device.match.id, "$name: anderes Match")
            assertEquals(events, eventsOf(device, id), "$name: andere Events")
            assertEquals(reference.score, device.score, "$name: anderer Zustand")
        }
        return id
    }

    private fun eventsOf(
        device: Device,
        id: MatchId,
    ) = device.data.events
        .scoreEvents(id)
        .toSet()
}
