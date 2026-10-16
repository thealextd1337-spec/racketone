package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.MatchSettings
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.TelemetryPreference
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.scoring.ContinueDecision
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.CorrectionKind
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
import eu.advantly.core.scoring.PlayerAssigned
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
import eu.advantly.data.PlayerKind
import eu.advantly.data.ScoreSummary
import eu.advantly.data.StoredSequence
import eu.advantly.data.assignments
import eu.advantly.data.freshPlaceholders
import eu.advantly.data.recentPeople
import eu.advantly.data.resolvedLineup
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
import eu.advantly.sync.protocol.TelemetrySetting
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

    /** Je Match die Geräte mit Events (Replikations-Routing Handy → Uhr), lazy aus dem Store, beim Speichern ergänzt. */
    private val devicesInMatch = HashMap<MatchId, MutableSet<String>>()

    /** Art bekannter Geräte aus früheren `Hello` (persistent), für Replikationsziele und den Rückstand. */
    private var knownKindsCache: Map<DeviceId, PeerKind>? = null

    /**
     * Anzuzeigender Hinweis: eigene Eingaben wurden nach einer Übernahme verworfen (Uhr). Persistent, bis die UI ihn mit
     * [acknowledgeDroppedInputs] quittiert (übersteht Neustarts).
     */
    private var droppedInputs: DroppedInputs? = loadDroppedNotice()

    /** Telemetrie-Sichtbarkeit des Besitzers (P2-J), persistent in den Settings; Abgleich über `MatchMeta.telemetry`. */
    private var telemetry: TelemetryPreference = loadTelemetry()

    /**
     * Matches, die nachweislich noch nie an eine Uhr gesendet wurden (Cache der Settings `session.neverSentToWatch.*`).
     * Konservativ (Review W3-M R3 #1): Nur hier neu angelegte Matches tragen den Merker; Bestandsdaten von vor dem Update
     * gelten damit als „möglicherweise gesendet“ und werden nie ohne Rückfrage übernommen.
     */
    private val neverSentCache = HashMap<MatchId, Boolean>()
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
        data.migrateWatchPlaceholders()
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
     * Zählt künftig die eigene Uhr ([scorer] = Uhr), wird das neue Match ihr Uhr-Match. Ihr bisheriges laufendes Match gibt
     * die Uhr selbst ans Handy ab, sobald sie die Zuweisung erhält (nach ihrer letzten Eingabe, verlustfrei). Ist sie nicht
     * erreichbar, aber hat dort selbst gezählt ([watchMatchNeedingTakeOver]), braucht der Wechsel die Bestätigung
     * [takeOverWatchMatch]: Das Handy übernimmt dann sofort, spätere Uhr-Eingaben dort verwirft die Rollenprüfung.
     *
     * @param scorer wer zählt: dieses Gerät (Default) oder ein gekoppeltes, z. B. die Uhr des Spielers
     * @throws IllegalArgumentException bei ungültigem Format oder Lineup (siehe `MatchFormatValidator`)
     * @throws IllegalStateException wenn die Übernahme eines Uhr-Matches bestätigt werden muss und [takeOverWatchMatch]
     *   fehlt
     */
    fun create(
        format: MatchFormat,
        lineup: Lineup,
        meta: MatchMeta = MatchMeta(),
        scorer: ScorerChoice = ScorerChoice.ThisDevice,
        takeOverWatchMatch: Boolean = false,
    ): MatchId {
        checkThread()
        val scorerId =
            when (scorer) {
                is ScorerChoice.Device -> scorer.deviceId
                ScorerChoice.MyWatch -> myWatch()
                ScorerChoice.ThisDevice -> deviceId
            }
        ScoringEngine.replay(format, lineup, emptyList()) // prüft Format und Lineup, bevor etwas geschrieben wird
        // Höchstens ein Match zählt die eigene Uhr: ihr bisheriges Match geht geordnet ans Handy (Director)
        val toWatch = isWatchDevice(scorerId)
        if (toWatch) {
            check(takeOverWatchMatch || watchMatchNeedingTakeOver() == null) { "Uhr nicht erreichbar: Übernahme ihres Matches bestätigen" }
            releaseWatchMatch(force = takeOverWatchMatch)
        }
        return createMatch(format, lineup, meta, scorerId, previous = null, assignToWatch = toWatch || watchIsFree())
    }

    /**
     * Handy: das laufende Uhr-Match, in dem die eigene Uhr selbst zählt, während sie **nicht erreichbar** ist. Ein Wechsel
     * des Uhr-Matches braucht dann die ausdrückliche Bestätigung „Das Handy übernimmt …, spätere Uhr-Eingaben werden
     * verworfen“ (`takeOverWatchMatch`). `null` = Wechsel ohne Rückfrage möglich (Uhr frei, erreichbar oder hat dort nie
     * gezählt).
     */
    fun watchMatchNeedingTakeOver(): MatchId? {
        checkThread()
        if (!config.parallelMatches) return null
        val id = watchAssignment?.matchId ?: return null
        val m = opened[id] ?: openWithoutFocus(id) ?: return null
        val scorer = m.scorer ?: return null
        val waits = m.status != MatchStatus.FINISHED && isWatchDevice(scorer) && watchMayHaveScored(m) && !reachable(scorer)
        return id.takeIf { waits }
    }

    /**
     * Uhr: Hinweis „N Eingaben nicht übernommen“ ([SessionState.droppedInputs]) wurde angezeigt – erst danach gilt er als
     * gesehen (bis dahin übersteht er Neustarts, Review W3-M R2 #4).
     */
    fun acknowledgeDroppedInputs() {
        checkThread()
        if (droppedInputs == null) return
        droppedInputs = null
        data.settings.remove(DROPPED_NOTICE_KEY)
        publish()
    }

    // --- Telemetrie-Sichtbarkeit (P2-J, F19/F29) -----------------------------------------------------------------

    /**
     * Nutzer ändert die Telemetrie-Sichtbarkeit auf diesem Gerät (Handy oder Uhr). Wird gespeichert, sofort an alle
     * verbundenen eigenen Geräte gesendet und sonst beim nächsten `Hello` abgeglichen. Konfliktregel kausal über einen
     * Versionsvektor ([TelemetryPreference.merge]): eine Änderung, die die andere schon kannte, gilt; nebenläufige
     * Änderungen (beide seit dem letzten Abgleich) → restriktiver Wert. Die Wirkung („Gar nicht“ stoppt Anzeige und
     * Übertragung) setzen die Apps aus [SessionState.telemetry] um.
     */
    fun setTelemetryVisibility(visibility: TelemetryVisibility) {
        checkThread()
        if (visibility == telemetry.visibility) return
        adoptTelemetry(telemetry.changedBy(deviceId.value, visibility))
    }

    /**
     * Schnittstelle für einen späteren Profil-Abgleich (P2-E, `profile_settings.telemetry_visibility`): [remote] wird nach
     * derselben Konfliktregel übernommen wie eine Angabe der eigenen Geräte (der Server muss dafür den Versionsvektor
     * mitführen; ohne ihn wäre jede Server-Angabe nebenläufig → restriktiver Wert). `true`, wenn sich der Wert geändert hat.
     */
    fun mergeTelemetryPreference(remote: TelemetryPreference): Boolean {
        checkThread()
        return adoptTelemetry(telemetry.merge(remote))
    }

    /**
     * Speichert [next] als **einen** Settings-Eintrag (Wert und Versionsangabe gemeinsam, ein Schreibvorgang = atomar) und
     * übernimmt ihn erst danach in den RAM-Zustand. Scheitert das Schreiben, bleibt alles beim alten Stand (nichts
     * gesendet, nichts veröffentlicht) und die Exception geht an den Aufrufer.
     */
    private fun adoptTelemetry(next: TelemetryPreference): Boolean {
        if (next == telemetry) return false
        val setting = checkNotNull(TelemetrySetting.of(next)) { "Telemetrie-Änderung ohne Versionsangabe" }
        data.settings.put(TELEMETRY_KEY, SyncCodec.json.encodeToString(TelemetrySetting.serializer(), setting))
        telemetry = next
        broadcast(MatchMetaMessage(telemetry = setting))
        publish()
        return true
    }

    /** Gespeicherte Einstellung; fehlt sie oder ist sie unlesbar, gilt der Standard (nie ein halber Stand). */
    private fun loadTelemetry(): TelemetryPreference {
        val stored = data.settings.get(TELEMETRY_KEY) ?: return TelemetryPreference()
        val setting =
            try {
                SyncCodec.json.decodeFromString(TelemetrySetting.serializer(), stored)
            } catch (e: IllegalArgumentException) {
                null
            }
        return setting?.toPreference() ?: TelemetryPreference()
    }

    private fun loadDroppedNotice(): DroppedInputs? {
        val stored = data.settings.get(DROPPED_NOTICE_KEY) ?: return null
        val id = stored.substringBefore('|').takeIf { it.isNotEmpty() } ?: return null
        val count = stored.substringAfter('|').toIntOrNull() ?: return null
        return DroppedInputs(MatchId(id), count)
    }

    /**
     * Handy: öffnet [matchId] in der Liste der offenen Matches, ohne den Fokus zu ändern (z. B. ältere laufende Matches
     * nach einem Neustart – erreichbar, aber nicht automatisch im Vordergrund). `false`, wenn Kopf oder Genesis fehlen.
     */
    fun openInBackground(matchId: MatchId): Boolean {
        checkThread()
        if (!config.parallelMatches) return false
        val ok = openWithoutFocus(matchId) != null
        publish()
        return ok
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
     * Zähler wird die eigene Uhr, wie „Meine Uhr“ beim Anlegen). Ihr bisheriges laufendes Match gibt die Uhr geordnet ans
     * Handy ab (wie bei [create]). `false` auf Uhren, für unbekannte bzw. beendete Matches und wenn die Übernahme des
     * bisherigen Uhr-Matches bestätigt werden muss ([watchMatchNeedingTakeOver], [takeOverWatchMatch]).
     */
    fun assignToWatch(
        matchId: MatchId,
        watchScores: Boolean = false,
        takeOverWatchMatch: Boolean = false,
    ): Boolean {
        checkThread()
        if (!config.parallelMatches) return false
        val target = opened[matchId] ?: openWithoutFocus(matchId) ?: return false
        if (target.status == MatchStatus.FINISHED) return false
        if (watchAssignment?.matchId != matchId) {
            if (!takeOverWatchMatch && watchMatchNeedingTakeOver() != null) return false
            releaseWatchMatch(force = takeOverWatchMatch)
            assignWatch(matchId, explicit = true)
            broadcastMeta(target.record)
            // bisher nicht an die Uhr repliziert: das ganze Log jetzt nachschieben (idempotent beim Empfänger)
            targets().forEach { sendEvents(it, matchId, data.events.scoreEvents(matchId)) }
            reconcileWatchScorers()
        } else {
            // dasselbe Ziel ausdrücklich bestätigt (z. B. war es nur implizit zugewiesen): neue Generation, damit die Uhr
            // es auch gegen ein inzwischen offline gestartetes eigenes Match annimmt (Review W3-M R3 #2)
            assignWatch(matchId, explicit = true)
            broadcastMeta(target.record)
            reconcileWatchScorers()
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
        devicesInMatch.remove(matchId)
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
        val resolved = data.resolvedLineup(previous)
        val nextPlayer = next?.player?.let { data.assignments(previous.id)[it]?.id ?: it }
        val lineup = if (nextPlayer != null) resolved.copy(firstServer = nextPlayer) else resolved
        val fresh = data.freshPlaceholders(lineup, replaceArchived = false) { PlayerId(ids.newUuid7(clock.nowMs()).value) }
        // Folge-Match des Uhr-Matches bleibt auf der Uhr (gleicher Zähler)
        val onWatch = watchAssignment?.matchId == previous.id || watchIsFree()
        return createMatch(format, fresh, previous.meta, m.scorer ?: deviceId, previous = previous.id, assignToWatch = onWatch)
    }

    /** Erstellerprüfung unabhängig von Zähler und Matchstatus; P1: eigene Geräte desselben Besitzers. */
    fun canAssignPlayer(matchId: MatchId): Boolean {
        checkThread()
        val creator = data.matchCreator(matchId) ?: return false
        return data.isSelf(creator)
    }

    fun assignmentFailure(
        matchId: MatchId,
        slot: PlayerId,
        playerId: PlayerId?,
    ): PlayerAssignmentFailure? {
        checkThread()
        val record = data.matches.get(matchId) ?: return PlayerAssignmentFailure.MATCH_UNAVAILABLE
        if (!canAssignPlayer(matchId)) return PlayerAssignmentFailure.NOT_OWNER
        if (!data.isMatchPlaceholder(matchId, slot) || record.lineup.sideOf(slot) == null) return PlayerAssignmentFailure.INVALID_SLOT
        val assigned = data.assignments(matchId)
        if (assigned[slot]?.id == playerId || playerId == null) return null
        val player = data.players.get(playerId) ?: return PlayerAssignmentFailure.INVALID_PLAYER
        if (data.isPlaceholder(playerId) || player.displayName.isBlank()) return PlayerAssignmentFailure.INVALID_PLAYER
        val occupied = data.resolvedLineup(record).let { it.a + it.b } - listOfNotNull(assigned[slot]?.id)
        if (occupied.any { it == playerId || (data.isSelf(it) && data.isSelf(playerId)) }) return PlayerAssignmentFailure.OCCUPIED
        return null
    }

    /** Letzte gültige Zuweisung gewinnt; null gibt den Platz frei. Identische Wiederholung ist ein No-op. */
    fun assignPlayer(
        matchId: MatchId,
        slot: PlayerId,
        playerId: PlayerId?,
    ): Boolean {
        checkThread()
        if (assignmentFailure(matchId, slot, playerId) != null) return false
        val assigned = data.assignments(matchId)
        if (assigned[slot]?.id == playerId) return true
        val player = playerId?.let { data.players.get(it)?.toPlayer() ?: return false }
        // F22: jedes offene Match (nicht nur das fokussierte), sonst eine Arbeitskopie aus dem Log
        val m = opened[matchId] ?: detached(matchId) ?: return false
        val actor = data.players.ensureSelf("", clock.nowMs()) { PlayerId(ids.newUuid7(clock.nowMs()).value) }.id
        val creator = data.matchCreator(matchId)
        emit(
            m,
            listOf(
                PlayerAssigned(
                    slot,
                    player,
                    actor,
                    legacySlot = !Placeholders.isPlaceholder(slot),
                    creatorAlias = creator?.takeIf { it != actor && data.isSelf(it) },
                ),
            ),
        )
        return true
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
        val expected = deriveServerChoiceContext(m.id, m.engine.state) ?: return false
        return chooseServer(player, expected)
    }

    /** Sichere Sheet-Variante: [expected] muss noch zum aktuellen Wahlfenster passen (kein Wire-Feld). */
    fun chooseServer(
        player: PlayerId,
        expected: ServerChoiceContext,
    ): Boolean {
        checkThread()
        val m = scoringMatch() ?: return false
        val context = deriveServerChoiceContext(m.id, m.engine.state) ?: return false
        if (!context.accepts(expected, player)) return false
        val side = m.record.lineup.sideOf(player) ?: return false
        if (context.chosen == player) return true
        emit(m, listOf(ServerChosen(context.setIndex, side, player)))
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
        if (correction.kind == CorrectionKind.INITIAL_SERVER && !canCorrectServerRetroactively(m.engine)) return false
        m.redo.clear()
        emit(m, listOf(correction))
        return true
    }

    /** Geprüfte Aufschlägerkorrektur; rückwirkend nur ohne übersteuernde eröffnende Wahl. Sonst „ab jetzt“ wählen. */
    fun correctServer(
        player: PlayerId,
        retroactive: Boolean,
    ): Boolean {
        checkThread()
        val m = scoringMatch() ?: return false
        if (m.record.lineup.sideOf(player) == null || m.status == MatchStatus.FINISHED) return false
        if (!retroactive && m.status != MatchStatus.IN_PROGRESS && m.status != MatchStatus.PAUSED) return false
        return correct(if (retroactive) Correction.initialServer(player) else Correction.server(player))
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
     * Beendet das fokussierte Match über `MatchEnded(reason, side)`; [side] = aufgebende bzw. nicht angetretene Side
     * (Pflicht bei `RETIRED` und `WALKOVER`). `STOPPED` = „Beenden, wie es steht“ (02 §9.27) – im Satzende-Dialog
     * (`AWAITING_DECISION`) stattdessen regulär `ContinueDecision(false)`. `ABANDONED` = Abbruch ohne Wertung, **auch** im
     * Satzende-Dialog (sonst entstünde ein gewertetes Ergebnis). Das reguläre „Nein“ im Dialog ist [continueDecision].
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
                m.status == MatchStatus.AWAITING_DECISION && reason == EndReason.STOPPED -> ContinueDecision(anotherSet = false)
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
        if (started) {
            targets().forEach(::sendArchives)
            broadcastMeta(primaryRecord())
        }
    }

    // --- Lokale Events ----------------------------------------------------------------------------

    private fun checkThread() {
        if (!threadGuard.isSchedulerThread()) throw SessionThreadViolation()
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
        val owner = data.players.ensureSelf("", now) { PlayerId(ids.newUuid7(now).value) }.id
        val created = MatchCreated(format, lineup, meta, scorerDeviceId = scorer, createdBy = owner)
        val genesis = ScoreEvent(EventId.of(ids.newUuid7(now)), matchId, deviceId, 1, 1, now, null, created)
        data.transaction {
            data.events.append(genesis.toStoredEvent())
            data.matches.insertIfAbsent(matchId, format, lineup, meta, now, previous)
            data.players.markUsed(lineup.a + lineup.b, now)
            if ((lineup.a + lineup.b).none(data::isPlaceholder)) {
                data.favorites.recordUse(format.sport, format.discipline, lineup.a, lineup.b, now)
            }
        }
        markNeverSentToWatch(matchId)
        openMatch(matchId, focus = true)
        // Ein Folge-Match ersetzt sein beendetes Vor-Match in der Liste (wie auf der Uhr)
        if (previous != null) opened.remove(previous)
        // Zuweisung vor dem Kopf speichern: die Uhr erfährt so im selben MatchMeta, dass sie diesem Match folgen soll
        if (assignToWatch && config.parallelMatches) assignWatch(matchId, explicit = isWatchDevice(scorer))
        broadcastMeta(opened[matchId]?.record)
        broadcast(Events(matchId, listOf(genesis)))
        reconcileWatchScorers()
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
        devicesIn(m.id) += deviceId.value
        m.engine = ScoringEngine.applyAll(m.engine, events)
        if (payloads.any { it is PlayerAssigned }) reconcilePeople(m.id)
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
            if (focus) focusedId = matchId
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
        // Handy: im Hintergrund geöffnete Matches (Neustart, Übergabe) bekommen nie automatisch den Fokus
        if (focus || !config.parallelMatches) focusedId = matchId
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
     * Handy: Die eigene Uhr verlässt ihr bisheriges Match (01 §4.9 „Übergabe beim Wechsel“):
     * - hat sie dort nie selbst gezählt (Platzhalter, nur zugewiesen): das Handy übernimmt sofort – nichts geht verloren;
     * - sonst gibt die Uhr das Match **selbst** per `ScorerChanged` ans Handy ab, sobald sie die neue Zuweisung erhält –
     *   geordnet nach ihrer letzten Eingabe, alle Uhr-Punkte bleiben gültig;
     * - nur mit Bestätigung ([force], Uhr nicht erreichbar): das Handy übernimmt sofort, spätere Uhr-Eingaben dort verwirft
     *   die Rollenprüfung (02 §7.5 Nr. 4), die Uhr meldet sie nach der Wiederverbindung („N Eingaben nicht übernommen“).
     */
    private fun releaseWatchMatch(force: Boolean) {
        if (!config.parallelMatches) return
        val id = watchAssignment?.matchId ?: return
        val m = opened[id] ?: openWithoutFocus(id) ?: return
        if (m.status == MatchStatus.FINISHED || !isWatchDevice(m.scorer)) return
        if (!watchMayHaveScored(m) || force) emit(m, listOf(ScorerChanged(deviceId)))
    }

    /**
     * Kann die Uhr, die [m] zählt, dort Eingaben gemacht haben, die dieses Handy (noch) nicht kennt? Ja, wenn sie dort
     * schon Events hat oder das Match kennt (Genesis von einer Gegenstelle bestätigt – in P1 ist das die eigene Uhr, denn
     * nur Uhr-Matches werden an sie repliziert). Nein beim Platzhalter und bei einem Match, das die Uhr nie erhalten hat –
     * dann übernimmt das Handy verlustfrei sofort.
     */
    private fun watchMayHaveScored(m: OpenMatch): Boolean {
        val scorer = m.scorer ?: return false
        if (scorer == PENDING_WATCH) return false
        // Review W3-M R2 #1: eine unbestätigte Genesis beweist nichts (Ack unterwegs, Trennung vor dem Ack) –
        // verlustfrei sofort übernehmen darf das Handy nur, wenn es das Match nie an eine Uhr gesendet hat
        return scorer.value in devicesIn(m.id) || !neverSentToWatch(m.id)
    }

    /** [matchId] wurde hier angelegt und noch nie an eine Uhr gesendet (fehlender Merker = möglicherweise gesendet). */
    private fun neverSentToWatch(matchId: MatchId): Boolean =
        neverSentCache.getOrPut(matchId) { data.settings.get(NEVER_SENT_TO_WATCH_PREFIX + matchId.value) != null }

    /** Neues, hier angelegtes Match: nachweislich noch nie an eine Uhr gesendet. */
    private fun markNeverSentToWatch(matchId: MatchId) {
        if (!config.parallelMatches) return
        data.settings.put(NEVER_SENT_TO_WATCH_PREFIX + matchId.value, "1")
        neverSentCache[matchId] = true
    }

    /** Erster Sendeversuch an eine Uhr: der Merker „nie gesendet“ fällt dauerhaft weg. */
    private fun markSentToWatch(matchId: MatchId) {
        if (!config.parallelMatches || !neverSentToWatch(matchId)) return
        data.settings.remove(NEVER_SENT_TO_WATCH_PREFIX + matchId.value)
        neverSentCache[matchId] = false
    }

    /** [device] ist eine verbundene, kompatible Gegenstelle. */
    private fun reachable(device: DeviceId): Boolean =
        targets().any { hellos[it]?.deviceId == device && compatibility[it]?.isCompatible == true }

    /**
     * Handy (Review W3-M #1): Höchstens ein laufendes Match darf die eigene Uhr als Zähler haben – das Uhr-Match. Jedes
     * andere laufende Match mit Uhr-Zähler, das die Uhr nie erhalten hat (z. B. offline zugewiesen, während sie ein eigenes
     * Match startete), übernimmt das Handy sofort. Kennt die Uhr es, gibt sie es selbst ab, sobald sie die Zuweisung
     * erhält ([handOverStrayMatches]) – geordnet nach ihrer letzten Eingabe.
     */
    private fun reconcileWatchScorers() {
        if (!config.parallelMatches) return
        val target = watchAssignment?.matchId
        opened.values
            .filter { it.id != target && it.status != MatchStatus.FINISHED && isWatchDevice(it.scorer) && !watchMayHaveScored(it) }
            .toList()
            .forEach { emit(it, listOf(ScorerChanged(deviceId))) }
    }

    /**
     * Uhr: Nach einer Zuweisung gibt die Uhr jedes andere laufende Match, das sie selbst zählt, per `ScorerChanged` ans
     * Handy ab – nach ihrer letzten Eingabe, damit keine Uhr-Punkte verloren gehen (geordnete Übergabe, 01 §4.9).
     * Ausnahme (dieselbe Regel wie am Handy): ein **auf dieser Uhr** gestartetes Match, das jünger ist als das zugewiesene,
     * behält sie – das Handy macht es beim Abgleich selbst zum Uhr-Match. Ist der Kopf des zugewiesenen Matches noch
     * unbekannt, wartet die Uhr damit (wird bei jedem `MatchMeta`/`Hello` erneut geprüft).
     */
    private fun handOverStrayMatches() {
        if (config.parallelMatches) return
        val target = watchAssignment?.matchId ?: return
        val phone = hellos.values.firstOrNull { it.kind == PeerKind.PHONE }?.deviceId ?: return
        val candidates = (opened.keys + data.matches.active().map { it.id }).distinct().filter { it != target }
        for (id in candidates) {
            val m = opened[id] ?: detached(id) ?: continue
            if (m.status == MatchStatus.FINISHED || m.scorer != deviceId || keepsOwnMatch(m, target)) continue
            emit(m, listOf(ScorerChanged(phone)))
        }
    }

    /**
     * Uhr: [m] ist hier gestartet und jünger als das zugewiesene [target] (oder dessen Kopf ist noch unbekannt) – bei
     * einer ausdrücklichen Zuweisung jünger als die Zuweisung selbst (Review W3-M R2 #2).
     */
    private fun keepsOwnMatch(
        m: OpenMatch,
        target: MatchId,
    ): Boolean {
        if (m.genesis.deviceId != deviceId) return false
        val assignment = watchAssignment
        if (assignment?.explicit == true) return m.record.createdAtMs > assignment.assignedAtMs
        val assigned = data.matches.get(target) ?: return true
        return m.record.createdAtMs > assigned.createdAtMs
    }

    /** Nicht geöffnetes Match als Arbeitskopie (z. B. für eine Übergabe), ohne die Liste der offenen zu ändern. */
    private fun detached(matchId: MatchId): OpenMatch? {
        val record = data.matches.get(matchId) ?: return null
        val engine =
            try {
                ScoringEngine.replay(data.events.scoreEvents(matchId))
            } catch (e: IllegalArgumentException) {
                return null
            }
        return OpenMatch(record, engine.genesis ?: return null, engine)
    }

    /**
     * Handy: neue Zuweisung speichern (Zeitstempel streng steigend, damit die Uhr sie ordnen kann). [explicit] = vom
     * Nutzer gewählt („Meine Uhr“, „Auf der Uhr zählen“): Die Uhr folgt ihr auch weg von einem auf ihr gestarteten Match,
     * das älter ist als die Zuweisung (Review W3-M R2 #2); implizite Zuweisungen unterliegen der Rennregel.
     */
    private fun assignWatch(
        matchId: MatchId?,
        explicit: Boolean = false,
    ) {
        val current = watchAssignment
        // implizit: gleiche Zuweisung ist ein No-op; ausdrücklich: immer neue Generation (Review W3-M R3 #2)
        if (current != null && current.matchId == matchId && !explicit) return
        val at = maxOf(clock.nowMs(), (current?.assignedAtMs ?: 0) + 1)
        storeAssignment(WatchAssignment(matchId, at, explicit))
    }

    /** Gelöschtes Match: am Handy ist die Uhr danach frei; die Uhr behält ihre Zuweisung (gelöschten folgt sie nie). */
    private fun forgetAssignment(matchId: MatchId) {
        if (config.parallelMatches && watchAssignment?.matchId == matchId) assignWatch(null)
    }

    /**
     * Handy: Ein auf der Uhr gestartetes (oder von ihr gezähltes) neues, laufendes Match wird das Uhr-Match, sofern es
     * jünger ist als ein noch laufendes bisheriges. Alte Matches aus einem Offline-Rückstand ändern nichts.
     */
    private fun adoptWatchStartedMatch(genesis: ScoreEvent) {
        if (!config.parallelMatches) return
        val created = genesis.payload as? MatchCreated ?: return
        val scorer = created.scorerDeviceId ?: genesis.deviceId
        if (!isWatchDevice(genesis.deviceId) && !isWatchDevice(scorer)) return
        val record = data.matches.get(genesis.matchId) ?: return
        if (record.isFinished || watchAssignment?.matchId == record.id) return
        val assignment = watchAssignment
        val current = assignment?.matchId?.let { opened[it]?.record ?: data.matches.get(it) }
        // nur echt jüngere: bei gleicher Millisekunde bleibt die bestehende Zuweisung (z. B. gerade am Handy angelegt);
        // gegen eine ausdrückliche Zuweisung zählt deren Zeitpunkt (dieselbe Regel wie keepsOwnMatch auf der Uhr)
        val reference = if (assignment?.explicit == true) assignment.assignedAtMs else current?.createdAtMs
        if (current != null && !current.isFinished && reference != null && record.createdAtMs <= reference) return
        assignWatch(record.id)
        broadcastMeta(record)
        reconcileWatchScorers()
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
        val target = assignment.matchId
        // ein hier gestartetes, jüngeres laufendes Match bleibt offen (das Handy übernimmt es als Uhr-Match)
        val keep = target != null && open?.let { it.status != MatchStatus.FINISHED && keepsOwnMatch(it, target) } == true
        assignmentPending = target != null && target != focusedId && !keep
        handOverStrayMatches()
    }

    private fun storeAssignment(assignment: WatchAssignment) {
        watchAssignment = assignment
        data.settings.put(WATCH_MATCH_KEY, assignment.matchId?.value.orEmpty())
        data.settings.putLong(WATCH_MATCH_AT_KEY, assignment.assignedAtMs)
        data.settings.putBoolean(WATCH_MATCH_EXPLICIT_KEY, assignment.explicit)
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
            val explicit = data.settings.getBoolean(WATCH_MATCH_EXPLICIT_KEY, false)
            return WatchAssignment(stored.takeIf { it.isNotEmpty() }?.let(::MatchId), at, explicit)
        }
        if (!config.parallelMatches) return null
        val legacy = data.matches.active().firstOrNull() ?: return null
        return WatchAssignment(legacy.id, 0).also(::storeAssignment)
    }

    private fun persistProgress(m: OpenMatch) {
        val state = m.engine.state
        val score =
            ScoreSummary.of(state).copy(
                lastEventAtMs =
                    m.engine.events
                        .lastOrNull { it.payload !is PlayerAssigned }
                        ?.wallClockMs ?: m.genesis.wallClockMs,
                eventCount = m.engine.events.size + 1,
            )
        data.matches.updateProgress(m.id, state.status, score, clock.nowMs())
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
        // Knoten → Gerät merken: das Routing kennt die Uhr so schon vor ihrem nächsten Hello
        data.settings.put(NODE_DEVICE_PREFIX + peer, hello.deviceId.value)
        knownKindsCache = null
        if (!result.isCompatible) {
            earlyOwners.remove(peer)
            return
        }
        // MatchMeta kam vor diesem Hello: Besitzer-Angaben jetzt mit bekannter Geräteart übernehmen
        earlyOwners.remove(peer)?.let { adoptOwnerIdentity(peer, it) }
        if (peer !in helloSent) sendHello(peer)
        handOverToWaitingWatch(hello)
        handOverStrayMatches()
        sendDeleted(peer)
        sendArchives(peer)
        send(peer, summaryOf(summaryMatches(), reply = false))
        send(peer, metaMessage(primaryRecord()))
        // jetzt identifiziert: zulässigen Rückstand nachschieben (vorher hielt das Routing alles zurück)
        if (config.parallelMatches) pushBacklog(peer)
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
            devicesInMatch.remove(tombstone.matchId)
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
        val relevantBefore = replicates(peer, matchId)
        devicesIn(matchId) += inserted.map { it.deviceId.value }
        // die Uhr kennt dieses Match (z. B. offline weitergezählt): jetzt gehört es zu ihrem Abgleich – sofort Stand tauschen
        if (!relevantBefore && replicates(peer, matchId)) send(peer, summaryOf(listOf(matchId), reply = false))
        val genesis = inserted.firstOrNull { it.payload is MatchCreated }
        genesis?.let { ensureHeader(it) }
        val m = opened[matchId]
        if (m != null) {
            // Live-Stand offener Matches steht in `SessionState.matches` – kein `dataRevision` (keine Listen neu laden)
            m.engine = ScoringEngine.applyAll(m.engine, inserted)
            persistProgress(m)
            if (inserted.any { it.payload is ScorerChanged }) noteDroppedInputs(m.id, m.engine.events, m.engine.state.rejectedByRole)
        } else {
            refreshHeader(matchId)
        }
        genesis?.let { adoptWatchStartedMatch(it) }
        // eine Übergabe der Uhr kann ein Match ohne Uhr-Eingaben mit Uhr-Zähler hinterlassen
        if (inserted.any { it.payload is ScorerChanged }) reconcileWatchScorers()
        if (inserted.any { it.payload is PlayerAssigned || it.payload is MatchCreated }) reconcilePeople(matchId)
        val forward = Events(matchId, inserted, message.more)
        targets().filter { it != peer }.forEach { send(it, forward) }
        return matchId
    }

    private fun onMatchMeta(
        peer: String,
        message: MatchMetaMessage,
    ): MatchId? {
        val now = clock.nowMs()
        val corrections =
            (message.placeholderIds + message.players.filter { it.isPlaceholder }.map { it.id })
                .distinct()
                .filterNot(data.players::canMarkPlaceholder)
                .mapNotNull {
                    data.players
                        .get(it)
                        ?.toPlayer()
                        ?.copy(isPlaceholder = false)
                }
        if (config.kind == PeerKind.PHONE) corrections.forEach { data.players.upsertRemote(it, now) }
        message.placeholderIds.filter(data.players::canMarkPlaceholder).forEach {
            if (!data.isPlaceholder(it)) {
                data.players.markPlaceholder(it, now)
                data.rebuildAssignmentsForSlot(it)
            }
        }
        val protectedIds = corrections.map { it.id }.toSet()
        (message.players + message.recentPlayers.orEmpty())
            .filterNot { it.id in protectedIds }
            .forEach { data.players.upsertRemote(it, now) }
        if (config.kind == PeerKind.PHONE) {
            corrections.chunked(ARCHIVES_IN_META).forEach { players ->
                send(
                    peer,
                    MatchMetaMessage(
                        players = players,
                        archivedPlayerIds = players.map { it.id }.filter { data.players.get(it)?.kind == PlayerKind.ARCHIVED },
                    ),
                )
            }
        }
        if (config.kind == PeerKind.WATCH) message.archivedPlayerIds.forEach { data.players.archiveRemote(it, now) }
        message.recentPlayers?.let { people ->
            data.settings.put(
                "players.recentRemote",
                people.joinToString(",") { it.id.value },
            )
        }
        message.favorites.filter { (it.a + it.b).none(data::isPlaceholder) }.forEach { data.favorites.merge(it.toLocal()) }
        dataRevision++
        adoptOwnerIdentity(peer, message)
        val header = message.match
        if (header != null) {
            data.matches.insertIfAbsent(
                header.matchId,
                header.format,
                header.lineup,
                header.meta,
                header.createdAtMs,
                header.previousMatchId,
            )
            opened[header.matchId]?.let { m -> data.matches.get(m.id)?.let { m.record = it } }
        }
        // Kopf zuerst: die Uhr vergleicht beim Übergeben mit dem zugewiesenen Match
        message.watchMatch?.let { adoptAssignment(peer, it) }
        // Telemetrie-Sichtbarkeit (P2-J): jüngere Änderung übernehmen, ohne Feld (ältere Absender) bleibt der eigene Wert
        message.telemetry?.toPreference()?.let { adoptTelemetry(telemetry.merge(it)) }
        handOverStrayMatches()
        return header?.matchId ?: message.watchMatch?.matchId
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

    private fun reconcilePeople(matchId: MatchId) {
        val at = data.matches.get(matchId)?.createdAtMs ?: return
        data.assignments(matchId).values.forEach {
            data.players.upsertRemote(it, clock.nowMs())
            data.players.markUsed(listOf(it.id), at)
        }
        dataRevision++
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
        val score =
            ScoreSummary.of(state).copy(
                lastEventAtMs = log.lastOrNull { it.payload !is PlayerAssigned }?.wallClockMs,
                eventCount = log.size,
            )
        data.matches.updateProgress(matchId, state.status, score, clock.nowMs())
        dataRevision++
        noteDroppedInputs(matchId, log, state.rejectedByRole)
    }

    /**
     * Uhr: Eigene Eingaben wurden nach einer Übernahme verworfen – z. B. nach der bestätigten Übernahme durch das Handy,
     * während die Uhr offline weiterzählte. Ein Hinweis je neuem Höchststand und Match (persistent gemerkt); die UI zeigt
     * ihn, wenn das Match nicht (mehr) offen ist (für das offene gilt `ActiveMatch.rejectedByRoleCount`).
     */
    private fun noteDroppedInputs(
        matchId: MatchId,
        log: List<ScoreEvent>,
        rejected: List<EventId>,
    ) {
        if (config.parallelMatches || rejected.isEmpty()) return
        val own = log.filter { it.deviceId == deviceId }.map { it.id }.toSet()
        val count = rejected.count { it in own }
        val key = DROPPED_PREFIX + matchId.value
        if (count <= data.settings.getLong(key, 0)) return
        data.settings.putLong(key, count.toLong())
        droppedInputs = DroppedInputs(matchId, count)
        data.settings.put(DROPPED_NOTICE_KEY, matchId.value + "|" + count)
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

    // --- Replikations-Routing (Review W3-M #4, 01 §4.9) -------------------------------------------

    /**
     * Zentrale Routingregel je Gegenstelle: Darf [matchId] an [peer] repliziert werden? Gilt für Live-Events,
     * Weiterleitung, Rückstand, Match-Köpfe, Summaries und Antworten darauf (alles läuft über [send]).
     * - Uhr → Handy und zwischen Handys: alles.
     * - Handy → eigene Uhr: nur das aktuelle Uhr-Match und Matches, in denen diese Uhr selbst Events hat (Übergabe,
     *   Rückstand früherer Uhr-Matches). Director-Matches der Nachbarplätze erreichen die Uhr nie.
     * Löschmarkierungen und `Hello` gehen immer an alle.
     */
    private fun replicates(
        peer: String,
        matchId: MatchId,
    ): Boolean {
        if (!config.parallelMatches) return true
        // Review W3-M R2 #3: noch nicht identifizierte Gegenstelle (kein Hello, kein gemerkter Knoten) bekommt keine
        // Matchdaten; nach ihrem Hello schiebt onHello Summary und zulässigen Rückstand nach
        val device = peerDeviceOf(peer) ?: return false
        val kind = peerKindOf(peer) ?: return false
        return kind != PeerKind.WATCH || matchId == watchAssignment?.matchId || device.value in devicesIn(matchId)
    }

    private fun peerDeviceOf(peer: String): DeviceId? =
        hellos[peer]?.deviceId ?: data.settings.get(NODE_DEVICE_PREFIX + peer)?.let(::DeviceId)

    private fun peerKindOf(peer: String): PeerKind? = hellos[peer]?.kind ?: peerDeviceOf(peer)?.let { knownKinds()[it] }

    /**
     * Hat [matchId] überhaupt ein Replikationsziel? Nur dann zählen seine unbestätigten Events als „ausstehend“ und halten
     * den Anti-Entropie-Takt am Laufen. Ohne Ziel (Director-Match, nur eigene Uhr bekannt) bleiben sie unbestätigt, aber
     * unsichtbar – nie fälschlich als bestätigt.
     */
    private fun hasReplicationTarget(matchId: MatchId): Boolean {
        if (!config.parallelMatches) return true
        val known = knownKinds()
        if (known.values.any { it != PeerKind.WATCH }) return true
        return known.isNotEmpty() && (matchId == watchAssignment?.matchId || known.keys.any { it.value in devicesIn(matchId) })
    }

    /** Persistenter Ausgangs-Rückstand mit Replikationsziel. */
    private fun pendingCount(): Int =
        if (!config.parallelMatches) {
            data.events.unconfirmedCount().toInt()
        } else {
            data.events
                .unconfirmedCountByMatch()
                .filterKeys { hasReplicationTarget(MatchId(it)) }
                .values
                .sum()
                .toInt()
        }

    private fun knownKinds(): Map<DeviceId, PeerKind> =
        knownKindsCache ?: data.settings
            .all()
            .filter { (key, _) -> key.startsWith(PEER_KIND_PREFIX) }
            .mapNotNull { (key, value) ->
                PeerKind.entries.firstOrNull { it.name == value }?.let { DeviceId(key.removePrefix(PEER_KIND_PREFIX)) to it }
            }.filter { it.first != deviceId }
            .toMap()
            .also { knownKindsCache = it }

    private fun devicesIn(matchId: MatchId): MutableSet<String> =
        devicesInMatch.getOrPut(matchId) {
            data.events
                .sequences(matchId.value)
                .map { it.deviceId }
                .toMutableSet()
        }

    /** Nachricht für [peer] nach der Routingregel zuschneiden; `null` = nichts senden. */
    private fun route(
        peer: String,
        message: SyncMessage,
    ): SyncMessage? =
        when (message) {
            is Events -> {
                message.takeIf { replicates(peer, it.matchId) }
            }

            is MatchMetaMessage -> {
                val header = message.match
                if (header == null || replicates(peer, header.matchId)) message else message.copy(match = null)
            }

            is Summary -> {
                val matches = message.matches.filterKeys { replicates(peer, MatchId(it)) }
                if (matches.isEmpty() && message.matches.isNotEmpty() && message.reply) null else message.copy(matches = matches)
            }

            is Hello, is Deleted -> {
                message
            }
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
            if (data.matches.isDeleted(matchId) || !replicates(peer, matchId)) continue
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

    /** Alle Archive in begrenzten Paketen: auch alte Löschmarkierungen konvergieren nach einem Reconnect. */
    private fun sendArchives(peer: String) {
        if (config.kind != PeerKind.PHONE) return
        data.players.archivedIds().toList().chunked(ARCHIVES_IN_META).forEach { ids ->
            send(peer, MatchMetaMessage(archivedPlayerIds = ids))
        }
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
        val routed = route(peer, message) ?: return
        if (routed is Events && routed.events.isNotEmpty() && peerKindOf(peer) == PeerKind.WATCH) markSentToWatch(routed.matchId)
        val callback = (routed as? Events)?.takeIf { it.events.isNotEmpty() }?.let { ackCallback(it) }
        link?.send(peer, SyncCodec.encode(routed), callback)
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
                // Takt nur für Matches mit Replikationsziel (Director-Matches ohne Ziel halten ihn nicht am Laufen)
                val active = opened.values.any { it.status != MatchStatus.FINISHED && hasReplicationTarget(it.id) }
                if (active || pendingCount() > 0) sendSummaries()
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
        val archived = data.players.archivedIds()
        val favorites =
            data.favorites.recent(limit = FAVORITES_IN_META).filter {
                (it.a + it.b).none { id -> data.isPlaceholder(id) || id in archived }
            }
        val lineupPlayers = record?.lineup?.let { it.a + it.b }.orEmpty()
        val playerIds = (lineupPlayers + favorites.flatMap { it.a + it.b }).distinct()
        return MatchMetaMessage(
            match = record?.let { MatchHeader(it.id, it.format, it.lineup, it.meta, it.createdAtMs, it.previousMatchId) },
            players = playerIds.filterNot(data::isPlaceholder).mapNotNull { data.players.get(it)?.toPlayer() },
            placeholderIds = data.players.placeholderIds().toList(),
            recentPlayers = if (config.kind == PeerKind.PHONE) data.recentPeople().map { it.toPlayer() } else null,
            archivedPlayerIds = if (config.kind == PeerKind.PHONE) playerIds.filter { it in archived } else emptyList(),
            favorites = favorites.map { it.toWire() },
            self = data.players.self()?.toPlayer(),
            selfAliases = data.selfAliases().toList(),
            watchMatch = watchAssignment?.takeIf { config.parallelMatches },
            telemetry = TelemetrySetting.of(telemetry),
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
        val pending = pendingCount()
        return SessionState(
            match,
            SyncStatus(linkState, peers, indicator, scorerConnected, pending, lastEventReceivedAtMs, lastSyncAtMs),
            dataRevision,
            matches,
            watchAssignment?.matchId,
            droppedInputs,
            telemetry,
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
            canCorrectServerRetroactively = canCorrectServerRetroactively(engine),
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
        private const val ARCHIVES_IN_META = 200
        private const val FAVORITES_IN_META = 10L
        private const val PEER_KIND_PREFIX = "session.peerKind."

        /** Settings: Geräte-ID je Link-Knoten (Routing vor dem Hello). */
        private const val NODE_DEVICE_PREFIX = "session.nodeDevice."

        /** Settings: schon gemeldete Zahl verworfener eigener Eingaben je Match (Uhr). */
        private const val DROPPED_PREFIX = "session.dropped."

        /** Settings: noch nicht quittierter Hinweis „matchId|Anzahl“ (Uhr). */
        private const val DROPPED_NOTICE_KEY = "session.droppedNotice"

        /** Settings: hier angelegtes Match wurde noch nie an eine Uhr gesendet (Handy); fehlt = möglicherweise gesendet. */
        private const val NEVER_SENT_TO_WATCH_PREFIX = "session.neverSentToWatch."

        /** Settings: Telemetrie-Sichtbarkeit samt Versionsangabe als ein JSON-Eintrag (`TelemetrySetting`, P2-J). */
        private const val TELEMETRY_KEY = "session.telemetry"

        /** Settings: Zuweisung war ausdrücklich (Handy bzw. erhaltene, Uhr). */
        private const val WATCH_MATCH_EXPLICIT_KEY = "session.watchMatchExplicit"

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
