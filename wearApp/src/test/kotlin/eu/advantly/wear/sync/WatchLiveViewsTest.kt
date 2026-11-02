package eu.advantly.wear.sync

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Sport
import eu.advantly.match.session.PeerInfo
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.LiveRelation
import eu.advantly.sync.protocol.LiveView
import eu.advantly.sync.protocol.LiveViewSubscription
import eu.advantly.sync.protocol.LiveViewSubscriptionCodec
import eu.advantly.sync.protocol.LiveViews
import eu.advantly.sync.protocol.LiveViewsCodec
import eu.advantly.sync.protocol.PeerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchLiveViewsTest {
    private val phone = PeerInfo("phone", null, PeerKind.PHONE, CompatibilityStatus.COMPATIBLE)
    private val view = LiveView(MatchId("m1"), LiveRelation.VISITOR, Sport.PADEL, "Alex", "Jonas")
    private val payload = LiveViewsCodec.encode(LiveViews(1, listOf(view)))

    @Test
    fun keepsOnlyAnswersOfTheOwnCompatiblePhone() {
        val views = WatchLiveViews()
        assertFalse(views.receive(payload, "stranger", listOf(phone), 1))
        assertFalse(views.receive(payload, "phone", listOf(phone.copy(compatibility = CompatibilityStatus.PEER_TOO_NEW)), 1))
        assertFalse(views.receive("""{"v":9}""".encodeToByteArray(), "phone", listOf(phone), 1))
        assertNull(views.received.value)
        assertTrue(views.receive(payload, "phone", listOf(phone), 42))
        assertEquals(ReceivedLiveViews(listOf(view), 42), views.received.value)
    }

    @Test
    fun subscribesOnlyTheSingleCompatiblePhone() {
        val sent = mutableListOf<Triple<String, String, ByteArray>>()
        val client = TelemetryMessageClient { node, path, bytes -> sent += Triple(node, path, bytes) }
        val views = WatchLiveViews()
        assertFalse(views.subscribe(emptyList(), client))
        assertFalse(views.subscribe(listOf(phone, phone.copy(peer = "phone2")), client))
        assertTrue(views.subscribe(listOf(phone, PeerInfo("w", null, PeerKind.WATCH, CompatibilityStatus.COMPATIBLE)), client))
        val (node, path, bytes) = sent.single()
        assertEquals("phone", node)
        assertEquals(LiveViewSubscription.MESSAGE_PATH, path)
        assertEquals(LiveViewSubscription(1), LiveViewSubscriptionCodec.decode(bytes))
    }
}
