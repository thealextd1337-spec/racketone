package eu.advantly.ui.phone

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.createInMemoryJvmLocalData
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.RandomIdGenerator
import eu.advantly.match.session.SessionConfig
import eu.advantly.presentation.match.MonotonicClock
import eu.advantly.presentation.match.ScorerOption
import eu.advantly.presentation.match.SessionMatchStore
import eu.advantly.presentation.match.StartOptions
import eu.advantly.presentation.match.WallClock
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.ui.phone.app.AdvantlyAppModel
import kotlin.random.Random
import eu.advantly.match.session.WallClock as SessionClock

/**
 * Echte Match-Session auf Handy (hinter [SessionMatchStore] und dem App-Modell) und Uhr, verbunden über ein
 * In-Memory-Netz mit virtueller Zeit ([ManualScheduler]). Die Wanduhr beider Geräte und der UI folgt der virtuellen
 * Zeit; [settle] lässt sie laufen. Der Besitzer heißt „Alex“.
 */
class SessionRig {
    val scheduler = ManualScheduler()
    val network = InMemoryLinkNetwork(scheduler, latencyMs = 20)
    val clock = WallClock { BASE_MS + scheduler.nowMs() }
    val monotonic = MonotonicClock { scheduler.nowMs() }
    private val sessionClock = SessionClock { BASE_MS + scheduler.nowMs() }
    private var messages = 0

    val watch: MatchSession = session("watch", PeerKind.WATCH, seed = 2)
    val phoneSession: MatchSession =
        session("phone", PeerKind.PHONE, seed = 1).also {
            it.data.players.ensureSelf("Alex", BASE_MS) { PlayerId("alex") }
        }
    val store = SessionMatchStore(phoneSession, clock, monotonic = monotonic)
    val model = AdvantlyAppModel(store, store, store, clock = clock, monotonic = monotonic)

    init {
        settle()
    }

    private fun session(
        name: String,
        kind: PeerKind,
        seed: Int,
    ): MatchSession {
        val link = ReliableDeviceLink(network.transport(name), scheduler, newMessageId = { "$name-${++messages}" })
        val data = createInMemoryJvmLocalData()
        return MatchSession(data, link, scheduler, SessionConfig(DeviceId(name), kind), sessionClock, RandomIdGenerator(Random(seed)))
            .also { it.start() }
    }

    fun settle(ms: Long = 2_000) = scheduler.advanceBy(ms)

    fun offline(name: String = "watch") = network.setConnected(name, false)

    fun online(name: String = "watch") = network.setConnected(name, true)

    /** Tennis-Einzel gegen „Jonas“, die Uhr zählt; der Live-Controller ist synchron. */
    fun startWatchScoredTennis(): MatchId {
        val id = store.start(model.setupFor(Presets.TENNIS_SINGLES_BO3.format()), StartOptions(ScorerOption.MY_WATCH))
        settle()
        model.controller.sync(store.active.value)
        return id
    }

    /** Punkte auf der Uhr („A“/„B“) und zustellen (vorher Zustellung abwarten, damit die Uhr das Match kennt). */
    fun watchPlays(points: String) {
        settle()
        points.forEach { watch.recordPoint(if (it == 'A') Side.A else Side.B) }
        settle()
    }

    private companion object {
        const val BASE_MS = 1_760_000_000_000L
    }
}
