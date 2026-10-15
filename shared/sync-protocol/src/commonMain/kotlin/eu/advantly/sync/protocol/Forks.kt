package eu.advantly.sync.protocol

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.scoring.ContinueDecision
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.EventLog
import eu.advantly.core.scoring.EventOrdering
import eu.advantly.core.scoring.LogAnalysis
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchEnded
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.Undo
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Ein Zweig einer Gabelung: alle Events, die ein Gerät (oder eine Gerätegruppe) ab dem gemeinsamen Vorfahren gezählt
 * hat, ohne den anderen Zweig zu kennen.
 *
 * @property events Event-IDs des Zweigs in Event-Ordnung (auch Undo/Pause usw.)
 * @property devices Geräte, die Events in diesem Zweig erzeugt haben (sortiert)
 */
@Serializable
data class ForkBranch(
    val events: List<EventId>,
    val devices: List<DeviceId>,
)

/**
 * Gabelung im Event-Log (01 §4.4 „Fork“): ab [ancestor] haben mindestens zwei Zweige unabhängig voneinander gezählt.
 * Zählen geht weiter (vorläufig zusammengeführt); die Auflösung wählt einen Zweig ([Forks.resolve]) oder behält beide.
 *
 * @property branches mindestens zwei Zweige, sortiert nach ihrem ersten Event in Event-Ordnung
 */
@Serializable
data class Fork(
    val ancestor: EventId,
    val branches: List<ForkBranch>,
) {
    /** Stabile ID (gleich auf allen Geräten, die dieselbe Gabelung erkennen). */
    val id: String get() = ancestor.value + ":" + branches.joinToString("|") { it.events.first().value }
}

/**
 * Fork-Erkennung und -Auflösung (01 §4.4) als reine Funktionen.
 *
 * **Erkennung beim Empfang** ([detect]): Das Event-Log trägt je Event nur einen `parentId` (den Kopf, den das Gerät
 * gesehen hat). Aus dem fertigen Log allein lässt sich deshalb nicht unterscheiden, ob ein späteres Event den anderen
 * Zweig schon kannte. Beim Empfang eines Batches ist das eindeutig: Ein neues Event, das an Vorfahr X hängt, wurde
 * ohne Kenntnis aller lokalen Nachfahren von X erzeugt (sonst wäre sein Kopf ein späteres Event). Lokale Nachfahren
 * von X und der eingehende Zweig sind also nebenläufig.
 *
 * Kein Fork (sondern Sache der Engine) sind:
 * - Zweige ohne wirksame Zähl-Events (`PointWon`, `ContinueDecision`, `Correction`, `MatchEnded`) – z. B. ein
 *   paralleles Undo („Undo parallel zu Punkt: keine Kollision“),
 * - der **gleichzeitige Tap**: beide Zweige beginnen mit `PointWon` im Doppel-Tap-Fenster (≤ 5 s) und einer der Zweige
 *   besteht aus genau diesem einen Zähl-Event → Doppel-Tap (absorbiert) bzw. Widerspruch (Konflikt-Banner).
 */
object Forks {
    /** Zähl-Events, die einen Zweig zu einem Fork-Zweig machen (UI-Undo-Ziele, 02 §7.3). */
    fun isScoreEvent(event: ScoreEvent): Boolean =
        event.payload.let { it is PointWon || it is ContinueDecision || it is Correction || it is MatchEnded }

    /**
     * Gabelungen, die durch den Empfang von [incoming] in ein Log mit dem bisherigen Inhalt [local] (inkl. Genesis)
     * entstehen. Bereits bekannte Events in [incoming] werden ignoriert. Ergebnis sortiert nach Vorfahr.
     */
    fun detect(
        local: Collection<ScoreEvent>,
        incoming: Collection<ScoreEvent>,
    ): List<Fork> {
        val localById = local.associateBy { it.id }
        val fresh = incoming.filter { it.id !in localById }.distinctBy { it.id }
        if (fresh.isEmpty() || local.isEmpty()) return emptyList()
        val union = local + fresh
        val children = HashMap<EventId, MutableList<ScoreEvent>>()
        union.forEach { e -> e.parentId?.let { children.getOrPut(it) { mutableListOf() } += e } }
        val localChildren = HashMap<EventId, MutableList<ScoreEvent>>()
        local.forEach { e -> e.parentId?.let { localChildren.getOrPut(it) { mutableListOf() } += e } }

        // Anknüpfungspunkt je neuem Event: erster lokaler Vorfahr entlang der parentId-Kette
        val attachedRoots = fresh.filter { it.parentId != null && it.parentId in localById }.groupBy { it.parentId!! }
        if (attachedRoots.isEmpty()) return emptyList()
        val analysis = EventLog.analyze(union)
        val forks = mutableListOf<Fork>()
        for (ancestor in attachedRoots.keys.sorted()) {
            val localBranch = descendants(localChildren[ancestor].orEmpty(), localChildren)
            if (localBranch.isEmpty()) continue
            // Eingehender Zweig: neue Wurzeln + alle Nachfahren, auch früher außer der Reihe gespeicherte Events,
            // deren Elternteil erst jetzt ankommt (lokale Events hängen nie an neuen Events → kein Überlapp)
            val incomingBranch = descendants(attachedRoots.getValue(ancestor), children)
            if (isFork(ancestor, localBranch, incomingBranch, analysis)) {
                val branches = listOf(localBranch, incomingBranch).sortedWith { a, b -> EventOrdering.compare(a.first(), b.first()) }
                forks += Fork(ancestor, branches.map { branchOf(it) })
            }
        }
        return forks
    }

    /** `true`, solange mindestens zwei Zweige von [fork] noch wirksame Zähl-Events haben. */
    fun isOpen(
        fork: Fork,
        log: Collection<ScoreEvent>,
    ): Boolean {
        val analysis = EventLog.analyze(log)
        val byId = analysis.ordered.associateBy { it.id }
        return fork.branches.count { branch ->
            branch.events.any { id -> byId[id]?.let { isScoreEvent(it) && isEffective(it.id, analysis) } == true }
        } >= 2
    }

    /**
     * Auflösung „Version [keep] behalten“: Ziele der `Undo`-Events für alle **anderen** Zweige (01 §4.4 – das Log bleibt
     * append-only). Unwirksame Events werden übersprungen; ein `Undo` im verworfenen Zweig, dessen Ziel außerhalb des
     * Zweigs liegt, wird selbst rückgängig gemacht (das Ziel wird wieder wirksam). Reihenfolge = Event-Ordnung.
     */
    fun resolve(
        fork: Fork,
        keep: Int,
        log: Collection<ScoreEvent>,
    ): List<EventId> {
        require(keep in fork.branches.indices) { "Zweig $keep existiert nicht" }
        val analysis = EventLog.analyze(log)
        val byId = analysis.ordered.associateBy { it.id }
        val targets = mutableListOf<EventId>()
        fork.branches.forEachIndexed { index, branch ->
            if (index == keep) return@forEachIndexed
            val ids = branch.events.toSet()
            branch.events.mapNotNull { byId[it] }.sortedWith(EventOrdering).forEach { event ->
                val payload = event.payload
                val discard =
                    isEffective(event.id, analysis) &&
                        payload !is MatchCreated &&
                        !(payload is Undo && payload.target in ids)
                if (discard) targets += event.id
            }
        }
        return targets
    }

    /**
     * Zustand, wenn nur Zweig [branch] gilt (für den Dialog „Version Uhr (A:B)“): Fold über [events] (ohne Genesis)
     * ohne die Events der anderen Zweige.
     */
    fun preview(
        format: MatchFormat,
        lineup: Lineup,
        events: Collection<ScoreEvent>,
        fork: Fork,
        branch: Int,
    ): MatchState {
        val dropped =
            fork.branches
                .filterIndexed { i, _ -> i != branch }
                .flatMap { it.events }
                .toSet()
        return ScoringEngine.fold(format, lineup, events.filter { it.id !in dropped })
    }

    private fun isFork(
        ancestor: EventId,
        localBranch: List<ScoreEvent>,
        incomingBranch: List<ScoreEvent>,
        analysis: LogAnalysis,
    ): Boolean {
        val localScore = localBranch.filter { isScoreEvent(it) && isEffective(it.id, analysis) }
        val incomingScore = incomingBranch.filter { isScoreEvent(it) && isEffective(it.id, analysis) }
        if (localScore.isEmpty() || incomingScore.isEmpty()) return false
        val localRoot = localBranch.filter { it.parentId == ancestor }.minWithOrNull(EventOrdering)
        val incomingRoot = incomingBranch.filter { it.parentId == ancestor }.minWithOrNull(EventOrdering)
        val simultaneousTap =
            localRoot != null &&
                incomingRoot != null &&
                localRoot.payload is PointWon &&
                incomingRoot.payload is PointWon &&
                abs(localRoot.wallClockMs - incomingRoot.wallClockMs) <= EventLog.DOUBLE_TAP_WINDOW_MS
        return !(simultaneousTap && (localScore.size == 1 || incomingScore.size == 1))
    }

    private fun isEffective(
        id: EventId,
        analysis: LogAnalysis,
    ): Boolean = id !in analysis.undone && id !in analysis.absorbed && id !in analysis.rejected

    private fun descendants(
        roots: List<ScoreEvent>,
        children: Map<EventId, List<ScoreEvent>>,
    ): List<ScoreEvent> {
        val seen = LinkedHashMap<EventId, ScoreEvent>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val event = queue.removeFirst()
            if (seen.put(event.id, event) != null) continue
            children[event.id]?.let { queue.addAll(it) }
        }
        return seen.values.sortedWith(EventOrdering)
    }

    private fun branchOf(events: List<ScoreEvent>): ForkBranch =
        ForkBranch(events.sortedWith(EventOrdering).map { it.id }, events.map { it.deviceId }.distinct().sorted())
}
