package eu.advantly.wear

import android.Manifest
import android.content.pm.PackageManager
import android.health.connect.HealthPermissions
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import eu.advantly.wear.diagnostics.DiagnosticLog
import eu.advantly.wear.scoring.ScoreRepository
import eu.advantly.wear.scoring.Side
import eu.advantly.wear.scoring.TapGuard
import eu.advantly.wear.ui.Haptics
import eu.advantly.wear.workout.CapabilitiesRepository
import eu.advantly.wear.workout.SessionStore
import eu.advantly.wear.workout.SpikeSport
import eu.advantly.wear.workout.WorkoutService
import kotlinx.coroutines.launch

/**
 * Einzige Activity der Wear-App. Spike S5: Sportwahl, Berechtigungen, Scoring-Screen, Ambient-Unterstützung
 * (AmbientLifecycleObserver, reduzierte Darstellung) und Logging von Ambient-/Sichtbarkeitswechseln.
 */
class MainActivity : ComponentActivity() {
    private val isAmbient = mutableStateOf(false)
    private val permissionsGranted = mutableStateOf(true)
    private val tapGuard = TapGuard()
    private lateinit var haptics: Haptics
    private lateinit var store: SessionStore
    private var pendingSport: SpikeSport? = null

    private val ambientCallback =
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                isAmbient.value = true
                DiagnosticLog.log(
                    "AMBIENT_ENTER",
                    "burnIn" to ambientDetails.burnInProtectionRequired,
                    "lowBit" to ambientDetails.deviceHasLowBitAmbient,
                )
            }

            override fun onUpdateAmbient() {
                DiagnosticLog.log("AMBIENT_UPDATE")
            }

            override fun onExitAmbient() {
                isAmbient.value = false
                tapGuard.onWake(SystemClock.uptimeMillis())
                DiagnosticLog.log("AMBIENT_EXIT")
            }
        }

    private val ambientObserver = AmbientLifecycleObserver(this, ambientCallback)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            DiagnosticLog.log("PERMISSIONS_RESULT", *result.map { (k, v) -> k.substringAfterLast('.') to v }.toTypedArray())
            permissionsGranted.value = result.values.all { it }
            pendingSport?.let { startWorkout(it) }
            pendingSport = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        haptics = Haptics(this)
        store = SessionStore(this)
        ScoreRepository.init(this)
        permissionsGranted.value = missingPermissions().isEmpty()
        DiagnosticLog.log("ACTIVITY_CREATE", "restored" to (savedInstanceState != null))

        // Capabilities beim App-Start abfragen und cachen (05 §0.1).
        lifecycleScope.launch {
            runCatching { CapabilitiesRepository.load(this@MainActivity) }
                .onFailure { DiagnosticLog.log("CAPABILITIES_FAILED", "error" to it.javaClass.simpleName, "message" to it.message) }
        }
        // Session war aktiv, Prozess wurde aber beendet → Service/Exercise wieder übernehmen.
        if (store.sessionActive) WorkoutService.recover(this)

        setContent {
            WearApp(
                isAmbient = isAmbient.value,
                permissionsGranted = permissionsGranted.value,
                initialSport = store.sport,
                logPath = DiagnosticLog.path,
                onStart = ::requestPermissionsAndStart,
                onPoint = ::onPoint,
                onUndo = ::onUndo,
                onEndMatch = { WorkoutService.stop(this) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        DiagnosticLog.log("ACTIVITY_RESUME")
    }

    override fun onPause() {
        DiagnosticLog.log("ACTIVITY_PAUSE")
        super.onPause()
    }

    override fun onStop() {
        DiagnosticLog.log("ACTIVITY_STOP")
        super.onStop()
    }

    override fun onDestroy() {
        DiagnosticLog.log("ACTIVITY_DESTROY", "finishing" to isFinishing)
        super.onDestroy()
    }

    /** Zählt einen Punkt, wenn Entprellung und Ambient es erlauben. Liefert `true`, wenn gezählt wurde. */
    private fun onPoint(side: Side): Boolean {
        if (isAmbient.value) return false
        if (!tapGuard.tryAccept(SystemClock.uptimeMillis())) {
            DiagnosticLog.log("TAP_IGNORED_LOCKOUT", "side" to side)
            return false
        }
        ScoreRepository.addPoint(side)
        if (side == Side.US) haptics.pointUs() else haptics.pointThem()
        return true
    }

    private fun onUndo(): Side? {
        if (isAmbient.value) return null
        val undone = ScoreRepository.undo()
        if (undone != null) haptics.undo()
        return undone
    }

    private fun requestPermissionsAndStart(sport: SpikeSport) {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            startWorkout(sport)
        } else {
            pendingSport = sport
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startWorkout(sport: SpikeSport) {
        store.sport = sport
        WorkoutService.start(this, sport)
    }

    private fun missingPermissions(): List<String> =
        requiredPermissions().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private companion object {
        /** Wear OS 6 (API 36): `health.READ_HEART_RATE` statt `BODY_SENSORS` (05 §2.2). */
        fun requiredPermissions(): List<String> =
            listOf(
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.BAKLAVA
                ) {
                    HealthPermissions.READ_HEART_RATE
                } else {
                    Manifest.permission.BODY_SENSORS
                },
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
    }
}
