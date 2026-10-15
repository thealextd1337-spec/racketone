package eu.advantly.presentation.match

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.MatchSettings
import eu.advantly.core.model.MatchTiebreakFormat
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.ContinueDecision
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.EngineState
import eu.advantly.core.scoring.EventLog
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.LineupValidator
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchEnded
import eu.advantly.core.scoring.MatchFormatValidator
import eu.advantly.core.scoring.MatchResult
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.Paused
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.ResultReason
import eu.advantly.core.scoring.Resumed
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.Undo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Engine-gestützte In-Memory-Implementierung aller drei Ports (W1-C). Jedes Match ist ein append-only Event-Log
 * (`MatchCreated` + Events) über der echten [ScoringEngine]; Zeit und IDs kommen aus [clock] und [ids].
 *
 * Für Tests und Previews (die Apps nutzen `SessionMatchStore`): keine Persistenz (Prozesstod verliert alles), kein
 * Sync, ein Gerät – es zählt immer selbst ([LiveRole.SCORER]), auch wenn „Meine Uhr“ gewählt wird.
 * Nur vom UI-Thread benutzen.
 *
 * @param selfName Anzeigename des Besitzers (leer = UI zeigt „Du“)
 * @param watchAvailable simulierte verbundene Uhr (nur für den Assistenten „Wer zählt?“)
 */
class InMemoryMatchStore(
    private val clock: WallClock = WallClock.System,
    private val ids: IdFactory = IdFactory.uuid7(clock),
    private val deviceId: DeviceId = DeviceId("phone"),
    selfName: String = "",
    watchAvailable: Boolean = false,
) : MatchStore {
    private class Entry(
        val id: MatchId,
        var setup: MatchSetup,
        val genesis: ScoreEvent,
        var engine: EngineState,
    ) {
        /** IDs der Undo-Events, die per Redo zurückgenommen werden können (oben = zuletzt). */
        val redo = ArrayDeque<EventId>()
        var seq = 1L
        var lamport = 1L
        var revision = 0L
        var finishedAtMs: Long? = null
        var followUp: MatchId? = null
    }

    private val entries = LinkedHashMap<MatchId, Entry>()
    private var activeId: MatchId? = null

    private val activeFlow = MutableStateFlow<LiveMatchSnapshot?>(null)
    private val historyFlow = MutableStateFlow<List<MatchSummary>>(emptyList())
    private val selfFlow = MutableStateFlow(Player(PlayerId(ids.newId().value), selfName))
    private val guestsFlow = MutableStateFlow<List<Player>>(emptyList())
    private val favoritesFlow = MutableStateFlow<List<FavoriteLineup>>(emptyList())
    private val lastSetupFlow = MutableStateFlow<MatchSetup?>(null)

    override val active: StateFlow<LiveMatchSnapshot?> = activeFlow.asStateFlow()
    override val matches: StateFlow<List<MatchSummary>> = historyFlow.asStateFlow()
    override val self: StateFlow<Player> = selfFlow.asStateFlow()
    override val guests: StateFlow<List<Player>> = guestsFlow.asStateFlow()
    override val favorites: StateFlow<List<FavoriteLineup>> = favoritesFlow.asStateFlow()
    override val lastSetup: StateFlow<MatchSetup?> = lastSetupFlow.asStateFlow()

    private val watchFlow = MutableStateFlow(watchAvailable)
    override val watchAvailable: StateFlow<Boolean> = watchFlow.asStateFlow()

    /** Simuliert das Verbinden/Trennen einer Uhr (Previews, Tests). */
    fun setWatchAvailable(available: Boolean) {
        watchFlow.value = available
    }

    // --- LiveMatchPort ---

    override fun start(
        setup: MatchSetup,
        options: StartOptions,
    ): MatchId {
        val formatErrors = MatchFormatValidator.validate(setup.format).errors
        require(formatErrors.isEmpty()) { "Ungültiges MatchFormat: ${formatErrors.joinToString { it.message }}" }
        val lineupErrors = LineupValidator.validate(setup.format, setup.lineup)
        require(lineupErrors.isEmpty()) { "Ungültiges Lineup: ${lineupErrors.joinToString()}" }
        val missing = (setup.lineup.a + setup.lineup.b).filter { setup.player(it) == null }
        require(missing.isEmpty()) { "Spieler ohne Namen: $missing" }

        current()?.let { previous ->
            if (previous.engine.state.status != MatchStatus.FINISHED) append(previous, MatchEnded(EndReason.ABANDONED))
        }
        val id = MatchId.of(ids.newId())
        val genesis =
            ScoreEvent(
                id = EventId.of(ids.newId()),
                matchId = id,
                deviceId = deviceId,
                deviceSeq = 1,
                lamport = 1,
                wallClockMs = clock.nowMs(),
                parentId = null,
                payload = MatchCreated(setup.format, setup.lineup, setup.meta),
            )
        val entry = Entry(id, setup, genesis, ScoringEngine.replay(listOf(genesis)))
        entries[id] = entry
        activeId = id
        lastSetupFlow.value = setup.copy(previousMatchId = null)
        rememberGuests(setup)
        publish()
        return id
    }

    override fun recordPoint(side: Side): Boolean =
        command(clearRedo = true) { e ->
            e.engine.state.status
                .let { it == MatchStatus.IN_PROGRESS || it == MatchStatus.PAUSED } && append(e, PointWon(side))
        }

    override fun undo(): Boolean =
        command(clearRedo = false) { e ->
            val target = e.engine.undoTarget ?: return@command false
            val undo = appendEvent(e, Undo(target)) ?: return@command false
            e.redo.addLast(undo.id)
            true
        }

    override fun redo(): Boolean =
        command(clearRedo = false) { e ->
            val undoId = e.redo.removeLastOrNull() ?: return@command false
            append(e, Undo(undoId))
        }

    override fun continueDecision(
        anotherSet: Boolean,
        matchTiebreak: Boolean,
    ): Boolean =
        command(clearRedo = true) { e ->
            if (e.engine.state.status != MatchStatus.AWAITING_DECISION) return@command false
            if (matchTiebreak && (!anotherSet || e.setup.format.matchTiebreak == null)) return@command false
            append(e, ContinueDecision(anotherSet, if (matchTiebreak) NextSetKind.MATCH_TIEBREAK else NextSetKind.REGULAR))
        }

    override fun endMatch(
        reason: EndReason,
        side: Side?,
    ): Boolean =
        command(clearRedo = true) { e ->
            if (e.engine.state.status == MatchStatus.FINISHED) return@command false
            if ((reason == EndReason.ABANDONED) != (side == null)) return@command false
            append(e, MatchEnded(reason, side))
        }

    override fun correctServer(
        player: PlayerId,
        retroactive: Boolean,
    ): Boolean =
        command(clearRedo = true) { e ->
            if (e.setup.lineup.sideOf(player) == null) return@command false
            append(e, if (retroactive) Correction.initialServer(player) else Correction.server(player))
        }

    override fun pause(): Boolean =
        command(clearRedo = false) { e ->
            e.engine.state.status == MatchStatus.IN_PROGRESS && append(e, Paused)
        }

    override fun resume(): Boolean =
        command(clearRedo = false) { e ->
            e.engine.state.status == MatchStatus.PAUSED && append(e, Resumed)
        }

    override fun createFollowUp(): MatchId? {
        val previous = current() ?: return null
        val state = previous.engine.state
        val format = previous.setup.format
        if (state.status != MatchStatus.FINISHED || state.result?.reason != ResultReason.COMPLETED) return null
        if (format.match.mode != MatchMode.BEST_OF) return null
        val opener = state.continuation ?: return null
        val followFormat =
            format.copy(
                presetId = null,
                match = MatchSettings.OPEN,
                decidingSet = null,
                matchTiebreak =
                    if (format.sport.isTennisLike) {
                        format.matchTiebreak ?: (format.decidingSet as? MatchTiebreakFormat) ?: MatchTiebreakFormat.MTB10
                    } else {
                        null
                    },
            )
        val setup =
            previous.setup.copy(
                format = followFormat,
                lineup = previous.setup.lineup.copy(firstServer = opener.player),
                previousMatchId = previous.id,
            )
        val id = start(setup, StartOptions())
        previous.followUp = id
        publish()
        return id
    }

    /** Ein Gerät: es zählt immer selbst, Übernehmen ist nie nötig. */
    override fun takeOverScoring(): Boolean = false

    override fun dismiss(): Boolean {
        val entry = current() ?: return false
        if (entry.engine.state.status != MatchStatus.FINISHED) return false
        activeId = null
        publish()
        return true
    }

    // --- MatchHistoryPort ---

    override fun record(matchId: MatchId): MatchRecord? {
        val entry = entries[matchId] ?: return null
        return MatchRecord(summary(entry), entry.engine.state, listOf(entry.genesis) + entry.engine.events)
    }

    override fun delete(matchId: MatchId): Boolean {
        val entry = entries[matchId] ?: return false
        if (entry.engine.state.status != MatchStatus.FINISHED) return false
        // Aktives (beendetes) Match zuerst schließen, dann löschen (Vertrag MatchHistoryPort.delete)
        if (activeId == matchId) dismiss()
        entries.remove(matchId)
        publish()
        return true
    }

    // --- PlayersPort ---

    override fun addGuest(name: String): Player {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Gastname darf nicht leer sein" }
        val player = Player(PlayerId(ids.newId().value), trimmed)
        guestsFlow.value = listOf(player) + guestsFlow.value
        return player
    }

    override fun saveFavorite(setup: MatchSetup): FavoriteLineup {
        val favorite = FavoriteLineup(ids.newId().value, setup.copy(previousMatchId = null))
        favoritesFlow.value = listOf(favorite) + favoritesFlow.value
        return favorite
    }

    override fun removeFavorite(id: String) {
        favoritesFlow.value = favoritesFlow.value.filterNot { it.id == id }
    }

    override fun canRename(id: PlayerId): Boolean = guestsFlow.value.any { it.id == id }

    override fun renameGuest(
        id: PlayerId,
        name: String,
    ): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !canRename(id)) return false
        val renamed = Player(id, trimmed)
        guestsFlow.value = guestsFlow.value.map { if (it.id == id) renamed else it }

        fun MatchSetup.renamed() = copy(players = players.map { if (it.id == id) renamed else it })
        entries.values.forEach { e ->
            e.setup = e.setup.renamed()
            e.revision++
        }
        favoritesFlow.value = favoritesFlow.value.map { it.copy(setup = it.setup.renamed()) }
        lastSetupFlow.value = lastSetupFlow.value?.renamed()
        publish()
        return true
    }

    // --- intern ---

    private fun current(): Entry? = activeId?.let(entries::get)

    private inline fun command(
        clearRedo: Boolean,
        block: (Entry) -> Boolean,
    ): Boolean {
        val entry = current() ?: return false
        val ok = block(entry)
        if (ok) {
            if (clearRedo) entry.redo.clear()
            publish()
        }
        return ok
    }

    private fun append(
        entry: Entry,
        payload: EventPayload,
    ): Boolean = appendEvent(entry, payload) != null

    /**
     * Hängt [payload] an, wenn die Engine es anwendet. Ein Event, das die Engine überspringen würde
     * (`ignoredEvents`), wird verworfen statt geloggt (02 §7.3 Schritt 4).
     */
    private fun appendEvent(
        entry: Entry,
        payload: EventPayload,
    ): ScoreEvent? {
        val event =
            ScoreEvent(
                id = EventId.of(ids.newId()),
                matchId = entry.id,
                deviceId = deviceId,
                deviceSeq = entry.seq + 1,
                lamport = entry.lamport + 1,
                wallClockMs = clock.nowMs(),
                parentId =
                    entry.engine.events
                        .lastOrNull()
                        ?.id ?: entry.genesis.id,
                payload = payload,
            )
        val next = ScoringEngine.apply(entry.engine, event)
        if (event.id in next.state.ignoredEvents) return null
        entry.engine = next
        entry.seq++
        entry.lamport++
        entry.revision++
        entry.finishedAtMs = if (next.state.status == MatchStatus.FINISHED) entry.finishedAtMs ?: event.wallClockMs else null
        return event
    }

    private fun rememberGuests(setup: MatchSetup) {
        val selfId = selfFlow.value.id
        val used = setup.players.filter { it.id != selfId }
        guestsFlow.value = used + guestsFlow.value.filterNot { g -> used.any { it.id == g.id } }
    }

    /** Wirksam angewendete Ballwechsel (auch ohne Punkt, z. B. Pickleball Side-Out). */
    private fun pointsPlayed(entry: Entry): Int {
        val log = EventLog.analyze(entry.engine.events)
        val skipped = log.undone + log.rejected + log.absorbed + entry.engine.state.ignoredEvents
        return log.ordered.count { it.payload is PointWon && it.id !in skipped }
    }

    private fun summary(entry: Entry): MatchSummary {
        val state = entry.engine.state
        return MatchSummary(
            matchId = entry.id,
            setup = entry.setup,
            result = state.result ?: MatchResult(null, ResultReason.ABANDONED),
            sets = state.sets,
            setsWon = state.setsWon,
            startedAtMs = entry.genesis.wallClockMs,
            finishedAtMs =
                entry.finishedAtMs ?: entry.engine.events
                    .lastOrNull()
                    ?.wallClockMs ?: entry.genesis.wallClockMs,
            interruptedSet = state.current.takeIf { state.status == MatchStatus.FINISHED },
        )
    }

    private fun publish() {
        activeFlow.value =
            current()?.let { e ->
                LiveMatchSnapshot(
                    matchId = e.id,
                    setup = e.setup,
                    state = e.engine.state,
                    canUndo = e.engine.undoTarget != null,
                    canRedo = e.redo.isNotEmpty(),
                    startedAtMs = e.genesis.wallClockMs,
                    finishedAtMs = e.finishedAtMs,
                    followUpMatchId = e.followUp,
                    revision = e.revision,
                    pointsPlayed = pointsPlayed(e),
                    role = LiveRole.SCORER,
                    scorerName = selfFlow.value.displayName,
                    scorerKind = DeviceKind.PHONE,
                    sync = SyncInfo.LOCAL_ONLY,
                    absorbedTaps = e.engine.absorbedEvents.size,
                )
            }
        historyFlow.value =
            entries.values
                .filter { it.engine.state.status == MatchStatus.FINISHED }
                .sortedByDescending { it.finishedAtMs }
                .map(::summary)
    }
}
