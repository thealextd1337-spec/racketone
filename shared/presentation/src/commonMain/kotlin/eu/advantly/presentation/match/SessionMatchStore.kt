package eu.advantly.presentation.match

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.LineupValidator
import eu.advantly.core.scoring.MatchFormatValidator
import eu.advantly.core.scoring.MatchResult
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.ResultReason
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.data.LocalData
import eu.advantly.data.PlayerKind
import eu.advantly.data.scoreEvents
import eu.advantly.match.session.ActiveMatch
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.ScorerChoice
import eu.advantly.match.session.SessionListener
import eu.advantly.match.session.SessionState
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
 * neu, wenn sich Match oder Status ändern oder Daten anderer Geräte eintreffen (nicht bei jedem eigenen Punkt);
 * Engine-Zusammenfassungen beendeter Matches sind je `updatedAt` zwischengespeichert.
 *
 * Nach einem App-Neustart öffnet der Adapter das zuletzt geänderte, noch laufende Match wieder (höchstens 12 h alt).
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
) : MatchStore {
    private val data: LocalData = session.data

    private val players = HashMap<PlayerId, Player>()
    private val summaries = HashMap<MatchId, Pair<Long, MatchSummary>>()
    private var finishedAt: Triple<MatchId, Int, Long?>? = null

    private val selfFlow = MutableStateFlow(ensureSelf())
    private val guestsFlow = MutableStateFlow<List<Player>>(emptyList())
    private val favoritesFlow = MutableStateFlow<List<FavoriteLineup>>(emptyList())
    private val lastSetupFlow = MutableStateFlow<MatchSetup?>(null)
    private val historyFlow = MutableStateFlow<List<MatchSummary>>(emptyList())
    private val activeFlow = MutableStateFlow<LiveMatchSnapshot?>(null)
    private val watchFlow = MutableStateFlow(false)

    override val self: StateFlow<Player> = selfFlow.asStateFlow()
    override val guests: StateFlow<List<Player>> = guestsFlow.asStateFlow()
    override val favorites: StateFlow<List<FavoriteLineup>> = favoritesFlow.asStateFlow()
    override val lastSetup: StateFlow<MatchSetup?> = lastSetupFlow.asStateFlow()
    override val matches: StateFlow<List<MatchSummary>> = historyFlow.asStateFlow()
    override val active: StateFlow<LiveMatchSnapshot?> = activeFlow.asStateFlow()
    override val watchAvailable: StateFlow<Boolean> = watchFlow.asStateFlow()

    // Zustand für Revision und „Stand vor X s“
    private var lastState: SessionState? = null
    private var revision = 0L
    private var observedMatch: MatchId? = null
    private var scorerSeenAtMs: Long? = null
    private var scorerWasConnected = false

    private val listener = SessionListener { onSessionState(it) }

    init {
        // App-Neustart: ein kürzlich noch laufendes Match wieder öffnen (Zähler zählt ggf. auf der Uhr weiter)
        if (session.state.value.match == null) resumeRecentMatch()
        // addListener liefert sofort den aktuellen Zustand (lädt dabei auch Historie, Spieler und Favoriten)
        session.addListener(listener)
    }

    /** Meldet den Adapter von der Session ab (die Session selbst läuft prozessweit weiter). */
    fun close() {
        session.removeListener(listener)
    }

    /** Workout-Wunsch, mit dem [matchId] auf diesem Handy gestartet wurde (`null` = unbekannt). */
    fun watchWorkout(matchId: MatchId): Boolean? = data.settings.get(workoutKey(matchId))?.toBooleanStrictOrNull()

    // --- LiveMatchPort ---------------------------------------------------------------------------

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
        rememberPlayers(setup.players)

        // Ein laufendes Match wird abgebrochen (die UI hat nachgefragt). Zählt dort ein anderes Gerät, übernimmt dieses
        // Handy vorher – sonst bliebe das alte Match auf der Uhr offen und sie folgte dem neuen nie (Auto-Follow).
        current()?.takeIf { it.state.status != MatchStatus.FINISHED }?.let { running ->
            if (!running.isScorer) session.takeOverScoring()
            session.endMatch(EndReason.ABANDONED)
        }
        val scorer =
            when (options.scorer) {
                ScorerOption.MY_WATCH -> connectedWatch()?.let { ScorerChoice.Device(it) } ?: ScorerChoice.ThisDevice
                ScorerOption.THIS_PHONE -> ScorerChoice.ThisDevice
            }
        val id = session.create(setup.format, setup.lineup, setup.meta, scorer)
        // Metadatum am Match (P1: lokal; die Übertragung an die Uhr braucht ein Feld im Protokoll, siehe Bericht W1-I)
        data.settings.putBoolean(workoutKey(id), options.watchWorkout)
        return id
    }

    override fun recordPoint(side: Side): Boolean = session.recordPoint(side)

    override fun undo(): Boolean = session.undo()

    override fun redo(): Boolean = session.redo()

    override fun continueDecision(
        anotherSet: Boolean,
        matchTiebreak: Boolean,
    ): Boolean = session.continueDecision(anotherSet, matchTiebreak)

    override fun endMatch(
        reason: EndReason,
        side: Side?,
    ): Boolean {
        val m = current() ?: return false
        if (!m.isScorer || m.state.status == MatchStatus.FINISHED) return false
        if ((reason == EndReason.ABANDONED) != (side == null)) return false
        // Walkover nur ohne wirksamen Ballwechsel (02 §9.8) – sonst würde die Engine das Event überspringen
        if (reason == EndReason.WALKOVER && m.pointsPlayed > 0) return false
        return session.endMatch(reason, side)
    }

    override fun correctServer(
        player: PlayerId,
        retroactive: Boolean,
    ): Boolean {
        val m = current() ?: return false
        if (!m.isScorer || m.lineup.sideOf(player) == null) return false
        val status = m.state.status
        if (status == MatchStatus.FINISHED) return false
        // Correction(SERVER) gilt nur bei IN_PROGRESS/PAUSED (02 §7.1), INITIAL_SERVER jederzeit
        if (!retroactive && status != MatchStatus.IN_PROGRESS && status != MatchStatus.PAUSED) return false
        return session.correct(if (retroactive) Correction.initialServer(player) else Correction.server(player))
    }

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

    // --- MatchHistoryPort ------------------------------------------------------------------------

    override fun record(matchId: MatchId): MatchRecord? {
        val header = data.matches.get(matchId) ?: return null
        val engine =
            try {
                ScoringEngine.replay(data.events.scoreEvents(matchId))
            } catch (e: IllegalArgumentException) {
                return null
            }
        val genesis = engine.genesis ?: return null
        val summary = summaryOf(header, engine.state, genesis.wallClockMs, engine.events.lastOrNull()?.wallClockMs)
        return MatchRecord(summary, engine.state, listOf(genesis) + engine.events)
    }

    override fun delete(matchId: MatchId): Boolean {
        val header = data.matches.get(matchId) ?: return false
        if (!header.isFinished) return false
        // Aktives (beendetes) Match zuerst schließen (Vertrag), dann löschen; die Löschmarkierung geht an die Uhr
        if (current()?.id == matchId) session.closeMatch()
        session.deleteMatch(matchId)
        summaries.remove(matchId)
        refreshStored()
        return true
    }

    // --- PlayersPort ------------------------------------------------------------------------------

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
    override fun canRename(id: PlayerId): Boolean = data.players.get(id)?.kind == PlayerKind.GUEST

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

    private fun resumeRecentMatch() {
        val now = clock.nowMs()
        data.matches
            .active()
            .firstOrNull { now - it.updatedAtMs <= RESUME_WINDOW_MS }
            ?.let { session.resume(it.id) }
    }

    private fun onSessionState(state: SessionState) {
        val previous = lastState
        lastState = state
        val now = clock.nowMs()
        val m = state.match
        if (m?.id != observedMatch) {
            // Neues Match im Blick: Basis für „Stand vor X s“ ist der Moment, ab dem es hier angezeigt wird
            observedMatch = m?.id
            scorerSeenAtMs = now
            scorerWasConnected = false
        }
        // Solange der Zähler verbunden ist (und im Moment der Trennung), gilt der Stand als aktuell
        if (state.sync.scorerConnected || scorerWasConnected) scorerSeenAtMs = now
        scorerWasConnected = state.sync.scorerConnected
        watchFlow.value = connectedWatch(state) != null
        if (storedDataMayHaveChanged(previous, state)) refreshStored()
        publishActive(state)
    }

    /**
     * Historie & Co. nur neu laden, wenn sich etwas außerhalb des offenen Matches geändert haben kann: anderes Match,
     * anderer Status, oder empfangene Daten/Gegenstellen ohne neues Event im offenen Match (z. B. Verlauf von der Uhr).
     */
    private fun storedDataMayHaveChanged(
        previous: SessionState?,
        state: SessionState,
    ): Boolean {
        if (previous == null) return true
        val before = previous.match
        val after = state.match
        if (before?.id != after?.id || before?.state?.status != after?.state?.status) return true
        val sameLog = before?.eventCount == after?.eventCount
        val received =
            previous.sync.lastEventReceivedAtMs != state.sync.lastEventReceivedAtMs ||
                previous.sync.lastSyncAtMs != state.sync.lastSyncAtMs ||
                previous.sync.peers != state.sync.peers
        return sameLog && received
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
    }

    private fun snapshotOf(
        m: ActiveMatch,
        state: SessionState,
    ): LiveMatchSnapshot {
        val finished = m.state.status == MatchStatus.FINISHED
        val sync = state.sync
        val lastContact = listOfNotNull(scorerSeenAtMs, sync.lastEventReceivedAtMs).maxOrNull()
        return LiveMatchSnapshot(
            matchId = m.id,
            setup = MatchSetup(m.format, m.lineup, m.meta, playersOf(m.lineup), m.previousMatchId),
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
            scorerKind = m.scorerKind.toDeviceKind(m.isScorer),
            rejectedByRoleCount = m.rejectedByRoleCount,
            sync =
                SyncInfo(
                    peers = sync.peers.count { it.compatibility == CompatibilityStatus.COMPATIBLE },
                    scorerConnected = sync.scorerConnected,
                    pendingToPeers = sync.pendingToPeers,
                    lastEventReceivedAtMs = sync.lastEventReceivedAtMs,
                    lastScorerContactAtMs = lastContact,
                ),
            absorbedTaps = m.absorbedTaps.size,
        )
    }

    /** Wanduhr des letzten Events eines beendeten Matches (je Match und Log-Länge zwischengespeichert). */
    private fun finishedAtOf(m: ActiveMatch): Long? {
        val cached = finishedAt
        if (cached != null && cached.first == m.id && cached.second == m.eventCount) return cached.third
        val at =
            data.events
                .scoreEvents(m.id)
                .lastOrNull()
                ?.wallClockMs
        finishedAt = Triple(m.id, m.eventCount, at)
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
        guestsFlow.value = loadGuests()
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
            .filter { it.kind == PlayerKind.GUEST }
            .map { it.toPlayer() }

    private fun player(id: PlayerId): Player =
        players[id] ?: data.players
            .get(id)
            ?.toPlayer()
            ?.also { players[id] = it } ?: Player(id, "")

    private fun playersOf(lineup: Lineup): List<Player> = (lineup.a + lineup.b).distinct().map(::player)

    /** Spieler aus einem Setup, die die Datenbank noch nicht kennt, als Gäste übernehmen (z. B. aus einem Favoriten). */
    private fun rememberPlayers(list: List<Player>) {
        val now = clock.nowMs()
        list.filter { data.players.get(it.id) == null }.forEach { data.players.addGuest(it.id, it.displayName, now) }
    }

    private fun loadLastSetup(): MatchSetup? =
        data.matches
            .history(limit = 1)
            .firstOrNull()
            ?.let { MatchSetup(it.format, it.lineup, it.meta, playersOf(it.lineup)) }

    private fun loadHistory(): List<MatchSummary> {
        val headers = data.matches.history(limit = historyLimit)
        summaries.keys.retainAll(headers.map { it.id }.toSet())
        return headers
            .filter { it.isFinished }
            .mapNotNull { header -> cachedSummary(header) }
            .map { it.copy(setup = it.setup.copy(players = playersOf(it.setup.lineup))) }
            .sortedWith(compareByDescending<MatchSummary> { it.finishedAtMs }.thenByDescending { it.matchId.value })
    }

    private fun cachedSummary(header: StoredMatch): MatchSummary? {
        summaries[header.id]?.let { (updatedAt, summary) -> if (updatedAt == header.updatedAtMs) return summary }
        val engine =
            try {
                ScoringEngine.replay(data.events.scoreEvents(header.id))
            } catch (e: IllegalArgumentException) {
                return null
            }
        val genesis = engine.genesis ?: return null
        val summary = summaryOf(header, engine.state, genesis.wallClockMs, engine.events.lastOrNull()?.wallClockMs)
        summaries[header.id] = header.updatedAtMs to summary
        return summary
    }

    private fun summaryOf(
        header: StoredMatch,
        state: MatchState,
        startedAtMs: Long,
        lastEventAtMs: Long?,
    ): MatchSummary =
        MatchSummary(
            matchId = header.id,
            setup = MatchSetup(header.format, header.lineup, header.meta, playersOf(header.lineup), header.previousMatchId),
            result = state.result ?: MatchResult(null, ResultReason.ABANDONED),
            sets = state.sets,
            setsWon = state.setsWon,
            startedAtMs = startedAtMs,
            finishedAtMs = lastEventAtMs ?: startedAtMs,
            interruptedSet = state.current.takeIf { state.status == MatchStatus.FINISHED },
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
        readFavorites().map { FavoriteLineup(it.id, MatchSetup(it.format, it.lineup, it.meta, playersOf(it.lineup))) }

    private companion object {
        const val DEFAULT_HISTORY_LIMIT = 200L

        /** Nach einem Neustart wird ein laufendes Match nur wieder geöffnet, wenn es in diesem Zeitraum geändert wurde. */
        const val RESUME_WINDOW_MS = 12L * 60 * 60 * 1000
        const val FAVORITES_KEY = "phone.favorites"
        val FAVORITES = ListSerializer(StoredFavorite.serializer())
        val FavoritesJson = Json { ignoreUnknownKeys = true }

        fun workoutKey(id: MatchId) = "match.watchWorkout.${id.value}"
    }
}

private fun PeerKind?.toDeviceKind(isScorer: Boolean): DeviceKind =
    when (this) {
        PeerKind.PHONE -> DeviceKind.PHONE
        PeerKind.WATCH -> DeviceKind.WATCH
        PeerKind.OTHER -> DeviceKind.OTHER
        null -> if (isScorer) DeviceKind.PHONE else DeviceKind.OTHER
    }
