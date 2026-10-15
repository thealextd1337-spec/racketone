package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.MatchMetaMessage
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review W1-I Runde 2: Die Besitzer-Identität wird übernommen, egal ob das `MatchMeta` der Gegenstelle vor oder nach
 * ihrem `Hello` ankommt (frühe Angaben werden gepuffert und mit bekannter Geräteart verarbeitet).
 */
class OwnerIdentityOrderTest {
    private val phoneSelf = Player(PlayerId("phone-self"), "Alex")
    private val watchSelf = Player(PlayerId("watch-self"), "")

    private fun watch(): WireNode =
        WireNode("watch", seed = 3, kind = PeerKind.WATCH).also {
            it.data.players.ensureSelf("", 1) { watchSelf.id }
        }

    private fun phone(): WireNode =
        WireNode("phone", seed = 4, kind = PeerKind.PHONE, identifyPeer = false).also {
            it.data.players.ensureSelf("Alex", 1) { phoneSelf.id }
        }

    @Test
    fun watchAdoptsWhenMetaArrivesBeforeHello() {
        val node = watch()
        node.link.deliver(MatchMetaMessage(self = phoneSelf))
        assertEquals(
            watchSelf.id,
            node.data.players
                .self()
                ?.id,
            "Art des Absenders noch unbekannt",
        )
        val revision = node.session.state.value.dataRevision
        node.link.deliver(Hello(DeviceId("phone"), PeerKind.PHONE))
        assertEquals(
            phoneSelf.id,
            node.data.players
                .self()
                ?.id,
        )
        assertTrue(node.data.isSelf(watchSelf.id))
        assertTrue(node.session.state.value.dataRevision > revision)
        // alter Self geht sofort ans Handy zurück
        assertTrue(
            node.link.sent
                .filterIsInstance<MatchMetaMessage>()
                .any { watchSelf.id in it.selfAliases },
        )
    }

    @Test
    fun watchAdoptsWhenHelloArrivesFirst() {
        val node = watch()
        node.link.deliver(Hello(DeviceId("phone"), PeerKind.PHONE))
        node.link.deliver(MatchMetaMessage(self = phoneSelf))
        assertEquals(
            phoneSelf.id,
            node.data.players
                .self()
                ?.id,
        )
        assertTrue(node.data.isSelf(watchSelf.id))
    }

    @Test
    fun phoneRemembersTheWatchSelfInBothOrders() {
        val early = phone()
        early.link.deliver(MatchMetaMessage(self = watchSelf))
        assertFalse(early.data.isSelf(watchSelf.id))
        early.link.deliver(Hello(DeviceId("watch"), PeerKind.WATCH))
        assertTrue(early.data.isSelf(watchSelf.id))

        val late = phone()
        late.link.deliver(Hello(DeviceId("watch"), PeerKind.WATCH))
        late.link.deliver(MatchMetaMessage(self = watchSelf))
        assertTrue(late.data.isSelf(watchSelf.id))
        assertEquals(
            phoneSelf.id,
            late.data.players
                .self()
                ?.id,
            "Handy bleibt Identitätsquelle",
        )
    }

    @Test
    fun anotherWatchNeverTakesOverTheWatchIdentity() {
        val node = watch()
        node.link.deliver(MatchMetaMessage(self = Player(PlayerId("other-watch"), "X")))
        node.link.deliver(Hello(DeviceId("other"), PeerKind.WATCH))
        assertEquals(
            watchSelf.id,
            node.data.players
                .self()
                ?.id,
        )
    }
}
