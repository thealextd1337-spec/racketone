package eu.advantly.match.session

import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.sync.protocol.Events
import eu.advantly.sync.protocol.MatchMetaMessage
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.SyncMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Konvergenz über **alle** Zustellreihenfolgen kleiner Szenarien (2–3 erzeugende Geräte): Ein frisches Anzeige-Gerät
 * bekommt die Nachrichten in jeder Permutation und muss denselben Zustand (inkl. Zähler und `rejectedByRole`) und
 * dasselbe offene Match haben wie der Fold über die vollständige Event-Menge.
 */
class PermutationConvergenceTest {
    private val format = Presets.TENNIS_SINGLES_BO3.format()

    private fun <T> permutations(items: List<T>): Sequence<List<T>> =
        if (items.size <= 1) {
            sequenceOf(items)
        } else {
            items.indices.asSequence().flatMap { i ->
                permutations(items.take(i) + items.drop(i + 1)).map { listOf(items[i]) + it }
            }
        }

    /** Spielt jede Permutation von [messages] auf einem frischen Gerät ab und prüft das Ergebnis. */
    private fun assertAllOrders(
        messages: List<SyncMessage>,
        check: (WireNode) -> Unit,
    ): Int {
        var count = 0
        for (order in permutations(messages)) {
            val receiver = WireNode("display", 99, PeerKind.PHONE)
            order.forEach { receiver.link.deliver(it) }
            check(receiver)
            count++
        }
        return count
    }

    private fun single(events: List<ScoreEvent>): List<SyncMessage> = events.map { Events(it.matchId, listOf(it)) }

    @Test
    fun handoverWithOfflineOldScorer() {
        // Uhr zählt; Handy übernimmt, während die Uhr offline weiterzählt
        val watch = WireNode("watch", 1, PeerKind.WATCH)
        val phone = WireNode("phone", 2)
        val id = watch.session.create(format, SINGLES)
        phone.link.deliver(Events(id, watch.events()))
        watch.points(Side.A, 2)
        phone.link.deliver(Events(id, watch.events()))
        assertTrue(phone.session.takeOverScoring())
        phone.points(Side.B, 1)
        watch.points(Side.A, 1)
        val all = (watch.events() + phone.events()).distinctBy { it.id }
        val reference = ScoringEngine.replay(all).state
        assertEquals(1, reference.rejectedByRole.size)
        val runs =
            assertAllOrders(single(all)) { receiver ->
                assertEquals(reference, receiver.match.state)
                assertEquals(phone.session.deviceId, receiver.match.scorerDeviceId)
                assertEquals(1, receiver.match.rejectedByRoleCount)
            }
        assertEquals(720, runs)
    }

    @Test
    fun simultaneousTakeoversOfThreeDevices() {
        val phone = WireNode("phone", 3)
        val watch1 = WireNode("watch1", 4, PeerKind.WATCH)
        val watch2 = WireNode("watch2", 5, PeerKind.WATCH)
        val id = phone.session.create(format, SINGLES)
        val genesis = phone.events()
        watch1.link.deliver(Events(id, genesis))
        watch2.link.deliver(Events(id, genesis))
        // beide Uhren übernehmen gleichzeitig (ohne voneinander zu wissen) und zählen je einen Punkt
        assertTrue(watch1.session.takeOverScoring())
        assertTrue(watch2.session.takeOverScoring())
        watch1.points(Side.A, 1)
        watch2.points(Side.B, 1)
        val all = (genesis + watch1.events() + watch2.events()).distinctBy { it.id }
        val reference = ScoringEngine.replay(all).state
        val runs =
            assertAllOrders(single(all)) { receiver ->
                assertEquals(reference, receiver.match.state)
                assertEquals(reference.scorerDeviceId, receiver.match.scorerDeviceId)
            }
        assertEquals(120, runs)
    }

    @Test
    fun followUpMatchIsFollowedInEveryOrder() {
        val watch = WireNode("watch", 6, PeerKind.WATCH)
        val old = watch.session.create(format, SINGLES)
        watch.points(Side.A, 1)
        watch.session.endMatch()
        val oldEvents = watch.events(old)
        val next = checkNotNull(watch.session.createFollowUp())
        val meta =
            watch.link.sent
                .filterIsInstance<MatchMetaMessage>()
                .last { it.match?.matchId == next }
        val messages = single(oldEvents) + single(watch.events(next)) + meta
        val runs =
            assertAllOrders(messages) { receiver ->
                assertEquals(next, receiver.match.id, "offenes Match")
                assertEquals(old, receiver.match.previousMatchId)
                assertEquals(watch.match.state, receiver.match.state)
            }
        assertEquals(120, runs)
    }
}
