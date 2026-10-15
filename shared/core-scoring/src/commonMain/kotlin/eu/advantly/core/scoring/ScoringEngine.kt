package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
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
 * @property genesis die `MatchCreated`-Genesis, falls über [ScoringEngine.replay] mit vollständigem Log erzeugt
 * @property initialScorer Zähler zu Matchbeginn (Genesis bzw. Parameter), `null` = keine Rollenprüfung (02 §7.5)
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
    val genesis: ScoreEvent?,
    internal val core: Core,
    internal val appliedPoints: Set<EventId>,
    val initialScorer: DeviceId? = null,
) {
    /** Anzahl wirksam angewendeter Ballwechsel (`PointWon`). */
    val pointsPlayed: Int get() = appliedPoints.size
}

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
     * Fold über [events] (ohne Genesis; Format, Besetzung und Zähler kommen als Parameter). **Produktivcode nutzt die
     * Genesis-Varianten** ([fold]/[replay] mit vollständigem Log), damit der Zähler aus `MatchCreated` gilt. Diese Variante
     * dient Testvektoren und Werkzeugen: [scorer] `null` = keine Rollenprüfung bis zum ersten `ScorerChanged`
     * (Alt-Logs, 02 §7.5 Punkt 1). Die Events dürfen in beliebiger
     * Reihenfolge und mit Duplikaten vorliegen; maßgeblich ist [EventOrdering].
     *
     * @throws IllegalArgumentException wenn Format oder Lineup ungültig sind ([MatchFormatValidator], [LineupValidator])
     */
    fun fold(
        format: MatchFormat,
        lineup: Lineup,
        events: Collection<ScoreEvent>,
        scorer: DeviceId? = null,
    ): MatchState = replay(format, lineup, events, scorer).state

    /** Fold über ein vollständiges Log; die in Ordnung erste [MatchCreated] ist die Genesis. */
    fun fold(log: Collection<ScoreEvent>): MatchState = replay(log).state

    /**
     * Wie [fold], liefert aber zusätzlich Log, Undo-Ziel und Sync-Hinweise ([EngineState]). Ohne Genesis: Startzähler
     * [scorer] wie bei [fold] (Produktivcode: [replay] mit vollständigem Log).
     */
    fun replay(
        format: MatchFormat,
        lineup: Lineup,
        events: Collection<ScoreEvent>,
        scorer: DeviceId? = null,
    ): EngineState = Replay.run(format, lineup, events, null, scorer = scorer)

    /**
     * Wie [fold] über ein vollständiges Log mit Genesis. Die Genesis ist die in Ordnung erste [MatchCreated].
     * Die ID-Kollisionsprüfung läuft **vor** der Genesis-Auswahl über das ganze Log: Bitgleiche Wiederholungen der Genesis
     * sind No-ops; trägt ein abweichendes Event die ID der Genesis, ist das Log ungültig (es gibt dann keinen eindeutigen
     * Matchbeginn).
     *
     * @throws IllegalArgumentException ohne `MatchCreated` oder bei kollidierender Genesis-ID
     */
    fun replay(log: Collection<ScoreEvent>): EngineState {
        val ordered = EventLog.distinctOrdered(log)
        val genesis = requireNotNull(ordered.firstOrNull { it.payload is MatchCreated }) { "Log ohne MatchCreated" }
        require(ordered.count { it.id == genesis.id } == 1) {
            "Genesis-ID ${genesis.id} kollidiert mit einem abweichenden Event – Log ungültig"
        }
        val created = genesis.payload as MatchCreated
        val scorer = created.scorerDeviceId ?: genesis.deviceId
        return Replay.run(created.config, created.lineup, ordered.filter { it != genesis }, null, genesis, scorer)
    }

    /**
     * Wendet [event] inkrementell an; Kurzform für [applyAll] mit einem Event. Das Ergebnis ist per Definition identisch
     * zum Fold über `state.events + event`. Eine bitgleiche Wiederholung ist ein No-op.
     *
     * @throws IllegalArgumentException wenn [event] die ID der Genesis mit anderem Inhalt trägt (siehe [replay])
     */
    fun apply(
        state: EngineState,
        event: ScoreEvent,
    ): EngineState = applyAll(state, listOf(event))

    /**
     * Wendet einen **Empfangs-Batch** an: Die neuen Events werden gemeinsam eingeordnet und höchstens **einmal** neu
     * gefaltet. Der Sync-Layer SOLL nach einem Verbindungsabriss alle nachgelieferten Events als ein Batch übergeben –
     * einzelne `apply`-Aufrufe außer der Ordnung falten jeweils neu und werden bei großen Rückständen quadratisch teuer.
     *
     * Schneller Pfad (ohne Neu-Falten), solange jedes Event des Batches in der Ordnung hinten anliegt, seine `id` neu
     * ist und von keinem `Undo` referenziert wird und es weder Undo, rückwirkende Korrektur noch Doppel-Tap-/
     * Widerspruchskandidat ist. Das Ergebnis ist immer identisch zum Fold über `state.events + events`.
     *
     * @throws IllegalArgumentException wenn ein Event die ID der Genesis mit anderem Inhalt trägt (siehe [replay])
     */
    fun applyAll(
        state: EngineState,
        events: Collection<ScoreEvent>,
    ): EngineState {
        val genesis = state.genesis
        if (genesis != null) {
            events.firstOrNull { it.id == genesis.id && it != genesis }?.let {
                throw IllegalArgumentException("Genesis-ID ${genesis.id} kollidiert mit einem abweichenden Event – Log ungültig")
            }
        }
        val known = state.events.toHashSet()
        val fresh = EventLog.distinctOrdered(events.filter { it !in known && it != genesis })
        if (fresh.isEmpty()) return state
        val ids = state.events.mapTo(HashSet()) { it.id }
        val undoTargets = state.events.mapNotNullTo(HashSet()) { (it.payload as? Undo)?.target }
        val pointParents = state.events.filter { it.payload is PointWon }.mapNotNullTo(HashSet()) { it.parentId }
        val applied = HashSet(state.appliedPoints)
        val core = state.core.copy()
        var undoTarget = state.undoTarget
        var last = state.events.lastOrNull()
        val scorer = state.state.scorerDeviceId
        for (event in fresh) {
            val payload = event.payload
            val fastPath =
                (last == null || EventOrdering.compare(event, last) > 0) &&
                    payload !is Undo &&
                    payload !is ScorerChanged &&
                    (scorer == null || !EventLog.isScorerOnly(payload) || event.deviceId == scorer) &&
                    payload !is MatchCreated &&
                    !(payload is Correction && payload.kind == CorrectionKind.INITIAL_SERVER) &&
                    event.id !in ids &&
                    event.id !in undoTargets &&
                    !(payload is PointWon && event.parentId != null && event.parentId in pointParents)
            if (!fastPath) return Replay.run(state.format, state.lineup, state.events + fresh, null, genesis, state.initialScorer)
            val ok = core.step(event) { it in applied }
            if (ok && payload is PointWon) applied += event.id
            if (ok && Replay.isUndoTargetType(payload)) undoTarget = event.id
            ids += event.id
            if (payload is PointWon) event.parentId?.let { pointParents += it }
            last = event
        }
        return EngineState(
            format = state.format,
            lineup = state.lineup,
            events = state.events + fresh,
            state = core.export(scorer, state.state.rejectedByRole),
            undoTarget = undoTarget,
            absorbedEvents = state.absorbedEvents,
            conflicts = state.conflicts,
            resolvedConflicts = state.resolvedConflicts,
            rejectedEvents = state.rejectedEvents,
            genesis = genesis,
            core = core,
            appliedPoints = applied,
            initialScorer = state.initialScorer,
        )
    }

    /**
     * Alle wirksam angewendeten Punkte mit Zustand davor und danach (Statistik-Basis, 02 §11.1) über ein
     * vollständiges Log mit Genesis – Zähler wie bei [replay] (Rollenregel 02 §7.5).
     */
    fun pointHistory(log: Collection<ScoreEvent>): List<PointRecord> {
        val ordered = EventLog.distinctOrdered(log)
        val genesis = requireNotNull(ordered.firstOrNull { it.payload is MatchCreated }) { "Log ohne MatchCreated" }
        val created = genesis.payload as MatchCreated
        return pointHistory(created.config, created.lineup, ordered.filter { it != genesis }, created.scorerDeviceId ?: genesis.deviceId)
    }

    /**
     * Wie [pointHistory] über ein Log ohne Genesis; der Startzähler [scorer] ist Pflicht (`null` nur bewusst für
     * Alt-Logs ohne Rollenprüfung, siehe 02 §7.5 Punkt 1).
     */
    fun pointHistory(
        format: MatchFormat,
        lineup: Lineup,
        events: Collection<ScoreEvent>,
        scorer: DeviceId?,
    ): List<PointRecord> {
        val records = mutableListOf<PointRecord>()
        Replay.run(format, lineup, events, onPoint = { event, before, after ->
            records += PointRecord(event, event.payload as PointWon, before, after)
        }, scorer = scorer)
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
        genesis: ScoreEvent? = null,
        scorer: DeviceId? = null,
    ): EngineState {
        val formatErrors = MatchFormatValidator.validate(format).errors
        require(formatErrors.isEmpty()) { "Ungültiges MatchFormat: ${formatErrors.joinToString { it.message }}" }
        val lineupErrors = LineupValidator.validate(format, lineup)
        require(lineupErrors.isEmpty()) { "Ungültiges Lineup: ${lineupErrors.joinToString()}" }

        val raw = EventLog.distinctOrdered(events)
        val log = EventLog.analyze(raw, scorer)
        val excluded = log.undone + log.rejected + log.roleRejected
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
        val appliedPoints = HashSet<EventId>()
        val rejectedByRole = mutableListOf<EventId>()
        var currentScorer = scorer
        for (event in log.ordered) {
            val payload = event.payload
            when {
                event.id in log.rejected -> {
                    core.markIgnored(event.id)
                }

                event.id in log.roleRejected -> {
                    // Rollenregel (02 §7.5): Eingabe eines Geräts, das an dieser Position nicht zählt
                    core.markIgnored(event.id)
                    rejectedByRole += event.id
                }

                payload is ScorerChanged -> {
                    core.effects = setOf(Effect.SCORER_CHANGED)
                    currentScorer = payload.scorerDeviceId
                }

                event.id in log.undone -> {
                    // Unwirksame Events verändern weder Zustand noch Effekte.
                }

                payload is Undo -> {
                    if (event.id in log.invalidUndos) core.markIgnored(event.id) else core.effects = setOf(Effect.UNDONE)
                }

                event.id in log.absorbed -> {
                    core.effects = emptySet()
                }

                else -> {
                    val before = if (onPoint != null && payload is PointWon) core.export(currentScorer, rejectedByRole.toList()) else null
                    val applied = core.step(event) { it in appliedPoints }
                    if (applied && payload is PointWon) appliedPoints += event.id
                    if (applied && isUndoTargetType(payload)) undoTarget = event.id
                    if (applied && before != null) onPoint?.invoke(event, before, core.export(currentScorer, rejectedByRole.toList()))
                }
            }
        }
        return EngineState(
            format = format,
            lineup = lineup,
            events = raw,
            state = core.export(log.scorer, rejectedByRole),
            undoTarget = undoTarget,
            absorbedEvents = log.ordered.filter { it.id in log.absorbed }.map { it.id },
            conflicts = log.activeConflicts,
            resolvedConflicts = log.resolvedConflicts,
            rejectedEvents = log.ordered.filter { it.id in log.rejected }.map { it.id },
            genesis = genesis,
            core = core,
            appliedPoints = appliedPoints,
            initialScorer = scorer,
        )
    }
}
