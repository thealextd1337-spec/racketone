package eu.advantly.core.scoring

import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat

/**
 * Ergebnis eines Folds inklusive Log – Grundlage für das inkrementelle [ScoringEngine.apply].
 *
 * @property events alle Events außer der Genesis, geordnet nach [EventOrdering]; bitgleiche Wiederholungen entfernt,
 *   abweichende Kopien derselben `id` bleiben enthalten (siehe [rejectedEvents])
 * @property state kanonischer Zustand (02 §8)
 * @property undoTarget Ziel für das UI-Undo: letztes wirksam **angewendetes** `PointWon`, `ContinueDecision`,
 *   `Correction` oder `MatchEnded` (02 §7.3); übersprungene Events sind nie Ziel. Sonst `null`.
 * @property absorbedEvents als Doppel-Tap absorbierte `PointWon` (01 §4.4), in Log-Ordnung
 * @property conflicts aktive Widersprüche (01 §4.4): beide `PointWon` wirksam und nicht absorbiert
 * @property resolvedConflicts frühere Widersprüche, die inzwischen (z. B. per Undo) aufgelöst sind
 * @property rejectedEvents IDs mit abweichenden Kopien; keine Kopie geht in den Fold ein (stehen in `ignoredEvents`)
 */
class EngineState internal constructor(
    val format: MatchFormat,
    val lineup: Lineup,
    val events: List<ScoreEvent>,
    val state: MatchState,
    val undoTarget: EventId?,
    val absorbedEvents: List<EventId>,
    val conflicts: List<TapConflict>,
    val resolvedConflicts: List<TapConflict>,
    val rejectedEvents: List<EventId>,
    internal val core: Core,
)

/**
 * Ein wirksames `PointWon` mit dem Zustand davor und danach – Basis der Statistik (02 §11.1).
 *
 * @property before Zustand **vor** dem Punkt (Aufschläger, Flags, Callout)
 * @property after Zustand danach (u. a. `effects`)
 */
data class PointRecord(
    val event: ScoreEvent,
    val point: PointWon,
    val before: MatchState,
    val after: MatchState,
)

/**
 * Öffentliche API der Scoring-Engine (01 §3, 02 §6–§8). Rein funktional und deterministisch:
 * gleiche Events → gleicher Zustand auf jedem Gerät.
 *
 * Typischer Ablauf:
 * ```
 * val start = ScoringEngine.replay(format, lineup, emptyList())
 * val next = ScoringEngine.apply(start, event)       // inkrementell, identisch zum Fold
 * next.state.serve?.player
 * ```
 */
object ScoringEngine {
    /** Startzustand vor dem ersten Event. */
    fun initial(
        format: MatchFormat,
        lineup: Lineup,
    ): MatchState = fold(format, lineup, emptyList())

    /**
     * Fold über [events] (ohne Genesis; Format und Besetzung kommen als Parameter). Die Events dürfen in beliebiger
     * Reihenfolge und mit Duplikaten vorliegen; maßgeblich ist [EventOrdering].
     *
     * @throws IllegalArgumentException wenn Format oder Lineup ungültig sind ([MatchFormatValidator], [LineupValidator])
     */
    fun fold(
        format: MatchFormat,
        lineup: Lineup,
        events: Collection<ScoreEvent>,
    ): MatchState = replay(format, lineup, events).state

    /** Fold über ein vollständiges Log; die in Ordnung erste [MatchCreated] ist die Genesis. */
    fun fold(log: Collection<ScoreEvent>): MatchState = replay(log).state

    /** Wie [fold], liefert aber zusätzlich Log, Undo-Ziel und Sync-Hinweise ([EngineState]). */
    fun replay(
        format: MatchFormat,
        lineup: Lineup,
        events: Collection<ScoreEvent>,
    ): EngineState = Replay.run(format, lineup, events, null)

    /** Wie [fold] über ein vollständiges Log mit Genesis. */
    fun replay(log: Collection<ScoreEvent>): EngineState {
        val ordered = EventLog.distinctOrdered(log)
        val genesis = requireNotNull(ordered.firstOrNull { it.payload is MatchCreated }) { "Log ohne MatchCreated" }
        val created = genesis.payload as MatchCreated
        return Replay.run(created.config, created.lineup, ordered.filter { it != genesis }, null)
    }

    /**
     * Wendet [event] inkrementell an. Das Ergebnis ist per Definition identisch zum Fold über `state.events + event`
     * (per Property-Test abgesichert, auch bei Zustellung in beliebiger Reihenfolge). Schneller Pfad nur, wenn das
     * Event in der Ordnung hinten anliegt, seine `id` neu ist und von keinem `Undo` referenziert wird und es weder
     * Undo, rückwirkende Korrektur noch Doppel-Tap-/Widerspruchskandidat ist; sonst wird neu gefaltet.
     * Eine bitgleiche Wiederholung ist ein No-op.
     */
    fun apply(
        state: EngineState,
        event: ScoreEvent,
    ): EngineState {
        if (event in state.events) return state
        val last = state.events.lastOrNull()
        val payload = event.payload
        val fastPath =
            (last == null || EventOrdering.compare(event, last) > 0) &&
                payload !is Undo &&
                payload !is MatchCreated &&
                !(payload is Correction && payload.kind == CorrectionKind.INITIAL_SERVER) &&
                state.events.none { it.id == event.id || (it.payload as? Undo)?.target == event.id } &&
                !(
                    payload is PointWon && event.parentId != null &&
                        state.events.any { it.parentId == event.parentId && it.payload is PointWon }
                )
        if (!fastPath) return Replay.run(state.format, state.lineup, state.events + event, null)
        val core = state.core.copy()
        val applied = core.step(event)
        val undoTarget = if (applied && Replay.isUndoTargetType(payload)) event.id else state.undoTarget
        return EngineState(
            format = state.format,
            lineup = state.lineup,
            events = state.events + event,
            state = core.export(),
            undoTarget = undoTarget,
            absorbedEvents = state.absorbedEvents,
            conflicts = state.conflicts,
            resolvedConflicts = state.resolvedConflicts,
            rejectedEvents = state.rejectedEvents,
            core = core,
        )
    }

    /** Alle wirksam angewendeten Punkte mit Zustand davor und danach (Statistik-Basis, 02 §11.1). */
    fun pointHistory(
        format: MatchFormat,
        lineup: Lineup,
        events: Collection<ScoreEvent>,
    ): List<PointRecord> {
        val records = mutableListOf<PointRecord>()
        Replay.run(format, lineup, events) { event, before, after ->
            records += PointRecord(event, event.payload as PointWon, before, after)
        }
        return records
    }
}

/** Fold nach 02 §7.3. */
internal object Replay {
    fun isUndoTargetType(payload: EventPayload): Boolean =
        payload is PointWon || payload is ContinueDecision || payload is Correction || payload is MatchEnded

    fun run(
        format: MatchFormat,
        lineup: Lineup,
        events: Collection<ScoreEvent>,
        onPoint: ((ScoreEvent, MatchState, MatchState) -> Unit)?,
    ): EngineState {
        val formatErrors = MatchFormatValidator.validate(format).errors
        require(formatErrors.isEmpty()) { "Ungültiges MatchFormat: ${formatErrors.joinToString { it.message }}" }
        val lineupErrors = LineupValidator.validate(format, lineup)
        require(lineupErrors.isEmpty()) { "Ungültiges Lineup: ${lineupErrors.joinToString()}" }

        val raw = EventLog.distinctOrdered(events)
        val log = EventLog.analyze(raw)
        val excluded = log.undone + log.rejected
        // Schritt 3: rückwirkende Correction(INITIAL_SERVER) – das letzte wirksame gilt
        val initialServer =
            log.ordered
                .lastOrNull {
                    val p = it.payload
                    it.id !in excluded &&
                        p is Correction &&
                        p.kind == CorrectionKind.INITIAL_SERVER &&
                        p.player != null &&
                        lineup.sideOf(p.player) != null
                }?.let { (it.payload as Correction).player }
        val effectiveLineup = if (initialServer != null) lineup.copy(firstServer = initialServer) else lineup
        val core = Core.initial(format, effectiveLineup)
        var undoTarget: EventId? = null
        for (event in log.ordered) {
            val payload = event.payload
            when {
                event.id in log.rejected -> {
                    core.markIgnored(event.id)
                }

                event.id in log.undone -> {
                    Unit
                }

                payload is Undo -> {
                    if (event.id in log.invalidUndos) core.markIgnored(event.id) else core.effects = setOf(Effect.UNDONE)
                }

                event.id in log.absorbed -> {
                    core.effects = emptySet()
                }

                else -> {
                    val before = if (onPoint != null && payload is PointWon) core.export() else null
                    val applied = core.step(event)
                    if (applied && isUndoTargetType(payload)) undoTarget = event.id
                    if (applied && before != null) onPoint?.invoke(event, before, core.export())
                }
            }
        }
        return EngineState(
            format = format,
            lineup = lineup,
            events = raw,
            state = core.export(),
            undoTarget = undoTarget,
            absorbedEvents = log.ordered.filter { it.id in log.absorbed }.map { it.id },
            conflicts = log.activeConflicts,
            resolvedConflicts = log.resolvedConflicts,
            rejectedEvents = log.ordered.filter { it.id in log.rejected }.map { it.id },
            core = core,
        )
    }
}
