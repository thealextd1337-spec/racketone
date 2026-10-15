package eu.advantly.core.scoring

import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat

/**
 * Ergebnis eines Folds inklusive geordnetem Log – Grundlage für das inkrementelle [ScoringEngine.apply].
 *
 * @property events alle Events außer der Genesis, geordnet nach [EventOrdering] und ohne doppelte IDs
 * @property state kanonischer Zustand (02 §8)
 * @property undoTarget Ziel für das UI-Undo: letztes wirksames `PointWon`, `ContinueDecision`, `Correction` oder
 *   `MatchEnded` (02 §7.3), sonst `null`
 * @property absorbedEvents als Doppel-Tap absorbierte `PointWon` (01 §4.4), in Log-Ordnung
 * @property conflicts gleichzeitige `PointWon` für verschiedene Sides (01 §4.4 „Widerspruch“)
 */
class EngineState internal constructor(
    val format: MatchFormat,
    val lineup: Lineup,
    val events: List<ScoreEvent>,
    val state: MatchState,
    val undoTarget: EventId?,
    val absorbedEvents: List<EventId>,
    val conflicts: List<TapConflict>,
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
    ): EngineState = Replay.run(format, lineup, EventLog.ordered(events), null)

    /** Wie [fold] über ein vollständiges Log mit Genesis. */
    fun replay(log: Collection<ScoreEvent>): EngineState {
        val ordered = EventLog.ordered(log)
        val genesis = requireNotNull(ordered.firstOrNull { it.payload is MatchCreated }) { "Log ohne MatchCreated" }
        val created = genesis.payload as MatchCreated
        return Replay.run(created.config, created.lineup, ordered.filter { it.id != genesis.id }, null)
    }

    /**
     * Wendet [event] inkrementell an. Das Ergebnis ist per Definition identisch zum Fold über `state.events + event`
     * (per Property-Test abgesichert). Schneller Pfad, wenn das Event in der Ordnung hinten anliegt und weder Undo,
     * rückwirkende Korrektur noch Doppel-Tap-Kandidat ist; sonst wird neu gefaltet. Bekannte IDs sind ein No-op.
     */
    fun apply(
        state: EngineState,
        event: ScoreEvent,
    ): EngineState {
        if (state.events.any { it.id == event.id }) return state
        val last = state.events.lastOrNull()
        val payload = event.payload
        val fastPath =
            (last == null || EventOrdering.compare(event, last) > 0) &&
                payload !is Undo &&
                payload !is MatchCreated &&
                !(payload is Correction && payload.kind == CorrectionKind.INITIAL_SERVER) &&
                !(
                    payload is PointWon && event.parentId != null &&
                        state.events.any { it.parentId == event.parentId && it.payload is PointWon }
                )
        if (!fastPath) return Replay.run(state.format, state.lineup, EventLog.ordered(state.events + event), null)
        val core = state.core.copy()
        core.step(event)
        val undoTarget = if (Replay.isUndoTargetType(payload)) event.id else state.undoTarget
        return EngineState(
            state.format,
            state.lineup,
            state.events + event,
            core.export(),
            undoTarget,
            state.absorbedEvents,
            state.conflicts,
            core,
        )
    }

    /** Alle wirksam angewendeten Punkte mit Zustand davor und danach (Statistik-Basis, 02 §11.1). */
    fun pointHistory(
        format: MatchFormat,
        lineup: Lineup,
        events: Collection<ScoreEvent>,
    ): List<PointRecord> {
        val records = mutableListOf<PointRecord>()
        Replay.run(format, lineup, EventLog.ordered(events)) { event, before, after ->
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
        ordered: List<ScoreEvent>,
        onPoint: ((ScoreEvent, MatchState, MatchState) -> Unit)?,
    ): EngineState {
        val formatErrors = MatchFormatValidator.validate(format).errors
        require(formatErrors.isEmpty()) { "Ungültiges MatchFormat: ${formatErrors.joinToString { it.message }}" }
        val lineupErrors = LineupValidator.validate(format, lineup)
        require(lineupErrors.isEmpty()) { "Ungültiges Lineup: ${lineupErrors.joinToString()}" }

        val undone = EventLog.undoneIds(ordered)
        val absorbed = EventLog.absorbedDoubleTaps(ordered)
        // Schritt 3: rückwirkende Correction(INITIAL_SERVER) – das letzte wirksame gilt
        val initialServer =
            ordered
                .lastOrNull {
                    val p = it.payload
                    it.id !in undone &&
                        p is Correction &&
                        p.kind == CorrectionKind.INITIAL_SERVER &&
                        p.player != null &&
                        lineup.sideOf(p.player) != null
                }?.let { (it.payload as Correction).player }
        val effectiveLineup = if (initialServer != null) lineup.copy(firstServer = initialServer) else lineup
        val core = Core.initial(format, effectiveLineup)
        var undoTarget: EventId? = null
        for (event in ordered) {
            val payload = event.payload
            when {
                payload is Undo -> {
                    core.effects = setOf(Effect.UNDONE)
                }

                event.id in undone -> {
                    Unit
                }

                event.id in absorbed -> {
                    core.effects = emptySet()
                }

                else -> {
                    if (isUndoTargetType(payload)) undoTarget = event.id
                    if (onPoint != null && payload is PointWon) {
                        val before = core.export()
                        val ignoredBefore = core.ignored.size
                        core.step(event)
                        if (core.ignored.size == ignoredBefore) onPoint(event, before, core.export())
                    } else {
                        core.step(event)
                    }
                }
            }
        }
        return EngineState(
            format = format,
            lineup = lineup,
            events = ordered,
            state = core.export(),
            undoTarget = undoTarget,
            absorbedEvents = ordered.filter { it.id in absorbed }.map { it.id },
            conflicts = EventLog.conflicts(ordered),
            core = core,
        )
    }
}
