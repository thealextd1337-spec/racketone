package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.MatchSettings
import eu.advantly.core.model.PlayerId
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
import eu.advantly.core.scoring.PointDetailRules
import eu.advantly.core.scoring.PointDetailSet
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Resumed
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScorerChanged
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.ServerChosen
import eu.advantly.core.scoring.SidePair
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
import eu.advantly.sync.protocol.WatchAssignment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import eu.advantly.sync.protocol.FavoriteLineup as WireFavorite

/**
 * Orchestriert die Matches eines Geräts nach dem **Single-Scorer-Modell** (02 §7.5, 01 §4.4): Pro Match zählt genau ein
 * Gerät (Standard: Uhr des Spielers, alternativ Director-Handy), alle anderen zeigen an und können „Zählen übernehmen“.
 *
 * **Mehrere Matches (F22, 01 §4.9):** Ein Handy ([SessionConfig.parallelMatches]) hält beliebig viele Matches offen
 * (z. B. Nachbarplätze); eines davon ist **fokussiert** (Live-Ansicht, [SessionState.match]), Zähl-Kommandos wirken auf
 * das fokussierte. Höchstens eines ist das **Uhr-Match** ([SessionState.watchMatchId]): das Match, das die eigene Uhr
 * zählt bzw. anzeigt. Die Uhr hält genau ein Match offen und folgt nur der Zuweisung des Handys (`MatchMeta.watchMatch`).
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

        /** Zuletzt gebaute UI-Sicht und ihre Eingaben (Engine/Kopf per Identität), spart Neuberechnung je `publish`. */
        var view: ActiveMatch? = null
        var viewEngine: EngineState? = null
        var viewRecord: MatchRecord? = null
        var viewRedo: List<EventId> = emptyList()
        var viewScorerKind: PeerKind? = null

        val id: MatchId get() = record.id
        val status: MatchStatus get() = engine.state.status
        val scorer: DeviceId? get() = engine.state.scorerDeviceId

        /** Log-Kopf = letztes Event in Event-Ordnung (01 §3.2 `parentId`). */
        fun head(): ScoreEvent = engine.events.lastOrNull()?.takeIf { EventOrdering.compare(it, genesis) > 0 } ?: genesis
    }

    /** Offene Matches in Öffnungsreihenfolge (Uhr: höchstens eines). */
    private val opened = LinkedHashMap<MatchId, OpenMatch>()

    /** Fokussiertes Match (Live-Ansicht); Ziel der Zähl-Kommandos. */
    private var focusedId: MatchId? = null
    private val open: OpenMatch? get() = focusedId?.let { opened[it] }

    /**
     * Handy: das Uhr-Match (zugewiesen beim Anlegen, bei einem auf der Uhr gestarteten Match oder per [assignToWatch]).
     * Uhr: die zuletzt vom Handy erhaltene Zuweisung. Persistent in den Settings (übersteht Neustarts).
     */
    private var watchAssignment: WatchAssignment? = loadAssignment()

    /** Uhr: neue Zuweisung erhalten, der noch nicht gefolgt wurde (Wechsel dann auch aus einem laufenden Match). */
    private var assignmentPending = false
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
        ScoringEngine.replay(format, lineup, emptyList()) // prüft Format und Lineup, bevor etwas geschrieben wird
        // Höchstens ein Match zählt die eigene Uhr: ihr bisheriges Match übernimmt dieses Handy (Director)
        val toWatch = isWatchDevice(scorerId)
        if (toWatch) releaseWatchMatch()
        return createMatch(format, lineup, meta, scorerId, previous = null, assignToWatch = toWatch || watchIsFree())
    }

    /**
     * Öffnet ein gespeichertes Match und fokussiert es (Resume-Karte, Wechsel der Live-Ansicht, App-Neustart). Ein schon
     * offenes Match wird nur fokussiert. `false`, wenn Kopf oder Genesis fehlen.
     */
    fun resume(matchId: MatchId): Boolean {
        checkThread()
        return resumeMatch(matchId)
    }

    /**
     * Schließt das fokussierte Match in der Anzeige (es bleibt gespeichert und wird weiter synchronisiert). Am Handy
     * rückt das jüngste noch offene Match in den Fokus (laufende vor beendeten).
     */
    fun closeMatch() {
        checkThread()
        focusedId?.let(::closeOpened)
        publish()
    }

    /** Schließt [matchId] in der Anzeige (z. B. beendetes Match in der Übersicht); No-op, wenn es nicht offen ist. */
    fun closeMatch(matchId: MatchId) {
        checkThread()
        closeOpened(matchId)
        publish()
    }

    /**
     * Handy: macht [matchId] zum Uhr-Match (F22) – die eigene Uhr zeigt es an bzw. zählt es ([watchScores] = `true`:
     * Zähler wird die eigene Uhr, wie „Meine Uhr“ beim Anlegen). Zählt die Uhr bisher ein anderes Match, übernimmt dieses
     * Handy dort (Director), damit höchstens ein Match auf der Uhr läuft. `false` auf Uhren und für unbekannte bzw.
     * beendete Matches.
     */
    fun assignToWatch(
        matchId: MatchId,
        watchScores: Boolean = false,
    ): Boolean {
        checkThread()
        if (!config.parallelMatches) return false
        val target = opened[matchId] ?: openWithoutFocus(matchId) ?: return false
        if (target.status == MatchStatus.FINISHED) return false
        if (watchAssignment?.matchId != matchId) {
            releaseWatchMatch()
            assignWatch(matchId)
            broadcastMeta(target.record)
        }
        if (watchScores && !isWatchDevice(target.scorer)) emit(target, listOf(ScorerChanged(myWatch())))
        publish()
        return true
    }

    /**
     * Löscht ein Match vollständig (Kopf + Events); ein offenes wird vorher geschlossen. Die dauerhafte Löschmarkierung
     * geht sofort (und bei jedem `Hello`) an die gekoppelten Geräte; dort wird das Match ebenfalls gelöscht und taucht
     * durch keinen Abgleich wieder auf.
     */
    fun deleteMatch(matchId: MatchId) {
        checkThread()
        closeOpened(matchId)
        forgetAssignment(matchId)
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
        // Folge-Match des Uhr-Matches bleibt auf der Uhr (gleicher Zähler)
        val onWatch = watchAssignment?.matchId == previous.id || watchIsFree()
        return createMatch(format, lineup, previous.meta, m.scorer ?: deviceId, previous = previous.id, assignToWatch = onWatch)
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

    /**
     * Erste Meldung einer Uhr: ein offenes Match mit Platzhalter-Zähler geht per `ScorerChanged` an sie – am Handy nur das
     * Uhr-Match (bzw. ohne Zuweisung jedes wartende).
     */
    private fun handOverToWaitingWatch(hello: Hello) {
        if (hello.kind != PeerKind.WATCH) return
        val assigned = watchAssignment?.matchId
        opened.values
            .filter { it.scorer == PENDING_WATCH && it.status != MatchStatus.FINISHED && (assigned == null || it.id == assigned) }
            .forEach { emit(it, listOf(ScorerChanged(hello.deviceId))) }
    }

    /**
     * „Zählen übernehmen“ (02 §7.5): dieses Gerät wird Zähler des fokussierten Matches (`ScorerChanged`). Die Bestätigung
     * fragt die UI vorher ab. `false`, wenn kein Match offen ist, es beendet ist oder dieses Gerät schon zählt.
     */
    fun takeOverScoring(): Boolean {
        checkThread()
        return takeOver(open ?: return false)
    }

    /** „Zählen übernehmen“ für ein bestimmtes offenes Match (z. B. aus der Übersicht). */
    fun takeOverScoring(matchId: MatchId): Boolean {
        checkThread()
        return takeOver(opened[matchId] ?: return false)
    }

    private fun takeOver(m: OpenMatch): Boolean {
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
     * Match-Tiebreak nach `config.matchTiebreak` (nur wenn das Format einen kennt). Neue UIs nutzen [startNextSet].
     */
    fun continueDecision(
        anotherSet: Boolean,
        matchTiebreak: Boolean = false,
    ): Boolean {
        checkThread()
        if (!anotherSet) {
            if (matchTiebreak) return false
            val m = scoringMatch() ?: return false
            if (m.status != MatchStatus.AWAITING_DECISION) return false
            m.redo.clear()
            emit(m, listOf(ContinueDecision(anotherSet = false)))
            return true
        }
        return startNextSet(if (matchTiebreak) NextSetKind.MATCH_TIEBREAK else NextSetKind.REGULAR)
    }

    /**
     * „Noch ein Satz?“ → weiter mit einem Satz der Art [kind] (02 §5.2, Entscheidung 2026-10-15): normaler Satz,
     * Tiebreak bis 7 oder Champions-Tiebreak bis 10 (Tennis/Padel, siehe `ScoringEngine.nextSetOptions`); „beenden“ ist
     * [endMatch]. Entspricht das gewählte Tiebreak-Format genau `config.matchTiebreak`, schreibt die Session die ältere
     * Wire-Form `MATCH_TIEBREAK`, damit auch Geräte mit älterem App-Stand das Event falten können.
     * `false` außerhalb von `AWAITING_DECISION`, auf Anzeige-Geräten oder für eine nicht angebotene Art.
     */
    fun startNextSet(kind: NextSetKind): Boolean {
        checkThread()
        val m = scoringMatch() ?: return false
        if (m.status != MatchStatus.AWAITING_DECISION) return false
        val decision = ScoringEngine.nextSetDecision(m.record.format, kind) ?: return false
        m.redo.clear()
        emit(m, listOf(decision))
        return true
    }

    /**
     * „Letzten Punkt von [side] abziehen“ (02 §7.3, Entscheidung 2026-10-15): `Undo` auf den letzten wirksamen Punkt
     * dieser Seite ([ScoringEngine.lastEffectivePointOf]), auch wenn danach schon Punkte der Gegenseite oder ein neuer
     * Satz gezählt wurden; der Fold rechnet alles danach neu. Das Undo kommt auf den Redo-Stapel: [redo] stellt den Punkt
     * wieder her. `false` ohne wirksamen Punkt dieser Seite oder auf Anzeige-Geräten.
     */
    fun removeLastPointOf(side: Side): Boolean {
        checkThread()
        val m = scoringMatch() ?: return false
        val target = ScoringEngine.lastEffectivePointOf(m.engine, side) ?: return false
        val undo = emit(m, listOf(Undo(target))).single()
        m.redo.addLast(undo.id)
        publish()
        return true
    }

    /**
     * Aufschlägerwahl im Doppel (02 §6.5.5, Tennis/Padel): [player] muss in `state.serverChoice.players` stehen (Game 1:
     * alle vier, Game 2: das andere Team). Erzeugt `ServerChosen` für den laufenden Satz; eine erneute Wahl im selben
     * Fenster überschreibt. Lässt den Redo-Stapel unberührt (kein UI-Undo-Ziel). `false` ohne offene Wahl, für einen nicht
     * wählbaren Spieler oder auf Anzeige-Geräten; die gleiche Wahl noch einmal ist ein No-op (`true`).
     */
    fun chooseServer(player: PlayerId): Boolean {
        checkThread()
        val m = scoringMatch() ?: return false
        val state = m.engine.state
        val choice = state.serverChoice ?: return false
        val setIndex = state.current?.setIndex ?: return false
        val side = m.record.lineup.sideOf(player) ?: return false
        if (player !in choice.players) return false
        if (choice.chosen == player) return true
        emit(m, listOf(ServerChosen(setIndex, side, player)))
        return true
    }

    /**
     * Aufschlag-Info für den Punkt [pointId] setzen, ändern oder löschen (`null`) – 02 §7.2, Entscheidung 2026-10-15.
     * Nur Tennis/Padel mit `meta.serveStats`, nur für wirksame Punkte; `DOUBLE_FAULT` nur, wenn die Rückschlagseite den
     * Punkt gewonnen hat. Übrige Detailfelder bleiben erhalten (widersprüchliche ältere Angaben `secondServe` bzw.
     * `kind = DOUBLE_FAULT` werden ersetzt). Schreibt `PointDetailSet` (letztes gilt); unverändert = No-op (`true`).
     * Lässt den Redo-Stapel unberührt.
     */
    fun setServeInfo(
        pointId: EventId,
        value: ServeOutcome?,
    ): Boolean {
        checkThread()
        val m = scoringMatch() ?: return false
        if (!serveInfoEnabled(m)) return false
        val point = m.engine.point(pointId) ?: return false
        if (value == ServeOutcome.DOUBLE_FAULT && point.side == point.servingSide) return false
        val detail = PointDetailRules.withServe(point.detail, value)
        if (detail == point.detail) return true
        emit(m, listOf(PointDetailSet(pointId, detail)))
        return true
    }

    private fun serveInfoEnabled(m: OpenMatch): Boolean = m.record.format.sport.isTennisLike && m.record.meta.serveStats

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
     * Beendet das fokussierte Match: im Satzende-Dialog (`AWAITING_DECISION`) regulär über `ContinueDecision(false)`,
     * sonst vorzeitig über `MatchEnded(reason, side)`; [side] = aufgebende bzw. nicht angetretene Side (Pflicht bei
     * `RETIRED` und `WALKOVER`). `STOPPED` = „Beenden, wie es steht“ (02 §9.27), `ABANDONED` = Abbruch ohne Wertung.
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
                side == null && (reason == EndReason.RETIRED || reason == EndReason.WALKOVER) -> return false
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
        assignToWatch: Boolean,
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
        openMatch(matchId, focus = true)
        // Ein Folge-Match ersetzt sein beendetes Vor-Match in der Liste (wie auf der Uhr)
        if (previous != null) opened.remove(previous)
        // Zuweisung vor dem Kopf speichern: die Uhr erfährt so im selben MatchMeta, dass sie diesem Match folgen soll
        if (assignToWatch && config.parallelMatches) assignWatch(matchId)
        broadcastMeta(opened[matchId]?.record)
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

    private fun resumeMatch(
        matchId: MatchId,
        focus: Boolean = true,
    ): Boolean {
        if (opened.containsKey(matchId)) {
            if (focus) focusedId = matchId
            publish()
            return true
        }
        if (!openMatch(matchId, focus)) return false
        opened[matchId]?.let { persistProgress(it) }
        sendSummaries()
        publish()
        return true
    }

    /** Öffnet [matchId] (Handy: zusätzlich, Uhr: ersetzt das bisherige) und fokussiert es bei [focus]. */
    private fun openMatch(
        matchId: MatchId,
        focus: Boolean,
    ): Boolean {
        if (opened.containsKey(matchId)) {
            if (focus || focusedId == null) focusedId = matchId
            return true
        }
        val record = data.matches.get(matchId) ?: return false
        val log = data.events.scoreEvents(matchId)
        if (log.none { it.payload is MatchCreated }) return false
        val engine =
            try {
                ScoringEngine.replay(log)
            } catch (e: IllegalArgumentException) {
                return false
            }
        if (!config.parallelMatches) opened.clear()
        opened[matchId] = OpenMatch(record, checkNotNull(engine.genesis), engine)
        if (focus || focusedId == null || !config.parallelMatches) focusedId = matchId
        return true
    }

    /** Handy: öffnet [matchId] im Hintergrund (ohne Fokuswechsel), z. B. für die Übergabe des Uhr-Matches. */
    private fun openWithoutFocus(matchId: MatchId): OpenMatch? {
        if (!config.parallelMatches) return null
        if (!openMatch(matchId, focus = false)) return null
        return opened[matchId]?.also { persistProgress(it) }
    }

    /** Schließt [matchId]; war es fokussiert, rückt am Handy das jüngste offene Match nach (laufende zuerst). */
    private fun closeOpened(matchId: MatchId) {
        opened.remove(matchId) ?: return
        if (focusedId != matchId) return
        focusedId =
            opened.values
                .maxWithOrNull(
                    compareBy<OpenMatch> { it.status != MatchStatus.FINISHED }
                        .thenBy { it.record.createdAtMs }
                        .thenBy { it.id.value },
                )?.id
    }

    // --- Uhr-Match (F22, 01 §4.9) -----------------------------------------------------------------

    private fun isWatchDevice(device: DeviceId?): Boolean =
        device != null && device != deviceId && (device == PENDING_WATCH || kindOf(device) == PeerKind.WATCH)

    /** Handy: kein laufendes Uhr-Match (nie zugewiesen, gelöscht oder beendet). */
    private fun watchIsFree(): Boolean {
        if (!config.parallelMatches) return false
        val id = watchAssignment?.matchId ?: return true
        val status = opened[id]?.status ?: data.matches.get(id)?.status ?: return true
        return status == MatchStatus.FINISHED
    }

    /**
     * Handy: Die eigene Uhr verlässt ihr bisheriges Match – zählt sie dort (oder der Platzhalter), übernimmt dieses Handy
     * per `ScorerChanged` (Director). So zählt die Uhr nie zwei Matches; ihre verspäteten Eingaben dort verwirft die
     * Rollenprüfung (02 §7.5 Nr. 4).
     */
    private fun releaseWatchMatch() {
        if (!config.parallelMatches) return
        val id = watchAssignment?.matchId ?: return
        val m = opened[id] ?: openWithoutFocus(id) ?: return
        if (m.status != MatchStatus.FINISHED && isWatchDevice(m.scorer)) emit(m, listOf(ScorerChanged(deviceId)))
    }

    /** Handy: neue Zuweisung (Zeitstempel streng steigend, damit die Uhr sie ordnen kann) speichern. */
    private fun assignWatch(matchId: MatchId?) {
        val current = watchAssignment
        if (current != null && current.matchId == matchId) return
        val at = maxOf(clock.nowMs(), (current?.assignedAtMs ?: 0) + 1)
        storeAssignment(WatchAssignment(matchId, at))
    }

    /** Gelöschtes Match: am Handy ist die Uhr danach frei; die Uhr behält ihre Zuweisung (gelöschten folgt sie nie). */
    private fun forgetAssignment(matchId: MatchId) {
        if (config.parallelMatches && watchAssignment?.matchId == matchId) assignWatch(null)
    }

    /**
     * Handy: Ein auf der Uhr gestartetes (oder von ihr gezähltes) neues, laufendes Match wird das Uhr-Match, sofern es
     * nicht älter ist als ein noch laufendes bisheriges. Alte Matches aus einem Offline-Rückstand ändern nichts.
     */
    private fun adoptWatchStartedMatch(genesis: ScoreEvent) {
        if (!config.parallelMatches) return
        val created = genesis.payload as? MatchCreated ?: return
        val scorer = created.scorerDeviceId ?: genesis.deviceId
        if (!isWatchDevice(genesis.deviceId) && !isWatchDevice(scorer)) return
        val record = data.matches.get(genesis.matchId) ?: return
        if (record.isFinished || watchAssignment?.matchId == record.id) return
        val current = watchAssignment?.matchId?.let { opened[it]?.record ?: data.matches.get(it) }
        if (current != null && !current.isFinished && record.createdAtMs < current.createdAtMs) return
        assignWatch(record.id)
        broadcastMeta(record)
    }

    /** Uhr: Zuweisung eines Handys übernehmen, wenn sie jünger ist als die bekannte. */
    private fun adoptAssignment(
        peer: String,
        assignment: WatchAssignment,
    ) {
        if (config.parallelMatches || hellos[peer]?.kind == PeerKind.WATCH) return
        val current = watchAssignment
        if (current != null && assignment.assignedAtMs <= current.assignedAtMs) return
        storeAssignment(assignment)
        assignmentPending = assignment.matchId != null && assignment.matchId != focusedId
    }

    private fun storeAssignment(assignment: WatchAssignment) {
        watchAssignment = assignment
        data.settings.put(WATCH_MATCH_KEY, assignment.matchId?.value.orEmpty())
        data.settings.putLong(WATCH_MATCH_AT_KEY, assignment.assignedAtMs)
    }

    /**
     * Gespeicherte Zuweisung. Migration (Handy ohne gespeicherte Zuweisung, Stand vor F22): Das zuletzt geänderte laufende
     * Match war das einzige offene und damit das der Uhr – es wird Uhr-Match (Zeitstempel 0, jede echte Zuweisung ist
     * jünger). Uhren ohne Zuweisung folgen bis zur ersten Zuweisung den bisherigen Auto-Follow-Regeln.
     */
    private fun loadAssignment(): WatchAssignment? {
        val stored = data.settings.get(WATCH_MATCH_KEY)
        if (stored != null) {
            val at = data.settings.getLong(WATCH_MATCH_AT_KEY, 0)
            return WatchAssignment(stored.takeIf { it.isNotEmpty() }?.let(::MatchId), at)
        }
        if (!config.parallelMatches) return null
        val legacy = data.matches.active().firstOrNull() ?: return null
        return WatchAssignment(legacy.id, 0).also(::storeAssignment)
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
        send(peer, metaMessage(primaryRecord()))
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
            closeOpened(tombstone.matchId)
            forgetAssignment(tombstone.matchId)
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
        val genesis = inserted.firstOrNull { it.payload is MatchCreated }
        genesis?.let { ensureHeader(it) }
        val m = opened[matchId]
        if (m != null) {
            m.engine = ScoringEngine.applyAll(m.engine, inserted)
            persistProgress(m)
            if (m.id != focusedId) dataRevision++
        } else {
            refreshHeader(matchId)
        }
        genesis?.let { adoptWatchStartedMatch(it) }
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
        message.watchMatch?.let { adoptAssignment(peer, it) }
        val header = message.match ?: return message.watchMatch?.matchId
        data.matches.insertIfAbsent(header.matchId, header.format, header.lineup, header.meta, header.createdAtMs, header.previousMatchId)
        opened[header.matchId]?.let { m -> data.matches.get(m.id)?.let { m.record = it } }
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
                    send(peer, metaMessage(primaryRecord()))
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
     * Auto-Follow – nach **jeder** Zustandsänderung neu geprüft (Review-Befund 5):
     * - **Handy** (parallele Matches): ein empfangenes, laufendes, höchstens [AUTO_OPEN_WINDOW_MS] altes Match kommt in die
     *   Liste der offenen Matches; fokussiert wird es nur, wenn kein laufendes Match im Fokus ist. Ein Folge-Match ersetzt
     *   sein beendetes, fokussiertes Vor-Match.
     * - **Uhr mit Zuweisung** (F22): nur dem Uhr-Match des Handys ([followAssignment]).
     * - **Uhr ohne Zuweisung** (älterer Stand): wie bisher dem gerade berührten Match bzw. nach Matchende dem Folge-Match
     *   oder dem jüngsten danach angelegten laufenden Match ([followLegacy]).
     */
    private fun maybeFollow(touched: MatchId?) {
        if (!config.autoFollow) return
        when {
            config.parallelMatches -> followOnPhone(touched)
            watchAssignment != null -> followAssignment()
            else -> followLegacy(touched)
        }
    }

    private fun followOnPhone(touched: MatchId?) {
        touched?.let(::openReceived)
        focusFollowUp()
    }

    /** Handy: empfangenes laufendes Match öffnen; fokussiert nur, wenn kein laufendes Match im Fokus ist. */
    private fun openReceived(id: MatchId) {
        if (opened.containsKey(id) || data.matches.isDeleted(id)) return
        val record = data.matches.get(id) ?: return
        if (record.isFinished || clock.nowMs() - record.createdAtMs > AUTO_OPEN_WINDOW_MS) return
        val current = open
        resumeMatch(id, focus = current == null || current.status == MatchStatus.FINISHED)
    }

    /**
     * Handy: Ist das fokussierte Match beendet und läuft schon sein Folge-Match (02 §5.2, z. B. „Noch einer?“ auf der Uhr),
     * rückt das Folge-Match in den Fokus und ersetzt das beendete – auch wenn der Abschluss erst danach ankam.
     */
    private fun focusFollowUp() {
        val current = open ?: return
        if (current.status != MatchStatus.FINISHED) return
        val next =
            opened.values
                .filter { it.record.previousMatchId == current.id && it.status != MatchStatus.FINISHED }
                .maxWithOrNull(compareBy<OpenMatch> { it.record.createdAtMs }.thenBy { it.id.value }) ?: return
        focusedId = next.id
        opened.remove(current.id)
    }

    /**
     * Uhr: dem zugewiesenen Match folgen. Eine **neue** Zuweisung wechselt sofort (auch aus einem laufenden Match – das
     * Handy hat es vorher übernommen); sonst nur, wenn hier nichts Laufendes offen ist und das Uhr-Match noch läuft (ein
     * beendetes, schon geschlossenes Uhr-Match öffnet sich nicht wieder).
     */
    private fun followAssignment() {
        val target = watchAssignment?.matchId ?: return
        val current = open
        if (current?.id == target || data.matches.isDeleted(target)) {
            assignmentPending = false
            return
        }
        val record = data.matches.get(target) ?: return
        val free = current == null || current.status == MatchStatus.FINISHED
        if (!assignmentPending && (!free || record.isFinished)) return
        if (resumeMatch(target)) assignmentPending = false
    }

    /** Auto-Follow vor F22 (Uhr ohne Zuweisung eines Handys). */
    private fun followLegacy(touched: MatchId?) {
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
        send(peer, Hello(deviceId, config.kind, opened.keys.toList()))
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

    private fun broadcastMeta(record: MatchRecord?) {
        if (targets().isNotEmpty()) broadcast(metaMessage(record))
    }

    /** Kopf für `MatchMeta` beim Verbinden: am Handy das Uhr-Match, sonst das fokussierte. */
    private fun primaryRecord(): MatchRecord? {
        val watchMatch = watchAssignment?.matchId?.takeIf { config.parallelMatches }
        return watchMatch?.let { opened[it]?.record ?: data.matches.get(it) } ?: open?.record
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
                val active = opened.values.any { it.status != MatchStatus.FINISHED }
                if (active || data.events.unconfirmedCount() > 0) sendSummaries()
                scheduleAntiEntropy()
            }
    }

    // --- Nachrichten bauen ------------------------------------------------------------------------

    /** Offene Matches, zuletzt geänderte und alle mit Ausgangs-Rückstand (gelöschte nie). */
    private fun summaryMatches(): List<MatchId> =
        (
            opened.keys.toList() +
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
            watchMatch = watchAssignment?.takeIf { config.parallelMatches },
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
        // stabil nach Erstellzeit: gleiche Millisekunde behält die Öffnungsreihenfolge
        val matches = opened.values.sortedBy { it.record.createdAtMs }.map { activeMatch(it) }
        val match = matches.firstOrNull { it.id == focusedId }
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
            matches,
            watchAssignment?.matchId?.takeIf { config.parallelMatches },
        )
    }

    private fun activeMatch(m: OpenMatch): ActiveMatch {
        val engine = m.engine
        val scorerKind = engine.state.scorerDeviceId?.let { kindOf(it) }
        val redo = m.redo.toList()
        val cached = m.view
        val unchanged =
            m.viewEngine === engine && m.viewRecord === m.record && m.viewRedo == redo && m.viewScorerKind == scorerKind
        if (cached != null && unchanged) return cached
        return buildActiveMatch(m, scorerKind).also {
            m.view = it
            m.viewEngine = engine
            m.viewRecord = m.record
            m.viewRedo = redo
            m.viewScorerKind = scorerKind
        }
    }

    private fun buildActiveMatch(
        m: OpenMatch,
        scorerKind: PeerKind?,
    ): ActiveMatch {
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
            scorerKind = scorerKind,
            isScorer = isScorer,
            rejectedByRoleCount = engine.state.rejectedByRole.size,
            canUndo = isScorer && engine.undoTarget != null,
            canRedo = isScorer && validRedo(m) != null,
            conflicts = engine.conflicts,
            absorbedTaps = engine.absorbedEvents,
            eventCount = engine.events.size + 1,
            lastPoint =
                engine.lastPoint?.let {
                    LastPoint(it.id, it.side, it.servingSide, it.server, it.detail?.serve, doubleFaultAllowed = it.side != it.servingSide)
                },
            serveInfoEnabled = serveInfoEnabled(m),
            canRemovePoint = SidePair.of { isScorer && ScoringEngine.lastEffectivePointOf(engine, it) != null },
            nextSetOptions =
                if (engine.state.status == MatchStatus.AWAITING_DECISION) ScoringEngine.nextSetOptions(m.record.format) else emptyList(),
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

        /** Settings: Uhr-Match (Handy) bzw. erhaltene Zuweisung (Uhr), leer = keins; dazu ihr Zeitstempel. */
        private const val WATCH_MATCH_KEY = "session.watchMatch"
        private const val WATCH_MATCH_AT_KEY = "session.watchMatchAt"

        /** Handy: empfangene laufende Matches öffnen sich nur, wenn sie höchstens so alt sind (keine Altlasten). */
        const val AUTO_OPEN_WINDOW_MS: Long = 24L * 60 * 60 * 1000

        /** Platzhalter-Zähler „die eigene Uhr, sobald sie sich meldet“ ([ScorerChoice.MyWatch]); zählt selbst nie. */
        val PENDING_WATCH: DeviceId = DeviceId("pending-watch")
    }
}

private fun FavoriteLineup.toWire(): WireFavorite = WireFavorite(id, sport, discipline, a, b, name, lastUsedAtMs, useCount)

private fun WireFavorite.toLocal(): FavoriteLineup = FavoriteLineup(id, sport, discipline, a, b, name, lastUsedAtMs, useCount)
