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
import eu.advantly.core.scoring.ScorerChanged
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.Undo
import eu.advantly.data.AppendResult
import eu.advantly.data.FavoriteLineup
import eu.advantly.data.LocalData
import eu.advantly.data.MatchRecord
import eu.advantly.data.ScoreSummary
import eu.advantly.data.StoredSequence
import eu.advantly.data.scoreEvents
import eu.advantly.data.toScoreEventOrNull
import eu.advantly.data.toStoredEvent
import eu.advantly.device.link.Cancellable
import eu.advantly.device.link.DeviceLink
import eu.advantly.device.link.DeviceLinkListener
import eu.advantly.device.link.LinkScheduler
import eu.advantly.device.link.LinkState
import eu.advantly.device.link.LinkStats
import eu.advantly.device.link.SendCallback
import eu.advantly.device.link.SendFailure
import eu.advantly.sync.protocol.AntiEntropy
import eu.advantly.sync.protocol.Compatibility
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.DecodeResult
import eu.advantly.sync.protocol.Deleted
import eu.advantly.sync.protocol.DeletedMatch
import eu.advantly.sync.protocol.DeviceSeq
import eu.advantly.sync.protocol.Events
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
 * Orchestriert ein Match auf einem Gerät nach dem **Single-Scorer-Modell** (02 §7.5, 01 §4.4): Genau ein Gerät zählt
 * (Standard: Uhr des Spielers, alternativ Director-Handy), alle anderen zeigen an und können „Zählen übernehmen“.
 *
 * - **Zähler-Tap** → Event (UUIDv7, Lamport, deviceId, deviceSeq, parentId) → [LocalData.events] (idempotent)
 *   → [ScoringEngine.applyAll] → Broadcast `Events` an alle Gegenstellen. Auf Anzeige-Geräten liefern Zähl-Kommandos
 *   `false` und schreiben nichts.
 * - **Empfang** → jedes neue Event sofort speichern und mit dem Batch der Nachricht anwenden (`applyAll`, Fold ist
 *   reihenfolgeunabhängig) → Weiterleitung an die übrigen Gegenstellen (Hub-Rolle des Handys).
 * - **Anti-Entropie** (`Hello` → `Summary` → fehlende Events) bei jeder Verbindung und alle 30 s im aktiven Match.
 *
 * Zustand: [state] (`StateFlow`) bzw. [addListener] (Callbacks, z. B. für die Swift-Facade).
 *
 * **Threading:** Alle öffentlichen Aufrufe und Callbacks laufen auf dem Thread des [scheduler]; [threadGuard] erzwingt
 * das (Android: Main-Looper, Apple: Main-Queue, Tests: `ManualScheduler`).
 *
 * @param data lokale Datenbank dieses Geräts; öffentlich, damit UI-Adapter (Historie, Spieler, Favoriten) dieselbe
 *   Instanz nutzen statt eine zweite Verbindung auf dieselbe Datei zu öffnen. Nur lesen bzw. Spieler/Favoriten/
 *   Settings schreiben – Events und Match-Köpfe ändert ausschließlich die Session. Nur auf dem Scheduler-Thread.
 * @param link Geräte-Link oder `null` (Standalone, nur lokal zählen)
 */
@Suppress("TooManyFunctions")
class MatchSession(
    val data: LocalData,
    private val link: DeviceLink?,
    private val scheduler: LinkScheduler,
    val config: SessionConfig,
    private val clock: WallClock = SystemWallClock,
    private val ids: IdGenerator = RandomIdGenerator(),
    private val threadGuard: ThreadGuard = ThreadGuard.NONE,
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
        val scorer: DeviceId? get() = engine.state.scorerDeviceId

        /** Log-Kopf = letztes Event in Event-Ordnung (01 §3.2 `parentId`). */
        fun head(): ScoreEvent = engine.events.lastOrNull()?.takeIf { EventOrdering.compare(it, genesis) > 0 } ?: genesis
    }

    private var open: OpenMatch? = null
    private val listeners = mutableListOf<SessionListener>()
    private val hellos = HashMap<String, Hello>()
    private val compatibility = HashMap<String, Compatibility>()
    private val helloSent = HashSet<String>()

    /** Besitzer-Angaben aus `MatchMeta`, die vor dem `Hello` der Gegenstelle kamen (Art noch unbekannt). */
    private val earlyOwners = HashMap<String, MatchMetaMessage>()
    private var started = false
    private var timer: Cancellable? = null
    private var lastSyncAtMs: Long? = null
    private var lastEventReceivedAtMs: Long? = null
    private var dataRevision = 0L

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

    /** Aktueller Zustand (Match, Rolle, Sync-Status). */
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    /** Zähler des Geräte-Links (Diagnose-Anzeige), `null` ohne Link. */
    val linkStats: LinkStats? get() = link?.stats

    // --- Lebenszyklus ---------------------------------------------------------------------------

    /** Startet Link und Anti-Entropie-Takt. Idempotent. */
    fun start() {
        checkThread()
        if (started) return
        started = true
        link?.setListener(linkListener)
        link?.start()
        peersChanged(link?.peers.orEmpty())
        scheduleAntiEntropy()
        publish()
    }

    /** Stoppt Link und Takt. */
    fun close() {
        checkThread()
        if (!started) return
        started = false
        timer?.cancel()
        timer = null
        link?.setListener(null)
        link?.stop()
        hellos.clear()
        compatibility.clear()
        helloSent.clear()
        earlyOwners.clear()
        publish()
    }

    /** Meldet [listener] an; er bekommt sofort den aktuellen Zustand und danach jede Änderung. */
    fun addListener(listener: SessionListener) {
        checkThread()
        listeners += listener
        listener.onStateChanged(mutableState.value)
    }

    fun removeListener(listener: SessionListener) {
        checkThread()
        listeners -= listener
    }

    // --- Match öffnen -----------------------------------------------------------------------------

    /**
     * Legt ein neues Match an (Genesis `MatchCreated` mit Zähler), öffnet es und meldet es den Gegenstellen.
     *
     * @param scorer wer zählt: dieses Gerät (Default) oder ein gekoppeltes, z. B. die Uhr des Spielers
     * @throws IllegalArgumentException bei ungültigem Format oder Lineup (siehe `MatchFormatValidator`)
     */
    fun create(
        format: MatchFormat,
        lineup: Lineup,
        meta: MatchMeta = MatchMeta(),
        scorer: ScorerChoice = ScorerChoice.ThisDevice,
    ): MatchId {
        checkThread()
        val scorerId =
            when (scorer) {
                is ScorerChoice.Device -> scorer.deviceId
                ScorerChoice.MyWatch -> myWatch()
                ScorerChoice.ThisDevice -> deviceId
            }
        return createMatch(format, lineup, meta, scorerId, previous = null)
    }

    /** Öffnet ein gespeichertes Match (Resume-Karte, App-Neustart). `false`, wenn Kopf oder Genesis fehlen. */
    fun resume(matchId: MatchId): Boolean {
        checkThread()
        return resumeMatch(matchId)
    }

    /** Schließt das offene Match in der Anzeige (es bleibt gespeichert und wird weiter synchronisiert). */
    fun closeMatch() {
        checkThread()
        open = null
        publish()
    }

    /**
     * Löscht ein Match vollständig (Kopf + Events); ein offenes wird vorher geschlossen. Die dauerhafte Löschmarkierung
     * geht sofort (und bei jedem `Hello`) an die gekoppelten Geräte; dort wird das Match ebenfalls gelöscht und taucht
     * durch keinen Abgleich wieder auf.
     */
    fun deleteMatch(matchId: MatchId) {
        checkThread()
        if (open?.id == matchId) open = null
        val now = clock.nowMs()
        data.deleteMatch(matchId, now)
        dataRevision++
        broadcast(Deleted(listOf(DeletedMatch(matchId, now))))
        publish()
    }

    /**
     * Verknüpftes Folge-Match nach einem beendeten Match (02 §5.2): gleiche Besetzung, gleiches Satzformat, Modus
     * `OPEN`, Erstaufschläger aus `state.continuation`, `previousMatchId` gesetzt, gleicher Zähler.
     * `null`, wenn kein beendetes Match offen ist.
     */
    fun createFollowUp(): MatchId? {
        checkThread()
        val m = open ?: return null
        if (m.status != MatchStatus.FINISHED) return null
        val previous = m.record
        val format = previous.format.copy(match = MatchSettings.OPEN, decidingSet = null)
        val next = m.engine.state.continuation
        val lineup = if (next != null) previous.lineup.copy(firstServer = next.player) else previous.lineup
        return createMatch(format, lineup, previous.meta, m.scorer ?: deviceId, previous = previous.id)
    }

    // --- Rolle ------------------------------------------------------------------------------------

    /**
     * Geräte, die sich irgendwann per `Hello` als [kind] gemeldet haben (gemerkt in den Settings), z. B. die eigene Uhr.
     */
    fun knownDevices(kind: PeerKind): List<DeviceId> {
        checkThread()
        return data.settings
            .all()
            .filter { (key, value) -> key.startsWith(PEER_KIND_PREFIX) && value == kind.name }
            .map { (key, _) -> DeviceId(key.removePrefix(PEER_KIND_PREFIX)) }
            .filter { it != deviceId }
    }

    /** Zähler für [ScorerChoice.MyWatch]: verbundene Uhr, sonst die einzige bekannte, sonst [PENDING_WATCH]. */
    private fun myWatch(): DeviceId {
        val connected =
            targets().mapNotNull { peer -> hellos[peer]?.takeIf { it.kind == PeerKind.WATCH }?.deviceId }.firstOrNull()
        return connected ?: knownDevices(PeerKind.WATCH).singleOrNull() ?: PENDING_WATCH
    }

    /** Erste Meldung einer Uhr: ein offenes Match mit Platzhalter-Zähler geht per `ScorerChanged` an sie. */
    private fun handOverToWaitingWatch(hello: Hello) {
        if (hello.kind != PeerKind.WATCH) return
        val m = open ?: return
        if (m.scorer != PENDING_WATCH || m.status == MatchStatus.FINISHED) return
        emit(m, listOf(ScorerChanged(hello.deviceId)))
    }

    /**
     * „Zählen übernehmen“ (02 §7.5): dieses Gerät wird Zähler (`ScorerChanged`). Die Bestätigung fragt die UI vorher ab.
     * `false`, wenn kein Match offen ist, es beendet ist oder dieses Gerät schon zählt.
     */
    fun takeOverScoring(): Boolean {
        checkThread()
        val m = open ?: return false
        if (m.status == MatchStatus.FINISHED || m.scorer == deviceId) return false
        emit(m, listOf(ScorerChanged(deviceId)))
        return true
    }

    // --- Zählen (nur der Zähler) ------------------------------------------------------------------

    /** Punkt für [side]. `false` ohne Match, auf Anzeige-Geräten oder wenn das Match gerade keinen Punkt annimmt. */
    fun recordPoint(side: Side): Boolean {
        checkThread()
        val m = scoringMatch() ?: return false
        if (m.status != MatchStatus.IN_PROGRESS && m.status != MatchStatus.PAUSED) return false
        m.redo.clear()
        emit(m, listOf(PointWon(side)))
        return true
    }

    /** UI-Undo: macht das letzte wirksame Zähl-Event rückgängig (02 §7.3). */
    fun undo(): Boolean {
        checkThread()
        val m = scoringMatch() ?: return false
        val target = m.engine.undoTarget ?: return false
        val undo = emit(m, listOf(Undo(target))).single()
        m.redo.addLast(undo.id)
        publish()
        return true
    }

    /** Redo = Undo auf das zuletzt hier erzeugte Undo (02 §7.3), solange es noch wirkt. */
    fun redo(): Boolean {
        checkThread()
        val m = scoringMatch() ?: return false
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
        checkThread()
        val m = scoringMatch() ?: return false
        if (m.status != MatchStatus.AWAITING_DECISION) return false
        if (matchTiebreak && (!anotherSet || m.record.format.matchTiebreak == null)) return false
        m.redo.clear()
        emit(m, listOf(ContinueDecision(anotherSet, if (matchTiebreak) NextSetKind.MATCH_TIEBREAK else NextSetKind.REGULAR)))
        return true
    }

    /** Korrektur von Aufschläger, Reihenfolge oder Positionen (02 §7.1/§7.4); ungültige überspringt die Engine. */
    fun correct(correction: Correction): Boolean {
        checkThread()
        val m = scoringMatch() ?: return false
        if (m.status == MatchStatus.FINISHED) return false
        m.redo.clear()
        emit(m, listOf(correction))
        return true
    }

    /** Unterbrechung (Regen, Verletzung). Lässt den Redo-Stapel unberührt. */
    fun pause(): Boolean {
        checkThread()
        val m = scoringMatch() ?: return false
        if (m.status != MatchStatus.IN_PROGRESS) return false
        emit(m, listOf(Paused))
        return true
    }

    /** Fortsetzung nach [pause]. Lässt den Redo-Stapel unberührt. */
    fun resumePlay(): Boolean {
        checkThread()
        val m = scoringMatch() ?: return false
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
        checkThread()
        val m = scoringMatch() ?: return false
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

    /** Stößt sofort einen Abgleich mit allen Gegenstellen an (z. B. App im Vordergrund). */
    fun syncNow() {
        checkThread()
        sendSummaries()
    }

    // --- Lokale Events ----------------------------------------------------------------------------

    private fun checkThread() {
        check(threadGuard.isSchedulerThread()) { "MatchSession nur auf dem Scheduler-Thread aufrufen (Main-Thread)" }
    }

    /** Offenes Match, falls dieses Gerät dort Zähler ist. */
    private fun scoringMatch(): OpenMatch? = open?.takeIf { it.scorer == null || it.scorer == deviceId }

    private fun createMatch(
        format: MatchFormat,
        lineup: Lineup,
        meta: MatchMeta,
        scorer: DeviceId,
        previous: MatchId?,
    ): MatchId {
        ScoringEngine.replay(format, lineup, emptyList()) // prüft Format und Lineup
        val now = clock.nowMs()
        val matchId = MatchId.of(ids.newUuid7(now))
        val created = MatchCreated(format, lineup, meta, scorerDeviceId = scorer)
        val genesis = ScoreEvent(EventId.of(ids.newUuid7(now)), matchId, deviceId, 1, 1, now, null, created)
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
        maybeFollow(null)
        publish()
        return events
    }

    private fun resumeMatch(matchId: MatchId): Boolean {
        if (!openMatch(matchId)) return false
        open?.let { persistProgress(it) }
        sendSummaries()
        publish()
        return true
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
        checkThread()
        val message = (SyncCodec.decode(payload) as? DecodeResult.Ok)?.message ?: return
        if (message !is Hello && compatibility[peer]?.isCompatible == false) return
        val touched =
            try {
                when (message) {
                    is Hello -> onHello(peer, message).let { null }
                    is Summary -> onSummary(peer, message).let { null }
                    is Events -> onEvents(peer, message)
                    is MatchMetaMessage -> onMatchMeta(peer, message)
                    is Deleted -> onDeleted(peer, message).let { null }
                }
            } catch (e: IllegalArgumentException) {
                // passt nicht zum lokalen Log (z. B. kollidierende Genesis-ID) → verwerfen; der Rest bleibt konsistent
                null
            }
        maybeFollow(touched)
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
        if (!result.isCompatible) {
            earlyOwners.remove(peer)
            return
        }
        // MatchMeta kam vor diesem Hello: Besitzer-Angaben jetzt mit bekannter Geräteart übernehmen
        earlyOwners.remove(peer)?.let { adoptOwnerIdentity(peer, it) }
        if (peer !in helloSent) sendHello(peer)
        handOverToWaitingWatch(hello)
        sendDeleted(peer)
        send(peer, summaryOf(summaryMatches(), reply = false))
        send(peer, metaMessage(open?.record))
    }

    private fun onSummary(
        peer: String,
        summary: Summary,
    ) {
        lastSyncAtMs = clock.nowMs()
        val behind = mutableListOf<MatchId>()
        val deleted = mutableListOf<MatchId>()
        for ((key, remote) in summary.matches) {
            val matchId = MatchId(key)
            if (data.matches.isDeleted(matchId)) {
                deleted += matchId
                continue
            }
            remote.watermarks.forEach { (device, seq) -> data.events.markConfirmedUpTo(key, device.value, seq) }
            val local = sequences(matchId)
            val ranges = AntiEntropy.missingRanges(local, remote)
            if (ranges.isNotEmpty()) sendEvents(peer, matchId, load(matchId, ranges))
            if (AntiEntropy.isBehind(VersionVector.fromSequences(local), remote)) behind += matchId
        }
        if (!summary.reply && behind.isNotEmpty()) send(peer, summaryOf(behind, reply = true))
        if (deleted.isNotEmpty()) sendDeleted(peer, deleted)
    }

    /** Löschmarkierungen einer Gegenstelle übernehmen (offenes Match schließen) und an die übrigen weiterreichen. */
    private fun onDeleted(
        peer: String,
        message: Deleted,
    ) {
        val fresh = message.matches.filter { !data.matches.isDeleted(it.matchId) }
        if (fresh.isEmpty()) return
        fresh.forEach { tombstone ->
            if (open?.id == tombstone.matchId) open = null
            data.deleteMatch(tombstone.matchId, tombstone.deletedAtMs)
        }
        dataRevision++
        val forward = Deleted(fresh)
        targets().filter { it != peer }.forEach { send(it, forward) }
    }

    /** Speichert neue Events sofort und wendet sie als Batch an (kein Puffern, `more` ist nur ein Hinweis). */
    private fun onEvents(
        peer: String,
        message: Events,
    ): MatchId? {
        lastSyncAtMs = clock.nowMs()
        val matchId = message.matchId
        if (data.matches.isDeleted(matchId)) {
            sendDeleted(peer, listOf(matchId)) // Absender soll es auch löschen, statt es immer wieder zu senden
            return null
        }
        val valid = message.events.filter { AntiEntropy.isPlausible(it, matchId) }
        // der Absender hat sie: als bestätigt speichern bzw. eigene Echos als bestätigt markieren
        val results = data.events.appendAll(valid.map { it.toStoredEvent().copy(confirmed = true) })
        data.events.markConfirmed(matchId.value, valid.map { StoredSequence(it.deviceId.value, it.deviceSeq) })
        val inserted = valid.filterIndexed { i, _ -> results[i] == AppendResult.INSERTED }
        if (inserted.isEmpty()) return matchId
        lastEventReceivedAtMs = clock.nowMs()
        inserted.firstOrNull { it.payload is MatchCreated }?.let { ensureHeader(it) }
        val m = open
        if (m != null && m.id == matchId) {
            m.engine = ScoringEngine.applyAll(m.engine, inserted)
            persistProgress(m)
        } else {
            refreshHeader(matchId)
        }
        val forward = Events(matchId, inserted, message.more)
        targets().filter { it != peer }.forEach { send(it, forward) }
        return matchId
    }

    private fun onMatchMeta(
        peer: String,
        message: MatchMetaMessage,
    ): MatchId? {
        val now = clock.nowMs()
        message.players.forEach { data.players.upsertRemote(it, now) }
        message.favorites.forEach { data.favorites.merge(it.toLocal()) }
        dataRevision++
        adoptOwnerIdentity(peer, message)
        val header = message.match ?: return null
        data.matches.insertIfAbsent(header.matchId, header.format, header.lineup, header.meta, header.createdAtMs, header.previousMatchId)
        val m = open
        if (m != null && m.id == header.matchId) data.matches.get(m.id)?.let { m.record = it }
        return header.matchId
    }

    /**
     * Identität des Besitzers über eigene Geräte (W1-I): Das Handy ist Identitätsquelle. Eine Uhr übernimmt den `self`
     * eines Handys (ihr bisheriger Self wird Alias und geht sofort zurück ans Handy); ein Handy merkt sich den `self`
     * anderer eigener Geräte als Alias. Aliase des Absenders werden immer übernommen.
     */
    private fun adoptOwnerIdentity(
        peer: String,
        message: MatchMetaMessage,
    ) {
        if (data.addSelfAliases(message.selfAliases)) dataRevision++
        val owner = message.self ?: return
        val senderKind = hellos[peer]?.kind
        if (senderKind == null) {
            // Art der Gegenstelle noch unbekannt (Hello steht aus) → puffern, im Hello verarbeiten
            earlyOwners[peer] = message
            return
        }
        when {
            config.kind != PeerKind.PHONE && senderKind == PeerKind.PHONE -> {
                if (data.adoptSelf(owner, clock.nowMs())) {
                    dataRevision++
                    send(peer, metaMessage(open?.record))
                }
            }

            config.kind == PeerKind.PHONE -> {
                if (data.addSelfAliases(listOf(owner.id))) dataRevision++
            }
        }
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
        dataRevision++
    }

    /**
     * Folgt automatisch einem neuen, laufenden Match – nach **jeder** Zustandsänderung neu geprüft (Review-Befund 5):
     * - ohne offenes Match: dem gerade berührten Match ([touched]),
     * - mit beendetem Match: dem Folge-Match (`previousMatchId`) bzw. dem jüngsten danach angelegten laufenden Match.
     * Bei mehreren Kandidaten deterministisch: Folge-Match zuerst, dann jüngstes `createdAt`, dann größte ID.
     */
    private fun maybeFollow(touched: MatchId?) {
        if (!config.autoFollow) return
        val m = open
        val candidates =
            when {
                m == null -> listOfNotNull(touched?.let { data.matches.get(it) })
                m.status == MatchStatus.FINISHED -> data.matches.active().filter { it.id != m.id && isNewer(it, m.record) }
                else -> return
            }
        val ordered =
            candidates
                .filter { !it.isFinished }
                .sortedWith(
                    compareByDescending<MatchRecord> { m != null && it.previousMatchId == m.id }
                        .thenByDescending { it.createdAtMs }
                        .thenByDescending { it.id.value },
                )
        for (record in ordered) {
            if (resumeMatch(record.id)) return
        }
    }

    private fun isNewer(
        candidate: MatchRecord,
        current: MatchRecord,
    ): Boolean =
        candidate.previousMatchId == current.id ||
            candidate.createdAtMs > current.createdAtMs ||
            (candidate.createdAtMs == current.createdAtMs && candidate.id.value > current.id.value)

    private fun ensureHeader(genesis: ScoreEvent) {
        val created = genesis.payload as MatchCreated
        if (data.matches.insertIfAbsent(genesis.matchId, created.config, created.lineup, created.meta, genesis.wallClockMs)) dataRevision++
    }

    private fun peersChanged(peers: List<String>) {
        checkThread()
        val gone = (hellos.keys + helloSent + compatibility.keys + earlyOwners.keys) - peers.toSet()
        gone.forEach {
            hellos.remove(it)
            compatibility.remove(it)
            helloSent.remove(it)
            earlyOwners.remove(it)
        }
        if (started) {
            peers.filter { it !in helloSent }.forEach { peer ->
                // sofort abgleichen (nicht erst im 30-s-Takt): Hello, eigener Stand, unbestätigte Events direkt nachschieben
                sendHello(peer)
                sendDeleted(peer)
                send(peer, summaryOf(summaryMatches(), reply = false))
                pushBacklog(peer)
            }
        }
        publish()
    }

    // --- Senden -----------------------------------------------------------------------------------

    private fun sendHello(peer: String) {
        helloSent += peer
        send(peer, Hello(deviceId, config.kind, listOfNotNull(open?.id)))
    }

    private fun sendSummaries() {
        if (started) broadcast(summaryOf(summaryMatches(), reply = false))
    }

    private fun sendEvents(
        peer: String,
        matchId: MatchId,
        events: List<ScoreEvent>,
    ) {
        val batches = AntiEntropy.batches(events)
        batches.forEachIndexed { i, batch -> send(peer, Events(matchId, batch, more = i < batches.lastIndex)) }
    }

    private fun broadcast(message: SyncMessage) {
        targets().forEach { send(it, message) }
    }

    /**
     * Gesamter Ausgangs-Rückstand (persistent, über alle Matches): je Match mit unbestätigten Events den Kopf
     * (`MatchMeta`) und die Events in Teilen – damit kommt auch ein langer Offline-Rückstand vollständig an.
     */
    private fun pushBacklog(peer: String) {
        for (key in data.events.unconfirmedMatches()) {
            val matchId = MatchId(key)
            if (data.matches.isDeleted(matchId)) continue
            data.matches.get(matchId)?.let { send(peer, metaMessage(it)) }
            sendEvents(peer, matchId, data.events.unconfirmedEvents(key).mapNotNull { it.toScoreEventOrNull() })
        }
    }

    private fun sendDeleted(
        peer: String,
        matches: List<MatchId>? = null,
    ) {
        val all = data.matches.deleted()
        val selected = if (matches == null) all else all.filter { it.matchId in matches }
        if (selected.isNotEmpty()) send(peer, Deleted(selected.map { DeletedMatch(it.matchId, it.deletedAtMs) }))
    }

    private fun broadcastMeta() {
        if (targets().isNotEmpty()) broadcast(metaMessage(open?.record))
    }

    private fun send(
        peer: String,
        message: SyncMessage,
    ) {
        if (!started) return
        val callback = (message as? Events)?.takeIf { it.events.isNotEmpty() }?.let { ackCallback(it) }
        link?.send(peer, SyncCodec.encode(message), callback)
    }

    /** Ack einer `Events`-Nachricht (ReliableDeviceLink, Scheduler-Thread) → Events gelten als bei der Gegenstelle. */
    private fun ackCallback(message: Events): SendCallback =
        object : SendCallback {
            override fun onAcked(
                messageId: String,
                attempts: Int,
                latencyMs: Long,
            ) {
                checkThread()
                data.events.markConfirmed(message.matchId.value, message.events.map { StoredSequence(it.deviceId.value, it.deviceSeq) })
                publish()
            }

            override fun onFailed(
                messageId: String,
                attempts: Int,
                reason: SendFailure,
            ) = Unit
        }

    /** Erreichbare Gegenstellen, die nicht als inkompatibel bekannt sind. */
    private fun targets(): List<String> = link?.peers.orEmpty().filter { compatibility[it]?.isCompatible != false }

    private fun scheduleAntiEntropy() {
        timer?.cancel()
        timer =
            scheduler.postDelayed(config.antiEntropyIntervalMs) {
                checkThread()
                timer = null
                if (!started) return@postDelayed
                val m = open
                val active = m != null && m.status != MatchStatus.FINISHED
                if (active || data.events.unconfirmedCount() > 0) sendSummaries()
                scheduleAntiEntropy()
            }
    }

    // --- Nachrichten bauen ------------------------------------------------------------------------

    /** Offenes Match, zuletzt geänderte und alle mit Ausgangs-Rückstand (gelöschte nie). */
    private fun summaryMatches(): List<MatchId> =
        (
            listOfNotNull(open?.id) +
                data.matches.recent(config.summaryMatchLimit).map { it.id } +
                data.events.unconfirmedMatches().map { MatchId(it) }
        ).distinct().filter { !data.matches.isDeleted(it) }

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

    private fun metaMessage(record: MatchRecord?): MatchMetaMessage {
        val favorites = data.favorites.recent(limit = FAVORITES_IN_META)
        val lineupPlayers = record?.lineup?.let { it.a + it.b }.orEmpty()
        val playerIds = (lineupPlayers + favorites.flatMap { it.a + it.b }).distinct()
        return MatchMetaMessage(
            match = record?.let { MatchHeader(it.id, it.format, it.lineup, it.meta, it.createdAtMs, it.previousMatchId) },
            players = playerIds.mapNotNull { data.players.get(it)?.toPlayer() },
            favorites = favorites.map { it.toWire() },
            self = data.players.self()?.toPlayer(),
            selfAliases = data.selfAliases().toList(),
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
        val compatiblePeers = peers.filter { it.compatibility == CompatibilityStatus.COMPATIBLE }
        val indicator =
            when {
                match != null && match.conflicts.isNotEmpty() -> SyncIndicator.CONFLICT
                started && compatiblePeers.isNotEmpty() -> SyncIndicator.CONNECTED
                else -> SyncIndicator.LOCAL_ONLY
            }
        val scorerConnected =
            match != null && (match.isScorer || (started && compatiblePeers.any { it.deviceId == match.scorerDeviceId }))
        val pending = data.events.unconfirmedCount().toInt()
        return SessionState(
            match,
            SyncStatus(linkState, peers, indicator, scorerConnected, pending, lastEventReceivedAtMs, lastSyncAtMs),
            dataRevision,
        )
    }

    private fun activeMatch(m: OpenMatch): ActiveMatch {
        val engine = m.engine
        val scorer = engine.state.scorerDeviceId
        val isScorer = scorer == null || scorer == deviceId
        return ActiveMatch(
            id = m.id,
            format = m.record.format,
            lineup = m.record.lineup,
            meta = m.record.meta,
            createdAtMs = m.record.createdAtMs,
            previousMatchId = m.record.previousMatchId,
            state = engine.state,
            pointsPlayed = engine.pointsPlayed,
            scorerDeviceId = scorer,
            scorerKind = scorer?.let { kindOf(it) },
            isScorer = isScorer,
            rejectedByRoleCount = engine.state.rejectedByRole.size,
            canUndo = isScorer && engine.undoTarget != null,
            canRedo = isScorer && validRedo(m) != null,
            conflicts = engine.conflicts,
            absorbedTaps = engine.absorbedEvents,
            eventCount = engine.events.size + 1,
        )
    }

    /** Oberstes Redo-Ziel, falls dessen Undo noch wirkt und sein Ziel noch unwirksam ist. */
    private fun validRedo(m: OpenMatch): EventId? {
        val top = m.redo.lastOrNull() ?: return null
        val analysis = EventLog.analyze(m.engine.events, m.engine.initialScorer)
        val undo = analysis.ordered.firstOrNull { it.id == top }?.payload as? Undo ?: return null
        return top.takeIf { it !in analysis.undone && it !in analysis.roleRejected && undo.target in analysis.undone }
    }

    /** Art des Geräts [device] (dieses, eine Gegenstelle aus einem `Hello` oder `null`), z. B. für „Eingaben ansehen“. */
    fun peerKind(device: DeviceId): PeerKind? {
        checkThread()
        return kindOf(device)
    }

    private fun kindOf(device: DeviceId): PeerKind? =
        if (device == deviceId) {
            config.kind
        } else {
            data.settings.get(peerKindKey(device))?.let { name -> PeerKind.entries.firstOrNull { it.name == name } }
        }

    private fun peerKindKey(device: DeviceId) = PEER_KIND_PREFIX + device.value

    companion object {
        private const val FAVORITES_IN_META = 10L
        private const val PEER_KIND_PREFIX = "session.peerKind."

        /** Platzhalter-Zähler „die eigene Uhr, sobald sie sich meldet“ ([ScorerChoice.MyWatch]); zählt selbst nie. */
        val PENDING_WATCH: DeviceId = DeviceId("pending-watch")
    }
}

private fun FavoriteLineup.toWire(): WireFavorite = WireFavorite(id, sport, discipline, a, b, name, lastUsedAtMs, useCount)

private fun WireFavorite.toLocal(): FavoriteLineup = FavoriteLineup(id, sport, discipline, a, b, name, lastUsedAtMs, useCount)
