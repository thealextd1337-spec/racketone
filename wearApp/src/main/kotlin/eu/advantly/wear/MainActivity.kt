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
import androidx.compose.runtime.mutableStateOf
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
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.match.session.RandomIdGenerator
import eu.advantly.presentation.match.systemLocalDay
import eu.advantly.wear.diagnostics.DiagnosticLog
import eu.advantly.wear.live.ValueThrottle
import eu.advantly.wear.live.WatchController
import eu.advantly.wear.live.WatchEffect
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

    private val viewModel: WatchViewModel by viewModels {
        viewModelFactory { initializer { WatchViewModel(createController()) } }
    }
    private val controller: WatchController get() = viewModel.controller

    private val ambientCallback =
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                isAmbient.value = true
                DiagnosticLog.log("AMBIENT_ENTER", "burnIn" to ambientDetails.burnInProtectionRequired)
            }

            override fun onUpdateAmbient() {
                // ≤ 1×/min: Staleness und Puls nachziehen (04 §4.3.6)
                controller.tick()
                updateTelemetry()
            }

            override fun onExitAmbient() {
                isAmbient.value = false
                controller.onWake()
                updateTelemetry()
                DiagnosticLog.log("AMBIENT_EXIT")
            }
        }

    private val ambientObserver = AmbientLifecycleObserver(this, ambientCallback)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            DiagnosticLog.log("PERMISSIONS_RESULT", *result.map { (k, v) -> k.substringAfterLast('.') to v }.toTypedArray())
            permissions.value = Permissions.current(this)
            permissionRequestInFlight = false
            onPermissionDecision()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        haptics = Haptics(this)
        holder = WearSessions.get(this)
        permissions.value = Permissions.current(this)
        permissionRequestInFlight = savedInstanceState?.getBoolean(KEY_PERMISSION_IN_FLIGHT) == true
        if (!permissionRequestInFlight && holder.prefs.workoutPending != null) {
            // Dialog ging verloren (z. B. Prozess neu, ohne Ergebnis) → Match gilt nicht als erledigt, neu fragen
            holder.prefs.workoutPending = null
        }
        DiagnosticLog.log("ACTIVITY_CREATE", "restored" to (savedInstanceState != null))

        // Capabilities beim App-Start abfragen und cachen (05 §0.1).
        lifecycleScope.launch {
            runCatching { CapabilitiesRepository.load(this@MainActivity) }
                .onFailure { DiagnosticLog.log("CAPABILITIES_FAILED", "error" to it.javaClass.simpleName, "message" to it.message) }
        }
        // Workout war aktiv, Prozess wurde aber beendet → evaluateWorkout() (onResume) gleicht über den Marker ab

        // Workout an das offene Match koppeln, solange die UI sichtbar ist (FGS-Start nur aus dem Vordergrund)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(holder.session.state, WorkoutStateHolder.state) { _, workout -> workout }
                    .collect {
                        updateTelemetry()
                        evaluateWorkout()
                    }
            }
        }

        setContent {
            val state by controller.state.collectAsState()
            val workout by WorkoutStateHolder.state.collectAsState()
            val ambient = isAmbient.value
            // Sekundentakt nur interaktiv (Staleness „Stand vor X s“); im Ambient über onUpdateAmbient
            LaunchedEffect(ambient) {
                while (!ambient) {
                    kotlinx.coroutines.delay(TICK_MS)
                    controller.tick()
                }
            }
            WearApp(
                state = state,
                workout = workout.copy(heartRateBpm = shownTelemetry.value.first, steps = shownTelemetry.value.second),
                permissions = permissions.value,
                heartRate = shownTelemetry.value.first,
                isAmbient = ambient,
                powerSave = powerSave.value,
                toast = toast.value,
                actions = actions,
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PERMISSION_IN_FLIGHT, permissionRequestInFlight)
    }

    override fun onStart() {
        super.onStart()
        controller.effects = ::onEffect
        updatePowerSave()
        ContextCompat.registerReceiver(
            this,
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Energiesparmodus kann die Verbindung zum Handy unterbrechen (Feldtest) → Hinweis auf der Startliste. */
    private fun updatePowerSave() {
        val saving = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        if (saving != powerSave.value) DiagnosticLog.log("POWER_SAVE", "on" to saving)
        powerSave.value = saving
    }

    override fun onResume() {
        super.onResume()
        UiVisibility.resumed = true
        MatchNotifier.cancel(this)
        permissions.value = Permissions.current(this) // Rechte können in den Einstellungen geändert worden sein
        holder.session.syncNow()
        controller.tick()
        evaluateWorkout()
        askNotificationsOnce()
        DiagnosticLog.log("ACTIVITY_RESUME")
    }

    override fun onPause() {
        UiVisibility.resumed = false
        DiagnosticLog.log("ACTIVITY_PAUSE")
        super.onPause()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(powerSaveReceiver) }
        controller.effects = null
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(clearToast)
        DiagnosticLog.log("ACTIVITY_DESTROY", "finishing" to isFinishing)
        super.onDestroy()
    }

    private fun createController(): WatchController {
        val h = WearSessions.get(this)
        val ids = RandomIdGenerator()
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
        )
    }

    private fun onEffect(effect: WatchEffect) {
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
        val match =
            holder.session.state.value.match
                ?.id
        if (match != telemetryMatch) {
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
        if (Permissions.current(this).canRunHealthService) {
            WorkoutService.start(this, sport)
        } else {
            DiagnosticLog.log("COUNT_ONLY_MODE", "reason" to "permissions_denied")
            WorkoutStateHolder.update { it.copy(countOnly = true) }
        }
    }

    private val actions =
        object : WatchActions {
            override fun newMatch() = controller.newMatch()

            override fun repeatLast(): Boolean = controller.repeatLast()

            override fun resume(matchId: MatchId): Boolean = controller.resume(matchId)

            override fun setWorkoutEnabled(enabled: Boolean) = controller.setWorkoutEnabled(enabled)

            override fun selectPlayer(
                slot: PlayerSlot,
                player: eu.advantly.core.model.PlayerId?,
            ) = controller.selectPlayer(slot, player)

            override fun selectSport(sport: Sport) = controller.selectSport(sport)

            override fun selectDiscipline(discipline: Discipline) = controller.selectDiscipline(discipline)

            override fun selectFormat(choice: FormatChoice) = controller.selectFormat(choice)

            override fun selectServer(
                server: Side,
                serverIndex: Int,
            ): Boolean = controller.selectServer(server, serverIndex)

            override fun back() = controller.back()

            override fun point(side: Side): Boolean = !isAmbient.value && controller.point(side)

            override fun undo(): Boolean = !isAmbient.value && controller.undo()

            override fun redo(): Boolean = controller.redo()

            override fun pause(): Boolean = controller.pause()

            override fun resumePlay(): Boolean = controller.resumePlay()

            override fun lock() = controller.lock()

            override fun unlock() = controller.unlock()

            override fun requestEnd() = controller.requestEnd()

            override fun requestCorrection() = controller.requestCorrection()

            override fun correctServer(player: PlayerId): Boolean = controller.correctServer(player)

            override fun swapPositions(side: Side): Boolean = controller.swapPositions(side)

            override fun confirmEnd(): Boolean = controller.confirmEnd()

            override fun requestTakeOver() = controller.requestTakeOver()

            override fun confirmTakeOver(): Boolean = controller.confirmTakeOver()

            override fun dismissOverlay() = controller.dismissOverlay()

            override fun continueDecision(
                anotherSet: Boolean,
                matchTiebreak: Boolean,
            ): Boolean = controller.continueDecision(anotherSet, matchTiebreak)

            override fun followUp(): Boolean = controller.followUp()

            override fun close() = controller.close()

            override fun retryWorkout() = WorkoutService.recover(this@MainActivity)

            override fun replaceOtherWorkout() = WorkoutService.replaceOther(this@MainActivity)

            override fun skipWorkout() = WorkoutService.stop(this@MainActivity)

            // --- Bedienung W2 ---

            override fun selectGamesPerSet(games: Int) = controller.selectGamesPerSet(games)

            override fun setServeStats(enabled: Boolean) = controller.setServeStats(enabled)

            override fun removePoint(side: Side): Boolean = !isAmbient.value && controller.removePoint(side)

            override fun setServe(outcome: ServeOutcome): Boolean = !isAmbient.value && controller.setServe(outcome)

            override fun openServerChoice() = controller.openServerChoice()

            override fun chooseServer(player: PlayerId): Boolean = controller.chooseServer(player)

            override fun startNextSet(kind: NextSetKind): Boolean = controller.startNextSet(kind)
        }

    private companion object {
        const val KEY_PERMISSION_IN_FLIGHT = "permissionRequestInFlight"
        const val TICK_MS = 1_000L
    }
}
