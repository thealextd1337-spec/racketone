package eu.advantly.core.scoring

import eu.advantly.core.model.EventId

/**
 * Strukturelle Verzweigung im `parentId`-Baum (02 §7.5): Event [ancestor] hat mindestens zwei Kinder ([roots]).
 *
 * @property branches je Wurzel ihr Zweig = beglaubigte Kette ab der Wurzel, in Ordnung
 */
internal class BranchPoint(
    val ancestor: EventId,
    val roots: List<ScoreEvent>,
    val branches: Map<EventId, List<ScoreEvent>>,
)

/** Gültige, in der Ordnung erste Auflösungen je Gabelung und was sie verwerfen. */
internal class Resolutions(
    val winners: List<ScoreEvent>,
    val ignored: Set<EventId>,
    val discarded: Set<EventId>,
)

/** Offene Gabelung ohne Vorschau (die rechnet [Replay] mit demselben Fold). */
internal class ForkCandidate(
    val point: BranchPoint,
    val live: List<ScoreEvent>,
    val points: Map<EventId, Int>,
)

/**
 * Fork-Erkennung als reine Funktion der Event-Menge (02 §7.5, 01 §4.4). Alle Geräte mit derselben Event-Menge
 * berechnen dieselben Gabelungen – unabhängig von Zustellreihenfolge, Batches und Gerät.
 *
 * **Wissen im Log:** Ein Gerät erzeugt `lamport = max(lamport(Kopf), Anzahl bekannter Events) + 1` (01 §4.4). Ein
 * Event, dessen `lamport` genau `lamport(parent) + 1` ist, kannte beim Erzeugen nichts außer dem Wissen seines Elternteils
 * („beglaubigte Kante“). Ein Zweig ist die Wurzel plus alle über beglaubigte Kanten erreichbaren Nachfahren; Events nach
 * einem Wissenssprung (z. B. nach dem Zusammenführen) gehören nicht mehr dazu. `ForkResolved` gehört nie zu einem Zweig.
 */
internal object ForkAnalysis {
    fun branchPoints(candidates: List<ScoreEvent>): List<BranchPoint> {
        val children = LinkedHashMap<EventId, MutableList<ScoreEvent>>()
        for (event in candidates) event.parentId?.let { children.getOrPut(it) { mutableListOf() } += event }
        return children
            .filter { it.value.size >= 2 }
            .map { (ancestor, roots) -> BranchPoint(ancestor, roots, roots.associate { it.id to chain(it, children) }) }
    }

    /** Wurzel + Nachfahren über beglaubigte Kanten (`lamport(Kind) = lamport(Eltern) + 1`), ohne `ForkResolved`. */
    private fun chain(
        root: ScoreEvent,
        children: Map<EventId, List<ScoreEvent>>,
    ): List<ScoreEvent> {
        val result = mutableListOf<ScoreEvent>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val event = queue.removeFirst()
            result += event
            children[event.id].orEmpty().forEach { child ->
                if (child.lamport == event.lamport + 1 && child.payload !is ForkResolved) queue.addLast(child)
            }
        }
        return result.sortedWith(EventOrdering)
    }

    /**
     * Auflösungen: gültig, wenn [ForkResolved.ancestor] eine Verzweigung ist, alle [ForkResolved.branches] (≥ 2,
     * verschieden) deren Wurzeln sind und `keep` dazugehört oder `null` ist. Je Gabelung (Vorfahr + Wurzelmenge) gilt die
     * in der Ordnung erste nicht rückgängig gemachte; spätere und ungültige stehen in [Resolutions.ignored].
     */
    fun resolutions(
        candidates: List<ScoreEvent>,
        undone: Set<EventId>,
        points: List<BranchPoint>,
    ): Resolutions {
        val byAncestor = points.associateBy { it.ancestor }
        val winners = mutableListOf<ScoreEvent>()
        val ignored = HashSet<EventId>()
        val discarded = HashSet<EventId>()
        val keys = HashSet<Pair<EventId, List<EventId>>>()
        for (event in candidates) {
            val payload = event.payload as? ForkResolved ?: continue
            if (event.id in undone) continue
            val point = byAncestor[payload.ancestor]
            val valid =
                point != null &&
                    payload.branches.size >= 2 &&
                    payload.branches.toSet().size == payload.branches.size &&
                    payload.branches.all { it in point.branches } &&
                    (payload.keep == null || payload.keep in payload.branches)
            if (!valid || !keys.add(payload.ancestor to payload.branches.sorted())) {
                ignored += event.id
                continue
            }
            winners += event
            val keep = payload.keep ?: continue
            payload.branches.filter { it != keep }.forEach { root -> point.branches.getValue(root).mapTo(discarded) { it.id } }
        }
        return Resolutions(winners, ignored, discarded)
    }

    /**
     * Offene Gabelungen: ≥ 2 lebende Zweige (mit wirksamem, nicht absorbiertem `PointWon`), keine gültige Auflösung,
     * die alle lebenden Wurzeln abdeckt, und kein einfacher Widerspruch (beide Zweige bestehen aus genau ihrem Wurzel-
     * `PointWon` mit verschiedenen Sides → Konflikt-Banner, 01 §4.4).
     */
    fun open(
        points: List<BranchPoint>,
        isLive: (EventId) -> Boolean,
        winners: List<ScoreEvent>,
    ): List<ForkCandidate> =
        points.mapNotNull { point ->
            val counts =
                point.roots.associate { root ->
                    root.id to point.branches.getValue(root.id).count { it.payload is PointWon && isLive(it.id) }
                }
            val live = point.roots.filter { counts.getValue(it.id) > 0 }
            val covered =
                winners.any { w ->
                    (w.payload as ForkResolved).let {
                        it.ancestor == point.ancestor &&
                            it.branches.containsAll(live.map { r -> r.id })
                    }
                }
            when {
                live.size < 2 || covered -> null
                isContradiction(live, counts, isLive) -> null
                else -> ForkCandidate(point, live, counts)
            }
        }

    private fun isContradiction(
        live: List<ScoreEvent>,
        counts: Map<EventId, Int>,
        isLive: (EventId) -> Boolean,
    ): Boolean {
        if (live.size != 2 || live.any { counts.getValue(it.id) != 1 }) return false
        val (a, b) = live
        val pa = a.payload as? PointWon ?: return false
        val pb = b.payload as? PointWon ?: return false
        return pa.side != pb.side && isLive(a.id) && isLive(b.id)
    }

    /** Stabile Fork-ID aus Vorfahr und lebenden Wurzeln. */
    fun id(
        ancestor: EventId,
        roots: List<EventId>,
    ): String = ancestor.value + ":" + roots.joinToString("|") { it.value }
}
