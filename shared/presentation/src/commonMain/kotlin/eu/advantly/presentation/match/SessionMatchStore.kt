package eu.advantly.presentation.match

import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.scoring.ContinueDecision
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.CurrentSet
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.GameKind
import eu.advantly.core.scoring.LineupValidator
import eu.advantly.core.scoring.MatchEnded
import eu.advantly.core.scoring.MatchFormatValidator
import eu.advantly.core.scoring.MatchResult
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.Paused
import eu.advantly.core.scoring.PlayerAssigned
import eu.advantly.core.scoring.PointDetailSet
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.ResultReason
import eu.advantly.core.scoring.Resumed
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.ServerChosen
import eu.advantly.core.scoring.SetKind
import eu.advantly.core.scoring.SetRecord
import eu.advantly.core.scoring.SidePair
import eu.advantly.core.scoring.Undo
import eu.advantly.data.LocalData
import eu.advantly.data.PlayerKind
import eu.advantly.data.assignments
import eu.advantly.data.freshPlaceholders
import eu.advantly.data.resolvedLineup
import eu.advantly.data.scoreEvents
import eu.advantly.device.link.CompanionDetector
import eu.advantly.device.link.CompanionState
import eu.advantly.match.session.ActiveMatch
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.ScorerChoice
import eu.advantly.match.session.ServerChoiceContext
import eu.advantly.match.session.SessionListener
import eu.advantly.match.session.SessionState
import eu.advantly.presentation.live.LiveTelemetryStore
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.PeerKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import eu.advantly.data.MatchRecord as StoredMatch

/**
 * Session-Adapter der Phone-UI: erfüllt [LiveMatchPort], [MatchHistoryPort] und [PlayersPort] über einer
 * [MatchSession] (Single-Scorer, Event-Log, Geräte-Sync) und deren [LocalData] (Historie, Spieler, Favoriten).
 *
 * Vertrag der Ports:
 * - **Synchron sichtbar:** Die Session meldet jede Änderung noch im Kommando über ihren Listener; [active] zeigt
 *   den neuen Zustand also schon, wenn das Kommando `true` liefert.
 * - **Abgelehnte Kommandos schreiben nichts:** Vorbedingungen, die die Session nicht selbst prüft (Rolle bei
 *   `endMatch`, Aufgabe/Walkover-Regeln, Aufschläger-Korrektur), prüft der Adapter vorher.
 * - **Redo** ist gerätelokal (Stapel in der Session); Pause/Fortsetzen leeren ihn nicht.
 * - **Rolle:** Zählt ein anderes Gerät (z. B. die Uhr), ist das Match hier [LiveRole.DISPLAY]; Zähl-Kommandos liefern
 *   `false`. „Zählen übernehmen“ erzeugt `ScorerChanged` ([takeOverScoring]).
 *
 * Kosten: Pro Punkt baut der Adapter nur den Live-Snapshot neu. Historie, „Wie letztes Mal“ und Spielernamen lädt er
 * neu, wenn sich Match oder Status ändern oder die Session eine neue `dataRevision` meldet (Löschung, Köpfe/Stand anderer
 * Matches, Spieler, Identität); Ergebnis-Köpfe beendeter Matches sind samt Setup zwischengespeichert; nur Details lesen das Event-Log.
 *
 * Mehrere Matches (F22): [openMatches] spiegelt alle offenen Matches der Session, [active] das fokussierte; [select]
 * wechselt die Live-Ansicht. Nach einem App-Neustart öffnet der Adapter **alle** noch laufenden Matches wieder (erreichbar
 * zum Fortsetzen, Beenden, Abbrechen, Löschen); in den Fokus kommt nur das zuletzt geänderte und nur, wenn es höchstens
 * 12 h alt ist.
 * „Stand vor X s“ misst er monoton ([monotonic]); den letzten Kontakt zum Zähler merkt er sich je Match in den Settings,
 * damit das Alter einen Neustart übersteht. Ohne gemerkten Kontakt ist es unbekannt (nicht 0).
 *
 * Identität: Spieler-IDs, die die Datenbank als Alias des Besitzers kennt (z. B. der Self der Uhr vor der Übernahme),
 * zeigt der Adapter als Besitzer – nie als Gast.
 *
 * Nur auf dem Scheduler-Thread der Session (Main-Thread) benutzen; [close] meldet den Listener ab.
 *
 * @param historyLimit so viele zuletzt angelegte Matches lädt der Verlauf (P1 ohne Paging)
 */
class SessionMatchStore(
    private val session: MatchSession,
    private val clock: WallClock = WallClock.System,
    private val ids: IdFactory = IdFactory.uuid7(clock),
    private val historyLimit: Long = DEFAULT_HISTORY_LIMIT,
    private val monotonic: MonotonicClock = MonotonicClock.System,
    private val companion: CompanionDetector? = null,
    /** Flüchtiger Telemetrie-Eingang, den „Gar nicht“ sofort leert (P2-J); `null` = keiner. */
    private val telemetryStore: LiveTelemetryStore? = LiveTelemetryStore.Phone,
) : MatchStore {
    private val data: LocalData = session.data
    private var aliases: Set<PlayerId> = emptySet()
    private val aliasesFlow = MutableStateFlow<Set<PlayerId>>(emptySet())
    override val ownerAliases: StateFlow<Set<PlayerId>> = aliasesFlow.asStateFlow()

    private val players = HashMap<PlayerId, Player>()
    private val summaries = HashMap<MatchId, Pair<StoredMatch, MatchSummary>>()
    private var summaryPeople: Map<PlayerId, Player> = emptyMap()
    private val records = LinkedHashMap<MatchId, Pair<Long, MatchRecord>>()
    private val finishedAt = HashMap<MatchId, Pair<Int, Long?>>()

    private val selfFlow = MutableStateFlow(ensureSelf())
    private val guestsFlow = MutableStateFlow<List<Player>>(emptyList())
    private val favoritesFlow = MutableStateFlow<List<FavoriteLineup>>(emptyList())
    private val lastSetupFlow = MutableStateFlow<MatchSetup?>(null)
    private val historyFlow = MutableStateFlow<List<MatchSummary>>(emptyList())
    private val activeFlow = MutableStateFlow<LiveMatchSnapshot?>(null)
    private val openFlow = MutableStateFlow<List<LiveMatchSnapshot>>(emptyList())
    private val watchFlow = MutableStateFlow(false)

    override val self: StateFlow<Player> = selfFlow.asStateFlow()
    private val archivedFlow = MutableStateFlow<Set<PlayerId>>(emptySet())
    override val archivedPlayerIds: StateFlow<Set<PlayerId>> = archivedFlow.asStateFlow()

    override val guests: StateFlow<List<Player>> = guestsFlow.asStateFlow()
    override val favorites: StateFlow<List<FavoriteLineup>> = favoritesFlow.asStateFlow()
    override val lastSetup: StateFlow<MatchSetup?> = lastSetupFlow.asStateFlow()
    override val matches: StateFlow<List<MatchSummary>> = historyFlow.asStateFlow()
    override val active: StateFlow<LiveMatchSnapshot?> = activeFlow.asStateFlow()
    override val openMatches: StateFlow<List<LiveMatchSnapshot>> = openFlow.asStateFlow()
    override val watchAvailable: StateFlow<Boolean> = watchFlow.asStateFlow()
    private val watchStatusFlow = MutableStateFlow(WatchStatus.UNKNOWN)
    override val watchStatus: StateFlow<WatchStatus> = watchStatusFlow.asStateFlow()
    private val updateRequiredFlow = MutableStateFlow<UpdateRequired?>(null)
    override val updateRequired: StateFlow<UpdateRequired?> = updateRequiredFlow.asStateFlow()
    private val telemetryFlow = MutableStateFlow(session.state.value.telemetryVisibility)
    override val telemetryVisibility: StateFlow<TelemetryVisibility> = telemetryFlow.asStateFlow()
    private var companionState = CompanionState.UNKNOWN

    // Zustand für Revision und „Stand vor X s“
    private var lastState: SessionState? = null
    private var revision = 0L
    private var observedMatch: MatchId? = null
    private var contactMono: Long? = null
    private var withoutScorerSinceMono: Long? = null
    private var contactPersistedMono: Long? = null
    private var scorerWasConnected = false

    private val listener = SessionListener { onSessionState(it) }

    init {
        // App-Neustart: kürzlich noch laufende Matches wieder öffnen (Zähler zählen ggf. auf der Uhr weiter)
        if (session.state.value.match == null) resumeRecentMatches()
        // addListener liefert sofort den aktuellen Zustand (lädt dabei auch Historie, Spieler und Favoriten)
        session.addListener(listener)
        // Uhr mit installierter App zählt als verfügbar, auch wenn sie gerade nicht verbunden ist (Feldtest W1-I)
        companion?.start { state ->
            companionState = state
            updateWatchStatus(session.state.value)
        }
    }

    /** Meldet den Adapter von der Session ab (die Session selbst läuft prozessweit weiter). */
    fun close() {
        companion?.stop()
        session.removeListener(listener)
    }

    override fun refreshWatchStatus() {
        companion?.refresh()
        session.syncNow()
        updateWatchStatus(session.state.value)
    }

    private fun updateWatchStatus(state: SessionState) {
        val status =
            when {
                connectedWatch(state) != null || companionState == CompanionState.APP_INSTALLED -> WatchStatus.AVAILABLE
                companionState == CompanionState.APP_NOT_INSTALLED -> WatchStatus.APP_NOT_INSTALLED
                companionState == CompanionState.NO_WATCH -> WatchStatus.NO_WATCH
                else -> WatchStatus.UNKNOWN
            }
        watchStatusFlow.value = status
        watchFlow.value = status == WatchStatus.AVAILABLE
    }

    /** Speichert über die Session; sie gleicht mit der eigenen Uhr ab (letzte Änderung gewinnt). */
    override fun setTelemetryVisibility(visibility: TelemetryVisibility) = session.setTelemetryVisibility(visibility)

    /** Workout-Wunsch des Matches (`MatchMeta.watchWorkout`, geht mit dem Match-Kopf an die Uhr), `null` = unbekannt. */
    fun watchWorkout(matchId: MatchId): Boolean? =
        data.matches
            .get(matchId)
            ?.meta
            ?.watchWorkout

    // --- LiveMatchPort ---------------------------------------------------------------------------

    override fun start(
        setup: MatchSetup,
        options: StartOptions,
    ): MatchId {
        val formatErrors = MatchFormatValidator.validate(setup.format).errors
        require(formatErrors.isEmpty()) { "Ungültiges MatchFormat: ${formatErrors.joinToString { it.message }}" }
        val lineupErrors = LineupValidator.validate(setup.format, setup.lineup)
        require(lineupErrors.isEmpty()) { "Ungültiges Lineup: ${lineupErrors.joinToString()}" }
        val missing = (setup.lineup.a + setup.lineup.b).filter { setup.player(it) == null && !data.isPlaceholder(it) }
        require(missing.isEmpty()) { "Spieler ohne Namen: $missing" }
        rememberPlayers(setup.players)

        // Laufende Matches bleiben offen (F22). Wechselt die Uhr zu diesem Match („Meine Uhr“), übernimmt die Session ihr
        // bisheriges laufendes Match als Director – die Uhr zählt nie zwei Matches.
        val scorer =
            when (options.scorer) {
                // Ohne Live-Verbindung: bekannte Uhr bzw. Platzhalter „Uhr, sobald sie sich meldet“ (Session)
                ScorerOption.MY_WATCH -> if (watchFlow.value) ScorerChoice.MyWatch else ScorerChoice.ThisDevice

                ScorerOption.THIS_PHONE -> ScorerChoice.ThisDevice
            }
        // Workout-Wunsch als Metadatum am Match (MatchMeta.watchWorkout) – geht mit Genesis und Match-Kopf an die Uhr
        val assigned = setup.assignedPlayers

        fun person(id: PlayerId) = assigned[id]?.id ?: id
        val lineup =
            setup.lineup.copy(
                a = setup.lineup.a.map(::person),
                b = setup.lineup.b.map(::person),
                firstServer = person(setup.lineup.firstServer),
                receiveRight = setup.lineup.receiveRight?.let { it.copy(a = it.a?.let(::person), b = it.b?.let(::person)) },
            )
        rememberPlayers(assigned.values.toList())
        val fresh = data.freshPlaceholders(lineup) { PlayerId(ids.newId().value) }
        val meta = setup.meta.copy(watchWorkout = options.watchWorkout)
        return session.create(setup.format, fresh, meta, scorer, takeOverWatchMatch = options.takeOverWatchMatch)
    }

    override fun watchTakeOverNeeded(): LiveMatchSnapshot? =
        session.watchMatchNeedingTakeOver()?.let { id -> openFlow.value.firstOrNull { it.matchId == id } }

    override fun recordPoint(side: Side): Boolean = session.recordPoint(side)

    override fun undo(): Boolean = session.undo()

    override fun redo(): Boolean = session.redo()

    override fun continueDecision(
        anotherSet: Boolean,
        matchTiebreak: Boolean,
    ): Boolean = session.continueDecision(anotherSet, matchTiebreak)

    override fun startNextSet(kind: NextSetKind): Boolean = session.startNextSet(kind)

    override fun removeLastPointOf(side: Side): Boolean = session.removeLastPointOf(side)

    override fun chooseServer(player: PlayerId): Boolean = session.chooseServer(player)

    override fun chooseServer(
        player: PlayerId,
        expected: ServerChoiceContext,
    ): Boolean = session.chooseServer(player, expected)

    override fun setServeInfo(
        pointId: EventId,
        value: ServeOutcome?,
    ): Boolean = session.setServeInfo(pointId, value)

    override fun select(matchId: MatchId): Boolean = session.state.value.match(matchId) != null && session.resume(matchId)

    override fun endMatch(
        reason: EndReason,
        side: Side?,
    ): Boolean {
        val m = current() ?: return false
        if (!m.isScorer || m.state.status == MatchStatus.FINISHED) return false
        val sideless = reason == EndReason.ABANDONED || reason == EndReason.STOPPED
        if (sideless != (side == null)) return false
        // Walkover nur ohne wirksamen Ballwechsel (02 §9.8) – sonst würde die Engine das Event überspringen
        if (reason == EndReason.WALKOVER && m.pointsPlayed > 0) return false
        return session.endMatch(reason, side)
    }

    override fun stopMatch(): Boolean = finishAsDirector(EndReason.STOPPED)

    override fun abandonMatch(): Boolean = finishAsDirector(EndReason.ABANDONED)

    /** Beenden/Abbrechen (F21) – zählt ein anderes Gerät, übernimmt dieses Handy vorher (`ScorerChanged`). */
    private fun finishAsDirector(reason: EndReason): Boolean {
        val m = current() ?: return false
        if (m.state.status == MatchStatus.FINISHED) return false
        if (!m.isScorer && !session.takeOverScoring()) return false
        return session.endMatch(reason)
    }

    override fun correctServer(
        player: PlayerId,
        retroactive: Boolean,
    ): Boolean = session.correctServer(player, retroactive)

    override fun pause(): Boolean = session.pause()

    override fun resume(): Boolean = session.resumePlay()

    override fun createFollowUp(): MatchId? {
        val m = current() ?: return null
        val state = m.state
        if (!m.isScorer || state.status != MatchStatus.FINISHED || state.result?.reason != ResultReason.COMPLETED) return null
        if (m.format.match.mode != MatchMode.BEST_OF || state.continuation == null) return null
        return session.createFollowUp()
    }

    override fun dismiss(): Boolean {
        val m = current() ?: return false
        if (m.state.status != MatchStatus.FINISHED) return false
        session.closeMatch()
        return true
    }

    override fun takeOverScoring(): Boolean = session.takeOverScoring()

    override fun rejectedInputs(): List<RejectedInput> {
        val m = current() ?: return emptyList()
        val rejected = m.state.rejectedByRole.toSet()
        if (rejected.isEmpty()) return emptyList()
        return data.events
            .scoreEvents(m.id)
            .filter { it.id in rejected }
            .mapNotNull { event ->
                val (kind, side) =
                    when (val payload = event.payload) {
                        is PointWon -> InputKind.POINT to payload.side
                        is Undo -> InputKind.UNDO to null
                        is ContinueDecision -> InputKind.SET_DECISION to null
                        is Correction, is ServerChosen -> InputKind.CORRECTION to null
                        Paused -> InputKind.PAUSE to null
                        Resumed -> InputKind.RESUME to null
                        is MatchEnded -> InputKind.END to payload.side
                        is PointDetailSet -> InputKind.DETAIL to null
                        else -> return@mapNotNull null
                    }
                val device = if (event.deviceId == session.deviceId) PeerKind.PHONE else session.peerKind(event.deviceId)
                RejectedInput(event.id, (event.wallClockMs - m.createdAtMs).coerceAtLeast(0), device.toDeviceKind(false), kind, side)
            }
    }

    // --- MatchHistoryPort ------------------------------------------------------------------------

    override fun canAssignPlayer(matchId: MatchId): Boolean = session.canAssignPlayer(matchId)

    override fun assignmentFailure(
        matchId: MatchId,
        slot: PlayerId,
        player: PlayerId?,
    ): PlayerAssignmentFailure? = session.assignmentFailure(matchId, slot, player)

    override fun assignPlayer(
        matchId: MatchId,
        slot: PlayerId,
        player: PlayerId?,
    ): Boolean = session.assignPlayer(matchId, slot, player)

    override fun record(matchId: MatchId): MatchRecord? {
        val header = data.matches.get(matchId) ?: return null
        val cached =
            records[matchId]
                ?.takeIf {
                    it.first == header.updatedAtMs &&
                        it.second.events.size
                            .toLong() == data.events.count(matchId.value)
                }?.second
        if (cached != null) {
            // Namen und Identitätszuweisungen können sich ohne neues Score-Event ändern.
            val setup = setupOf(header)
            return if (cached.summary.setup == setup) cached else cached.copy(summary = cached.summary.copy(setup = setup))
        }
        val engine =
            try {
                ScoringEngine.replay(data.events.scoreEvents(matchId))
            } catch (e: IllegalArgumentException) {
                return null
            }
        val genesis = engine.genesis ?: return null
        val summary =
            summaryOf(
                header,
                engine.state,
                genesis.wallClockMs,
                engine.events
                    .lastOrNull {
                        it.payload !is PlayerAssigned
                    }?.wallClockMs,
                engine.events.size + 1,
            )
        val record = MatchRecord(summary, engine.state, listOf(genesis) + engine.events)
        records.remove(matchId)
        records[matchId] = header.updatedAtMs to record
        if (records.size > 8) records.remove(records.keys.first())
        return record
    }

    override fun delete(matchId: MatchId): Boolean {
        val header = data.matches.get(matchId) ?: return false
        if (!header.isFinished) return false
        // Offenes (beendetes) Match zuerst schließen (Vertrag), dann löschen; die Löschmarkierung geht an die Uhr
        session.closeMatch(matchId)
        session.deleteMatch(matchId)
        data.settings.remove(contactKey(matchId))
        summaries.remove(matchId)
        records.remove(matchId)
        finishedAt.remove(matchId)
        refreshStored()
        return true
    }

    // --- PlayersPort ------------------------------------------------------------------------------

    /** Je 100 sportbezogene und allgemeine Matches; Zuweisungen lösen die ursprünglichen Platz-IDs auf. */
    override fun recentPlayers(sport: Sport): List<Player> {
        val mine = aliases + self.value.id
        val archived = data.players.archivedIds()
        return (data.matches.recentLineups(sport = sport, limit = 100) + data.matches.recentLineups(limit = 100))
            .filter { lineup -> (lineup.a + lineup.b).any { it in mine } }
            .flatMap { playersOf(it) }
            .filterNot { it.id in mine || it.isPlaceholder || it.id in archived }
            .filter { it.displayName.isNotBlank() }
            .distinctBy { it.id }
    }

    override fun addGuest(name: String): Player {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Gastname darf nicht leer sein" }
        val player = data.players.addGuest(PlayerId(ids.newId().value), trimmed, clock.nowMs()).toPlayer()
        players[player.id] = player
        guestsFlow.value = loadGuests()
        return player
    }

    override fun saveFavorite(setup: MatchSetup): FavoriteLineup {
        rememberPlayers(setup.players)
        val stored = StoredFavorite(ids.newId().value, setup.format, setup.lineup, setup.meta)
        writeFavorites(listOf(stored) + readFavorites())
        favoritesFlow.value = loadFavorites()
        return favoritesFlow.value.first { it.id == stored.id }
    }

    override fun removeFavorite(id: String) {
        val all = readFavorites()
        if (all.none { it.id == id }) return
        writeFavorites(all.filterNot { it.id == id })
        favoritesFlow.value = loadFavorites()
    }

    /**
     * P1: alle lokalen Dummy-/Gastspieler stammen vom Besitzer (dieses Handy oder seine gekoppelte Uhr) – er ist also
     * der Anleger. Ab P2 (Accounts, geteilte Matches) muss hier der Anleger geprüft werden.
     */
    override fun canRename(id: PlayerId): Boolean = data.players.get(id)?.kind == PlayerKind.GUEST && id !in aliases

    override fun deleteGuest(id: PlayerId): Boolean {
        if (!canRename(id) || !data.players.deleteGuest(id)) return false
        refreshStored()
        session.syncNow()
        return true
    }

    override fun renameGuest(
        id: PlayerId,
        name: String,
    ): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !canRename(id)) return false
        data.players.rename(id, trimmed)
        players.remove(id)
        refreshStored()
        lastState?.let { publishActive(it) }
        return true
    }

    // --- Session-Zustand ----------------------------------------------------------------------------

    private fun current(): ActiveMatch? = session.state.value.match

    /**
     * Alle laufenden Matches öffnen (Review W3-M #3: auch ältere bleiben erreichbar); den Fokus bekommt nur das zuletzt
     * geänderte, wenn es höchstens [RESUME_WINDOW_MS] alt ist.
     */
    private fun resumeRecentMatches() {
        val now = clock.nowMs()
        val running = data.matches.active()
        running.reversed().forEach { session.openInBackground(it.id) }
        running.firstOrNull()?.takeIf { now - it.updatedAtMs <= RESUME_WINDOW_MS }?.let { session.resume(it.id) }
    }

    private fun onSessionState(state: SessionState) {
        val previous = lastState
        lastState = state
        trackScorerContact(state)
        updateWatchStatus(state)
        updateRequiredFlow.value = updateRequiredOf(state)
        telemetryFlow.value = state.telemetryVisibility
        // „Gar nicht“ – auch wenn die Uhr es eingestellt hat: gepufferte Werte sofort verwerfen
        if (!state.telemetryVisibility.allowsLive) telemetryStore?.clear()
        if (storedDataMayHaveChanged(previous, state)) refreshStored()
        publishActive(state)
    }

    private fun updateRequiredOf(state: SessionState): UpdateRequired? =
        when (state.sync.versionMismatch) {
            CompatibilityStatus.PEER_TOO_OLD -> UpdateRequired.OTHER_DEVICE
            CompatibilityStatus.PEER_TOO_NEW -> UpdateRequired.THIS_DEVICE
            else -> null
        }

    /**
     * Letzter Kontakt zum Zähler, monoton gemessen. Beim Öffnen eines Matches aus dem gemerkten Wert (Wanduhr, übersteht
     * Neustarts) übernommen; ohne gemerkten Wert unbekannt. Während der Zähler verbunden ist, gilt der Stand als aktuell
     * (gemerkt höchstens alle 10 s), beim Trennen wird der Moment sofort gemerkt.
     */
    private fun trackScorerContact(state: SessionState) {
        val m = state.match
        val nowMono = monotonic.nowMs()
        if (m?.id != observedMatch) {
            observedMatch = m?.id
            scorerWasConnected = false
            contactPersistedMono = null
            withoutScorerSinceMono = nowMono
            contactMono =
                m?.let { data.settings.get(contactKey(it.id))?.toLongOrNull() }?.let { savedWall ->
                    nowMono - (clock.nowMs() - savedWall).coerceAtLeast(0)
                }
        }
        if (m == null || m.isScorer) {
            scorerWasConnected = state.sync.scorerConnected
            return
        }
        val connected = state.sync.scorerConnected
        if (connected || scorerWasConnected) {
            contactMono = nowMono
            val lastSave = contactPersistedMono
            if (!connected || lastSave == null || nowMono - lastSave >= CONTACT_PERSIST_INTERVAL_MS) {
                data.settings.putLong(contactKey(m.id), clock.nowMs())
                contactPersistedMono = nowMono
            }
        }
        if (connected) {
            withoutScorerSinceMono = null
        } else if (withoutScorerSinceMono == null) {
            withoutScorerSinceMono = nowMono
        }
        scorerWasConnected = connected
    }

    /**
     * Historie & Co. nur neu laden, wenn sich etwas außerhalb des offenen Matches geändert haben kann: anderes Match,
     * anderer Status oder eine neue `dataRevision` der Session (Löschung, andere Matches, Spieler, Identität).
     */
    private fun storedDataMayHaveChanged(
        previous: SessionState?,
        state: SessionState,
    ): Boolean {
        if (previous == null || previous.dataRevision != state.dataRevision) return true

        // anderes Match im Fokus, Matches geöffnet/geschlossen oder ein Status gewechselt (z. B. Nachbarplatz beendet)
        fun statuses(s: SessionState) = s.matches.map { it.id to it.state.status }
        return previous.match?.id != state.match?.id || statuses(previous) != statuses(state)
    }

    private fun publishActive(state: SessionState) {
        val next = state.match?.let { snapshotOf(it, state) }
        val current = activeFlow.value
        activeFlow.value =
            when {
                next == null -> null
                current != null && current.copy(revision = 0) == next -> current
                else -> next.copy(revision = ++revision)
            }
        val active = activeFlow.value
        openFlow.value = state.matches.map { m -> if (m.id == active?.matchId) active else snapshotOf(m, state) }
    }

    /**
     * Snapshot für [m]. „Stand vor X s“ und das Übernahmeangebot verfolgt der Adapter nur für das fokussierte Match;
     * für die übrigen offenen Matches (Übersicht) ist der letzte Kontakt unbekannt.
     */
    private fun snapshotOf(
        m: ActiveMatch,
        state: SessionState,
    ): LiveMatchSnapshot {
        val finished = m.state.status == MatchStatus.FINISHED
        val sync = state.sync
        val focused = m.id == state.match?.id
        return LiveMatchSnapshot(
            matchId = m.id,
            setup = MatchSetup(m.format, m.lineup, m.meta, playersOf(m.lineup, m.id), m.previousMatchId, data.assignments(m.id)),
            state = m.state,
            canUndo = m.canUndo,
            canRedo = m.canRedo,
            startedAtMs = m.createdAtMs,
            finishedAtMs = if (finished) finishedAtOf(m) else null,
            followUpMatchId =
                if (finished) {
                    data.matches
                        .followUps(m.id)
                        .firstOrNull()
                        ?.id
                } else {
                    null
                },
            revision = 0,
            pointsPlayed = m.pointsPlayed,
            role = if (m.isScorer) LiveRole.SCORER else LiveRole.DISPLAY,
            // P1: es zählt immer ein eigenes Gerät (Uhr oder Handy des Besitzers)
            scorerName = selfFlow.value.displayName,
            scorerKind = if (m.scorerDeviceId == MatchSession.PENDING_WATCH) DeviceKind.WATCH else m.scorerKind.toDeviceKind(m.isScorer),
            rejectedByRoleCount = m.rejectedByRoleCount,
            lastPoint =
                m.lastPoint?.let { LastPointInfo(it.id, it.side, it.servingSide, it.server, it.serve, it.doubleFaultAllowed) },
            serveInfoEnabled = m.serveInfoEnabled,
            canRemovePoint = m.canRemovePoint,
            nextSetOptions = m.nextSetOptions,
            canCorrectServerRetroactively = m.canCorrectServerRetroactively,
            sync =
                SyncInfo(
                    peers = sync.peers.count { it.compatibility == CompatibilityStatus.COMPATIBLE },
                    scorerConnected = state.scorerConnected(m),
                    pendingToPeers = sync.pendingToPeers,
                    lastEventReceivedAtMs = sync.lastEventReceivedAtMs,
                    lastScorerContactMono = if (m.isScorer || !focused) null else contactMono,
                    withoutScorerSinceMono = if (m.isScorer || !focused) null else withoutScorerSinceMono,
                    updateRequired = updateRequiredOf(state),
                ),
            absorbedTaps = m.absorbedTaps.size,
            waitingForScorer = waitingForScorer(m, state, focused),
            onWatch = m.id == state.watchMatchId,
        )
    }

    /**
     * Anzeige-Gerät, der Zähler war für dieses Match noch nie in Kontakt (auch nicht vor einem Neustart) und hat noch
     * nichts gesendet – das Match wartet auf die Uhr. War er schon verbunden, gilt stattdessen „Stand vor X s“.
     */
    private fun waitingForScorer(
        m: ActiveMatch,
        state: SessionState,
        focused: Boolean,
    ): Boolean {
        if (m.isScorer || state.scorerConnected(m) || m.state.status == MatchStatus.FINISHED) return false
        if (focused && contactMono != null) return false
        if (!focused && data.settings.get(contactKey(m.id)) != null) return false
        val scorer = m.scorerDeviceId ?: return false
        return data.events.sequences(m.id.value).none { it.deviceId == scorer.value }
    }

    /** Wanduhr des letzten Events eines beendeten Matches (je Match und Log-Länge zwischengespeichert). */
    private fun finishedAtOf(m: ActiveMatch): Long? {
        finishedAt[m.id]?.let { (count, at) -> if (count == m.eventCount) return at }
        val at =
            data.events
                .scoreEvents(m.id)
                .lastOrNull { it.payload !is PlayerAssigned }
                ?.wallClockMs
        finishedAt[m.id] = m.eventCount to at
        return at
    }

    private fun connectedWatch(state: SessionState = session.state.value) =
        state.sync.peers
            .firstOrNull { it.kind == PeerKind.WATCH && it.compatibility == CompatibilityStatus.COMPATIBLE && it.deviceId != null }
            ?.deviceId

    // --- Gespeicherte Daten -------------------------------------------------------------------------

    /** Spielernamen, Gäste, Favoriten, „Wie letztes Mal“ und Verlauf neu aus der Datenbank. */
    private fun refreshStored() {
        players.clear()
        val previousAliases = aliases
        aliases = data.selfAliases()
        aliasesFlow.value = aliases
        guestsFlow.value = loadGuests()
        archivedFlow.value = data.players.archivedIds()
        val people = (guestsFlow.value + selfFlow.value).associateBy { it.id }
        val changedPeople = (people.keys + summaryPeople.keys).filter { people[it] != summaryPeople[it] }.toSet()
        val changedAliases = (previousAliases - aliases) + (aliases - previousAliases)
        summaries.entries.removeAll { (_, cached) ->
            val setup = cached.second.setup
            setup.players.any { it.id in changedPeople || it.id in changedAliases } ||
                setup.assignedPlayers.values.any { it.id in changedPeople || it.id in changedAliases }
        }
        summaryPeople = people
        favoritesFlow.value = loadFavorites()
        lastSetupFlow.value = loadLastSetup()
        historyFlow.value = loadHistory()
    }

    private fun ensureSelf(): Player =
        data.players
            .ensureSelf(displayName = "", nowMs = clock.nowMs()) { PlayerId(ids.newId().value) }
            .toPlayer()

    private fun loadGuests(): List<Player> =
        data.players
            .all()
            .filter { it.kind == PlayerKind.GUEST && it.id !in aliases && it.displayName.isNotBlank() }
            .map { it.toPlayer() }

    /** Anzeigename je Spieler-ID; Aliase des Besitzers (z. B. alter Uhr-Self) tragen den Namen des Besitzers. */
    private fun player(id: PlayerId): Player =
        players[id] ?: run {
            val resolved =
                if (id in
                    aliases
                ) {
                    Player(id, selfFlow.value.displayName)
                } else if (data.isPlaceholder(id)) {
                    Player(id, "", isPlaceholder = true)
                } else {
                    data.players.get(id)?.toPlayer()
                }
            resolved?.also { players[id] = it } ?: Player(id, "")
        }

    private fun setupOf(record: StoredMatch): MatchSetup =
        MatchSetup(
            record.format,
            record.lineup,
            record.meta,
            playersOf(record.lineup, record.id),
            record.previousMatchId,
            data.assignments(record.id),
        )

    private fun playersOf(
        lineup: Lineup,
        matchId: MatchId? = null,
    ): List<Player> =
        (lineup.a + lineup.b).distinct().map { id ->
            if (matchId != null && data.isMatchPlaceholder(matchId, id)) Player(id, "", isPlaceholder = true) else player(id)
        }

    /** Spieler aus einem Setup, die die Datenbank noch nicht kennt, als Gäste übernehmen (z. B. aus einem Favoriten). */
    private fun rememberPlayers(list: List<Player>) {
        val now = clock.nowMs()
        list
            .filter {
                !it.isPlaceholder && !data.isPlaceholder(it.id) && data.players.get(it.id) == null
            }.forEach { data.players.addGuest(it.id, it.displayName, now) }
    }

    /** „Wie letztes Mal“: jüngstes Match, das kein Abbruch ohne Wertung war (F21). */
    private fun loadLastSetup(): MatchSetup? =
        data.matches
            .lastRated()
            ?.let {
                val lineup = data.freshPlaceholders(data.resolvedLineup(it)) { PlayerId(ids.newId().value) }
                MatchSetup(it.format, lineup, it.meta, playersOf(lineup))
            }

    private fun loadHistory(): List<MatchSummary> {
        val headers = data.matches.history(limit = historyLimit)
        summaries.keys.retainAll(headers.map { it.id }.toSet())
        return headers
            .filter { it.isFinished }
            .map { header ->
                summaries[header.id]?.takeIf { it.first == header }?.second
                    ?: headerSummary(header).also { summaries[header.id] = header to it }
            }.sortedWith(compareByDescending<MatchSummary> { it.finishedAtMs }.thenByDescending { it.matchId.value })
    }

    /** Nur der Kopf: kein Event-Count, kein Payload-Lesen und kein Replay beim Laden des Verlaufs. */
    private fun headerSummary(header: StoredMatch): MatchSummary {
        val score = header.score
        val tennisLike = header.sport.isTennisLike
        val sets =
            score.completedSets ?: score.sets.map { set ->
                val pair = SidePair(set.a, set.b)
                val winner = if (set.a > set.b) Side.A else Side.B
                when {
                    set.matchTiebreak -> {
                        SetRecord(SetKind.MATCH_TIEBREAK, SidePair.of { if (it == winner) 1 else 0 }, pair, null, winner)
                    }

                    tennisLike -> {
                        SetRecord(
                            SetKind.REGULAR,
                            pair,
                            set.tiebreakA?.let { SidePair(it, set.tiebreakB ?: 0) },
                            null,
                            winner,
                        )
                    }

                    else -> {
                        SetRecord(
                            if (header.sport ==
                                Sport.PICKLEBALL
                            ) {
                                SetKind.PB_GAME
                            } else {
                                SetKind.RALLY_GAME
                            },
                            null,
                            null,
                            pair,
                            winner,
                        )
                    }
                }
            }
        // Ältere Köpfe enthalten nur Games bzw. Satzpunkte; fehlende Ballwechselpunkte werden nicht erfunden.
        val current =
            score.currentState ?: score.current?.let { set ->
                val regular = tennisLike && !set.matchTiebreak
                val pair = SidePair(set.a, set.b)
                CurrentSet(
                    sets.size,
                    when {
                        set.matchTiebreak -> SetKind.MATCH_TIEBREAK
                        regular -> SetKind.REGULAR
                        header.sport == Sport.PICKLEBALL -> SetKind.PB_GAME
                        else -> SetKind.RALLY_GAME
                    },
                    if (set.matchTiebreak) {
                        GameKind.MATCH_TIEBREAK
                    } else if (regular) {
                        GameKind.STANDARD
                    } else {
                        GameKind.RALLY_GAME
                    },
                    pair.takeIf { regular },
                    if (regular) SidePair(0, 0) else pair,
                    if (regular) SidePair("", "") else SidePair(set.a.toString(), set.b.toString()),
                    null,
                    null,
                    null,
                )
            }
        return MatchSummary(
            header.id,
            setupOf(header),
            MatchResult(score.winner, score.reason ?: ResultReason.ABANDONED),
            sets,
            SidePair(score.setsWonA, score.setsWonB),
            header.createdAtMs,
            score.lastEventAtMs ?: header.updatedAtMs,
            current,
            header.updatedAtMs,
            score.eventCount,
        )
    }

    private fun summaryOf(
        header: StoredMatch,
        state: MatchState,
        startedAtMs: Long,
        lastEventAtMs: Long?,
        eventCount: Int,
    ): MatchSummary =
        MatchSummary(
            matchId = header.id,
            setup = setupOf(header),
            result = state.result ?: MatchResult(null, ResultReason.ABANDONED),
            sets = state.sets,
            setsWon = state.setsWon,
            startedAtMs = startedAtMs,
            finishedAtMs = lastEventAtMs ?: startedAtMs,
            interruptedSet = state.current.takeIf { state.status == MatchStatus.FINISHED },
            updatedAtMs = header.updatedAtMs,
            eventCount = eventCount,
        )

    // --- Favoriten (Format + Besetzung, JSON in den Settings) ----------------------------------------

    @Serializable
    private data class StoredFavorite(
        val id: String,
        val format: MatchFormat,
        val lineup: Lineup,
        val meta: MatchMeta = MatchMeta(),
    )

    private fun readFavorites(): List<StoredFavorite> {
        val json = data.settings.get(FAVORITES_KEY) ?: return emptyList()
        return try {
            FavoritesJson.decodeFromString(FAVORITES, json)
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    private fun writeFavorites(list: List<StoredFavorite>) {
        data.settings.put(FAVORITES_KEY, FavoritesJson.encodeToString(FAVORITES, list))
    }

    private fun loadFavorites(): List<FavoriteLineup> =
        readFavorites()
            .filter {
                (it.lineup.a + it.lineup.b).none { id -> data.isPlaceholder(id) || id in archivedPlayerIds.value }
            }.map { FavoriteLineup(it.id, MatchSetup(it.format, it.lineup, it.meta, playersOf(it.lineup))) }

    private companion object {
        const val DEFAULT_HISTORY_LIMIT = Long.MAX_VALUE

        /** Nach einem Neustart wird ein laufendes Match nur wieder geöffnet, wenn es in diesem Zeitraum geändert wurde. */
        const val RESUME_WINDOW_MS = 12L * 60 * 60 * 1000
        const val FAVORITES_KEY = "phone.favorites"
        const val CONTACT_PERSIST_INTERVAL_MS = 10_000L
        val FAVORITES = ListSerializer(StoredFavorite.serializer())
        val FavoritesJson = Json { ignoreUnknownKeys = true }

        fun contactKey(id: MatchId) = "phone.scorerContact.${id.value}"
    }
}

private fun PeerKind?.toDeviceKind(isScorer: Boolean): DeviceKind =
    when (this) {
        PeerKind.PHONE -> DeviceKind.PHONE
        PeerKind.WATCH -> DeviceKind.WATCH
        PeerKind.OTHER -> DeviceKind.OTHER
        null -> if (isScorer) DeviceKind.PHONE else DeviceKind.OTHER
    }
