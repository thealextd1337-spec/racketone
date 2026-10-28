package eu.advantly.match.session

import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.PointDetailSet
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

    /**
     * Wie [single], aber ein automatischer 1. Aufschlag (Modus „Ja“) reist mit dem Punkt, vor dem ihn die Session schrieb
     * (gleiches Gerät, direkt nacheinander gesendet) – so bleiben die Permutationen im Modus „Ja“ überschaubar.
     */
    private fun withSettles(events: List<ScoreEvent>): List<SyncMessage> {
        val messages = mutableListOf<SyncMessage>()
        var pending: ScoreEvent? = null
        for (e in events) {
            val settle = pending
            pending = null
            when {
                e.payload is PointDetailSet -> pending = e
                settle != null && settle.deviceId == e.deviceId -> messages += Events(e.matchId, listOf(settle, e))
                else -> messages += listOfNotNull(settle, e).map { Events(it.matchId, listOf(it)) }
            }
        }
        pending?.let { messages += Events(it.matchId, listOf(it)) }
        return messages
    }

    private fun factorial(n: Int): Int = (1..n).fold(1) { acc, i -> acc * i }

    @Test
    fun handoverWithOfflineOldScorer() = SERVE_MODES.forEach(::handoverWithOfflineOldScorer)

    private fun handoverWithOfflineOldScorer(meta: MatchMeta) {
        // Uhr zählt; Handy übernimmt, während die Uhr offline weiterzählt
        val watch = WireNode("watch", 1, PeerKind.WATCH)
        val phone = WireNode("phone", 2)
        val id = watch.session.create(format, SINGLES, meta)
        phone.link.deliver(Events(id, watch.events()))
        watch.points(Side.A, 2)
        phone.link.deliver(Events(id, watch.events()))
        assertTrue(phone.session.takeOverScoring())
        phone.points(Side.B, 1)
        watch.points(Side.A, 1)
        val all = (watch.events() + phone.events()).distinctBy { it.id }
        val reference = ScoringEngine.replay(all).state
        // verworfen: der offline gezählte Uhr-Punkt (Modus „Ja“: dazu ihr 1. Aufschlag zum vorigen Punkt)
        assertEquals(if (meta == NO_SERVE_TRACKING) 1 else 2, reference.rejectedByRole.size)
        assertTrue(
            phone.events().none { it.payload is PointDetailSet && it.deviceId == phone.session.deviceId },
            "kein automatischer 1. Aufschlag des Handys für Uhr-Punkte",
        )
        val messages = withSettles(all)
        val runs =
            assertAllOrders(messages) { receiver ->
                assertEquals(reference, receiver.match.state)
                assertEquals(phone.session.deviceId, receiver.match.scorerDeviceId)
                assertEquals(reference.rejectedByRole.size, receiver.match.rejectedByRoleCount)
            }
        assertEquals(720, runs, "$meta")
        assertEquals(factorial(messages.size), runs)
    }

    @Test
    fun simultaneousTakeoversOfThreeDevices() {
        val phone = WireNode("phone", 3)
        val watch1 = WireNode("watch1", 4, PeerKind.WATCH)
        val watch2 = WireNode("watch2", 5, PeerKind.WATCH)
        val id = phone.session.create(format, SINGLES, NO_SERVE_TRACKING)
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
    fun followUpMatchIsFollowedInEveryOrder() = SERVE_MODES.forEach(::followUpMatchIsFollowedInEveryOrder)

    private fun followUpMatchIsFollowedInEveryOrder(meta: MatchMeta) {
        val watch = WireNode("watch", 6, PeerKind.WATCH)
        val old = watch.session.create(format, SINGLES, meta)
        watch.points(Side.A, 1)
        watch.session.endMatch()
        val oldEvents = watch.events(old)
        val next = checkNotNull(watch.session.createFollowUp())
        val nextMeta =
            watch.link.sent
                .filterIsInstance<MatchMetaMessage>()
                .last { it.match?.matchId == next }
        val messages = single(oldEvents) + single(watch.events(next)) + nextMeta
        val runs =
            assertAllOrders(messages) { receiver ->
                assertEquals(next, receiver.match.id, "offenes Match")
                assertEquals(old, receiver.match.previousMatchId)
                assertEquals(watch.match.state, receiver.match.state)
            }
        // Modus „Ja“: Matchende schreibt den 1. Aufschlag zum letzten Punkt – eine Nachricht mehr
        assertEquals(if (meta == NO_SERVE_TRACKING) 120 else 720, runs)
    }
}
