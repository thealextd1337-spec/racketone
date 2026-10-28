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
import eu.advantly.core.model.Sport
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.scoring.ContinueDecision
import eu.advantly.core.scoring.CorrectedSet
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.EngineState
import eu.advantly.core.scoring.EventLog
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.GameServer
import eu.advantly.core.scoring.GameServersCorrected
import eu.advantly.core.scoring.LineupValidator
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchEnded
import eu.advantly.core.scoring.MatchFormatValidator
import eu.advantly.core.scoring.MatchResult
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.Paused
import eu.advantly.core.scoring.PointDetailRules
import eu.advantly.core.scoring.PointDetailSet
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.ResultCorrected
import eu.advantly.core.scoring.ResultReason
import eu.advantly.core.scoring.Resumed
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.ServerChosen
import eu.advantly.core.scoring.SidePair
import eu.advantly.core.scoring.Undo
import eu.advantly.match.session.DeviceStatus
import eu.advantly.match.session.ServerChoiceContext
import eu.advantly.match.session.canCorrectServerRetroactively
import eu.advantly.match.session.deriveServerChoiceContext
import eu.advantly.sync.protocol.AppBuild
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Engine-gestützte In-Memory-Implementierung aller drei Ports (W1-C). Jedes Match ist ein append-only Event-Log
 * (`MatchCreated` + Events) über der echten [ScoringEngine]; Zeit und IDs kommen aus [clock] und [ids].
 *
 * Für Tests und Previews (die Apps nutzen `SessionMatchStore`): keine Persistenz (Prozesstod verliert alles), kein
 * Sync, ein Gerät – es zählt immer selbst ([LiveRole.SCORER]), auch wenn „Meine Uhr“ gewählt wird. Mehrere Matches
 * können parallel offen sein (F22); ein Uhr-Match gibt es hier nicht.
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

    /** Offene Matches in Startreihenfolge (laufende und beendete, noch nicht geschlossene). */
    private val openIds = LinkedHashSet<MatchId>()

    private val activeFlow = MutableStateFlow<LiveMatchSnapshot?>(null)
    private val openFlow = MutableStateFlow<List<LiveMatchSnapshot>>(emptyList())
    private val historyFlow = MutableStateFlow<List<MatchSummary>>(emptyList())
    private val selfFlow = MutableStateFlow(Player(PlayerId(ids.newId().value), selfName))
    private val guestsFlow = MutableStateFlow<List<Player>>(emptyList())
    private val favoritesFlow = MutableStateFlow<List<FavoriteLineup>>(emptyList())
    private val lastSetupFlow = MutableStateFlow<MatchSetup?>(null)

    override val active: StateFlow<LiveMatchSnapshot?> = activeFlow.asStateFlow()
    override val openMatches: StateFlow<List<LiveMatchSnapshot>> = openFlow.asStateFlow()
    override val matches: StateFlow<List<MatchSummary>> = historyFlow.asStateFlow()
    override val self: StateFlow<Player> = selfFlow.asStateFlow()
    private val archivedFlow = MutableStateFlow<Set<PlayerId>>(emptySet())
    override val archivedPlayerIds: StateFlow<Set<PlayerId>> = archivedFlow.asStateFlow()

    override val guests: StateFlow<List<Player>> = guestsFlow.asStateFlow()
    override val favorites: StateFlow<List<FavoriteLineup>> = favoritesFlow.asStateFlow()
    override val lastSetup: StateFlow<MatchSetup?> = lastSetupFlow.asStateFlow()

    private val watchFlow = MutableStateFlow(watchAvailable)
    override val watchAvailable: StateFlow<Boolean> = watchFlow.asStateFlow()
    private val deviceStatusFlow = MutableStateFlow<List<DeviceStatus>>(emptyList())
    override val deviceStatuses = deviceStatusFlow.asStateFlow()
    override var localAppBuild: AppBuild? = null

    fun setDeviceStatuses(devices: List<DeviceStatus>) {
        deviceStatusFlow.value = devices
    }

    private val watchStatusFlow = MutableStateFlow(if (watchAvailable) WatchStatus.AVAILABLE else WatchStatus.UNKNOWN)
    override val watchStatus: StateFlow<WatchStatus> = watchStatusFlow.asStateFlow()

    private val telemetryFlow = MutableStateFlow(TelemetryVisibility.DEFAULT)
    override val telemetryVisibility: StateFlow<TelemetryVisibility> = telemetryFlow.asStateFlow()

    override fun setTelemetryVisibility(visibility: TelemetryVisibility) {
        telemetryFlow.value = visibility
    }

    /** Ein Gerät: nie ein Versionsunterschied. */
    override val updateRequired: StateFlow<UpdateRequired?> = MutableStateFlow<UpdateRequired?>(null).asStateFlow()

    /** Anzahl der Neuabfragen ([refreshWatchStatus]) – für Tests. */
    var watchRefreshes: Int = 0
        private set

    /** Simuliert das Verbinden/Trennen einer Uhr (Previews, Tests). */
    fun setWatchAvailable(available: Boolean) = setWatchStatus(if (available) WatchStatus.AVAILABLE else WatchStatus.UNKNOWN)

    /** Simuliert den Stand der Uhr (Previews, Tests). */
    fun setWatchStatus(status: WatchStatus) {
        watchStatusFlow.value = status
        watchFlow.value = status == WatchStatus.AVAILABLE
    }

    override fun refreshWatchStatus() {
        watchRefreshes++
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

        val id = MatchId.of(ids.newId())
        val meta = setup.meta.copy(watchWorkout = options.watchWorkout)
        val genesis =
            ScoreEvent(
                id = EventId.of(ids.newId()),
                matchId = id,
                deviceId = deviceId,
                deviceSeq = 1,
                lamport = 1,
                wallClockMs = clock.nowMs(),
                parentId = null,
                payload = MatchCreated(setup.format, setup.lineup, meta),
            )
        val entry = Entry(id, setup.copy(meta = meta), genesis, ScoringEngine.replay(listOf(genesis)))
        entries[id] = entry
        openIds += id
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

    override fun startNextSet(kind: NextSetKind): Boolean =
        command(clearRedo = true) { e ->
            if (e.engine.state.status != MatchStatus.AWAITING_DECISION) return@command false
            append(e, ScoringEngine.nextSetDecision(e.setup.format, kind) ?: return@command false)
        }

    override fun removeLastPointOf(side: Side): Boolean =
        command(clearRedo = false) { e ->
            val target = ScoringEngine.lastEffectivePointOf(e.engine, side) ?: return@command false
            val undo = appendEvent(e, Undo(target)) ?: return@command false
            e.redo.addLast(undo.id)
            true
        }

    override fun chooseServer(player: PlayerId): Boolean = active.value?.serverChoiceContext?.let { chooseServer(player, it) } ?: false

    override fun chooseServer(
        player: PlayerId,
        expected: ServerChoiceContext,
    ): Boolean =
        command(clearRedo = false) { e ->
            val context = deriveServerChoiceContext(e.id, e.engine.state) ?: return@command false
            if (!context.accepts(expected, player)) return@command false
            val side = e.setup.lineup.sideOf(player) ?: return@command false
            context.chosen == player || append(e, ServerChosen(context.setIndex, side, player))
        }

    override fun setServeInfo(
        pointId: EventId,
        value: ServeOutcome?,
    ): Boolean =
        command(clearRedo = false) { e ->
            if (!serveInfoEnabled(e)) return@command false
            val point = e.engine.point(pointId) ?: return@command false
            if (value == ServeOutcome.DOUBLE_FAULT && point.side == point.servingSide) return@command false
            val detail = PointDetailRules.withServe(point.detail, value)
            detail == point.detail || append(e, PointDetailSet(pointId, detail))
        }

    private fun serveInfoEnabled(e: Entry): Boolean = e.setup.format.sport.isTennisLike && e.setup.meta.serveStats

    /** Ein Gerät: nie eine Uhr, die übernommen werden müsste. */
    override fun watchTakeOverNeeded(): LiveMatchSnapshot? = null

    override fun select(matchId: MatchId): Boolean {
        if (matchId !in openIds) return false
        activeId = matchId
        publish()
        return true
    }

    override fun endMatch(
        reason: EndReason,
        side: Side?,
    ): Boolean =
        command(clearRedo = true) { e ->
            if (e.engine.state.status == MatchStatus.FINISHED) return@command false
            val sideless = reason == EndReason.ABANDONED || reason == EndReason.STOPPED
            if (sideless != (side == null)) return@command false
            append(e, MatchEnded(reason, side))
        }

    /** Wie die Session: im Satzende-Dialog regulär (`ContinueDecision(false)`), sonst `MatchEnded(STOPPED)`. */
    override fun stopMatch(): Boolean =
        command(clearRedo = true) { e ->
            when (e.engine.state.status) {
                MatchStatus.FINISHED -> false
                MatchStatus.AWAITING_DECISION -> append(e, ContinueDecision(anotherSet = false))
                else -> append(e, MatchEnded(EndReason.STOPPED))
            }
        }

    override fun abandonMatch(): Boolean =
        command(clearRedo = true) { e ->
            val ok = e.engine.state.status != MatchStatus.FINISHED && append(e, MatchEnded(EndReason.ABANDONED))
            // „Wie letztes Mal“ zeigt kein abgebrochenes Match (F21)
            if (ok) lastSetupFlow.value = lastRatedSetup()
            ok
        }

    private fun lastRatedSetup(): MatchSetup? =
        entries.values
            .filter {
                it.engine.state.result
                    ?.reason != ResultReason.ABANDONED
            }.maxByOrNull { it.genesis.wallClockMs }
            ?.setup
            ?.copy(previousMatchId = null)

    override fun correctServer(
        player: PlayerId,
        retroactive: Boolean,
    ): Boolean =
        command(clearRedo = true) { e ->
            if (e.setup.lineup.sideOf(player) == null) return@command false
            if (retroactive && !canCorrectServerRetroactively(e.engine)) return@command false
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
        openIds -= previous.id // Folge-Match ersetzt das beendete Vor-Match in der Liste (wie die Session)
        publish()
        return id
    }

    /** Ein Gerät: es zählt immer selbst, Übernehmen ist nie nötig. */
    override fun takeOverScoring(): Boolean = false

    /** Ein Gerät: keine Rollenkonflikte, nichts verworfen. */
    override fun rejectedInputs(): List<RejectedInput> = emptyList()

    override fun dismiss(): Boolean {
        val entry = current() ?: return false
        if (entry.engine.state.status != MatchStatus.FINISHED) return false
        close(entry.id)
        publish()
        return true
    }

    /** Schließt [id]; war es aktiv, rückt das jüngste offene Match nach (laufende vor beendeten, wie die Session). */
    private fun close(id: MatchId) {
        openIds -= id
        if (activeId != id) return
        activeId =
            openIds
                .mapNotNull(entries::get)
                .maxWithOrNull(
                    compareBy<Entry> { it.engine.state.status != MatchStatus.FINISHED }.thenBy { it.genesis.wallClockMs },
                )?.id
    }

    // --- MatchHistoryPort ---

    override fun record(matchId: MatchId): MatchRecord? {
        val entry = entries[matchId] ?: return null
        return MatchRecord(summary(entry), entry.engine.state, listOf(entry.genesis) + entry.engine.events)
    }

    override fun canCorrectResult(matchId: MatchId): Boolean = entries[matchId]?.engine?.state?.status == MatchStatus.FINISHED

    override fun correctResult(
        matchId: MatchId,
        sets: List<CorrectedSet>,
    ): Boolean = correction(matchId, ResultCorrected(sets))

    override fun correctGameServers(
        matchId: MatchId,
        servers: List<GameServer>,
    ): Boolean = correction(matchId, GameServersCorrected(servers))

    private fun correction(
        matchId: MatchId,
        payload: EventPayload,
    ): Boolean {
        val entry = entries[matchId] ?: return false
        if (appendEvent(entry, payload) == null) return false
        publish()
        return true
    }

    override fun delete(matchId: MatchId): Boolean {
        val entry = entries[matchId] ?: return false
        if (entry.engine.state.status != MatchStatus.FINISHED) return false
        // Offenes (beendetes) Match zuerst schließen, dann löschen (Vertrag MatchHistoryPort.delete)
        close(matchId)
        entries.remove(matchId)
        publish()
        return true
    }

    // --- PlayersPort ---

    override fun recentPlayers(sport: Sport): List<Player> =
        entries.values
            .sortedByDescending { it.genesis.wallClockMs }
            .sortedBy { it.setup.format.sport != sport }
            .filter {
                it.engine.state.result
                    ?.reason != ResultReason.ABANDONED
            }.filter { self.value.id in it.setup.lineup.a + it.setup.lineup.b }
            .flatMap { it.setup.players }
            .filterNot { it.id == self.value.id || it.id in archivedFlow.value }
            .distinctBy { it.id }

    override fun addGuest(name: String): Player {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Gastname darf nicht leer sein" }
        val player = Player(PlayerId(ids.newId().value), trimmed)
        guestsFlow.value = listOf(player) + guestsFlow.value
        return player
    }

    private val friendIds = mutableMapOf<String, PlayerId>()

    override fun addFriend(
        profileId: String,
        alias: String,
    ): Player? =
        Player(friendIds.getOrPut(profileId) { PlayerId(ids.newId().value) }, alias.trim()).takeIf {
            profileId.isNotBlank() && it.displayName.isNotEmpty()
        }

    override fun friendProfile(id: PlayerId): String? = friendIds.entries.firstOrNull { it.value == id }?.key

    override fun saveFavorite(setup: MatchSetup): FavoriteLineup {
        val favorite = FavoriteLineup(ids.newId().value, setup.copy(previousMatchId = null))
        favoritesFlow.value = listOf(favorite) + favoritesFlow.value
        return favorite
    }

    override fun removeFavorite(id: String) {
        favoritesFlow.value = favoritesFlow.value.filterNot { it.id == id }
    }

    override fun canRename(id: PlayerId): Boolean = guestsFlow.value.any { it.id == id }

    override fun deleteGuest(id: PlayerId): Boolean {
        if (!canRename(id)) return false
        archivedFlow.value += id
        guestsFlow.value = guestsFlow.value.filterNot { it.id == id }
        return true
    }

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
        guestsFlow.value = (used + guestsFlow.value.filterNot { g -> used.any { it.id == g.id } }).filterNot { it.id in archivedFlow.value }
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
            eventCount = entry.engine.events.size + 1,
            updatedAtMs =
                entry.engine.events
                    .lastOrNull()
                    ?.wallClockMs ?: entry.genesis.wallClockMs,
        )
    }

    private fun publish() {
        activeFlow.value = current()?.let(::snapshot)
        openFlow.value = openIds.mapNotNull(entries::get).map(::snapshot)
        historyFlow.value =
            entries.values
                .filter { it.engine.state.status == MatchStatus.FINISHED }
                .sortedByDescending { it.finishedAtMs }
                .map(::summary)
    }

    private fun snapshot(e: Entry): LiveMatchSnapshot =
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
            lastPoint =
                e.engine.lastPoint?.let {
                    LastPointInfo(it.id, it.side, it.servingSide, it.server, it.detail?.serve, it.side != it.servingSide)
                },
            serveInfoEnabled = serveInfoEnabled(e),
            canRemovePoint = SidePair.of { ScoringEngine.lastEffectivePointOf(e.engine, it) != null },
            canCorrectServerRetroactively = canCorrectServerRetroactively(e.engine),
            nextSetOptions =
                if (e.engine.state.status == MatchStatus.AWAITING_DECISION) {
                    ScoringEngine.nextSetOptions(e.setup.format)
                } else {
                    emptyList()
                },
        )
}
