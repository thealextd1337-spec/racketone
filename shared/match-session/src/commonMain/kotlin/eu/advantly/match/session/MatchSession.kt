package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.MatchSettings
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.ContinueDecision
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.EngineState
import eu.advantly.core.scoring.EventLog
import eu.advantly.core.scoring.EventOrdering
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchEnded
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.Paused
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Resumed
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.TapConflict
import eu.advantly.core.scoring.Undo
import eu.advantly.data.AppendResult
import eu.advantly.data.FavoriteLineup
import eu.advantly.data.LocalData
import eu.advantly.data.MatchRecord
import eu.advantly.data.ScoreSummary
import eu.advantly.data.scoreEvents
import eu.advantly.data.toScoreEventOrNull
import eu.advantly.data.toStoredEvent
import eu.advantly.device.link.Cancellable
import eu.advantly.device.link.DeviceLink
import eu.advantly.device.link.DeviceLinkListener
import eu.advantly.device.link.LinkScheduler
import eu.advantly.device.link.LinkState
import eu.advantly.sync.protocol.AntiEntropy
import eu.advantly.sync.protocol.Compatibility
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.DecodeResult
import eu.advantly.sync.protocol.DeviceSeq
import eu.advantly.sync.protocol.Events
import eu.advantly.sync.protocol.Forks
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.MatchHeader
import eu.advantly.sync.protocol.MatchMetaMessage
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.ProtocolVersion
import eu.advantly.sync.protocol.SeqRange
import eu.advantly.sync.protocol.Summary
import eu.advantly.sync.protocol.SyncCodec
import eu.advantly.sync.protocol.SyncMessage
import eu.advantly.sync.protocol.VersionVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import eu.advantly.sync.protocol.FavoriteLineup as WireFavorite

/**
 * Orchestriert ein Match auf einem Gerät – gleichwertig auf Handy und Uhr (01 §4, PLAN K1):
 *
 * - **Lokaler Tap** → Event (UUIDv7, Lamport, deviceId, deviceSeq, parentId) → [LocalData.events] (idempotent)
 *   → [ScoringEngine.applyAll] → Broadcast `Events` an alle Gegenstellen.
 * - **Empfang** → Store (eine Transaktion je Nachricht) → Engine mit dem **ganzen Batch** (`applyAll`) → Fork-Prüfung
 *   → Weiterleitung an die übrigen Gegenstellen (Hub-Rolle des Handys).
 * - **Anti-Entropie** (`Hello` → `Summary` → fehlende Events) bei jeder Verbindung und alle 30 s im aktiven Match.
 *
 * Zustand: [state] (`StateFlow`) bzw. [addListener] (Callbacks, z. B. für die Swift-Facade).
 *
 * **Threading:** Alle Methoden und Callbacks laufen auf dem Thread des [scheduler] (Android: Main-Looper, Apple:
 * Main-Queue, Tests: `ManualScheduler`) – derselbe Thread, auf dem der [link] seine Callbacks liefert.
 *
 * @param link Geräte-Link oder `null` (Standalone, nur lokal zählen)
 */
class MatchSession(
    private val data: LocalData,
    private val link: DeviceLink?,
    private val scheduler: LinkScheduler,
    val config: SessionConfig,
    private val clock: WallClock = SystemWallClock,
    private val ids: IdGenerator = RandomIdGenerator(),
) {
    /** Stabile Installations-ID dieses Geräts. */
    val deviceId: DeviceId get() = config.deviceId

    /** Offenes Match mit Engine-Zustand und gerätelokalem Redo-Stapel. */
    private class OpenMatch(
        var record: MatchRecord,
        val genesis: ScoreEvent,
        var engine: EngineState,
    ) {
        val redo = ArrayDeque<EventId>()
        val id: MatchId get() = record.id
        val status: MatchStatus get() = engine.state.status

        /** Vollständiges Log inkl. Genesis. */
        fun log(): List<ScoreEvent> = listOf(genesis) + engine.events

        /** Log-Kopf = letztes Event in Event-Ordnung (01 §3.2 `parentId`). */
        fun head(): ScoreEvent = engine.events.lastOrNull()?.takeIf { EventOrdering.compare(it, genesis) > 0 } ?: genesis
    }

    private var open: OpenMatch? = null
    private val forks = ForkLedger(data.settings)
    private val listeners = mutableListOf<SessionListener>()
    private val hellos = HashMap<String, Hello>()
    private val compatibility = HashMap<String, Compatibility>()
    private val helloSent = HashSet<String>()

    /** Gespeicherte, aber noch nicht angewendete Teile einer Nachlieferung (`Events.more = true`). */
    private val pending = LinkedHashMap<MatchId, MutableList<ScoreEvent>>()

    /** Weitergeleitete Nachlieferungen, deren letzter Teil noch aussteht (Match → Absender). */
    private val openForwards = HashMap<MatchId, String>()
    private var started = false
    private var timer: Cancellable? = null
    private var lastSyncAtMs: Long? = null

    private val linkListener =
        object : DeviceLinkListener {
            override fun onMessage(
                peer: String,
                messageId: String,
                payload: ByteArray,
            ) = receive(peer, payload)

            override fun onStateChanged(
                state: LinkState,
                peers: List<String>,
            ) = peersChanged(peers)
        }

    private val mutableState = MutableStateFlow(buildState())

    /** Aktueller Zustand (Match, Sync-Status, Konflikte, Fork). */
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    // --- Lebenszyklus ---------------------------------------------------------------------------

    /** Startet Link und Anti-Entropie-Takt. Idempotent. */
    fun start() {
        if (started) return
        started = true
        link?.setListener(linkListener)
        link?.start()
        peersChanged(link?.peers.orEmpty())
        scheduleAntiEntropy()
        publish()
    }

    /** Stoppt Link und Takt; offene Teil-Lieferungen werden noch angewendet. */
    fun close() {
        if (!started) return
        started = false
        timer?.cancel()
        timer = null
        link?.setListener(null)
        link?.stop()
        flushAll()
        hellos.clear()
        compatibility.clear()
        helloSent.clear()
        publish()
    }

    /** Meldet [listener] an; er bekommt sofort den aktuellen Zustand und danach jede Änderung. */
    fun addListener(listener: SessionListener) {
        listeners += listener
        listener.onStateChanged(mutableState.value)
    }

    fun removeListener(listener: SessionListener) {
        listeners -= listener
    }

    // --- Match öffnen -----------------------------------------------------------------------------

    /**
     * Legt ein neues Match an (Genesis `MatchCreated`), öffnet es und meldet es den Gegenstellen.
     *
     * @throws IllegalArgumentException bei ungültigem Format oder Lineup (siehe `MatchFormatValidator`)
     */
    fun create(
        format: MatchFormat,
        lineup: Lineup,
        meta: MatchMeta = MatchMeta(),
    ): MatchId = createMatch(format, lineup, meta, previous = null)

    /** Öffnet ein gespeichertes Match (Resume-Karte, App-Neustart). `false`, wenn Kopf oder Genesis fehlen. */
    fun resume(matchId: MatchId): Boolean {
        if (!openMatch(matchId)) return false
        open?.let { persistProgress(it) }
        syncNow()
        publish()
        return true
    }

    /** Schließt das offene Match in der Anzeige (es bleibt gespeichert und wird weiter synchronisiert). */
    fun closeMatch() {
        open = null
        publish()
    }

    /**
     * Verknüpftes Folge-Match nach einem beendeten Match (02 §5.2): gleiche Besetzung und gleiches Satzformat,
     * Modus `OPEN`, Erstaufschläger aus `state.continuation`, `previousMatchId` = das beendete Match.
     * `null`, wenn kein beendetes Match offen ist.
     */
    fun createFollowUp(): MatchId? {
        val m = open ?: return null
        if (m.status != MatchStatus.FINISHED) return null
        val previous = m.record
        val format = previous.format.copy(match = MatchSettings.OPEN, decidingSet = null)
        val next = m.engine.state.continuation
        val lineup = if (next != null) previous.lineup.copy(firstServer = next.player) else previous.lineup
        return createMatch(format, lineup, previous.meta, previous = previous.id)
    }

    // --- Zählen -----------------------------------------------------------------------------------

    /** Punkt für [side]. `false`, wenn kein Match offen ist oder es gerade keinen Punkt annimmt. */
    fun recordPoint(side: Side): Boolean {
        val m = open ?: return false
        if (m.status != MatchStatus.IN_PROGRESS && m.status != MatchStatus.PAUSED) return false
        m.redo.clear()
        emit(m, listOf(PointWon(side)))
        return true
    }

    /** UI-Undo: macht das letzte wirksame Zähl-Event rückgängig (02 §7.3). */
    fun undo(): Boolean {
        val m = open ?: return false
        val target = m.engine.undoTarget ?: return false
        val undo = emit(m, listOf(Undo(target))).single()
        m.redo.addLast(undo.id)
        publish()
        return true
    }

    /** Redo = Undo auf das zuletzt hier erzeugte Undo (02 §7.3), solange es noch wirkt. */
    fun redo(): Boolean {
        val m = open ?: return false
        val undo = validRedo(m) ?: return false
        m.redo.removeLast()
        emit(m, listOf(Undo(undo)))
        return true
    }

    /**
     * Antwort auf „Noch ein Satz?“ (02 §5.2, nur bei `AWAITING_DECISION`). [matchTiebreak] = nächster Satz als
     * Match-Tiebreak (nur wenn das Format einen kennt).
     */
    fun continueDecision(
        anotherSet: Boolean,
        matchTiebreak: Boolean = false,
    ): Boolean {
        val m = open ?: return false
        if (m.status != MatchStatus.AWAITING_DECISION) return false
        if (matchTiebreak && (!anotherSet || m.record.format.matchTiebreak == null)) return false
        m.redo.clear()
        emit(m, listOf(ContinueDecision(anotherSet, if (matchTiebreak) NextSetKind.MATCH_TIEBREAK else NextSetKind.REGULAR)))
        return true
    }

    /** Korrektur von Aufschläger, Reihenfolge oder Positionen (02 §7.1/§7.4); ungültige überspringt die Engine. */
    fun correct(correction: Correction): Boolean {
        val m = open ?: return false
        if (m.status == MatchStatus.FINISHED) return false
        m.redo.clear()
        emit(m, listOf(correction))
        return true
    }

    /** Unterbrechung (Regen, Verletzung). */
    fun pause(): Boolean {
        val m = open ?: return false
        if (m.status != MatchStatus.IN_PROGRESS) return false
        emit(m, listOf(Paused))
        return true
    }

    /** Fortsetzung nach [pause]. */
    fun resumePlay(): Boolean {
        val m = open ?: return false
        if (m.status != MatchStatus.PAUSED) return false
        emit(m, listOf(Resumed))
        return true
    }

    /**
     * Beendet das Match: im Satzende-Dialog (`AWAITING_DECISION`) regulär über `ContinueDecision(false)`, sonst
     * vorzeitig über `MatchEnded(reason, side)`; [side] = aufgebende bzw. nicht angetretene Side (Pflicht außer
     * bei `ABANDONED`).
     */
    fun endMatch(
        reason: EndReason = EndReason.ABANDONED,
        side: Side? = null,
    ): Boolean {
        val m = open ?: return false
        val payload: EventPayload =
            when {
                m.status == MatchStatus.FINISHED -> return false
                m.status == MatchStatus.AWAITING_DECISION -> ContinueDecision(anotherSet = false)
                side == null && reason != EndReason.ABANDONED -> return false
                else -> MatchEnded(reason, side)
            }
        m.redo.clear()
        emit(m, listOf(payload))
        return true
    }

    // --- Fork-Auflösung ---------------------------------------------------------------------------

    /**
     * „Version [keepBranch] behalten“ (01 §4.4): erzeugt Undo-Events für alle anderen Zweige der offenen Gabelung
     * ([ActiveMatch.fork]). Wirkt über den Sync auf allen Geräten.
     */
    fun resolveFork(keepBranch: Int): Boolean {
        val m = open ?: return false
        val fork = openFork(m)?.fork ?: return false
        if (keepBranch !in fork.branches.indices) return false
        val targets = Forks.resolve(fork, keepBranch, m.log())
        if (targets.isEmpty()) return false
        m.redo.clear()
        emit(m, targets.map { Undo(it) })
        return true
    }

    /** „Beide zusammenführen“: alle Punkte bleiben; die Gabelung gilt auf diesem Gerät als erledigt. */
    fun mergeFork(): Boolean {
        val m = open ?: return false
        val record = openFork(m) ?: return false
        forks.markMerged(m.id, record.fork.id)
        publish()
        return true
    }

    /** Stößt sofort einen Abgleich mit allen Gegenstellen an (z. B. App im Vordergrund). */
    fun syncNow() {
        if (!started) return
        broadcast(summaryOf(summaryMatches(), reply = false))
    }

    // --- Lokale Events ----------------------------------------------------------------------------

    private fun createMatch(
        format: MatchFormat,
        lineup: Lineup,
        meta: MatchMeta,
        previous: MatchId?,
    ): MatchId {
        ScoringEngine.replay(format, lineup, emptyList()) // prüft Format und Lineup
        val now = clock.nowMs()
        val matchId = MatchId.of(ids.newUuid7(now))
        val genesis = ScoreEvent(EventId.of(ids.newUuid7(now)), matchId, deviceId, 1, 1, now, null, MatchCreated(format, lineup, meta))
        data.transaction {
            data.events.append(genesis.toStoredEvent())
            data.matches.insertIfAbsent(matchId, format, lineup, meta, now, previous)
            data.players.markUsed(lineup.a + lineup.b, now)
            data.favorites.recordUse(format.sport, format.discipline, lineup.a, lineup.b, now)
        }
        openMatch(matchId)
        broadcastMeta()
        broadcast(Events(matchId, listOf(genesis)))
        publish()
        return matchId
    }

    /** Erzeugt, speichert, wendet an und sendet Events für [payloads] (eine Kette, ein Batch). */
    private fun emit(
        m: OpenMatch,
        payloads: List<EventPayload>,
    ): List<ScoreEvent> {
        val now = clock.nowMs()
        var lamport = data.events.maxLamport(m.id.value)
        var seq = data.events.maxDeviceSeq(m.id.value, deviceId.value)
        var parent = m.head().id
        val events =
            payloads.map { payload ->
                ScoreEvent(EventId.of(ids.newUuid7(now)), m.id, deviceId, ++seq, ++lamport, now, parent, payload).also { parent = it.id }
            }
        data.events.appendAll(events.map { it.toStoredEvent() })
        m.engine = ScoringEngine.applyAll(m.engine, events)
        persistProgress(m)
        broadcast(Events(m.id, events))
        publish()
        return events
    }

    private fun openMatch(matchId: MatchId): Boolean {
        val record = data.matches.get(matchId) ?: return false
        val log = data.events.scoreEvents(matchId)
        if (log.none { it.payload is MatchCreated }) return false
        val engine =
            try {
                ScoringEngine.replay(log)
            } catch (e: IllegalArgumentException) {
                return false
            }
        open = OpenMatch(record, checkNotNull(engine.genesis), engine)
        pending.remove(matchId)
        return true
    }

    private fun persistProgress(m: OpenMatch) {
        val state = m.engine.state
        data.matches.updateProgress(m.id, state.status, ScoreSummary.of(state), clock.nowMs())
    }

    // --- Empfang ----------------------------------------------------------------------------------

    private fun receive(
        peer: String,
        payload: ByteArray,
    ) {
        val message = (SyncCodec.decode(payload) as? DecodeResult.Ok)?.message ?: return
        if (message !is Hello && compatibility[peer]?.isCompatible == false) return
        try {
            when (message) {
                is Hello -> onHello(peer, message)
                is Summary -> onSummary(peer, message)
                is Events -> onEvents(peer, message)
                is MatchMetaMessage -> onMatchMeta(message)
            }
        } catch (e: IllegalArgumentException) {
            // passt nicht zum lokalen Log (z. B. kollidierende Genesis-ID) → verwerfen; der Rest bleibt konsistent
        }
        publish()
    }

    private fun onHello(
        peer: String,
        hello: Hello,
    ) {
        hellos[peer] = hello
        val result = ProtocolVersion.negotiate(hello.v, hello.minV)
        compatibility[peer] = result
        data.settings.put(peerKindKey(hello.deviceId), hello.kind.name)
        if (!result.isCompatible) return
        if (peer !in helloSent) sendHello(peer)
        send(peer, summaryOf(summaryMatches(), reply = false))
        send(peer, metaMessage())
    }

    private fun onSummary(
        peer: String,
        summary: Summary,
    ) {
        lastSyncAtMs = clock.nowMs()
        val behind = mutableListOf<MatchId>()
        for ((key, remote) in summary.matches) {
            val matchId = MatchId(key)
            val local = sequences(matchId)
            val ranges = AntiEntropy.missingRanges(local, remote)
            if (ranges.isNotEmpty()) sendEvents(peer, matchId, load(matchId, ranges))
            if (AntiEntropy.isBehind(VersionVector.fromSequences(local), remote)) behind += matchId
        }
        if (!summary.reply && behind.isNotEmpty()) send(peer, summaryOf(behind, reply = true))
    }

    private fun onEvents(
        peer: String,
        message: Events,
    ) {
        lastSyncAtMs = clock.nowMs()
        val matchId = message.matchId
        val valid = message.events.filter { AntiEntropy.isPlausible(it, matchId) }
        val results = data.events.appendAll(valid.map { it.toStoredEvent() })
        val inserted = valid.filterIndexed { i, _ -> results[i] == AppendResult.INSERTED }
        inserted.firstOrNull { it.payload is MatchCreated }?.let { ensureHeader(it) }
        if (inserted.isNotEmpty()) pending.getOrPut(matchId) { mutableListOf() } += inserted
        if (!message.more) flush(matchId)
        forward(peer, matchId, inserted, message.more)
    }

    private fun onMatchMeta(message: MatchMetaMessage) {
        val now = clock.nowMs()
        message.players.forEach { data.players.upsertRemote(it, now) }
        message.favorites.forEach { data.favorites.merge(it.toLocal()) }
        val header = message.match ?: return
        data.matches.insertIfAbsent(header.matchId, header.format, header.lineup, header.meta, header.createdAtMs, header.previousMatchId)
        val m = open
        if (m != null && m.id == header.matchId) data.matches.get(m.id)?.let { m.record = it }
        maybeFollow(header.matchId)
    }

    /** Wendet die gesammelten Events eines Matches an (offenes Match: Engine + Fork-Prüfung; sonst Kopf-Cache). */
    private fun flush(matchId: MatchId) {
        val batch = pending.remove(matchId).orEmpty()
        val m = open
        if (m != null && m.id == matchId) {
            if (batch.isNotEmpty()) applyRemote(m, batch)
            return
        }
        if (batch.isNotEmpty()) refreshHeader(matchId)
        maybeFollow(matchId)
    }

    private fun flushAll() {
        pending.keys.toList().forEach { flush(it) }
    }

    private fun applyRemote(
        m: OpenMatch,
        batch: List<ScoreEvent>,
    ) {
        val detected = Forks.detect(m.log(), batch)
        m.engine = ScoringEngine.applyAll(m.engine, batch)
        forks.add(m.id, detected)
        if (batch.any { Forks.isScoreEvent(it) }) m.redo.clear()
        persistProgress(m)
    }

    /** Status/Ergebnis eines nicht offenen Matches aus seinem Log neu berechnen. */
    private fun refreshHeader(matchId: MatchId) {
        val log = data.events.scoreEvents(matchId)
        if (log.none { it.payload is MatchCreated } || data.matches.get(matchId) == null) return
        val state =
            try {
                ScoringEngine.fold(log)
            } catch (e: IllegalArgumentException) {
                return
            }
        data.matches.updateProgress(matchId, state.status, ScoreSummary.of(state), clock.nowMs())
    }

    /** Ohne offenes (oder mit beendetem) Match einem neuen, laufenden Match einer Gegenstelle folgen. */
    private fun maybeFollow(matchId: MatchId) {
        if (!config.autoFollow) return
        val m = open
        if (m != null && (m.id == matchId || m.status != MatchStatus.FINISHED)) return
        val record = data.matches.get(matchId) ?: return
        if (record.isFinished) return
        if (m != null && record.createdAtMs < m.record.createdAtMs) return
        resume(matchId)
    }

    private fun ensureHeader(genesis: ScoreEvent) {
        val created = genesis.payload as MatchCreated
        data.matches.insertIfAbsent(genesis.matchId, created.config, created.lineup, created.meta, genesis.wallClockMs)
    }

    private fun peersChanged(peers: List<String>) {
        val gone = (hellos.keys + helloSent + compatibility.keys) - peers.toSet()
        gone.forEach {
            hellos.remove(it)
            compatibility.remove(it)
            helloSent.remove(it)
        }
        if (gone.isNotEmpty()) {
            flushAll()
            openForwards.entries.removeAll { it.value in gone }
        }
        if (started) peers.filter { it !in helloSent }.forEach { sendHello(it) }
        publish()
    }

    // --- Senden -----------------------------------------------------------------------------------

    private fun sendHello(peer: String) {
        helloSent += peer
        send(peer, Hello(deviceId, config.kind, listOfNotNull(open?.id)))
    }

    private fun sendEvents(
        peer: String,
        matchId: MatchId,
        events: List<ScoreEvent>,
    ) {
        val batches = AntiEntropy.batches(events)
        batches.forEachIndexed { i, batch -> send(peer, Events(matchId, batch, more = i < batches.lastIndex)) }
    }

    /** Hub-Weiterleitung (01 §4.3 Regel 4): neue Events an alle außer dem Absender. */
    private fun forward(
        from: String,
        matchId: MatchId,
        inserted: List<ScoreEvent>,
        more: Boolean,
    ) {
        val targets = targets().filter { it != from }
        // leere Schlussteile nur weiterreichen, wenn vorher Teile derselben Nachlieferung weitergereicht wurden
        val continuesForward = openForwards[matchId] == from
        if (inserted.isEmpty() && !(continuesForward && !more)) return
        if (more) openForwards[matchId] = from else openForwards.remove(matchId)
        targets.forEach { send(it, Events(matchId, inserted, more)) }
    }

    private fun broadcast(message: SyncMessage) {
        targets().forEach { send(it, message) }
    }

    private fun broadcastMeta() {
        if (targets().isNotEmpty()) broadcast(metaMessage())
    }

    private fun send(
        peer: String,
        message: SyncMessage,
    ) {
        if (!started) return
        link?.send(peer, SyncCodec.encode(message))
    }

    /** Erreichbare Gegenstellen, die nicht als inkompatibel bekannt sind. */
    private fun targets(): List<String> = link?.peers.orEmpty().filter { compatibility[it]?.isCompatible != false }

    private fun scheduleAntiEntropy() {
        timer?.cancel()
        timer =
            scheduler.postDelayed(config.antiEntropyIntervalMs) {
                timer = null
                if (!started) return@postDelayed
                val m = open
                if (m != null && m.status != MatchStatus.FINISHED) syncNow()
                scheduleAntiEntropy()
            }
    }

    // --- Nachrichten bauen ------------------------------------------------------------------------

    private fun summaryMatches(): List<MatchId> =
        (
            listOfNotNull(open?.id) +
                data.matches.recent(config.summaryMatchLimit).map {
                    it.id
                }
        ).distinct()

    private fun summaryOf(
        matches: List<MatchId>,
        reply: Boolean,
    ): Summary = Summary(matches.associate { it.value to VersionVector.fromSequences(sequences(it)) }, reply)

    private fun sequences(matchId: MatchId): List<DeviceSeq> =
        data.events.sequences(matchId.value).map { DeviceSeq(DeviceId(it.deviceId), it.deviceSeq) }

    private fun load(
        matchId: MatchId,
        ranges: List<SeqRange>,
    ): List<ScoreEvent> =
        ranges
            .flatMap { data.events.eventsInRange(matchId.value, it.deviceId.value, it.first, it.last) }
            .mapNotNull { it.toScoreEventOrNull() }
            .sortedWith(EventOrdering)

    private fun metaMessage(): MatchMetaMessage {
        val m = open
        val favorites = data.favorites.recent(limit = FAVORITES_IN_META)
        val playerIds =
            (
                m
                    ?.record
                    ?.lineup
                    ?.let { it.a + it.b }
                    .orEmpty() + favorites.flatMap { it.a + it.b }
            ).distinct()
        return MatchMetaMessage(
            match =
                m?.record?.let { MatchHeader(it.id, it.format, it.lineup, it.meta, it.createdAtMs, it.previousMatchId) },
            players = playerIds.mapNotNull { data.players.get(it)?.toPlayer() },
            favorites = favorites.map { it.toWire() },
        )
    }

    // --- Zustand ----------------------------------------------------------------------------------

    private fun publish() {
        val next = buildState()
        if (next == mutableState.value) return
        mutableState.value = next
        listeners.toList().forEach { it.onStateChanged(next) }
    }

    private fun buildState(): SessionState {
        val match = open?.let { activeMatch(it) }
        val linkState = link?.state ?: LinkState.UNAVAILABLE
        val peers =
            link?.peers.orEmpty().map { peer ->
                PeerInfo(peer, hellos[peer]?.deviceId, hellos[peer]?.kind, compatibility[peer]?.status)
            }
        val indicator =
            when {
                match != null && (match.conflicts.isNotEmpty() || match.fork != null) -> SyncIndicator.CONFLICT
                started && peers.any { it.compatibility == CompatibilityStatus.COMPATIBLE } -> SyncIndicator.CONNECTED
                else -> SyncIndicator.LOCAL_ONLY
            }
        return SessionState(match, SyncStatus(linkState, peers, indicator, lastSyncAtMs))
    }

    private fun activeMatch(m: OpenMatch): ActiveMatch {
        val engine = m.engine
        return ActiveMatch(
            id = m.id,
            format = m.record.format,
            lineup = m.record.lineup,
            meta = m.record.meta,
            createdAtMs = m.record.createdAtMs,
            previousMatchId = m.record.previousMatchId,
            state = engine.state,
            canUndo = engine.undoTarget != null,
            canRedo = validRedo(m) != null,
            conflicts = engine.conflicts.filterNot { isForkConflict(m, it) },
            absorbedTaps = engine.absorbedEvents,
            fork = forkInfo(m),
            eventCount = engine.events.size + 1,
        )
    }

    /**
     * Widerspruch zwischen den ersten Punkten zweier Zweige einer erkannten Gabelung: Den behandelt der Fork-Dialog
     * (bzw. „zusammenführen“ hat ihn bewusst bestätigt) – kein zusätzliches Konflikt-Banner.
     */
    private fun isForkConflict(
        m: OpenMatch,
        conflict: TapConflict,
    ): Boolean =
        forks.records(m.id).any { record ->
            val first = record.fork.branches.indexOfFirst { conflict.first in it.events }
            val second = record.fork.branches.indexOfFirst { conflict.second in it.events }
            first >= 0 && second >= 0 && first != second
        }

    private fun openFork(m: OpenMatch): ForkRecord? {
        val records = forks.records(m.id)
        if (records.isEmpty()) return null
        val log = m.log()
        return records.firstOrNull { !it.merged && Forks.isOpen(it.fork, log) }
    }

    private fun forkInfo(m: OpenMatch): ForkInfo? {
        val fork = openFork(m)?.fork ?: return null
        val analysis = EventLog.analyze(m.log())
        val byId = analysis.ordered.associateBy { it.id }
        return ForkInfo(
            id = fork.id,
            branches =
                fork.branches.mapIndexed { index, branch ->
                    ForkVersion(
                        devices = branch.devices,
                        kinds = branch.devices.mapNotNull { kindOf(it) },
                        includesThisDevice = deviceId in branch.devices,
                        preview = Forks.preview(m.record.format, m.record.lineup, m.engine.events, fork, index),
                        points =
                            branch.events.count { id ->
                                byId[id]?.payload is PointWon && id !in analysis.undone && id !in analysis.absorbed
                            },
                    )
                },
        )
    }

    /** Oberstes Redo-Ziel, falls dessen Undo noch wirkt und sein Ziel noch unwirksam ist. */
    private fun validRedo(m: OpenMatch): EventId? {
        val top = m.redo.lastOrNull() ?: return null
        val analysis = EventLog.analyze(m.engine.events)
        val undo = analysis.ordered.firstOrNull { it.id == top }?.payload as? Undo ?: return null
        return top.takeIf { it !in analysis.undone && undo.target in analysis.undone }
    }

    private fun kindOf(device: DeviceId): PeerKind? =
        if (device == deviceId) {
            config.kind
        } else {
            data.settings.get(peerKindKey(device))?.let { name -> PeerKind.entries.firstOrNull { it.name == name } }
        }

    private fun peerKindKey(device: DeviceId) = "session.peerKind.${device.value}"

    private companion object {
        const val FAVORITES_IN_META = 10L
    }
}

private fun FavoriteLineup.toWire(): WireFavorite = WireFavorite(id, sport, discipline, a, b, name, lastUsedAtMs, useCount)

private fun WireFavorite.toLocal(): FavoriteLineup = FavoriteLineup(id, sport, discipline, a, b, name, lastUsedAtMs, useCount)
