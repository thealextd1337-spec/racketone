package eu.advantly.sync.protocol

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.Undo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork-Erkennung und -Auflösung (01 §4.4) an kleinen, handgebauten Logs. */
class ForksTest {
    private val g = genesis()

    // gemeinsamer Vorfahr: ein Punkt vom Handy
    private val x = point("x", "phone", 2, 2, "g", Side.A)

    private fun ids(vararg values: String) = values.map { EventId(it) }

    private fun undo(
        id: String,
        device: String,
        seq: Long,
        lamport: Long,
        parent: String,
        target: String,
    ): ScoreEvent = ev(id, device, seq, lamport, parent, Undo(EventId(target)))

    @Test
    fun fastForwardIsNoFork() {
        val local = listOf(g, x)
        val incoming = listOf(point("w1", "watch", 1, 3, "x", Side.A), point("w2", "watch", 2, 4, "w1", Side.B))
        assertEquals(emptyList(), Forks.detect(local, incoming))
        // auch nicht in Gegenrichtung: das Handy hat seit x nichts gezählt
        assertEquals(emptyList(), Forks.detect(listOf(g, x) + incoming, listOf(point("p1", "phone", 3, 5, "w2", Side.A))))
    }

    @Test
    fun offlineCountingOnBothDevicesIsAFork() {
        val phoneBranch = listOf(point("p1", "phone", 3, 3, "x", Side.A), point("p2", "phone", 4, 4, "p1", Side.A))
        val watchBranch =
            listOf(
                point("w1", "watch", 1, 3, "x", Side.B, wallClockMs = 100_000),
                point("w2", "watch", 2, 4, "w1", Side.B),
                point("w3", "watch", 3, 5, "w2", Side.B),
            )
        val expected =
            Fork(
                EventId("x"),
                listOf(
                    ForkBranch(ids("p1", "p2"), listOf(DeviceId("phone"))),
                    ForkBranch(ids("w1", "w2", "w3"), listOf(DeviceId("watch"))),
                ),
            )
        // symmetrisch: beide Geräte erkennen dieselbe Gabelung
        assertEquals(listOf(expected), Forks.detect(listOf(g, x) + phoneBranch, watchBranch))
        assertEquals(listOf(expected), Forks.detect(listOf(g, x) + watchBranch, phoneBranch))
        assertEquals("x:p1|w1", expected.id)
        assertTrue(Forks.isOpen(expected, listOf(g, x) + phoneBranch + watchBranch))
    }

    @Test
    fun simultaneousTapsAreLeftToTheEngine() {
        // Widerspruch (verschiedene Seiten) und Doppel-Tap (gleiche Seite) innerhalb von 5 s
        val local = listOf(g, x, point("a1", "watch", 1, 3, "x", Side.A, wallClockMs = 50_000))
        assertEquals(emptyList(), Forks.detect(local, listOf(point("b1", "phone", 3, 3, "x", Side.B, wallClockMs = 51_000))))
        assertEquals(emptyList(), Forks.detect(local, listOf(point("b1", "phone", 3, 3, "x", Side.A, wallClockMs = 52_000))))
        // Paketverlust: Widerspruch und die Fortsetzung des Handys kommen zusammen an → trotzdem kein Fork
        val late =
            listOf(
                point("b1", "phone", 3, 3, "x", Side.B, wallClockMs = 51_000),
                point("c", "phone", 4, 4, "b1", Side.A, wallClockMs = 70_000),
            )
        assertEquals(emptyList(), Forks.detect(local, late))
    }

    @Test
    fun separateOfflineSingleTapsOnTheSameSideAreAFork() {
        // je ein Punkt offline, 60 s auseinander: kein Doppel-Tap → wäre still doppelt gezählt → Fork-Dialog
        val local = listOf(g, x, point("a1", "watch", 1, 3, "x", Side.A, wallClockMs = 50_000))
        val forks = Forks.detect(local, listOf(point("b1", "phone", 3, 3, "x", Side.A, wallClockMs = 110_000)))
        assertEquals(1, forks.size)
    }

    @Test
    fun parallelUndoIsNoFork() {
        val local = listOf(g, x, undo("u", "watch", 1, 3, "x", target = "x"))
        assertEquals(emptyList(), Forks.detect(local, listOf(point("p1", "phone", 3, 3, "x", Side.B))))
    }

    @Test
    fun outOfOrderBranchEventsJoinTheirBranch() {
        val phone = listOf(point("p1", "phone", 3, 3, "x", Side.A), point("p2", "phone", 4, 4, "p1", Side.A))
        val orphan = point("w2", "watch", 2, 4, "w1", Side.B) // kam vor seinem Elternteil an
        val forks = Forks.detect(listOf(g, x, orphan) + phone, listOf(point("w1", "watch", 1, 3, "x", Side.B, wallClockMs = 5)))
        assertEquals(listOf(ids("p1", "p2"), ids("w1", "w2")), forks.single().branches.map { it.events })
    }

    @Test
    fun resolveUndoesTheOtherBranchAndClosesTheFork() {
        val phone = listOf(point("p1", "phone", 3, 3, "x", Side.A), point("p2", "phone", 4, 4, "p1", Side.A))
        val watch = listOf(point("w1", "watch", 1, 3, "x", Side.B, wallClockMs = 5), point("w2", "watch", 2, 4, "w1", Side.B))
        val log = listOf(g, x) + phone + watch
        val fork = Forks.detect(listOf(g, x) + phone, watch).single()

        val keepWatch = Forks.resolve(fork, keep = 1, log)
        assertEquals(ids("p1", "p2"), keepWatch)
        val resolved =
            log +
                keepWatch.mapIndexed { i, target -> undo("r$i", "phone", 5L + i, 5L + i, if (i == 0) "w2" else "r${i - 1}", target.value) }
        assertFalse(Forks.isOpen(fork, resolved))

        val events = log - g
        val previewWatch = Forks.preview(FORMAT, LINEUP, events, fork, 1)
        assertEquals(ScoringEngine.fold(FORMAT, LINEUP, listOf(x) + watch), previewWatch)
        // nach der Auflösung entspricht der Stand der Vorschau (bis auf die Effekte des letzten Events: UNDONE)
        assertEquals(previewWatch.copy(effects = emptyList()), ScoringEngine.fold(FORMAT, LINEUP, resolved - g).copy(effects = emptyList()))
        assertEquals(ScoringEngine.fold(FORMAT, LINEUP, listOf(x) + phone), Forks.preview(FORMAT, LINEUP, events, fork, 0))
    }

    @Test
    fun resolveRevertsUndosThatPointOutOfTheDiscardedBranch() {
        // Handy-Zweig: Undo des gemeinsamen Punkts x, dann ein neuer Punkt; Uhr-Zweig: zwei Punkte
        val phone = listOf(undo("p1", "phone", 3, 3, "x", target = "x"), point("p2", "phone", 4, 4, "p1", Side.B))
        val watch = listOf(point("w1", "watch", 1, 3, "x", Side.A, wallClockMs = 5), point("w2", "watch", 2, 4, "w1", Side.A))
        val log = listOf(g, x) + phone + watch
        val fork = Forks.detect(listOf(g, x) + watch, phone).single()
        assertEquals(ids("p1", "p2"), Forks.resolve(fork, keep = 1, log))
        // Handy behalten: die Uhr-Punkte fallen weg, das Undo im Handy-Zweig bleibt
        assertEquals(ids("w1", "w2"), Forks.resolve(fork, keep = 0, log))
    }
}
