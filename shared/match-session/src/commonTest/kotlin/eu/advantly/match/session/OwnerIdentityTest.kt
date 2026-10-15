package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.LocalData
import eu.advantly.data.PlayerKind
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.PeerKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Identität des Besitzers (W1-I): Das Handy ist Identitätsquelle, die eigene Uhr übernimmt dessen Self-Spieler; vorher
 * auf der Uhr angelegte Matches zeigen am Handy den Besitzer (Alias), keinen Gast. Außerdem: `dataRevision` meldet
 * Änderungen außerhalb des offenen Matches (Löschung von der Uhr).
 */
class OwnerIdentityTest {
    private class Pair2 {
        val scheduler = ManualScheduler()
        val network = InMemoryLinkNetwork(scheduler)
        private var ids = 0
        val phoneData: LocalData = testLocalData()
        val watchData: LocalData = testLocalData()

        fun session(
            name: String,
            kind: PeerKind,
            data: LocalData,
        ) = MatchSession(
            data,
            ReliableDeviceLink(network.transport(name), scheduler, newMessageId = { "$name-${++ids}" }),
            scheduler,
            SessionConfig(DeviceId(name), kind),
            { 1_760_000_000_000L + scheduler.nowMs() },
            RandomIdGenerator(Random(name.length)),
        )

        fun settle(ms: Long = 2_000) = scheduler.advanceBy(ms)
    }

    @Test
    fun watchAdoptsThePhoneIdentityAndOldWatchMatchesShowTheOwner() {
        val rig = Pair2()
        val phoneSelf = rig.phoneData.players.ensureSelf("Alex", 1) { PlayerId("phone-self") }
        val watchSelf = rig.watchData.players.ensureSelf("", 1) { PlayerId("watch-self") }
        val watch = rig.session("watch", PeerKind.WATCH, rig.watchData)
        val phone = rig.session("phone", PeerKind.PHONE, rig.phoneData)
        // Uhr zählt allein (Handy aus), Besetzung mit dem Uhr-eigenen Self
        val lineup = Lineup(listOf(watchSelf.id), listOf(PlayerId("gegner")), watchSelf.id)
        watch.start()
        val oldMatch = watch.create(Presets.TENNIS_SINGLES_BO3.format(), lineup)
        watch.recordPoint(Side.A)

        phone.start()
        rig.settle()

        // Uhr: übernimmt die Identität des Handys, der alte Self wird Alias
        assertEquals(
            phoneSelf.id,
            rig.watchData.players
                .self()
                ?.id,
        )
        assertEquals(
            "Alex",
            rig.watchData.players
                .self()
                ?.displayName,
        )
        assertTrue(rig.watchData.isSelf(watchSelf.id))
        // Handy: kennt den alten Uhr-Self als Alias, das alte Match zeigt also den Besitzer
        assertNotNull(rig.phoneData.matches.get(oldMatch))
        assertTrue(rig.phoneData.isSelf(watchSelf.id))
        assertEquals(
            phoneSelf.id,
            rig.phoneData.players
                .self()
                ?.id,
            "Handy bleibt Identitätsquelle",
        )
        assertEquals(
            1,
            rig.phoneData.players
                .all()
                .count { it.kind == PlayerKind.SELF },
        )
    }

    @Test
    fun remoteDeletionRaisesTheDataRevision() {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        val phone = cluster["phone"]
        val id = phone.session.create(Presets.BADMINTON_1GAME_15.format(), SINGLES)
        repeat(15) { phone.session.recordPoint(Side.A) }
        cluster.settle()
        phone.session.closeMatch()
        val before = phone.session.state.value.dataRevision
        cluster["watch"].session.deleteMatch(id)
        cluster.settle()
        assertTrue(phone.session.state.value.dataRevision > before)
        assertNull(phone.data.matches.get(id))
        assertNull(phone.session.state.value.match)
    }
}
