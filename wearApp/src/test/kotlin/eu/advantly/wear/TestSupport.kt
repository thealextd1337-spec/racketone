package eu.advantly.wear

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Uuid7
import eu.advantly.data.LocalData
import eu.advantly.data.db.AdvantlyDatabase
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.RandomIdGenerator
import eu.advantly.match.session.SessionConfig
import eu.advantly.match.session.WallClock
import eu.advantly.presentation.match.LocalDay
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.wear.live.WatchController
import eu.advantly.wear.live.WatchEffect
import eu.advantly.wear.live.WatchPrefs
import eu.advantly.wear.live.WatchToast
import eu.advantly.wear.match.WatchDirectory
import java.util.Properties
import kotlin.random.Random

/** Frische In-Memory-Datenbank (JDBC statt Android-Treiber, wie die Host-Tests von `match-session`). */
fun testLocalData(): LocalData = LocalData(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), AdvantlyDatabase.Schema))

class FakePrefs(
    override var workoutEnabled: Boolean = true,
) : WatchPrefs

/** Deterministische Spieler-IDs. */
class PlayerIds {
    private var n = 0

    fun next(): PlayerId = PlayerId(Uuid7.fromParts(1_000L + ++n, n, n.toLong()).value)
}

/**
 * Handy und Uhr mit echten `MatchSession`s über ein In-Memory-Netz (Fake-Transport, virtuelle Zeit), dazu der
 * [WatchController] der Uhr. Wanduhr = Basis + virtuelle Zeit, Entprellung über dieselbe virtuelle Zeit.
 */
class WatchHarness(
    today: LocalDay = LocalDay(2026, 10, 15),
    latencyMs: Long = 20,
) {
    private var messages = 0
    val scheduler = ManualScheduler()
    val network = InMemoryLinkNetwork(scheduler, latencyMs = latencyMs)

    /** Verstellbare Wanduhr (Zeitumstellung simulieren); monotone Zeit = virtuelle Zeit des Schedulers. */
    var wallOffsetMs = 0L
    val clock = WallClock { BASE_MS + scheduler.nowMs() + wallOffsetMs }
    val phoneData = testLocalData()
    val watchData = testLocalData()
    val phone = session("phone", PeerKind.PHONE, phoneData, seed = 1)
    val watch = session("watch", PeerKind.WATCH, watchData, seed = 2)
    val prefs = FakePrefs()
    val effects = mutableListOf<WatchEffect>()
    private val ids = PlayerIds()
    val directory = WatchDirectory(watchData, clock::nowMs, ids::next)
    val controller =
        WatchController(
            session = watch,
            directory = directory,
            prefs = prefs,
            wallClock = clock::nowMs,
            elapsed = scheduler::nowMs,
            uptime = scheduler::nowMs,
            today = { today },
        )

    init {
        phone.start()
        watch.start()
        controller.effects = { effects += it }
        controller.start()
        controller.setInteractive(true)
        settle()
    }

    /** Virtuelle Zeit laufen lassen (Zustellung, Acks, Wiederholungen, Abgleich). */
    fun settle(ms: Long = 2_000) {
        scheduler.advanceBy(ms)
        controller.tick()
    }

    fun offline(name: String) = network.setConnected(name, false)

    fun online(name: String) = network.setConnected(name, true)

    val toasts: List<WatchToast> get() = effects.filterIsInstance<WatchEffect.Toast>().map { it.toast }

    val haptics get() = effects.filterIsInstance<WatchEffect.Haptic>()

    private fun session(
        name: String,
        kind: PeerKind,
        data: LocalData,
        seed: Int,
    ): MatchSession {
        val link = ReliableDeviceLink(network.transport(name), scheduler, newMessageId = { "$name-${++messages}" })
        return MatchSession(data, link, scheduler, SessionConfig(DeviceId(name), kind), clock, RandomIdGenerator(Random(seed)))
    }

    companion object {
        const val BASE_MS = 1_760_000_000_000L
    }
}

/** Wartet auf die Hintergrundprüfung des Guards und führt die dadurch geposteten Main-Thread-Ergebnisse aus. */
internal fun settleInstallationGuard(idleMain: Boolean = true) {
    val worker =
        eu.advantly.data.AndroidInstallationGuard
            .executorForTesting()
    // Ergebnis-Callbacks dürfen weitere Prüfungen anstoßen (Activity nach Application): zweimal abwarten.
    repeat(if (idleMain) 2 else 1) {
        worker.submit {}.get(30, java.util.concurrent.TimeUnit.SECONDS)
        if (idleMain) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
        }
    }
}
