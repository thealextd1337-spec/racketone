package eu.advantly.android.sync

import eu.advantly.cloud.sync.LiveMatch
import eu.advantly.cloud.sync.LiveMatchesPort
import eu.advantly.cloud.sync.VisitorTeamMember
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.device.link.LiveViewsProvider
import eu.advantly.match.session.PeerInfo
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.LiveRelation
import eu.advantly.sync.protocol.LiveView
import eu.advantly.sync.protocol.LiveViewSubscription
import eu.advantly.sync.protocol.LiveViewSubscriptionCodec
import eu.advantly.sync.protocol.LiveViews
import eu.advantly.sync.protocol.LiveViewsCodec
import eu.advantly.sync.protocol.PeerKind
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.time.Instant

class PhoneLiveViewsTest {
    private val watch = PeerInfo("watch", null, PeerKind.WATCH, CompatibilityStatus.COMPATIBLE)
    private val subscription = LiveViewSubscriptionCodec.encode(LiveViewSubscription(LiveViewSubscription.CURRENT_VERSION))
    private val view = LiveView(MatchId("m1"), LiveRelation.PLAYER, Sport.TENNIS, "Alex", "Jonas", points = listOf("15", "0"))
    private val sent = mutableListOf<Triple<String, String, ByteArray>>()

    private fun counting(result: suspend () -> List<LiveView>) =
        object : LiveViewsProvider {
            var calls = 0

            override suspend fun current(): List<LiveView> {
                calls++
                return result()
            }
        }

    /** Host-Kontext wie Main am Handy: ein eigener Thread, auf dem allein Konto-Host und Cloud-Sitzung arbeiten dürfen. */
    private var hostThread: Thread? = null
    private val host = Executors.newSingleThreadExecutor { Thread(it, "host").also { hostThread = it } }.asCoroutineDispatcher()

    @After fun closeHost() = host.close()

    private fun respond(
        provider: LiveViewsProvider,
        node: String = "watch",
        data: ByteArray = subscription,
        peers: List<PeerInfo> = listOf(watch),
        timeoutMs: Long = PhoneLiveViews.TIMEOUT_MS,
    ) = PhoneLiveViews.respond(node, data, peers, { n, p, b -> sent += Triple(n, p, b) }, provider, timeoutMs, host)

    /**
     * H1: Der Listener ruft auf einem Worker-Thread; der Abruf läuft trotzdem auf dem Host-Kontext, sonst wirft der
     * Thread-Guard der Cloud-Sitzung und die Uhr bekäme nie eine Antwort.
     */
    @Test
    fun theProviderRunsOnTheHostThreadEvenWhenCalledFromAWorker() {
        val guarded =
            counting {
                // Wie CloudSessionGate.confined(): check(threadGuard())
                check(Thread.currentThread() === hostThread) { "Cloud-Sitzung nur auf dem seriellen Host-Kontext verwenden" }
                listOf(view)
            }
        var answered = false
        val worker = Thread({ answered = respond(guarded) }, "wearable-worker")
        worker.start()
        worker.join()
        assertTrue(answered)
        assertEquals(1, guarded.calls)
        assertEquals(listOf(view), LiveViewsCodec.decode(sent.single().third)?.matches)
    }

    @Test
    fun eachSubscriptionFetchesOnceAndAnswersTheWatch() {
        val provider = counting { listOf(view) }
        assertTrue(respond(provider))
        assertEquals(1, provider.calls)
        val (node, path, payload) = sent.single()
        assertEquals("watch", node)
        assertEquals(LiveViews.MESSAGE_PATH, path)
        assertEquals(listOf(view), LiveViewsCodec.decode(payload)?.matches)
    }

    @Test
    fun withoutAccountTheWatchHearsNothingIsLive() {
        assertTrue(respond(LiveViewsProvider.Empty))
        assertEquals(emptyList<LiveView>(), LiveViewsCodec.decode(sent.single().third)?.matches)
    }

    @Test
    fun foreignOrIncompatibleSendersAndBrokenMessagesFetchNothing() {
        val provider = counting { listOf(view) }
        assertFalse(respond(provider, node = "other"))
        assertFalse(respond(provider, peers = listOf(watch.copy(compatibility = CompatibilityStatus.PEER_TOO_OLD))))
        assertFalse(respond(provider, peers = listOf(watch.copy(kind = PeerKind.PHONE))))
        assertFalse(respond(provider, data = """{"v":2}""".encodeToByteArray()))
        assertEquals(0, provider.calls)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun failuresAndTimeoutsSendNothingSoTheWatchKeepsItsState() {
        assertFalse(respond(counting { error("offline") }))
        assertFalse(
            respond(
                counting {
                    delay(1_000)
                    listOf(view)
                },
                timeoutMs = 10,
            ),
        )
        assertTrue(sent.isEmpty())
    }

    private fun live(
        relation: String,
        sport: String = "tennis",
    ) = LiveMatch(
        "m-$relation",
        relation,
        sport,
        "singles",
        listOf(VisitorTeamMember("A", 1, "Alex"), VisitorTeamMember("B", 1, "Jonas")),
        Json.parseToJsonElement("""{"sets":[[6,4]],"games":[2,1],"points":["40","AD"],"serving":"B","heartRate":150}""")
            as JsonObject,
        scorerConnected = true,
        updatedAt = "2026-11-02T20:00:00Z",
    )

    private class Port(
        val result: () -> List<LiveMatch>,
    ) : LiveMatchesPort {
        val profiles = mutableListOf<String>()

        override suspend fun liveMatches(profileId: String): List<LiveMatch> {
            profiles += profileId
            return result()
        }
    }

    @Test
    fun accountProviderMapsTheSanitizedProjectionOncePerCall() {
        val port = Port { listOf(live("player"), live("visitor"), live("future"), live("scorer", sport = "squash")) }
        val now = Instant.parse("2026-11-02T20:00:12Z").toEpochMilliseconds()
        val views = runBlocking { AccountLiveViews(port, { "jonas" }, { now }).current() }

        assertEquals(listOf("jonas"), port.profiles)
        assertEquals(listOf(LiveRelation.PLAYER, LiveRelation.VISITOR), views.map { it.relation })
        val first = views.first()
        assertEquals(MatchId("m-player"), first.matchId)
        assertEquals("Alex" to "Jonas", first.teamA to first.teamB)
        assertEquals(listOf(listOf(6, 4)), first.sets)
        assertEquals(listOf(2, 1), first.games)
        assertEquals(listOf("40", "AD"), first.points)
        assertEquals(Side.B, first.serving)
        assertTrue(first.scorerConnected)
        assertEquals(12_000L, first.ageMs)
        // Codec-Rundweg ohne Telemetrie
        val bytes = LiveViewsCodec.encode(LiveViews(LiveViews.CURRENT_VERSION, views))
        assertFalse(bytes.decodeToString().contains("heartRate"))
    }

    @Test
    fun accountProviderIsEmptyWithoutAccountAndThrowsOnFailure() {
        val port = Port { error("offline") }
        assertEquals(emptyList<LiveView>(), runBlocking { AccountLiveViews(port, { null }).current() })
        assertTrue(port.profiles.isEmpty())
        assertTrue(runCatching { runBlocking { AccountLiveViews(port, { "jonas" }).current() } }.isFailure)
        assertFalse(respond(AccountLiveViews(port, { "jonas" })))
    }

    /**
     * M1: Hat die Uhr den Prozess gestartet, ist die Sitzung noch ungeprüft – erst die Kontoprüfung abwarten, dann
     * abrufen. Scheitert sie (offline), keine Antwort statt „nichts live“; abgemeldet bleibt es leer.
     */
    @Test
    fun aColdProcessChecksTheAccountBeforeFetching() {
        val port = Port { listOf(live("player")) }
        var profile: String? = null
        var checks = 0
        val cold = AccountLiveViews(port, { profile }, awaitAccount = { checks++.also { profile = "jonas" } })
        assertEquals(1, runBlocking { cold.current() }.size)
        runBlocking { cold.current() }
        assertEquals("geprüfte Sitzung: keine zweite Prüfung", 1, checks)
        assertEquals(listOf("jonas", "jonas"), port.profiles)

        assertFalse(respond(AccountLiveViews(port, { null }, awaitAccount = { error("offline") })))
        assertTrue(respond(AccountLiveViews(port, { null }, awaitAccount = {})))
        assertEquals(emptyList<LiveView>(), LiveViewsCodec.decode(sent.single().third)?.matches)
    }
}
