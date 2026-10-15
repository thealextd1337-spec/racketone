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

/**
 * Ergebnis der Log-Analyse vor dem Fold (02 §7.3 Schritte 1–2, 01 §4.4). Fold und inkrementelles `apply` nutzen
 * dieselbe Analyse, damit beide immer dasselbe Ergebnis liefern.
 *
 * @property ordered ein Event je `id` in [EventOrdering]; bei abweichenden Kopien steht die in Ordnung erste als Platzhalter
 * @property rejected IDs, zu denen **abweichende** Kopien vorliegen – keine Kopie geht in den Fold ein (→ `ignoredEvents`)
 * @property undone durch ein wirksames, gültiges `Undo` unwirksame Events
 * @property invalidUndos wirksame `Undo`, deren Ziel in der Ordnung nicht vor ihnen steht (→ `ignoredEvents`)
 * @property absorbed als Doppel-Tap absorbierte `PointWon`
 * @property activeConflicts Widersprüche zwischen zwei wirksamen, nicht absorbierten `PointWon`, die nicht Teil einer
 *   Gabelung sind (die zeigt der Fork-Dialog)
 * @property resolvedConflicts Widersprüche, von denen mindestens eine Seite inzwischen unwirksam ist oder deren Gabelung
 *   aufgelöst ist
 * @property discarded Events verworfener Zweige (gültiges `ForkResolved` mit `keep`, 02 §7.5) – unwirksam wie undone
 * @property resolutions wirksame, gültige `ForkResolved` (je Gabelung das erste in Ordnung)
 * @property ignoredResolutions ungültige bzw. überholte `ForkResolved` (→ `ignoredEvents`)
 */
data class LogAnalysis(
    val ordered: List<ScoreEvent>,
    val rejected: Set<EventId>,
    val undone: Set<EventId>,
    val invalidUndos: Set<EventId>,
    val absorbed: Set<EventId>,
    val activeConflicts: List<TapConflict>,
    val resolvedConflicts: List<TapConflict>,
    val discarded: Set<EventId> = emptySet(),
    val resolutions: Set<EventId> = emptySet(),
    val ignoredResolutions: Set<EventId> = emptySet(),
)

/** Reine Funktionen über ein Event-Log: Ordnung, Duplikate, Wirksamkeit (Undo), Doppel-Taps und Widersprüche. */
object EventLog {
    /** Zeitfenster für Doppel-Taps (01 §4.4): höchstens 5 s Abstand der `wallClockMs`. */
    const val DOUBLE_TAP_WINDOW_MS: Long = 5_000L

    /** Totale Ordnung auch für abweichende Kopien gleicher Ordnungsschlüssel (nur für deren deterministische Ablage). */
    private val TOTAL: Comparator<ScoreEvent> = EventOrdering.thenBy { it.toString() }

    /**
     * Rohes Log: geordnet, **bitgleiche** Wiederholungen entfernt (Merge ist damit idempotent). Abweichende Kopien
     * derselben `id` bleiben erhalten und werden von [analyze] zurückgewiesen.
     */
    fun distinctOrdered(events: Collection<ScoreEvent>): List<ScoreEvent> = events.distinct().sortedWith(TOTAL)

    /** Ein Event je `id` in [EventOrdering] (Platzhalter für abweichende Kopien siehe [LogAnalysis.ordered]). */
    fun ordered(events: Collection<ScoreEvent>): List<ScoreEvent> = analyze(events).ordered

    /** Vollständige Analyse eines (ungeordneten) Logs. */
    fun analyze(events: Collection<ScoreEvent>): LogAnalysis = analyzeWithForks(events).first

    /**
     * Analyse inkl. Gabelungen (02 §7.3 Schritte 1–2, 6 und §7.5):
     * 1. Undo-Wirksamkeit über alle nicht zurückgewiesenen Events (Durchlauf 1).
     * 2. Verzweigungen und Auflösungen: je Gabelung gilt das erste nicht rückgängig gemachte gültige `ForkResolved`.
     * 3. Verworfene Zweige: Undo-Wirksamkeit erneut, wobei verworfene Events nichts bewirken (Durchlauf 2).
     * 4. Doppel-Taps ohne verworfene Partner, Widersprüche, offene Gabelungen.
     */
    internal fun analyzeWithForks(events: Collection<ScoreEvent>): Pair<LogAnalysis, List<ForkCandidate>> {
        val raw = distinctOrdered(events)
        val seen = HashSet<EventId>()
        val rejected = HashSet<EventId>()
        val ordered = mutableListOf<ScoreEvent>()
        for (event in raw) {
            if (seen.add(event.id)) ordered += event else rejected += event.id
        }
        val candidates = ordered.filter { it.id !in rejected }
        var (undone, invalidUndos) = effectiveness(ordered, rejected)
        val points = ForkAnalysis.branchPoints(candidates)
        val resolutions = ForkAnalysis.resolutions(candidates, undone, points)
        val discarded = resolutions.discarded
        if (discarded.isNotEmpty()) {
            val second = effectiveness(ordered, rejected + discarded)
            undone = second.first - discarded
            invalidUndos = second.second
        }
        val absorbed = absorbedDoubleTaps(candidates.filter { it.id !in discarded })
        val ineffective = { id: EventId -> id in undone || id in absorbed || id in discarded }
        val forks = ForkAnalysis.open(points, { id -> !ineffective(id) }, resolutions.winners)
        val all = conflicts(candidates)
        val openPairs = forks.flatMap { f -> f.live.map { it.id } }.toSet()
        val resolvedPairs =
            resolutions.winners
                .map { it.payload as ForkResolved }
                .flatMap { r -> r.branches.flatMap { a -> r.branches.filter { it != a }.map { b -> a to b } } }
                .toSet()
        val visible = all.filterNot { it.first in openPairs && it.second in openPairs }
        val (active, resolved) =
            visible.partition { c ->
                listOf(c.first, c.second).none { ineffective(it) } && (c.first to c.second) !in resolvedPairs
            }
        val analysis =
            LogAnalysis(
                ordered = ordered,
                rejected = rejected,
                undone = undone,
                invalidUndos = invalidUndos,
                absorbed = absorbed,
                activeConflicts = active,
                resolvedConflicts = resolved,
                discarded = discarded,
                resolutions = resolutions.winners.mapTo(HashSet()) { it.id },
                ignoredResolutions = resolutions.ignored,
            )
        return analysis to forks
    }

    /**
     * 02 §7.3 Schritt 2: Das geordnete Log wird rückwärts durchlaufen; ein Event ist unwirksam, wenn ein *wirksames*
     * `Undo` auf seine `id` zeigt. Ein `Undo` wirkt nur auf Events, die in der Ordnung **vor** ihm stehen; zeigt es auf
     * ein späteres Event (oder sich selbst), ist es ungültig und wird übersprungen. Ein unbekanntes Ziel ist ein No-op.
     */
    private fun effectiveness(
        ordered: List<ScoreEvent>,
        rejected: Set<EventId>,
    ): Pair<Set<EventId>, Set<EventId>> {
        val position = HashMap<EventId, Int>()
        ordered.forEachIndexed { i, e -> position[e.id] = i }
        val undone = HashSet<EventId>()
        val invalid = HashSet<EventId>()
        for (i in ordered.indices.reversed()) {
            val event = ordered[i]
            if (event.id in undone || event.id in rejected) continue
            val payload = event.payload as? Undo ?: continue
            val target = position[payload.target] ?: continue
            if (target >= i) invalid += event.id else undone += payload.target
        }
        return undone to invalid
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
            val payload = event.payload as? PointWon ?: continue
            val earlier = byParent.getOrPut(parent) { mutableListOf() }
            val duplicate =
                earlier.any {
                    it.id !in absorbed &&
                        (it.payload as PointWon).side == payload.side &&
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
