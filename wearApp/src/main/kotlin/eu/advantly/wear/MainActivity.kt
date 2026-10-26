package eu.advantly.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.wear.ambient.AmbientLifecycleObserver
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.match.session.AndroidSessionAccess
import eu.advantly.match.session.AndroidSessionBoundary
import eu.advantly.match.session.RandomIdGenerator
import eu.advantly.presentation.match.systemLocalDay
import eu.advantly.wear.diagnostics.DiagnosticLog
import eu.advantly.wear.glance.GlanceAction
import eu.advantly.wear.glance.GlanceIntentConsumption
import eu.advantly.wear.glance.GlanceLaunch
import eu.advantly.wear.live.ValueThrottle
import eu.advantly.wear.live.WatchController
import eu.advantly.wear.live.WatchEffect
import eu.advantly.wear.live.WatchTelemetryPolicy
import eu.advantly.wear.live.WatchToast
import eu.advantly.wear.live.displayMillis
import eu.advantly.wear.match.FormatChoice
import eu.advantly.wear.match.PlayerSlot
import eu.advantly.wear.match.WatchDirectory
import eu.advantly.wear.sync.MatchNotifier
import eu.advantly.wear.sync.UiVisibility
import eu.advantly.wear.sync.WearSessions
import eu.advantly.wear.ui.Haptics
import eu.advantly.wear.ui.WatchActions
import eu.advantly.wear.ui.WearApp
import eu.advantly.wear.workout.CapabilitiesRepository
import eu.advantly.wear.workout.Permissions
import eu.advantly.wear.workout.SessionStore
import eu.advantly.wear.workout.WorkoutAction
import eu.advantly.wear.workout.WorkoutCoordinator
import eu.advantly.wear.workout.WorkoutInputs
import eu.advantly.wear.workout.WorkoutPermissions
import eu.advantly.wear.workout.WorkoutService
import eu.advantly.wear.workout.WorkoutSport
import eu.advantly.wear.workout.WorkoutStateHolder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Hält den [WatchController] über Konfigurationswechsel; die Session selbst ist prozessweit ([WearSessions]). */
class WatchViewModel(
    val controller: WatchController,
) : ViewModel() {
    init {
        controller.start()
    }

    override fun onCleared() {
        controller.dispose()
    }
}

/**
 * Einzige Activity der Wear-App: Startliste, Kurz-Wizard, Zähler-/Anzeige-Ansicht über die prozessweite
 * `MatchSession` (Art `WATCH`). Hier liegen nur Android-Bindungen – Ambient (reduzierte Darstellung, Puls ≤ 1×/min),
 * Haptik/Toasts aus den Controller-Effekten, Laufzeitrechte und der Start des Workouts zum offenen Match (nur aus dem
 * Vordergrund, 05 §2.2). Die Logik steckt im JVM-getesteten [WatchController].
 */
class MainActivity : ComponentActivity() {
    @Volatile private var sessionReady = false
    private var sessionAccess: AndroidSessionAccess? = null
    private var runtimeBoundary: AndroidSessionBoundary? = null
    private var runtimeJobs = mutableListOf<kotlinx.coroutines.Job>()
    private var powerReceiverRegistered = false
    private var runtimeStopped = false

    private fun runtimeUnavailable() {
        if (runtimeStopped) return
        runtimeStopped = true
        sessionReady = false
        runtimeJobs.forEach { it.cancel() }
        runtimeJobs.clear()
        UiVisibility.resumed = false
        if (powerReceiverRegistered) {
            unregisterReceiver(powerSaveReceiver)
            powerReceiverRegistered = false
        }
        if (::holder.isInitialized) {
            activeController?.effects = null
            activeController?.dispose()
            activeController = null
            lifecycle.removeObserver(ambientObserver)
        }
        viewModelStore.clear()
        shownTelemetry.value = null to null
        if (!isFinishing && !isDestroyed) showInstallationError()
    }

    private fun currentSession(): Boolean = sessionReady && sessionAccess?.ready == true

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (sessionAccess?.ready == false && !runtimeStopped) {
            runtimeUnavailable()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private val glanceLaunch = mutableIntStateOf(0)
    private lateinit var glanceConsumption: GlanceIntentConsumption
    private val isAmbient = mutableStateOf(false)
    private val permissions = mutableStateOf(WorkoutPermissions.ALL)
    private val toast = mutableStateOf<WatchToast?>(null)
    private val shownTelemetry = mutableStateOf<Pair<Int?, Long?>>(null to null)
    private val powerSave = mutableStateOf(false)
    private val powerSaveReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = updatePowerSave()
        }
    private var telemetryThrottle = ValueThrottle<Pair<Int?, Long?>>()
    private var telemetryMatch: eu.advantly.core.model.MatchId? = null
    private val handler = Handler(Looper.getMainLooper())
    private val clearToast = Runnable { toast.value = null }
    private lateinit var haptics: Haptics
    private lateinit var holder: WearSessions.Holder

    /** Ein Rechte-Dialog ist offen (übersteht die Neuerstellung über den Saved State). */
    private var permissionRequestInFlight = false

    /** Erst gesetzt, wenn [start] die Entscheidungen dieser Instanz getroffen hat (nicht schon im Ladezustand). */
    private var started = false
    private var newIntentWhilePending = false

    private val viewModel: WatchViewModel by viewModels {
        viewModelFactory { initializer { WatchViewModel(createController()) } }
    }
    private var activeController: WatchController? = null
    private val controller: WatchController get() = viewModel.controller.also { activeController = it }

    private val ambientCallback =
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                if (!currentSession()) return
                isAmbient.value = true
                controller.setInteractive(false)
                DiagnosticLog.log("AMBIENT_ENTER", "burnIn" to ambientDetails.burnInProtectionRequired)
            }

            override fun onUpdateAmbient() {
                if (!currentSession()) return
                // ≤ 1×/min: Staleness und Puls nachziehen (04 §4.3.6)
                controller.tick()
                updateTelemetry()
            }

            override fun onExitAmbient() {
                if (!currentSession()) return
                isAmbient.value = false
                controller.onWake()
                updateTelemetry()
                DiagnosticLog.log("AMBIENT_EXIT")
            }
        }

    private val ambientObserver by lazy { AmbientLifecycleObserver(this, ambientCallback) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (!currentSession()) return@registerForActivityResult
            DiagnosticLog.log("PERMISSIONS_RESULT", *result.map { (k, v) -> k.substringAfterLast('.') to v }.toTypedArray())
            permissions.value = Permissions.current(this)
            permissionRequestInFlight = false
            onPermissionDecision()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Kopie und quick_check laufen im Hintergrund; bis dahin nur die Ladeansicht (bekanntes Ergebnis: sofort).
        showInstallationLoading()
        AndroidInstallationGuard.checkAsync(this) { availability ->
            if (!isFinishing && !isDestroyed) start(savedInstanceState, availability)
        }
    }

    private fun start(
        savedInstanceState: Bundle?,
        availability: AndroidInstallationAvailability,
    ) {
        started = true
        // Konfigurationswechsel im Ladezustand zählt nicht als Wiederherstellung (sonst fehlen Hold-Dialog und Glance).
        val restored = savedInstanceState?.getBoolean(KEY_STARTED, true) == true
        if (availability !is AndroidInstallationAvailability.Ready) {
            showInstallationError()
            return
        }
        val afterInstallationRetry = intent.getBooleanExtra(INSTALLATION_RETRY_COMPLETE, false)
        intent.removeExtra(INSTALLATION_RETRY_COMPLETE)
        if ((application as? AdvantlyWearApplication)?.bootstrap() == false) {
            showInstallationError()
            return
        }
        sessionReady = true
        // Ein restauriertes Task-Intent darf nie erneut einen Schnellstart auslösen.
        // Ein neues Intent während der Prüfung (Tile-Tipp nach Prozesstod) ist nicht verbraucht.
        glanceConsumption = GlanceIntentConsumption(consumed = restored && !newIntentWhilePending)
        lifecycle.addObserver(ambientObserver)
        haptics = Haptics(this)
        holder = AndroidInstallationGuard.storage(this) { WearSessions.get(this) } ?: run {
            showInstallationError()
            return
        }
        sessionAccess =
            AndroidSessionAccess(this, holder.session) {
                sessionReady = false
                handler.post { runtimeUnavailable() }
            }
        runtimeBoundary = AndroidSessionBoundary(applicationContext, allowed = { currentSession() })
        if (runtimeBoundary?.run { controller } == null) {
            runtimeUnavailable()
            return
        }
        if (!availability.installation.privateTransportAllowed && (!restored || afterInstallationRetry)) {
            showRecoveryDecision(availability.installation, holder.session)
        } else if (availability.installation.restoreFailed && !restored) {
            showLaterRestoreFailed()
        }
        runtimeJobs +=
            lifecycleScope.launch {
                holder.session.state
                    .map { it.pairingConflict }
                    .distinctUntilChanged()
                    .collect { conflict -> if (conflict && currentSession()) showPairingRepair(holder.session, ::currentSession) }
            }
        permissions.value = Permissions.current(this)
        permissionRequestInFlight = savedInstanceState?.getBoolean(KEY_PERMISSION_IN_FLIGHT) == true
        if (!permissionRequestInFlight && holder.prefs.workoutPending != null) {
            // Dialog ging verloren (z. B. Prozess neu, ohne Ergebnis) → Match gilt nicht als erledigt, neu fragen
            holder.prefs.workoutPending = null
        }
        DiagnosticLog.log("ACTIVITY_CREATE", "restored" to restored)

        // Capabilities beim App-Start abfragen und cachen (05 §0.1).
        runtimeJobs +=
            lifecycleScope.launch {
                runCatching { CapabilitiesRepository.load(this@MainActivity) }
                    .onFailure { DiagnosticLog.log("CAPABILITIES_FAILED", "error" to it.javaClass.simpleName, "message" to it.message) }
            }
        // Workout war aktiv, Prozess wurde aber beendet → evaluateWorkout() (onResume) gleicht über den Marker ab

        // Workout an das offene Match koppeln, solange die UI sichtbar ist (FGS-Start nur aus dem Vordergrund)
        runtimeJobs +=
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    combine(holder.session.state, WorkoutStateHolder.state) { _, workout -> workout }
                        .collect {
                            if (!currentSession()) return@collect
                            updateTelemetry()
                            evaluateWorkout()
                        }
                }
            }

        setContent {
            val state by controller.state.collectAsState()
            val workout by WorkoutStateHolder.state.collectAsState()
            // Telemetrie-Sichtbarkeit (P2-J) direkt aus der Session: „Gar nicht“ blendet Puls/Schritte sofort aus
            val telemetryVisibility by remember {
                holder.session.state
                    .map { it.telemetryVisibility }
                    .distinctUntilChanged()
            }.collectAsState(TelemetryVisibility.DEFAULT)
            val ambient = isAmbient.value
            // Sekundentakt nur interaktiv (Staleness „Stand vor X s“); im Ambient über onUpdateAmbient
            LaunchedEffect(ambient) {
                while (!ambient) {
                    kotlinx.coroutines.delay(TICK_MS)
                    if (!currentSession()) break
                    controller.tick()
                }
            }
            WearApp(
                state = state,
                workout =
                    WatchTelemetryPolicy.apply(
                        workout.copy(heartRateBpm = shownTelemetry.value.first, steps = shownTelemetry.value.second),
                        telemetryVisibility,
                    ),
                permissions = permissions.value,
                heartRate = shownTelemetry.value.first.takeIf { telemetryVisibility.allowsLive },
                isAmbient = ambient,
                powerSave = powerSave.value,
                toast = toast.value,
                actions = actions,
                glanceLaunch = glanceLaunch.intValue,
                onRestorePrevious =
                    availability.installation
                        .takeIf { it.privateTransportAllowed && it.restorable != null }
                        ?.let { { showLaterRestore(holder.session) } },
            )
        }
        // Kam das Ergebnis erst nach onStart/onResume, die dort ausgelassenen Schritte nachholen.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startRuntime()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resumeRuntime()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PERMISSION_IN_FLIGHT, permissionRequestInFlight)
        outState.putBoolean(KEY_STARTED, started)
    }

    override fun onStart() {
        super.onStart()
        startRuntime()
    }

    private fun startRuntime() {
        if (!currentSession()) return
        controller.effects = ::onEffect
        updatePowerSave()
        ContextCompat.registerReceiver(
            this,
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        powerReceiverRegistered = true
    }

    /** Energiesparmodus kann die Verbindung zum Handy unterbrechen (Feldtest) → Hinweis auf der Startliste. */
    private fun updatePowerSave() {
        val saving = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        if (saving != powerSave.value) DiagnosticLog.log("POWER_SAVE", "on" to saving)
        powerSave.value = saving
    }

    override fun onResume() {
        super.onResume()
        resumeRuntime()
    }

    private fun resumeRuntime() {
        if (!currentSession()) return
        UiVisibility.resumed = true
        controller.setInteractive(!isAmbient.value)
        consumeGlanceAction()
        MatchNotifier.cancel(this)
        permissions.value = Permissions.current(this) // Rechte können in den Einstellungen geändert worden sein
        runtimeBoundary?.run { holder.session.syncNow() }
        controller.tick()
        evaluateWorkout()
        askNotificationsOnce()
        DiagnosticLog.log("ACTIVITY_RESUME")
    }

    override fun onPause() {
        if (!currentSession()) {
            super.onPause()
            return
        }
        UiVisibility.resumed = false
        controller.setInteractive(false)
        DiagnosticLog.log("ACTIVITY_PAUSE")
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Auch während der Installationsprüfung übernehmen; start() wertet die neueste Glance-Aktion dann als unverbraucht.
        setIntent(intent)
        if (!started) newIntentWhilePending = true
        if (!currentSession()) return
        glanceConsumption.onNewIntent()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) consumeGlanceAction()
    }

    /** Erst aus der sichtbaren Activity starten; Extra verbrauchen, bevor Session/Rechte-Dialoge reagieren. */
    private fun consumeGlanceAction() {
        runtimeBoundary?.run { consumeGlanceActionReady() }
    }

    private fun consumeGlanceActionReady() {
        val value = glanceConsumption.consume(intent.getStringExtra(GlanceLaunch.EXTRA_ACTION)) ?: return
        intent.removeExtra(GlanceLaunch.EXTRA_ACTION)
        // Eine bereits offene Steuerseite beim externen Einstieg wieder auf die Score-Seite setzen.
        glanceLaunch.intValue++
        val status =
            holder.session.state.value.match
                ?.state
                ?.status
        val action = GlanceLaunch.resolve(value, status)
        // Das Ergebnis bleibt gespeichert; ein Schnellstart nach Matchende darf direkt beginnen.
        if (status == MatchStatus.FINISHED && action != GlanceAction.OPEN) controller.close()
        when (action) {
            GlanceAction.REPEAT -> if (!controller.repeatLast()) controller.newMatch()
            GlanceAction.NEW -> controller.newMatch()
            GlanceAction.OPEN -> controller.tick()
        }
    }

    override fun onStop() {
        if (powerReceiverRegistered) unregisterReceiver(powerSaveReceiver)
        powerReceiverRegistered = false
        activeController?.effects = null
        super.onStop()
    }

    override fun onDestroy() {
        sessionAccess?.close()
        runtimeJobs.forEach { it.cancel() }
        handler.removeCallbacks(clearToast)
        DiagnosticLog.log("ACTIVITY_DESTROY", "finishing" to isFinishing)
        super.onDestroy()
    }

    private fun createController(): WatchController {
        val h = holder
        val calls = AndroidSessionBoundary.forSession(applicationContext, h.session)
        val ids = RandomIdGenerator()
        try {
            val directory =
                WatchDirectory(
                    data = h.data,
                    nowMs = System::currentTimeMillis,
                    newId = { PlayerId(ids.newUuid7(System.currentTimeMillis()).value) },
                )
            return WatchController(
                session = h.session,
                directory = directory,
                prefs = h.prefs,
                wallClock = System::currentTimeMillis,
                elapsed = SystemClock::elapsedRealtime,
                uptime = SystemClock::uptimeMillis,
                today = ::systemLocalDay,
                operationBoundary = { action ->
                    calls.run {
                        action()
                        true
                    } == true
                },
                disposeBoundary = calls::close,
            )
        } catch (error: Exception) {
            calls.close()
            throw error
        }
    }

    private fun onEffect(effect: WatchEffect) {
        if (!currentSession()) return
        when (effect) {
            is WatchEffect.Haptic -> {
                if (!isAmbient.value) haptics.play(effect.pattern, effect.attention)
            }

            is WatchEffect.Toast -> {
                toast.value = effect.toast
                handler.removeCallbacks(clearToast)
                handler.postDelayed(clearToast, effect.toast.displayMillis)
            }
        }
    }

    private fun updateTelemetry() {
        if (!currentSession()) return
        val session = holder.session.state.value
        val match = session.match?.id
        if (match != telemetryMatch || !session.telemetryVisibility.allowsLive) {
            // Match-Wechsel oder „Gar nicht“ (P2-J): angezeigte/gedrosselte Werte verwerfen
            telemetryMatch = match
            telemetryThrottle = ValueThrottle()
        }
        val workout = WorkoutStateHolder.state.value
        shownTelemetry.value =
            telemetryThrottle.display(workout.heartRateBpm to workout.steps, SystemClock.elapsedRealtime(), isAmbient.value)
                ?: (null to null)
    }

    // --- Workout ↔ Match --------------------------------------------------------------------------

    private fun evaluateWorkout() {
        runtimeBoundary?.run { evaluateWorkoutReady() }
    }

    private fun evaluateWorkoutReady() {
        if (!currentSession()) return
        if (!UiVisibility.resumed) return
        val match = holder.session.state.value.match
        val inputs =
            WorkoutInputs(
                // Handy-Matches: Wahl im Handy-Assistenten; Uhr-Matches: Uhr-Einstellung (beides in MatchMeta.watchWorkout)
                enabled = WorkoutCoordinator.enabled(match?.meta, holder.prefs.workoutEnabled),
                matchId = match?.id,
                sport = match?.format?.sport,
                phase = WorkoutStateHolder.state.value.phase,
                handledMatchId = holder.prefs.workoutHandledMatchId,
                pendingMatchId = holder.prefs.workoutPending?.first,
                markerActive = SessionStore(this).sessionActive,
            )
        when (val action = WorkoutCoordinator.decide(inputs)) {
            is WorkoutAction.Start -> requestWorkout(action)
            WorkoutAction.Stop -> WorkoutService.stop(this)
            WorkoutAction.Recover -> WorkoutService.recover(this)
            WorkoutAction.None -> Unit
        }
    }

    /**
     * Einmal je Match: fehlende Rechte erfragen (kontextuell beim ersten Workout), dann starten. Der Auftrag liegt bis
     * zur Antwort persistent in den Prefs; als „erledigt“ gilt das Match erst nach der Entscheidung.
     */
    private fun requestWorkout(action: WorkoutAction.Start) {
        if (permissionRequestInFlight) return // anderer Dialog offen; nach dessen Antwort wird neu bewertet
        val missing = Permissions.missing(this)
        if (missing.isEmpty()) {
            holder.prefs.workoutHandledMatchId = action.matchId
            launchWorkout(action.sport)
            return
        }
        holder.prefs.workoutPending = action.matchId to action.sport
        launchPermissions(missing)
    }

    /** Antwort auf einen Rechte-Dialog: ausstehenden Workout-Auftrag abschließen, dann neu bewerten. */
    private fun onPermissionDecision() {
        runtimeBoundary?.run { onPermissionDecisionReady() }
    }

    private fun onPermissionDecisionReady() {
        if (!currentSession()) return
        val pending = holder.prefs.workoutPending
        holder.prefs.workoutPending = null
        if (pending != null) {
            holder.prefs.workoutHandledMatchId = pending.first
            // Match inzwischen gewechselt/geschlossen → nichts starten, die Bewertung unten entscheidet neu
            if (holder.session.state.value.match
                    ?.id == pending.first
            ) {
                launchWorkout(pending.second)
            }
        }
        evaluateWorkout()
    }

    /** Benachrichtigungen unabhängig vom Workout einmal anbieten (Hinweis auf Handy-Matches, Zifferblatt-Symbol). */
    private fun askNotificationsOnce() {
        runtimeBoundary?.run { askNotificationsOnceReady() }
    }

    private fun askNotificationsOnceReady() {
        if (!currentSession()) return
        if (holder.prefs.notificationsAsked || permissionRequestInFlight || permissions.value.notifications) return
        holder.prefs.notificationsAsked = true
        launchPermissions(listOf(android.Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun launchPermissions(missing: List<String>) {
        permissionRequestInFlight = true
        permissionLauncher.launch(missing.toTypedArray())
    }

    /** Mit Puls- oder Aktivitätsrecht Health-FGS + Exercise, sonst Match ohne Workout (Hinweis in der UI). */
    private fun launchWorkout(sport: WorkoutSport) {
        if (!currentSession()) return
        if (Permissions.current(this).canRunHealthService) {
            WorkoutService.start(this, sport)
        } else {
            DiagnosticLog.log("COUNT_ONLY_MODE", "reason" to "permissions_denied")
            WorkoutStateHolder.update { it.copy(countOnly = true) }
        }
    }

    private val actions =
        object : WatchActions {
            override fun newMatch() = if (currentSession()) controller.newMatch() else Unit

            override fun repeatLast(): Boolean = currentSession() && (controller.repeatLast())

            override fun resume(matchId: MatchId): Boolean = currentSession() && (controller.resume(matchId))

            override fun setWorkoutEnabled(enabled: Boolean) = if (currentSession()) controller.setWorkoutEnabled(enabled) else Unit

            override fun setTelemetryVisibility(visibility: TelemetryVisibility) =
                if (currentSession()) controller.setTelemetryVisibility(visibility) else Unit

            override fun selectPlayer(
                slot: PlayerSlot,
                player: eu.advantly.core.model.PlayerId?,
            ) = if (currentSession()) controller.selectPlayer(slot, player) else Unit

            override fun selectSport(sport: Sport) = if (currentSession()) controller.selectSport(sport) else Unit

            override fun selectDiscipline(discipline: Discipline) = if (currentSession()) controller.selectDiscipline(discipline) else Unit

            override fun selectFormat(choice: FormatChoice) = if (currentSession()) controller.selectFormat(choice) else Unit

            override fun selectServer(
                server: Side,
                serverIndex: Int,
            ): Boolean = currentSession() && (controller.selectServer(server, serverIndex))

            override fun back() = if (currentSession()) controller.back() else Unit

            override fun point(side: Side): Boolean = currentSession() && (!isAmbient.value && controller.point(side))

            override fun undo(): Boolean = currentSession() && (!isAmbient.value && controller.undo())

            override fun redo(): Boolean = currentSession() && (controller.redo())

            override fun pause(): Boolean = currentSession() && (controller.pause())

            override fun resumePlay(): Boolean = currentSession() && (controller.resumePlay())

            override fun lock() = if (currentSession()) controller.lock() else Unit

            override fun unlock() = if (currentSession()) controller.unlock() else Unit

            override fun requestEnd() = if (currentSession()) controller.requestEnd() else Unit

            override fun requestCorrection() = if (currentSession()) controller.requestCorrection() else Unit

            override fun correctServer(player: PlayerId): Boolean = currentSession() && (controller.correctServer(player))

            override fun swapPositions(side: Side): Boolean = currentSession() && (controller.swapPositions(side))

            override fun confirmEnd(): Boolean = currentSession() && (controller.confirmEnd())

            override fun requestTakeOver() = if (currentSession()) controller.requestTakeOver() else Unit

            override fun confirmTakeOver(): Boolean = currentSession() && (controller.confirmTakeOver())

            override fun dismissOverlay() = if (currentSession()) controller.dismissOverlay() else Unit

            override fun continueDecision(
                anotherSet: Boolean,
                matchTiebreak: Boolean,
            ): Boolean = currentSession() && (controller.continueDecision(anotherSet, matchTiebreak))

            override fun followUp(): Boolean = currentSession() && (controller.followUp())

            override fun close() = if (currentSession()) controller.close() else Unit

            override fun retryWorkout() = if (currentSession()) WorkoutService.recover(this@MainActivity) else Unit

            override fun replaceOtherWorkout() = if (currentSession()) WorkoutService.replaceOther(this@MainActivity) else Unit

            override fun skipWorkout() = if (currentSession()) WorkoutService.stop(this@MainActivity) else Unit

            // --- Bedienung W2 ---

            override fun selectGamesPerSet(games: Int) = if (currentSession()) controller.selectGamesPerSet(games) else Unit

            override fun setServeStats(enabled: Boolean) = if (currentSession()) controller.setServeStats(enabled) else Unit

            override fun removePoint(side: Side): Boolean = currentSession() && (!isAmbient.value && controller.removePoint(side))

            override fun setServe(outcome: ServeOutcome): Boolean = currentSession() && (!isAmbient.value && controller.setServe(outcome))

            override fun openServerChoice() = if (currentSession()) controller.openServerChoice() else Unit

            override fun chooseServer(
                player: PlayerId,
                expected: eu.advantly.match.session.ServerChoiceContext,
            ): Boolean = currentSession() && (controller.chooseServer(player, expected))

            override fun startNextSet(kind: NextSetKind): Boolean = currentSession() && (controller.startNextSet(kind))
        }

    private companion object {
        const val KEY_PERMISSION_IN_FLIGHT = "permissionRequestInFlight"
        const val KEY_STARTED = "installation_started"
        const val TICK_MS = 1_000L
    }
}
