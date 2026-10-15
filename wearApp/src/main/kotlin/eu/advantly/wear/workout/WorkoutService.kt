package eu.advantly.wear.workout

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.clearUpdateCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.endExercise
import androidx.health.services.client.getCurrentExerciseInfo
import androidx.health.services.client.startExercise
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import eu.advantly.wear.MainActivity
import eu.advantly.wear.R
import eu.advantly.wear.diagnostics.DiagLine
import eu.advantly.wear.diagnostics.DiagnosticLog
import eu.advantly.wear.diagnostics.HeartRateGapDetector
import eu.advantly.wear.diagnostics.PeriodicTrigger
import eu.advantly.wear.live.LiveModel
import eu.advantly.wear.sync.LiveTelemetrySender
import eu.advantly.wear.sync.WearSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Foreground Service (Typ `health`) mit Health-Services-`ExerciseClient` und Ongoing Activity (05 §2.1/§2.2, 01 E15).
 * Hält die Zähl-App während des Matches am Leben – auch bei Display aus / Handgelenk unten –, zeigt den Live-Puls und
 * in der Ongoing Activity den Spielstand der Match-Session. Der Puls wird **nicht gespeichert** (P1, Health erst P3);
 * am Ende wird die Exercise beendet und verworfen. Flüchtige Werte gehen nur live ans eigene Handy.
 * Das Debug-Diagnoselog enthält Lebenszyklus, Sensor-Lücken, Akku und Displaystatus, keine Messwerte.
 *
 * Robustheit:
 * - Eine **fremde** Exercise wird nie stillschweigend ersetzt ([ExerciseOwnership]); die UI fragt nach ([WorkoutPhase.CONFLICT]).
 * - Abfragen (Capabilities, Exercise-Status) laufen mit begrenzten Wiederholungen; danach bedienbarer Fehlerzustand
 *   ([WorkoutPhase.FAILED] + „Erneut versuchen“) statt Absturz. Unerwartete Exceptions fängt ein Handler ab.
 * - Der Update-Callback gilt erst nach `onRegistered()` als angemeldet; bei Fehlschlag wird neu angemeldet.
 * - Beim Beenden wird das Ende bestätigt; scheitert es, bleiben Callback, FGS und Recovery-Marker erhalten.
 * - Fehlen Puls- und Aktivitätsrecht, startet kein Health-FGS (die Activity zählt dann ohne Workout weiter).
 */
class WorkoutService : Service() {
    private val exceptionHandler =
        CoroutineExceptionHandler { _, e ->
            DiagnosticLog.log("COROUTINE_ERROR", "error" to e.javaClass.simpleName, "message" to e.message)
            WorkoutStateHolder.fail(WorkoutError.INTERNAL, e.message ?: e.javaClass.simpleName)
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)
    private lateinit var exerciseClient: ExerciseClient
    private lateinit var store: SessionStore

    private var ongoingActivity: OngoingActivity? = null
    private var foregroundStarted = false
    private var callbackRequested = false
    private var callbackConfirmed = false
    private var callbackAttempts = 0
    private var sessionJob: Job? = null
    private var stopJob: Job? = null
    private var tickerJob: Job? = null
    private var scoreJob: Job? = null

    private val gapDetector = HeartRateGapDetector()
    private val batteryTrigger = PeriodicTrigger(BATTERY_LOG_INTERVAL_MILLIS)
    private var lastState: ExerciseState? = null
    private var hrSampleCount = 0L
    private var hrGapCount = 0L
    private var stepCounter = MatchStepCounter()
    private var fallbackStepsTotal = 0L
    private var lastStepEndMs = -1L
    private var updateCount = 0L
    private val telemetry by lazy { LiveTelemetrySender(this, WearSessions.get(this).session) }

    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> DiagnosticLog.log("SCREEN", "state" to "on")
                    Intent.ACTION_SCREEN_OFF -> DiagnosticLog.log("SCREEN", "state" to "off")
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.log("SERVICE_CREATE")
        exerciseClient = HealthServices.getClient(this).exerciseClient
        store = SessionStore(this)
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        DiagnosticLog.log(
            "SERVICE_START_COMMAND",
            "action" to (intent?.action ?: "null(system-restart)"),
            "flags" to flags,
            "startId" to startId,
        )
        when (intent?.action) {
            ACTION_START -> {
                val sport = intent.getStringExtra(EXTRA_SPORT)?.let { runCatching { WorkoutSport.valueOf(it) }.getOrNull() }
                startSession(sport ?: WorkoutSport.TENNIS)
            }

            ACTION_STOP -> {
                stopSession()
            }

            ACTION_REPLACE_OTHER -> {
                replaceOtherExercise()
            }

            else -> {
                recoverSession()
            }
        }
        // Nur mit aktiver Session vom System neu starten lassen, sonst keine Neustart-Schleife.
        return if (store.sessionActive) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- Session

    private fun startSession(sport: WorkoutSport) {
        val phase = WorkoutStateHolder.state.value.phase
        if (sessionJob?.isActive == true || phase == WorkoutPhase.ACTIVE || phase == WorkoutPhase.CONFLICT) {
            DiagnosticLog.log("SESSION_START_IGNORED", "reason" to "already_running", "phase" to phase)
            return
        }
        val permissions = Permissions.current(this)
        if (!permissions.canRunHealthService) {
            // Sollte nicht vorkommen (Activity startet dann kein Workout) – ohne Rechte kein Health-FGS.
            DiagnosticLog.log("FOREGROUND_SKIPPED_NO_PERMISSION")
            WorkoutStateHolder.fail(WorkoutError.PERMISSIONS)
            stopSelf()
            return
        }
        val sessionId = "w-${System.currentTimeMillis()}"
        store.sessionId = sessionId
        store.sport = sport
        store.sessionActive = true
        store.endRequested = false
        resetCounters()
        DiagnosticLog.log("SESSION_START", "sessionId" to sessionId, "sport" to sport, "mode" to "workout")
        logPermissions(permissions)
        WorkoutStateHolder.update { WorkoutUiState(phase = WorkoutPhase.STARTING) }
        if (!startForegroundWithOngoingActivity()) return
        sessionJob = scope.launch { beginExercise(sport, userConfirmedReplace = false) }
    }

    /** Nutzer hat bestätigt, ein fremdes Training durch dieses Match zu ersetzen. */
    private fun replaceOtherExercise() {
        if (!store.sessionActive) return
        DiagnosticLog.log("EXERCISE_REPLACE_CONFIRMED")
        WorkoutStateHolder.update { it.copy(phase = WorkoutPhase.STARTING, error = null, errorDetail = null) }
        if (!startForegroundWithOngoingActivity()) return
        sessionJob?.cancel()
        sessionJob = scope.launch { beginExercise(store.sport, userConfirmedReplace = true) }
    }

    private fun recoverSession() {
        if (!store.sessionActive) {
            DiagnosticLog.log("SERVICE_RECOVER_SKIPPED", "reason" to "no_active_session")
            if (!foregroundStarted) stopSelf()
            return
        }
        val phase = WorkoutStateHolder.state.value.phase
        if (phase == WorkoutPhase.ACTIVE && callbackConfirmed) return
        if (sessionJob?.isActive == true || stopJob?.isActive == true) return
        if (phase == WorkoutPhase.FAILED && WorkoutStateHolder.state.value.error == WorkoutError.END) {
            // Beenden war fehlgeschlagen – „Erneut versuchen“ heißt hier: erneut beenden.
            stopSession()
            return
        }
        val holder = WearSessions.get(this)
        val match = holder.session.state.value.match
        val wanted = WorkoutCoordinator.enabled(match?.meta, holder.prefs.workoutEnabled)
        if (WorkoutCoordinator.recovery(store.endRequested, match != null, wanted) == RecoveryIntent.END) {
            // Prozess starb zwischen Match-Schließen und bestätigtem Exercise-Ende bzw. neues Match ohne Workout
            DiagnosticLog.log(
                "SESSION_RECOVER_END",
                "endRequested" to store.endRequested,
                "matchOpen" to (match != null),
                "wanted" to wanted,
            )
            stopSession()
            return
        }
        DiagnosticLog.log("SESSION_RECOVER", "sessionId" to store.sessionId, "sport" to store.sport)
        WorkoutStateHolder.update { it.copy(phase = WorkoutPhase.STARTING, error = null, errorDetail = null) }
        if (!startForegroundWithOngoingActivity()) return
        sessionJob =
            scope.launch {
                val status = queryTrackedStatus(retries = RETRY_DELAYS_MILLIS.size)
                DiagnosticLog.log("EXERCISE_INFO", "trackedStatus" to status)
                when (ExerciseOwnership.onRecover(status)) {
                    RecoveryAction.REATTACH -> {
                        // Exercise lief in Health Services weiter – nur Callback neu anmelden.
                        val caps = loadCapabilities()
                        when (val result = ExerciseOwnership.onReattach(store.sport, caps, Permissions.current(this@WorkoutService))) {
                            ReattachResult.CapabilitiesUnavailable -> {
                                DiagnosticLog.log("EXERCISE_REATTACH_FAILED", "reason" to "capabilities")
                                WorkoutStateHolder.fail(WorkoutError.CAPABILITIES)
                            }

                            is ReattachResult.Active -> {
                                stepCounter = MatchStepCounter.recovered()
                                registerCallback()
                                WorkoutStateHolder.update { it.copy(phase = WorkoutPhase.ACTIVE, selection = result.selection) }
                                DiagnosticLog.log("EXERCISE_REATTACHED")
                                startTicker()
                            }
                        }
                    }

                    RecoveryAction.RESTART -> {
                        DiagnosticLog.log("EXERCISE_RESTART_AFTER_KILL")
                        beginExercise(store.sport, userConfirmedReplace = false)
                    }

                    RecoveryAction.ASK_USER -> {
                        enterConflict()
                    }

                    RecoveryAction.RETRY_OR_FAIL -> {
                        DiagnosticLog.log("EXERCISE_INFO_FAILED", "context" to "recover")
                        WorkoutStateHolder.fail(WorkoutError.STATUS_QUERY)
                    }
                }
            }
    }

    private suspend fun beginExercise(
        sport: WorkoutSport,
        userConfirmedReplace: Boolean,
    ) {
        val caps =
            loadCapabilities() ?: run {
                WorkoutStateHolder.fail(WorkoutError.CAPABILITIES)
                return
            }
        val permissions = Permissions.current(this)
        val selection = ExerciseTypeSelector.select(sport, caps, permissions)
        if (selection == null) {
            DiagnosticLog.log("EXERCISE_UNSUPPORTED", "sport" to sport)
            WorkoutStateHolder.update { it.copy(phase = WorkoutPhase.ACTIVE, selection = null) }
            startTicker()
            return
        }
        val status = queryTrackedStatus(retries = RETRY_DELAYS_MILLIS.size)
        DiagnosticLog.log("EXERCISE_INFO", "trackedStatus" to status, "context" to "start")
        when (ExerciseOwnership.onStart(status, userConfirmedReplace)) {
            StartAction.ASK_USER -> {
                enterConflict()
                return
            }

            StartAction.RETRY_OR_FAIL -> {
                WorkoutStateHolder.fail(WorkoutError.STATUS_QUERY)
                return
            }

            StartAction.START -> {
                Unit
            }
        }
        val config =
            ExerciseConfig
                .Builder(HealthServicesMapping.exerciseType(selection.type))
                .setDataTypes(selection.metrics.map(HealthServicesMapping::dataType).toSet())
                .setIsAutoPauseAndResumeEnabled(false)
                .setIsGpsEnabled(false) // kein GPS (05 §2.1, größter Akkuhebel)
                .build()
        registerCallback()
        DiagnosticLog.log(
            "EXERCISE_START_REQUESTED",
            "sport" to sport,
            "type" to selection.type,
            "fallback" to selection.isFallback,
            "metrics" to selection.metrics.joinToString(","),
            "replacedOtherApp" to (status == TrackedStatus.OTHER_APP),
        )
        try {
            stepCounter = MatchStepCounter()
            fallbackStepsTotal = 0
            lastStepEndMs = -1
            exerciseClient.startExercise(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLog.log("EXERCISE_START_FAILED", "error" to e.javaClass.simpleName, "message" to e.message)
            WorkoutStateHolder.fail(WorkoutError.START, e.message ?: e.javaClass.simpleName)
            return
        }
        DiagnosticLog.log("EXERCISE_START_OK")
        WorkoutStateHolder.update { it.copy(phase = WorkoutPhase.ACTIVE, selection = selection, error = null, errorDetail = null) }
        startTicker()
    }

    private fun enterConflict() {
        DiagnosticLog.log("EXERCISE_CONFLICT_OTHER_APP")
        WorkoutStateHolder.update { it.copy(phase = WorkoutPhase.CONFLICT) }
    }

    private fun stopSession() {
        if (stopJob?.isActive == true) return
        DiagnosticLog.log("SESSION_STOP_REQUESTED", "sessionId" to store.sessionId)
        if (store.sessionActive) store.endRequested = true
        WorkoutStateHolder.update { it.copy(phase = WorkoutPhase.ENDING, error = null, errorDetail = null) }
        sessionJob?.cancel()
        stopJob =
            scope.launch {
                if (!endExerciseConfirmed()) {
                    // Zustand halten: Callback, FGS und Recovery-Marker bleiben, Nutzer kann erneut beenden.
                    DiagnosticLog.log("SESSION_END_FAILED", "sessionId" to store.sessionId)
                    WorkoutStateHolder.fail(WorkoutError.END)
                    return@launch
                }
                unregisterCallback()
                logStatus("SESSION_END")
                store.sessionActive = false
                store.endRequested = false
                tickerJob?.cancel()
                WorkoutStateHolder.reset()
                ServiceCompat.stopForeground(this@WorkoutService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                stopSelf()
            }
    }

    /**
     * Beendet die **eigene** Exercise und bestätigt das Ende per Statusabfrage. Eine fremde Exercise bleibt unangetastet.
     * Liefert `false`, wenn nach allen Versuchen weiterhin eine eigene Exercise läuft oder der Status unklar ist.
     */
    private suspend fun endExerciseConfirmed(): Boolean {
        repeat(END_ATTEMPTS) { attempt ->
            val before = queryTrackedStatus(retries = 0)
            if (ExerciseOwnership.isEndConfirmed(before)) {
                DiagnosticLog.log("EXERCISE_END_CONFIRMED", "status" to before, "attempt" to attempt)
                return true
            }
            if (ExerciseOwnership.needsEnd(before)) {
                try {
                    exerciseClient.endExercise()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagnosticLog.log(
                        "EXERCISE_END_FAILED",
                        "attempt" to attempt,
                        "error" to e.javaClass.simpleName,
                        "message" to e.message,
                    )
                }
            }
            repeat(END_CONFIRM_POLLS) {
                delay(END_CONFIRM_POLL_MILLIS)
                val after = queryTrackedStatus(retries = 0)
                if (ExerciseOwnership.isEndConfirmed(after)) {
                    DiagnosticLog.log("EXERCISE_END_CONFIRMED", "status" to after, "attempt" to attempt)
                    return true
                }
            }
        }
        return false
    }

    private fun resetCounters() {
        gapDetector.reset()
        lastState = null
        hrSampleCount = 0
        hrGapCount = 0
        stepCounter = MatchStepCounter()
        fallbackStepsTotal = 0
        lastStepEndMs = -1
        updateCount = 0
    }

    private fun logPermissions(permissions: WorkoutPermissions) {
        DiagnosticLog.log(
            "PERMISSIONS",
            "heartRate" to permissions.heartRate,
            "activityRecognition" to permissions.activityRecognition,
            "notifications" to permissions.notifications,
        )
    }

    // ---------------------------------------------------------------- Abfragen mit begrenzten Wiederholungen

    private suspend fun loadCapabilities(): ExerciseCapabilitiesView? {
        for (attempt in 0..RETRY_DELAYS_MILLIS.size) {
            try {
                return CapabilitiesRepository.load(this)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.log("CAPABILITIES_FAILED", "attempt" to attempt, "error" to e.javaClass.simpleName, "message" to e.message)
                RETRY_DELAYS_MILLIS.getOrNull(attempt)?.let { delay(it) }
            }
        }
        return null
    }

    private suspend fun queryTrackedStatus(retries: Int): TrackedStatus {
        for (attempt in 0..retries) {
            val status =
                try {
                    TrackedStatus.fromHealthServices(exerciseClient.getCurrentExerciseInfo().exerciseTrackedStatus)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagnosticLog.log(
                        "EXERCISE_INFO_ERROR",
                        "attempt" to attempt,
                        "error" to e.javaClass.simpleName,
                        "message" to e.message,
                    )
                    TrackedStatus.FAILED
                }
            if (status != TrackedStatus.FAILED) return status
            if (attempt < retries) RETRY_DELAYS_MILLIS.getOrNull(attempt)?.let { delay(it) }
        }
        return TrackedStatus.FAILED
    }

    // ---------------------------------------------------------------- Foreground + Ongoing Activity

    private fun startForegroundWithOngoingActivity(): Boolean {
        if (foregroundStarted) return true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_workout), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val touchIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_advantly)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(touchIntent)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setOngoing(true)
                .setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val activity =
            OngoingActivity
                .Builder(this, NOTIFICATION_ID, builder)
                .setStaticIcon(R.drawable.ic_stat_advantly)
                .setTouchIntent(touchIntent)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setTitle(getString(R.string.notification_title))
                .setStatus(buildStatus(currentScoreText()))
                .build()
        activity.apply(this)
        ongoingActivity = activity
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            foregroundStarted = true
            DiagnosticLog.log("FOREGROUND_STARTED", "type" to "health")
            observeScore()
            true
        } catch (e: Exception) {
            // z. B. SecurityException ohne Sensor-/Activity-Berechtigung oder ForegroundServiceStartNotAllowedException
            DiagnosticLog.log("FOREGROUND_START_FAILED", "error" to e.javaClass.simpleName, "message" to e.message)
            WorkoutStateHolder.fail(WorkoutError.FOREGROUND, e.message ?: e.javaClass.simpleName)
            stopSelf()
            false
        }
    }

    /** Spielstand des offenen Matches für die Ongoing Activity (z. B. „6:4 3:2 · 30:15“). */
    private fun currentScoreText(): String =
        LiveModel.ongoingText(
            WearSessions
                .get(this)
                .session.state.value.match,
        )

    private fun buildStatus(score: String): Status {
        val state = WorkoutStateHolder.state.value
        val anchor = state.durationAnchorElapsedMillis ?: SystemClock.elapsedRealtime()
        val stopwatchZero = anchor - state.activeDurationMillis
        return Status
            .Builder()
            .addTemplate(getString(R.string.ongoing_status_template))
            .addPart("score", Status.TextPart(score))
            .addPart("duration", Status.StopwatchPart(stopwatchZero))
            .build()
    }

    private fun observeScore() {
        scoreJob?.cancel()
        scoreJob =
            scope.launch {
                WearSessions
                    .get(this@WorkoutService)
                    .session.state
                    .map {
                        val next = stepCounter.forMatch(it.match?.id)
                        if (next != stepCounter) {
                            stepCounter = next
                            WorkoutStateHolder.update { workout -> workout.copy(steps = next.steps) }
                        }
                        LiveModel.ongoingText(it.match)
                    }.distinctUntilChanged()
                    .collect { score ->
                        // Ohne POST_NOTIFICATIONS läuft der Service weiter, nur der Status im Zifferblatt-Chip bleibt stehen.
                        val granted =
                            ContextCompat.checkSelfPermission(this@WorkoutService, Manifest.permission.POST_NOTIFICATIONS) ==
                                PackageManager.PERMISSION_GRANTED
                        if (granted) ongoingActivity?.update(this@WorkoutService, buildStatus(score))
                    }
            }
    }

    // ---------------------------------------------------------------- ExerciseClient-Callback

    private val updateCallback =
        object : ExerciseUpdateCallback {
            override fun onRegistered() {
                callbackConfirmed = true
                callbackAttempts = 0
                DiagnosticLog.log("CALLBACK_REGISTERED")
            }

            override fun onRegistrationFailed(throwable: Throwable) {
                callbackRequested = false
                callbackConfirmed = false
                callbackAttempts++
                DiagnosticLog.log(
                    "CALLBACK_REGISTRATION_FAILED",
                    "attempt" to callbackAttempts,
                    "error" to throwable.javaClass.simpleName,
                    "message" to throwable.message,
                )
                if (callbackAttempts <= RETRY_DELAYS_MILLIS.size) {
                    scope.launch {
                        delay(RETRY_DELAYS_MILLIS[callbackAttempts - 1])
                        if (store.sessionActive) registerCallback()
                    }
                } else {
                    WorkoutStateHolder.fail(WorkoutError.CALLBACK, throwable.message)
                }
            }

            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                handleUpdate(update)
            }

            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {
                DiagnosticLog.log("LAP", "count" to lapSummary.lapCount)
            }

            override fun onAvailabilityChanged(
                dataType: DataType<*, *>,
                availability: Availability,
            ) {
                DiagnosticLog.log("AVAILABILITY", "dataType" to dataType.name, "availability" to availability)
                if (dataType == DataType.HEART_RATE_BPM) {
                    WorkoutStateHolder.update { it.copy(heartRateAvailability = availability.toString()) }
                }
            }
        }

    /** Fordert die Anmeldung an; als angemeldet gilt der Callback erst nach `onRegistered()`. */
    private fun registerCallback() {
        if (callbackRequested) return
        callbackRequested = true
        callbackConfirmed = false
        exerciseClient.setUpdateCallback(ContextCompat.getMainExecutor(this), updateCallback)
    }

    private suspend fun unregisterCallback() {
        if (!callbackRequested) return
        runCatching { exerciseClient.clearUpdateCallback(updateCallback) }
            .onFailure { if (it is CancellationException) throw it }
        callbackRequested = false
        callbackConfirmed = false
    }

    private fun handleUpdate(update: ExerciseUpdate) {
        updateCount++
        val state = update.exerciseStateInfo.state
        if (state != lastState) {
            DiagnosticLog.log("EXERCISE_STATE", "state" to state.name, "endReason" to update.exerciseStateInfo.endReason)
            lastState = state
        }
        val metrics = update.latestMetrics
        val bootInstant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())

        val hrPoints = metrics.getData(DataType.HEART_RATE_BPM)
        val lines = ArrayList<Pair<String, Map<String, Any?>>>(hrPoints.size + 2)
        hrPoints.forEach { point ->
            val sampleMillis = point.getTimeInstant(bootInstant).toEpochMilli()
            gapDetector.onSample(sampleMillis)?.let { gap ->
                hrGapCount++
                lines +=
                    "HR_GAP" to
                    mapOf(
                        "from" to DiagLine.isoUtc(gap.fromEpochMillis),
                        "to" to DiagLine.isoUtc(gap.toEpochMillis),
                        "sec" to gap.durationMillis / 1000.0,
                    )
            }
        }
        hrSampleCount += hrPoints.size
        lines += "EXERCISE_UPDATE" to mapOf("n" to updateCount, "hrSamples" to hrPoints.size, "state" to state.name)
        DiagnosticLog.logAll(lines)

        val calories = metrics.getData(DataType.CALORIES_TOTAL)?.total
        stepCounter =
            stepCounter.forMatch(
                WearSessions
                    .get(this)
                    .session.state.value.match
                    ?.id,
            )
        val totalSteps = metrics.getData(DataType.STEPS_TOTAL)?.total
        if (totalSteps != null) {
            stepCounter = stepCounter.withTotal(totalSteps)
        } else if (WorkoutMetric.STEPS in (
                WorkoutStateHolder.state.value.selection
                    ?.metrics ?: emptySet()
            )
        ) {
            // Fallback für Geräte ohne STEPS_TOTAL: Intervalle nur einmal zählen; nach Prozessverlust ab null.
            val points = metrics.getData(DataType.STEPS).sortedBy { it.endDurationFromBoot }
            points.forEach { point ->
                val endMs = point.endDurationFromBoot.toMillis()
                if (endMs > lastStepEndMs) {
                    fallbackStepsTotal += point.value
                    lastStepEndMs = endMs
                    stepCounter = stepCounter.withTotal(fallbackStepsTotal)
                }
            }
        }
        val checkpoint = update.activeDurationCheckpoint
        WorkoutStateHolder.update { current ->
            current.copy(
                heartRateBpm = hrPoints.lastOrNull()?.value?.roundToInt() ?: current.heartRateBpm,
                caloriesKcal = calories ?: current.caloriesKcal,
                steps = stepCounter.steps,
                activeDurationMillis = checkpoint?.activeDuration?.toMillis() ?: current.activeDurationMillis,
                durationAnchorElapsedMillis =
                    checkpoint?.let {
                        SystemClock.elapsedRealtime() - (System.currentTimeMillis() - it.time.toEpochMilli())
                    } ?: current.durationAnchorElapsedMillis,
            )
        }
        telemetry.onUpdate(WorkoutStateHolder.state.value, exerciseActive = state == ExerciseState.ACTIVE)
        maybeLogBattery()

        if (state.isEnded && WorkoutStateHolder.state.value.phase != WorkoutPhase.ENDING) {
            // Ende, das wir nicht ausgelöst haben (z. B. andere App startet Training, Berechtigung entzogen).
            DiagnosticLog.log("EXERCISE_ENDED_EXTERNALLY", "state" to state.name, "endReason" to update.exerciseStateInfo.endReason)
            WorkoutStateHolder.fail(WorkoutError.EXTERNAL_END, state.name)
        }
    }

    // ---------------------------------------------------------------- Akku / Status

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob =
            scope.launch {
                while (isActive) {
                    maybeLogBattery()
                    delay(TICKER_MILLIS)
                }
            }
    }

    private fun maybeLogBattery() {
        if (batteryTrigger.shouldFire(SystemClock.elapsedRealtime())) logStatus("BATTERY")
    }

    private fun logStatus(event: String) {
        val battery =
            ContextCompat.registerReceiver(this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val pm = getSystemService(PowerManager::class.java)
        val state = WorkoutStateHolder.state.value
        DiagnosticLog.log(
            event,
            "level" to if (level >= 0 && scale > 0) level * 100 / scale else -1,
            "plugged" to plugged,
            "tempC" to tempTenths / 10.0,
            "powerSave" to pm?.isPowerSaveMode,
            "deviceIdle" to pm?.isDeviceIdleMode,
            "score" to currentScoreText(),
            "hrSamples" to hrSampleCount,
            "hrGaps" to hrGapCount,
            "updates" to updateCount,
            "activeSec" to state.activeDurationMillis / 1000,
        )
    }

    // ---------------------------------------------------------------- Lebenszyklus-Logging

    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagnosticLog.log("TASK_REMOVED")
        super.onTaskRemoved(rootIntent)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        DiagnosticLog.log("FGS_TIMEOUT", "startId" to startId, "fgsType" to fgsType)
        super.onTimeout(startId, fgsType)
    }

    override fun onTrimMemory(level: Int) {
        DiagnosticLog.log("SERVICE_TRIM_MEMORY", "level" to level)
        super.onTrimMemory(level)
    }

    override fun onDestroy() {
        DiagnosticLog.log("SERVICE_DESTROY", "sessionActive" to store.sessionActive)
        runCatching { unregisterReceiver(screenReceiver) }
        if (callbackRequested) {
            // Exercise läuft in Health Services weiter; nur den Callback lösen (ohne auf das Future zu warten).
            exerciseClient.clearUpdateCallbackAsync(updateCallback)
            callbackRequested = false
            callbackConfirmed = false
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "eu.advantly.wear.action.START_WORKOUT"
        const val ACTION_STOP = "eu.advantly.wear.action.STOP_WORKOUT"
        const val ACTION_RECOVER = "eu.advantly.wear.action.RECOVER_WORKOUT"
        const val ACTION_REPLACE_OTHER = "eu.advantly.wear.action.REPLACE_OTHER_WORKOUT"
        const val EXTRA_SPORT = "sport"
        private const val CHANNEL_ID = "workout"
        private const val NOTIFICATION_ID = 4711
        private const val BATTERY_LOG_INTERVAL_MILLIS = 5 * 60 * 1000L
        private const val TICKER_MILLIS = 60 * 1000L

        /** Wartezeiten zwischen Wiederholungen (Capabilities, Status, Callback-Anmeldung). */
        private val RETRY_DELAYS_MILLIS = longArrayOf(1_000, 3_000, 10_000)
        private const val END_ATTEMPTS = 3
        private const val END_CONFIRM_POLLS = 6
        private const val END_CONFIRM_POLL_MILLIS = 500L

        /**
         * Startet eine Session. Starts kommen nur aus der **sichtbaren** Activity (Hintergrundstarts von FGS sind
         * eingeschränkt, 05 §2.2); [stop] darf auch aus dem Hintergrund kommen (Match am Handy gelöscht).
         *
         * Bewusst `startService` statt des von der Android-Doku für Foreground Services empfohlenen
         * `startForegroundService`: Der Service ruft `startForeground` sofort selbst auf, solange die App im Vordergrund
         * ist (für API 34–36 zulässig). `startForegroundService` würde die App mit „did not then call startForeground“
         * abstürzen lassen, wenn der Start abbricht (z. B. `SecurityException` bei fehlenden Rechten) und der Service
         * sich vorher beendet. Gilt ebenso für [stop], [recover] und [replaceOther].
         */
        fun start(
            context: Context,
            sport: WorkoutSport,
        ) = send(context, Intent(context, WorkoutService::class.java).setAction(ACTION_START).putExtra(EXTRA_SPORT, sport.name))

        fun stop(context: Context) =
            WorkoutCoordinator.requestStop(SessionStore(context)) {
                send(context, Intent(context, WorkoutService::class.java).setAction(ACTION_STOP))
            }

        /** Nach einem Prozess-Neustart oder als „Erneut versuchen“: laufende Session wieder übernehmen. */
        fun recover(context: Context) = send(context, Intent(context, WorkoutService::class.java).setAction(ACTION_RECOVER))

        /** Nur nach ausdrücklicher Bestätigung im Konflikt-Dialog. */
        fun replaceOther(context: Context) = send(context, Intent(context, WorkoutService::class.java).setAction(ACTION_REPLACE_OTHER))

        private fun send(
            context: Context,
            intent: Intent,
        ) {
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                // Hintergrund-Startverbot: Dienst läuft nicht (mehr) – dann gibt es auch nichts zu beenden
                DiagnosticLog.log("SERVICE_START_REJECTED", "action" to intent.action, "error" to e.javaClass.simpleName)
                if (intent.action == ACTION_STOP && !SessionStore(context).sessionActive) WorkoutStateHolder.reset()
            }
        }
    }
}
