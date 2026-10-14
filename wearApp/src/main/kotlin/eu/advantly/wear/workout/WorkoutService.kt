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
import eu.advantly.wear.scoring.ScoreRepository
import eu.advantly.wear.scoring.SpikeScore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Foreground Service (Typ `health`) mit Health-Services-`ExerciseClient` und Ongoing Activity (05 §2.1/§2.2, 01 E15).
 * Hält die Zähl-App während des Matches am Leben – auch bei Display aus / Handgelenk unten – und schreibt das
 * Feldtest-Log (Spike S5): Lebenszyklus, Puls-Samples, Lücken > 10 s, Akku alle 5 min, Display an/aus.
 *
 * Start/Stop über [start]/[stop]. Wird der Prozess vom System beendet und der Service neu gestartet (`START_STICKY`,
 * Intent `null`) oder öffnet der Nutzer die App nach einem Kill, wird die laufende Exercise wieder übernommen.
 */
class WorkoutService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var exerciseClient: ExerciseClient
    private lateinit var store: SessionStore

    private var notificationBuilder: NotificationCompat.Builder? = null
    private var ongoingActivity: OngoingActivity? = null
    private var foregroundStarted = false
    private var callbackRegistered = false
    private var sessionJob: Job? = null
    private var tickerJob: Job? = null
    private var scoreJob: Job? = null

    private val gapDetector = HeartRateGapDetector()
    private val batteryTrigger = PeriodicTrigger(BATTERY_LOG_INTERVAL_MILLIS)
    private var lastState: ExerciseState? = null
    private var hrSampleCount = 0L
    private var hrGapCount = 0L
    private var stepsTotal = 0L
    private var updateCount = 0L
    private var statsSummary: String? = null

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
        ScoreRepository.init(this)
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
                val sport = intent.getStringExtra(EXTRA_SPORT)?.let { runCatching { SpikeSport.valueOf(it) }.getOrNull() }
                startSession(sport ?: SpikeSport.TENNIS)
            }

            ACTION_STOP -> {
                stopSession()
            }

            else -> {
                recoverSession()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- Session

    private fun startSession(sport: SpikeSport) {
        if (sessionJob?.isActive == true || WorkoutStateHolder.state.value.phase == WorkoutPhase.ACTIVE) {
            DiagnosticLog.log("SESSION_START_IGNORED", "reason" to "already_running")
            return
        }
        val sessionId = "s5-${System.currentTimeMillis()}"
        store.sessionId = sessionId
        store.sport = sport
        store.sessionActive = true
        ScoreRepository.reset()
        resetCounters()
        DiagnosticLog.log("SESSION_START", "sessionId" to sessionId, "sport" to sport)
        WorkoutStateHolder.update { WorkoutUiState(phase = WorkoutPhase.STARTING) }
        if (!startForegroundWithOngoingActivity()) return
        sessionJob = scope.launch { startExercise(sport) }
    }

    private fun recoverSession() {
        if (!store.sessionActive) {
            DiagnosticLog.log("SERVICE_RECOVER_SKIPPED", "reason" to "no_active_session")
            if (!foregroundStarted) stopSelf()
            return
        }
        if (foregroundStarted && callbackRegistered) return
        DiagnosticLog.log("SESSION_RECOVER", "sessionId" to store.sessionId, "sport" to store.sport)
        WorkoutStateHolder.update { it.copy(phase = WorkoutPhase.STARTING) }
        if (!startForegroundWithOngoingActivity()) return
        sessionJob =
            scope.launch {
                val info = runCatching { exerciseClient.getCurrentExerciseInfo() }.getOrNull()
                DiagnosticLog.log("EXERCISE_INFO", "trackedStatus" to info?.exerciseTrackedStatus?.let(::trackedStatusName))
                if (info?.exerciseTrackedStatus == TRACKED_OWNED_EXERCISE_IN_PROGRESS) {
                    // Exercise lief in Health Services weiter – nur Callback neu anmelden.
                    val caps = CapabilitiesRepository.load(this@WorkoutService)
                    val selection = ExerciseTypeSelector.select(store.sport, caps)
                    registerCallback()
                    WorkoutStateHolder.update { it.copy(phase = WorkoutPhase.ACTIVE, selection = selection) }
                    DiagnosticLog.log("EXERCISE_REATTACHED")
                    startTicker()
                } else {
                    DiagnosticLog.log("EXERCISE_RESTART_AFTER_KILL")
                    startExercise(store.sport)
                }
            }
    }

    private suspend fun startExercise(sport: SpikeSport) {
        try {
            val caps = CapabilitiesRepository.load(this)
            val selection = ExerciseTypeSelector.select(sport, caps)
            if (selection == null) {
                DiagnosticLog.log("EXERCISE_UNSUPPORTED", "sport" to sport)
                WorkoutStateHolder.update { it.copy(phase = WorkoutPhase.ACTIVE, selection = null) }
                startTicker()
                return
            }
            val info = runCatching { exerciseClient.getCurrentExerciseInfo() }.getOrNull()
            if (info?.exerciseTrackedStatus == TRACKED_OTHER_APP_IN_PROGRESS) {
                // Wir übernehmen; die andere App (z. B. Fitbit/Samsung Health) beendet dann ihr Training.
                DiagnosticLog.log("EXERCISE_OTHER_APP_IN_PROGRESS")
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
            )
            exerciseClient.startExercise(config)
            DiagnosticLog.log("EXERCISE_START_OK")
            WorkoutStateHolder.update { it.copy(phase = WorkoutPhase.ACTIVE, selection = selection) }
            startTicker()
        } catch (e: Exception) {
            DiagnosticLog.log("EXERCISE_START_FAILED", "error" to e.javaClass.simpleName, "message" to e.message)
            WorkoutStateHolder.update { it.copy(phase = WorkoutPhase.FAILED, errorMessage = e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun stopSession() {
        DiagnosticLog.log("SESSION_STOP_REQUESTED", "sessionId" to store.sessionId)
        WorkoutStateHolder.update { it.copy(phase = WorkoutPhase.ENDING) }
        sessionJob?.cancel()
        scope.launch {
            runCatching { exerciseClient.endExercise() }
                .onFailure { DiagnosticLog.log("EXERCISE_END_FAILED", "error" to it.javaClass.simpleName, "message" to it.message) }
            unregisterCallback()
            logStatus("SESSION_END")
            store.sessionActive = false
            tickerJob?.cancel()
            WorkoutStateHolder.reset()
            ServiceCompat.stopForeground(this@WorkoutService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        }
    }

    private fun resetCounters() {
        gapDetector.reset()
        lastState = null
        hrSampleCount = 0
        hrGapCount = 0
        stepsTotal = 0
        updateCount = 0
        statsSummary = null
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
        notificationBuilder = builder
        val activity =
            OngoingActivity
                .Builder(this, NOTIFICATION_ID, builder)
                .setStaticIcon(R.drawable.ic_stat_advantly)
                .setTouchIntent(touchIntent)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setTitle(getString(R.string.notification_title))
                .setStatus(buildStatus(ScoreRepository.score.value))
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
            WorkoutStateHolder.update { it.copy(phase = WorkoutPhase.FAILED, errorMessage = e.message ?: e.javaClass.simpleName) }
            stopSelf()
            false
        }
    }

    private fun buildStatus(score: SpikeScore): Status {
        val state = WorkoutStateHolder.state.value
        val anchor = state.durationAnchorElapsedMillis ?: SystemClock.elapsedRealtime()
        val stopwatchZero = anchor - state.activeDurationMillis
        return Status
            .Builder()
            .addTemplate(getString(R.string.ongoing_status_template))
            .addPart("score", Status.TextPart(score.format()))
            .addPart("duration", Status.StopwatchPart(stopwatchZero))
            .build()
    }

    private fun observeScore() {
        scoreJob?.cancel()
        scoreJob =
            scope.launch {
                ScoreRepository.score.collect { score ->
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
                DiagnosticLog.log("CALLBACK_REGISTERED")
            }

            override fun onRegistrationFailed(throwable: Throwable) {
                DiagnosticLog.log("CALLBACK_REGISTRATION_FAILED", "error" to throwable.javaClass.simpleName, "message" to throwable.message)
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

    private fun registerCallback() {
        if (callbackRegistered) return
        exerciseClient.setUpdateCallback(ContextCompat.getMainExecutor(this), updateCallback)
        callbackRegistered = true
    }

    private suspend fun unregisterCallback() {
        if (!callbackRegistered) return
        runCatching { exerciseClient.clearUpdateCallback(updateCallback) }
        callbackRegistered = false
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
            lines += "HR" to mapOf("t" to DiagLine.isoUtc(sampleMillis), "bpm" to point.value.roundToInt())
        }
        hrSampleCount += hrPoints.size
        lines += "EXERCISE_UPDATE" to mapOf("n" to updateCount, "hrSamples" to hrPoints.size, "state" to state.name)
        DiagnosticLog.logAll(lines)

        val calories = metrics.getData(DataType.CALORIES_TOTAL)?.total
        val stepDelta = metrics.getData(DataType.STEPS).sumOf { it.value }
        stepsTotal += stepDelta
        metrics.getData(DataType.HEART_RATE_BPM_STATS)?.let {
            statsSummary = "avg=${it.average.roundToInt()}/min=${it.min.roundToInt()}/max=${it.max.roundToInt()}"
        }
        val checkpoint = update.activeDurationCheckpoint
        WorkoutStateHolder.update { current ->
            current.copy(
                heartRateBpm = hrPoints.lastOrNull()?.value?.roundToInt() ?: current.heartRateBpm,
                caloriesKcal = calories ?: current.caloriesKcal,
                steps = if (DataType.STEPS in metrics.dataTypes || current.steps != null) stepsTotal else null,
                activeDurationMillis = checkpoint?.activeDuration?.toMillis() ?: current.activeDurationMillis,
                durationAnchorElapsedMillis =
                    checkpoint?.let {
                        SystemClock.elapsedRealtime() - (System.currentTimeMillis() - it.time.toEpochMilli())
                    } ?: current.durationAnchorElapsedMillis,
            )
        }
        maybeLogBattery()

        if (state.isEnded) {
            // Ende, das wir nicht ausgelöst haben (z. B. andere App startet Training, Berechtigung entzogen).
            if (WorkoutStateHolder.state.value.phase != WorkoutPhase.ENDING) {
                DiagnosticLog.log("EXERCISE_ENDED_EXTERNALLY", "state" to state.name, "endReason" to update.exerciseStateInfo.endReason)
                WorkoutStateHolder.update { it.copy(phase = WorkoutPhase.FAILED, errorMessage = state.name) }
            }
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
            ContextCompat.registerReceiver(
                this,
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
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
            "score" to ScoreRepository.score.value.format(),
            "hrSamples" to hrSampleCount,
            "hrGaps" to hrGapCount,
            "updates" to updateCount,
            "activeSec" to state.activeDurationMillis / 1000,
            "kcal" to state.caloriesKcal?.roundToInt(),
            "steps" to state.steps,
            "hrStats" to statsSummary,
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
        if (callbackRegistered) {
            // Exercise läuft in Health Services weiter; nur den Callback lösen (ohne auf das Future zu warten).
            exerciseClient.clearUpdateCallbackAsync(updateCallback)
            callbackRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun trackedStatusName(status: Int): String =
        when (status) {
            TRACKED_OWNED_EXERCISE_IN_PROGRESS -> "OWNED_EXERCISE_IN_PROGRESS"
            TRACKED_OTHER_APP_IN_PROGRESS -> "OTHER_APP_IN_PROGRESS"
            TRACKED_NO_EXERCISE_IN_PROGRESS -> "NO_EXERCISE_IN_PROGRESS"
            else -> "UNKNOWN"
        }

    companion object {
        const val ACTION_START = "eu.advantly.wear.action.START_WORKOUT"
        const val ACTION_STOP = "eu.advantly.wear.action.STOP_WORKOUT"
        const val ACTION_RECOVER = "eu.advantly.wear.action.RECOVER_WORKOUT"
        const val EXTRA_SPORT = "sport"

        // Werte von androidx.health.services.client.data.ExerciseTrackedStatus (1.1.0). Kotlin greift auf die Konstanten
        // nur über den als @RestrictTo markierten Companion zu (Lint RestrictedApi), deshalb hier gespiegelt.
        private const val TRACKED_OTHER_APP_IN_PROGRESS = 1
        private const val TRACKED_OWNED_EXERCISE_IN_PROGRESS = 2
        private const val TRACKED_NO_EXERCISE_IN_PROGRESS = 3
        private const val CHANNEL_ID = "workout"
        private const val NOTIFICATION_ID = 4711
        private const val BATTERY_LOG_INTERVAL_MILLIS = 5 * 60 * 1000L
        private const val TICKER_MILLIS = 60 * 1000L

        /** Aus der sichtbaren Activity aufrufen (Hintergrundstarts von FGS sind eingeschränkt, 05 §2.2). */
        fun start(
            context: Context,
            sport: SpikeSport,
        ) {
            val intent = Intent(context, WorkoutService::class.java).setAction(ACTION_START).putExtra(EXTRA_SPORT, sport.name)
            ContextCompat.startForegroundService(context, intent)
        }

        // Stop/Recover kommen ebenfalls aus der sichtbaren Activity. Bewusst startService statt startForegroundService:
        // Stop ruft kein startForeground mehr auf, und Recover tut es selbst, solange die App im Vordergrund ist.
        fun stop(context: Context) {
            context.startService(Intent(context, WorkoutService::class.java).setAction(ACTION_STOP))
        }

        /** Nach einem Prozess-Neustart aus der (sichtbaren) Activity: laufende Session wieder übernehmen. */
        fun recover(context: Context) {
            context.startService(Intent(context, WorkoutService::class.java).setAction(ACTION_RECOVER))
        }
    }
}
