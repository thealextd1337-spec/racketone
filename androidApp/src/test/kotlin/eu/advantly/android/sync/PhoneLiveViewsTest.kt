package eu.advantly.android.sync

import eu.advantly.core.model.MatchId
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
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun respond(
        provider: LiveViewsProvider,
        node: String = "watch",
        data: ByteArray = subscription,
        peers: List<PeerInfo> = listOf(watch),
        timeoutMs: Long = PhoneLiveViews.TIMEOUT_MS,
    ) = PhoneLiveViews.respond(node, data, peers, { n, p, b -> sent += Triple(n, p, b) }, provider, timeoutMs)

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
    fun defaultProviderAnswersEmpty() {
        assertTrue(respond(LiveViewsProvider.Empty))
        assertEquals(emptyList<LiveView>(), LiveViewsCodec.decode(sent.single().third)?.matches)
        assertEquals(LiveViewsProvider.Empty, PhoneLiveViews.provider)
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
}
