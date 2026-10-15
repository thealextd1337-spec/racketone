package eu.advantly.wear.live

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.data.assignments
import eu.advantly.match.session.ActiveMatch
import eu.advantly.match.session.DroppedInputs
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionListener
import eu.advantly.match.session.SessionState
import eu.advantly.presentation.live.HapticCue
import eu.advantly.presentation.live.LiveScoreRules
import eu.advantly.presentation.live.ScoreHint
import eu.advantly.presentation.match.LocalDay
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.wear.match.FormatChoice
import eu.advantly.wear.match.PlayerSlot
import eu.advantly.wear.match.SetupCatalog
import eu.advantly.wear.match.WatchDirectory
import eu.advantly.wear.match.WatchSetup
import eu.advantly.wear.scoring.TapGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Logik der Wear-App zwischen UI und [MatchSession] (Session-Art `WATCH`): Startliste, Kurz-Wizard, Zähler- und
 * Anzeige-Ansicht, Übergabe, Staleness, Effekte (Haptik/Toasts). Ohne Android und ohne Coroutines – die Session meldet
 * Änderungen per [SessionListener], die UI ruft [tick] im Sekundentakt (Staleness „Stand vor X s“).
 *
 * Alle Aufrufe auf dem Session-Thread (Main). Zähl-Kommandos wirken nur, wenn dieses Gerät Zähler ist – die Session
 * prüft das ohnehin; hier kommen Entprellung (600 ms), Touch-Sperre und Rückmeldungen dazu.
 *
 * @param wallClock Wanduhr (ms seit Epoche) – nur zum einmaligen Umrechnen der Zeitstempel aus `SyncStatus`
 * @param elapsed monotone Uhr inkl. Tiefschlaf (`SystemClock.elapsedRealtime()`) – Staleness und 30-s-Schwelle
 * @param uptime monotone Uhr für die Entprellung
 */
@Suppress("TooManyFunctions")
class WatchController(
    private val session: MatchSession,
    private val directory: WatchDirectory,
    private val prefs: WatchPrefs,
    private val wallClock: () -> Long,
    private val elapsed: () -> Long,
    private val uptime: () -> Long,
    private val today: () -> LocalDay,
    private val tapGuard: TapGuard = TapGuard(),
) {
    /** Entwurf des Kurz-Wizards; `null` = nicht im Wizard. */
    private data class Draft(
        val step: SetupStep = SetupStep.SPORT,
        val sport: Sport? = null,
        val discipline: Discipline? = null,
        val format: FormatChoice? = null,
        /** Gewählte Games pro Satz; `null` = Standard des Formats. */
        val gamesPerSet: Int? = null,
        /** „Aufschlag-Statistik erfassen“ (Tennis/Padel, Default an). */
        val serveStats: Boolean = true,
        val selectedPlayers: Map<PlayerSlot, PlayerId> = emptyMap(),
    )

    private var draft: Draft? = null
    private var overlay: LiveOverlay? = null
    private var locked = false

    /** Sichtbarkeit der Aufschlag-Leiste nach dem Punkt (monoton über [elapsed]). */
    private val serveBar = ServeBarTimer()

    /** Eröffnungswahl schon getroffen? (Hinweis nach aufgehobener Wahl; aus dem Event-Log, übersteht Neustarts) */
    private val openingChoice = OpeningChoiceCheck(directory::serverChosen)

    /** Monoton: seit wann der Zähler aus Sicht dieser Anzeige nicht verbunden ist. */
    private var disconnectedSince: Long? = null

    /** Zuletzt gesehene Kontakt-Zeitstempel der Session (Wanduhr), je Quelle getrennt. */
    private var seenEventWall: Long? = null
    private var seenSyncWall: Long? = null

    /** Wann (monoton) der jeweils letzte neue Kontakt kam. */
    private var eventAt: Long? = null
    private var syncAt: Long? = null
    private val lastContactAt: Long? get() = listOfNotNull(eventAt, syncAt).maxOrNull()
    private var seenMatch: MatchId? = null
    private var seenScorer: Boolean? = null
    private var seenRejected = 0

    /** Zuletzt gemeldeter Hinweis „Eingaben in einem nicht mehr offenen Match verworfen“ (nach Übernahme durchs Handy). */
    private var seenDropped: DroppedInputs? = null
    private var closingLocally = false
    private var started = false

    private val mutableState = MutableStateFlow(WatchUiState(homeScreen()))

    /** Zustand für die UI. */
    val state: StateFlow<WatchUiState> = mutableState.asStateFlow()

    /** Empfänger einmaliger Effekte (Activity: Vibrator, Toast). */
    var effects: ((WatchEffect) -> Unit)? = null

    private val listener = SessionListener { onSession(it) }

    /** Meldet sich bei der Session an (liefert sofort den aktuellen Zustand). Idempotent. */
    fun start() {
        if (started) return
        started = true
        session.addListener(listener)
    }

    fun dispose() {
        if (!started) return
        started = false
        session.removeListener(listener)
    }

    /** Sekundentakt der UI (Staleness, Übernahme-Angebot nach 30 s). */
    fun tick() = publish()

    /** Display wieder aktiv (Ambient → interaktiv): kurze Sperre gegen den Aufweck-Tap (04 §4.3.3 Punkt 4). */
    fun onWake() {
        tapGuard.onWake(uptime())
        // Leiste gehört zum Moment direkt nach dem Punkt – nach dem Ambient-Modus ist der vorbei
        serveBar.dismiss()
        publish()
    }

    // --- Startliste -------------------------------------------------------------------------------

    fun newMatch() {
        draft = Draft()
        publish()
    }

    /** „Wie letztes Mal“: gleiche Besetzung, gleiches Format, diese Uhr zählt. */
    fun repeatLast(): Boolean {
        val last = directory.lastSetup() ?: return false
        // Workout-Wunsch kommt immer aus der aktuellen Uhr-Einstellung (MatchMeta.watchWorkout)
        return start(last.copy(meta = last.meta.copy(watchWorkout = prefs.workoutEnabled)))
    }

    fun resume(matchId: MatchId): Boolean = session.resume(matchId)

    fun setWorkoutEnabled(enabled: Boolean) {
        prefs.workoutEnabled = enabled
        publish()
    }

    // --- Kurz-Wizard ------------------------------------------------------------------------------

    fun selectSport(sport: Sport) {
        val disciplines = SetupCatalog.disciplines(sport)
        draft =
            if (disciplines.size == 1) {
                Draft(SetupStep.FORMAT, sport, disciplines.single())
            } else {
                Draft(SetupStep.DISCIPLINE, sport)
            }
        publish()
    }

    fun selectDiscipline(discipline: Discipline) {
        val d = draft ?: return
        if (d.sport == null || discipline !in SetupCatalog.disciplines(d.sport)) return
        draft =
            d.copy(
                step = SetupStep.FORMAT,
                discipline = discipline,
                selectedPlayers =
                    if (discipline ==
                        Discipline.SINGLES
                    ) {
                        d.selectedPlayers.filterKeys { it == PlayerSlot.OPPONENT_1 }
                    } else {
                        d.selectedPlayers
                    },
            )
        publish()
    }

    fun selectFormat(choice: FormatChoice) {
        val d = draft ?: return
        if (choice.sport != d.sport) return
        draft = d.copy(step = SetupStep.SERVER, format = choice, gamesPerSet = null)
        publish()
    }

    fun selectPlayer(
        slot: PlayerSlot,
        player: PlayerId?,
    ) {
        val d = draft ?: return
        if (d.discipline == null || (d.discipline == Discipline.SINGLES && slot != PlayerSlot.OPPONENT_1)) return
        if (player != null && (directory.recentPlayers().none { it.id == player } || player in d.selectedPlayers.values)) return
        draft = d.copy(selectedPlayers = d.selectedPlayers - slot + if (player == null) emptyMap() else mapOf(slot to player))
        publish()
    }

    /** „Games pro Satz“ im letzten Schritt (Tennis/Padel 1–6, nicht bei „Nur Tiebreak“). */
    fun selectGamesPerSet(games: Int) {
        val d = draft ?: return
        val choice = d.format ?: return
        if (games !in SetupCatalog.gamesOptions(choice)) return
        draft = d.copy(gamesPerSet = games)
        publish()
    }

    /** Schalter „Aufschlag-Statistik erfassen“ (nur Tennis/Padel). */
    fun setServeStats(enabled: Boolean) {
        val d = draft ?: return
        if (d.sport?.let(SetupCatalog::serveStatsAvailable) != true) return
        draft = d.copy(serveStats = enabled)
        publish()
    }

    /**
     * Letzter Schritt: Erstaufschläger wählen = Match starten (diese Uhr zählt). Im Doppel wählt [serverIndex] den
     * konkreten Spieler (0 = Du bzw. Gegner 1, 1 = Partner bzw. Gegner 2).
     */
    fun selectServer(
        server: Side,
        serverIndex: Int = 0,
    ): Boolean {
        val d = draft ?: return false
        val sport = d.sport ?: return false
        val discipline = d.discipline ?: return false
        val choice = d.format ?: return false
        val setup =
            WatchSetup(
                format = SetupCatalog.format(choice, discipline, today(), d.gamesPerSet),
                lineup = SetupCatalog.lineup(discipline, directory.lineupPlayers(d.selectedPlayers), server, serverIndex),
                meta = SetupCatalog.meta(sport, d.serveStats).copy(watchWorkout = prefs.workoutEnabled),
            )
        return start(setup)
    }

    /** Ein Schritt zurück; im ersten Schritt zurück zur Startliste. */
    fun back() {
        val d = draft ?: return
        draft =
            when (d.step) {
                SetupStep.SPORT -> {
                    null
                }

                SetupStep.DISCIPLINE -> {
                    Draft()
                }

                SetupStep.FORMAT -> {
                    if (d.sport != null && SetupCatalog.disciplines(d.sport).size > 1) {
                        d.copy(step = SetupStep.DISCIPLINE, format = null)
                    } else {
                        Draft()
                    }
                }

                SetupStep.SERVER -> {
                    d.copy(step = SetupStep.FORMAT)
                }
            }
        publish()
    }

    // --- Zählen (nur Zähler) ----------------------------------------------------------------------

    /** Tap auf eine Hälfte. `true`, wenn ein Punkt gezählt wurde (UI: Aufleuchten). */
    fun point(side: Side): Boolean {
        val live = mutableState.value.live ?: return false
        if (!live.isScorer || live.overlay != null) return false
        if (locked) {
            emit(WatchEffect.Haptic(WatchHaptic.REJECTED))
            emit(WatchEffect.Toast(WatchToast.Locked))
            return false
        }
        if (!live.status.isRunning) return false
        if (!tapGuard.tryAccept(uptime())) return false
        val before = live.hint
        if (!session.recordPoint(side)) {
            emit(WatchEffect.Haptic(WatchHaptic.REJECTED))
            return false
        }
        val after = session.state.value.match ?: return true
        // Aufschlag-Leiste für genau diesen Punkt (ersetzt die des vorigen Punktes)
        after.lastPoint?.takeIf { after.serveInfoEnabled }?.let { serveBar.onPoint(it.id, elapsed()) }
        publish()
        val hint = mutableState.value.live?.hint
        val attention = hint != null && hint.kind in ATTENTION_HINTS && hint != before
        emit(WatchEffect.Haptic(hapticFor(LiveScoreRules.cueFor(after.state, side)), attention))
        return true
    }

    /**
     * Long-Press auf eine Hälfte: letzten Punkt dieser Seite abziehen (02 §7.3 K14, auch über Satzgrenzen). Kurze
     * Haptik und Hinweis „Punkt abgezogen · Rückgängig“ – „Rückgängig“ (Aktionsleiste) ist Redo.
     */
    fun removePoint(side: Side): Boolean {
        val live = mutableState.value.live ?: return false
        if (!live.isScorer || live.overlay != null) return false
        if (locked) {
            emit(WatchEffect.Haptic(WatchHaptic.REJECTED))
            emit(WatchEffect.Toast(WatchToast.Locked))
            return false
        }
        if (!live.canRemovePoint[side]) {
            emit(WatchEffect.Haptic(WatchHaptic.REJECTED))
            emit(WatchEffect.Toast(WatchToast.NothingToRemove))
            return false
        }
        serveBar.dismiss()
        if (!session.removeLastPointOf(side)) {
            publish()
            emit(WatchEffect.Haptic(WatchHaptic.REJECTED))
            return false
        }
        emit(WatchEffect.Haptic(WatchHaptic.UNDO))
        emit(WatchEffect.Toast(WatchToast.PointRemoved(side)))
        return true
    }

    /** Aufschlag-Leiste: Wert für den letzten Punkt setzen bzw. ändern (nur solange die Leiste sichtbar ist). */
    fun setServe(outcome: ServeOutcome): Boolean {
        val live = mutableState.value.live ?: return false
        val bar = live.serveBar ?: return false
        if (locked || outcome !in bar.options) return false
        if (!session.setServeInfo(bar.pointId, outcome)) {
            emit(WatchEffect.Haptic(WatchHaptic.REJECTED))
            return false
        }
        serveBar.onChosen(elapsed())
        emit(WatchEffect.Haptic(WatchHaptic.CONFIRM))
        publish()
        return true
    }

    /** Hinweis „Aufschlag wählen“ antippen → Auswahl (nur Zähler, offene Wahl). */
    fun openServerChoice() {
        val live = mutableState.value.live ?: return
        if (!live.isScorer || live.serverChoice == null || locked) return
        setOverlay(LiveOverlay.SERVER_CHOICE)
    }

    /** Aufschläger wählen (02 §6.5.5): nur Spieler aus `serverChoice.players`; schließt die Auswahl. */
    fun chooseServer(player: PlayerId): Boolean {
        val choice = mutableState.value.live?.serverChoice ?: return false
        if (choice.players.none { it.id == player }) return false
        overlay = null
        val chosen = session.chooseServer(player)
        if (chosen) emit(WatchEffect.Haptic(WatchHaptic.CONFIRM))
        publish()
        return chosen
    }

    /** Satzende „Noch ein Satz?“: weiter mit [kind] aus `nextSetOptions` (normal, Tiebreak 7, Champions-Tiebreak 10). */
    fun startNextSet(kind: NextSetKind): Boolean {
        val decision = mutableState.value.live?.decision ?: return false
        if (kind !in decision.options) return false
        return session.startNextSet(kind)
    }

    /** Wischen nach unten bzw. Button „Rückgängig“. */
    fun undo(): Boolean {
        val live = mutableState.value.live ?: return false
        if (!live.isScorer || locked) return false
        val before =
            session.state.value.match
                ?.state ?: return false
        serveBar.dismiss()
        if (!session.undo()) {
            emit(WatchEffect.Toast(WatchToast.NothingToUndo))
            return false
        }
        val after =
            session.state.value.match
                ?.state ?: return true
        emit(WatchEffect.Haptic(WatchHaptic.UNDO))
        emit(WatchEffect.Toast(WatchToast.Undone(LiveModel.scoreText(before), LiveModel.scoreText(after))))
        return true
    }

    /** Wiederholen – auch „Rückgängig“ nach „Punkt abziehen“ (gleicher Redo-Stapel, W2-Vertrag §1). */
    fun redo(): Boolean {
        if (mutableState.value.live?.isScorer != true || locked) return false
        if (!session.redo()) return false
        emit(WatchEffect.Toast(WatchToast.Redone))
        return true
    }

    fun pause(): Boolean = session.pause()

    fun resumePlay(): Boolean = session.resumePlay()

    /** Touch-Sperre (Steuerseite); Entsperren durch langes Halten auf dem Score. */
    fun lock() {
        if (mutableState.value.live?.isScorer != true) return
        locked = true
        publish()
    }

    fun unlock() {
        if (!locked) return
        locked = false
        emit(WatchEffect.Toast(WatchToast.Unlocked))
        publish()
    }

    /** Antwort im Satzende-Dialog („Noch ein Satz?“). */
    fun continueDecision(
        anotherSet: Boolean,
        matchTiebreak: Boolean = false,
    ): Boolean = session.continueDecision(anotherSet, matchTiebreak)

    fun requestEnd() = setOverlay(LiveOverlay.CONFIRM_END)

    /** Steuerseite → „Aufschläger korrigieren“ (nur Zähler, laufendes Match). */
    fun requestCorrection() {
        if (mutableState.value.live?.corrections == null) return
        setOverlay(LiveOverlay.CORRECT_SERVER)
    }

    /**
     * Aufschläger korrigieren: im ersten Spiel bzw. Satz rückwirkend (`INITIAL_SERVER`), sonst ab jetzt (`SERVER`),
     * 02 §7.1/§9.4.
     */
    fun correctServer(player: PlayerId): Boolean {
        val corrections = mutableState.value.live?.corrections ?: return false
        if (corrections.players.none { it.id == player }) return false
        return applyCorrection(corrections.serverCorrection(player))
    }

    /** Positionen von [side] tauschen (Doppel; Tischtennis: Rückschläger), nur wenn angeboten. */
    fun swapPositions(side: Side): Boolean {
        val swap =
            mutableState.value.live
                ?.corrections
                ?.swaps
                ?.firstOrNull { it.side == side } ?: return false
        return applyCorrection(swap.correction)
    }

    private fun applyCorrection(correction: Correction): Boolean {
        overlay = null
        val applied = session.correct(correction)
        if (applied) emit(WatchEffect.Toast(WatchToast.Corrected))
        publish()
        return applied
    }

    /** Bestätigtes Matchende (im Satzende-Dialog regulär, sonst Abbruch ohne Wertung). */
    fun confirmEnd(): Boolean {
        overlay = null
        val ended = session.endMatch()
        publish()
        return ended
    }

    /** „Zählen übernehmen“ antippen → Bestätigung (04 §3.2). */
    fun requestTakeOver() {
        val live = mutableState.value.live ?: return
        if (live.isScorer || live.status == MatchStatus.FINISHED) return
        setOverlay(LiveOverlay.CONFIRM_TAKE_OVER)
    }

    /** Bestätigte Übernahme: repliziertes `ScorerChanged`; erst das angewandte Event schaltet die Eingaben frei. */
    fun confirmTakeOver(): Boolean {
        overlay = null
        val taken = session.takeOverScoring()
        publish()
        return taken
    }

    fun dismissOverlay() = setOverlay(null)

    /** „Noch einer in gleicher Besetzung?“ → verknüpftes Folge-Match (02 §5.2). */
    fun followUp(): Boolean = session.createFollowUp() != null

    /** „Fertig“: Match schließen, zurück zur Startliste (es bleibt gespeichert und synchronisiert). */
    fun close() {
        closingLocally = true
        try {
            session.closeMatch()
        } finally {
            closingLocally = false
        }
        publish()
    }

    // --- intern -----------------------------------------------------------------------------------

    private fun start(setup: WatchSetup): Boolean =
        try {
            session.create(setup.format, setup.lineup, setup.meta)
            draft = null
            publish()
            true
        } catch (e: IllegalArgumentException) {
            emit(WatchEffect.Toast(WatchToast.StartFailed))
            false
        }

    private fun setOverlay(value: LiveOverlay?) {
        overlay = value
        publish()
    }

    private fun onSession(state: SessionState) {
        val match = state.match
        if (match?.id != seenMatch) {
            if (seenMatch != null && match == null && !closingLocally) emit(WatchEffect.Toast(WatchToast.MatchRemoved))
            seenMatch = match?.id
            seenScorer = match?.isScorer
            seenRejected = match?.rejectedByRoleCount ?: 0
            overlay = null
            locked = false
            disconnectedSince = null
            serveBar.dismiss()
            if (match != null) draft = null // ein vom Handy gestartetes Match übernimmt den Bildschirm
        } else if (match != null) {
            if (seenScorer != match.isScorer) {
                seenScorer = match.isScorer
                if (!match.isScorer) {
                    locked = false
                    if (overlay == LiveOverlay.CONFIRM_END) overlay = null
                } else if (overlay == LiveOverlay.CONFIRM_TAKE_OVER) {
                    overlay = null // Rolle kam inzwischen auf anderem Weg hierher
                }
                emit(WatchEffect.Toast(WatchToast.RoleChanged(match.isScorer, match.scorerKind)))
            }
            if (match.rejectedByRoleCount > seenRejected) {
                emit(WatchEffect.Toast(WatchToast.InputsRejected(match.rejectedByRoleCount)))
            }
            seenRejected = match.rejectedByRoleCount
        }
        // Nach der Wiederverbindung: verworfene Eingaben im früheren Uhr-Match sichtbar melden (Review W3-M #5)
        state.droppedInputs?.let { dropped ->
            if (dropped != seenDropped && dropped.matchId != match?.id) emit(WatchEffect.Toast(WatchToast.InputsRejected(dropped.count)))
            seenDropped = dropped
            // angezeigt (bzw. am offenen Match schon sichtbar): erst jetzt als gesehen quittieren (übersteht sonst Neustarts)
            session.acknowledgeDroppedInputs()
        }
        // Auswahl „Wer schlägt auf?“ schließt, sobald das Fenster zu ist (Punkt, Undo, Rollenwechsel)
        if (overlay == LiveOverlay.SERVER_CHOICE && (match?.isScorer != true || match.serverChoice == null)) overlay = null
        trackConnection(match, state)
        publish()
    }

    /**
     * Kontakt zum Zähler monoton verfolgen (Staleness, Übernahme-Angebot): Die Session liefert Wanduhr-Zeitstempel;
     * ein neuer Wert heißt „Kontakt jetzt“. Nur der erste beim Start schon vorhandene Wert wird über die Wanduhr
     * zurückgerechnet. Zeitumstellungen verfälschen die Dauern damit nicht.
     */
    private fun trackConnection(
        match: ActiveMatch?,
        state: SessionState,
    ) {
        // Beide Quellen getrennt: ein neuer Summary-Kontakt nach einem Wanduhr-Rücksprung ist kleiner als das letzte
        // Event und ginge in einem gemeinsamen Maximum verloren.
        state.sync.lastEventReceivedAtMs?.let { wall ->
            if (wall != seenEventWall) {
                eventAt = contactTime(wall, first = seenEventWall == null)
                seenEventWall = wall
            }
        }
        state.sync.lastSyncAtMs?.let { wall ->
            if (wall != seenSyncWall) {
                syncAt = contactTime(wall, first = seenSyncWall == null)
                seenSyncWall = wall
            }
        }
        disconnectedSince =
            when {
                match == null || match.isScorer || state.sync.scorerConnected -> null
                disconnectedSince != null -> disconnectedSince
                else -> lastContactAt ?: elapsed()
            }
    }

    /** Neuer Kontakt = jetzt; nur der beim Start schon vorhandene Wert wird über die Wanduhr zurückgerechnet. */
    private fun contactTime(
        wall: Long,
        first: Boolean,
    ): Long {
        val now = elapsed()
        return if (first) now - (wallClock() - wall).coerceAtLeast(0) else now
    }

    private fun publish() {
        mutableState.value = WatchUiState(screen())
    }

    private fun screen(): WatchScreen {
        val sessionState = session.state.value
        val match = sessionState.match
        if (match != null) {
            return WatchScreen.Live(
                LiveModel.build(
                    match = match,
                    sync = sessionState.sync,
                    names = ::nameOf,
                    isSelf = directory::isSelf,
                    contactAgoMs = lastContactAt?.let { elapsed() - it },
                    disconnectedForMs = disconnectedSince?.let { elapsed() - it },
                    locked = locked,
                    overlay = overlay,
                    serveBarPoint = serveBar.visiblePoint(elapsed()),
                    choiceMadeInWindow = openingChoice.choiceMade(match),
                ),
            )
        }
        val d = draft ?: return homeScreen()
        val sport = d.sport
        val day = today()
        val gamesOptions = d.format?.let(SetupCatalog::gamesOptions).orEmpty()
        return WatchScreen.Setup(
            step = d.step,
            sport = sport,
            disciplines = sport?.let(SetupCatalog::disciplines).orEmpty(),
            discipline = d.discipline,
            formats = sport?.let { SetupCatalog.formats(it, day) }.orEmpty(),
            defaultFormat = sport?.let { SetupCatalog.defaultFormat(it, day) },
            format = d.format,
            workoutEnabled = prefs.workoutEnabled,
            gamesOptions = gamesOptions,
            gamesPerSet = if (gamesOptions.isEmpty()) null else d.gamesPerSet ?: d.format?.let(SetupCatalog::defaultGames),
            serveStatsAvailable = sport?.let(SetupCatalog::serveStatsAvailable) == true,
            serveStats = d.serveStats,
            recentPlayers = directory.recentPlayers(),
            selectedPlayers = d.selectedPlayers,
        )
    }

    private fun homeScreen(): WatchScreen.Home {
        val day = today()
        val last =
            directory.lastSetup()?.let { s ->
                val recognized = SetupCatalog.recognize(s.format, day)
                SetupSummary(s.format.sport, s.format.discipline, recognized?.choice, s.format, recognized?.gamesPerSet)
            }
        // Mit Zuweisung des Handys (F22) bietet die Uhr nur ihr Uhr-Match an, nie die Director-Matches der Nachbarplätze
        val assigned = session.state.value.watchMatchId
        val resume =
            (
                if (assigned != null) {
                    session.data.matches
                        .get(assigned)
                        ?.takeIf { !it.isFinished }
                } else {
                    directory.resumable()
                }
            )?.let { r -> ResumeUi(r.id, r.sport, LiveModel.setsText(r.score)) }
        val recent =
            directory.recentResults().map { r ->
                ResultUi(r.id, r.sport, LiveModel.setsText(r.score), r.score.winner, r.score.reason)
            }
        val phoneConnected =
            session.state.value.sync.peers
                .any { it.kind == PeerKind.PHONE && it.compatibility == CompatibilityStatus.COMPATIBLE }
        val updateRequired = OperationRules.updateRequired(session.state.value.sync)
        return WatchScreen.Home(resume, last, recent, prefs.workoutEnabled, phoneConnected, updateRequired)
    }

    private fun nameOf(id: PlayerId): String? =
        session.state.value.match
            ?.id
            ?.let { session.data.assignments(it)[id]?.displayName } ?: directory.displayName(id)

    private fun emit(effect: WatchEffect) {
        effects?.invoke(effect)
    }

    private companion object {
        val ATTENTION_HINTS = setOf(ScoreHint.Kind.MatchPoint, ScoreHint.Kind.SetPoint, ScoreHint.Kind.BreakPoint)

        fun hapticFor(cue: HapticCue): WatchHaptic =
            when (cue) {
                HapticCue.PointUs -> WatchHaptic.POINT_US
                HapticCue.PointThem -> WatchHaptic.POINT_THEM
                HapticCue.Undo -> WatchHaptic.UNDO
                HapticCue.GameWon -> WatchHaptic.GAME_WON
                HapticCue.GameLost -> WatchHaptic.GAME_LOST
                HapticCue.SetWon -> WatchHaptic.SET_WON
                HapticCue.SetLost -> WatchHaptic.SET_LOST
                HapticCue.ChangeEnds -> WatchHaptic.CHANGE_ENDS
                HapticCue.Rejected -> WatchHaptic.REJECTED
            }
    }
}
