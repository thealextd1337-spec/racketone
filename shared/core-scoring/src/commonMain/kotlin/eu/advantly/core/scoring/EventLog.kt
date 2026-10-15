package eu.advantly.core.scoring

import eu.advantly.core.model.EventId

/**
 * Totale, deterministische Ordnung der Events: `(lamport, deviceId, id)` (01 §3.2, 02 §7.3 Schritt 1).
 * Strings werden lexikografisch verglichen – auf allen Plattformen gleich.
 */
object EventOrdering : Comparator<ScoreEvent> {
    override fun compare(
        a: ScoreEvent,
        b: ScoreEvent,
    ): Int =
        when {
            a.lamport != b.lamport -> a.lamport.compareTo(b.lamport)
            a.deviceId != b.deviceId -> a.deviceId.compareTo(b.deviceId)
            else -> a.id.compareTo(b.id)
        }
}

/** Zwei gleichzeitige `PointWon` mit gleichem `parentId` für **verschiedene** Sides (01 §4.4 „Widerspruch“). */
data class TapConflict(
    val first: EventId,
    val second: EventId,
)

/** Reine Funktionen über ein Event-Log: Ordnung, Wirksamkeit (Undo) und Doppel-Tap-Erkennung. */
object EventLog {
    /** Zeitfenster für Doppel-Taps (01 §4.4): höchstens 5 s Abstand der `wallClockMs`. */
    const val DOUBLE_TAP_WINDOW_MS: Long = 5_000L

    /**
     * Ordnet [events] nach [EventOrdering] und entfernt Duplikate gleicher `id` (Merge ist damit idempotent).
     * Bei gleicher `id` gilt das in Ordnung erste Exemplar.
     */
    fun ordered(events: Collection<ScoreEvent>): List<ScoreEvent> {
        val seen = HashSet<EventId>()
        return events.sortedWith(EventOrdering).filter { seen.add(it.id) }
    }

    /**
     * IDs der unwirksamen Events (02 §7.3 Schritt 2): Das geordnete Log wird rückwärts durchlaufen; ein Event ist
     * unwirksam, wenn ein *wirksames* `Undo` (das in Ordnung später steht) auf seine `id` zeigt.
     */
    fun undoneIds(ordered: List<ScoreEvent>): Set<EventId> {
        val undone = HashSet<EventId>()
        for (i in ordered.indices.reversed()) {
            val event = ordered[i]
            if (event.id in undone) continue
            val payload = event.payload
            if (payload is Undo) undone += payload.target
        }
        return undone
    }

    /**
     * Doppel-Taps (01 §4.4): `PointWon` mit gleichem `parentId`, gleicher Side und höchstens
     * [DOUBLE_TAP_WINDOW_MS] Abstand. Das in Ordnung erste gilt, die weiteren sind „absorbiert“ und gehen nicht in
     * den Fold ein. Die Erkennung läuft vor dem Fold auf dem ganzen geordneten Log (02 §7.3 Schritt 6).
     */
    fun absorbedDoubleTaps(ordered: List<ScoreEvent>): Set<EventId> {
        val absorbed = HashSet<EventId>()
        val byParent = LinkedHashMap<EventId, MutableList<ScoreEvent>>()
        for (event in ordered) {
            val parent = event.parentId ?: continue
            if (event.payload !is PointWon) continue
            val earlier = byParent.getOrPut(parent) { mutableListOf() }
            val side = (event.payload as PointWon).side
            val duplicate =
                earlier.any {
                    it.id !in absorbed &&
                        (it.payload as PointWon).side == side &&
                        kotlin.math.abs(it.wallClockMs - event.wallClockMs) <= DOUBLE_TAP_WINDOW_MS
                }
            if (duplicate) absorbed += event.id
            earlier += event
        }
        return absorbed
    }

    /**
     * Widersprüche (01 §4.4): `PointWon` mit gleichem `parentId`, aber verschiedenen Sides. Beide bleiben wirksam;
     * die UI zeigt einen Hinweis mit 1-Tap-Undo. Paare in Log-Ordnung.
     */
    fun conflicts(ordered: List<ScoreEvent>): List<TapConflict> {
        val result = mutableListOf<TapConflict>()
        val byParent = LinkedHashMap<EventId, MutableList<ScoreEvent>>()
        for (event in ordered) {
            val parent = event.parentId ?: continue
            val payload = event.payload as? PointWon ?: continue
            val earlier = byParent.getOrPut(parent) { mutableListOf() }
            earlier.filter { (it.payload as PointWon).side != payload.side }.forEach { result += TapConflict(it.id, event.id) }
            earlier += event
        }
        return result
    }
}
