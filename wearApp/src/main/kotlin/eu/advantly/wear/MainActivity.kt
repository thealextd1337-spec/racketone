package eu.advantly.wear

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import eu.advantly.wear.diagnostics.DiagnosticLog
import eu.advantly.wear.scoring.ScoreRepository
import eu.advantly.wear.scoring.Side
import eu.advantly.wear.scoring.TapGuard
import eu.advantly.wear.ui.Haptics
import eu.advantly.wear.workout.CapabilitiesRepository
import eu.advantly.wear.workout.CountOnlySession
import eu.advantly.wear.workout.Permissions
import eu.advantly.wear.workout.SessionStore
import eu.advantly.wear.workout.SpikeSport
import eu.advantly.wear.workout.WorkoutPermissions
import eu.advantly.wear.workout.WorkoutService
import eu.advantly.wear.workout.WorkoutStateHolder
import kotlinx.coroutines.launch

/**
 * Einzige Activity der Wear-App. Spike S5: Sportwahl, Berechtigungen, Scoring-Screen, Ambient-Unterstützung
 * (AmbientLifecycleObserver, reduzierte Darstellung) und Logging von Ambient-/Sichtbarkeitswechseln.
 */
class MainActivity : ComponentActivity() {
    private val isAmbient = mutableStateOf(false)
    private val permissions = mutableStateOf(WorkoutPermissions.ALL)
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
            permissions.value = Permissions.current(this)
            pendingSport?.let { startWorkout(it) }
            pendingSport = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        haptics = Haptics(this)
        store = SessionStore(this)
        ScoreRepository.init(this)
        permissions.value = Permissions.current(this)
        DiagnosticLog.log("ACTIVITY_CREATE", "restored" to (savedInstanceState != null))

        // Capabilities beim App-Start abfragen und cachen (05 §0.1).
        lifecycleScope.launch {
            runCatching { CapabilitiesRepository.load(this@MainActivity) }
                .onFailure { DiagnosticLog.log("CAPABILITIES_FAILED", "error" to it.javaClass.simpleName, "message" to it.message) }
        }
        // Session war aktiv, Prozess wurde aber beendet → Service/Exercise (bzw. Nur-Zählen-Zustand) wieder übernehmen.
        if (store.sessionActive) {
            if (store.countOnly) CountOnlySession.restore(this) else WorkoutService.recover(this)
        }

        setContent {
            WearApp(
                isAmbient = isAmbient.value,
                permissions = permissions.value,
                initialSport = store.sport,
                logPath = DiagnosticLog.path,
                onStart = ::requestPermissionsAndStart,
                onPoint = ::onPoint,
                onUndo = ::onUndo,
                onEndMatch = ::endMatch,
                onRetry = { WorkoutService.recover(this) },
                onReplaceOther = { WorkoutService.replaceOther(this) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        permissions.value = Permissions.current(this) // Rechte können in den Einstellungen geändert worden sein
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
        val missing = Permissions.missing(this)
        if (missing.isEmpty()) {
            startWorkout(sport)
        } else {
            pendingSport = sport
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * Startet je nach gewährten Rechten: mit Puls- oder Aktivitätsrecht das Workout (Health-FGS, Messgrößen gefiltert),
     * sonst den Rückfall „nur zählen“ ohne Laufzeitgarantie.
     */
    private fun startWorkout(sport: SpikeSport) {
        store.sport = sport
        val granted = Permissions.current(this)
        if (granted.canRunHealthService) {
            WorkoutService.start(this, sport)
        } else {
            CountOnlySession.start(this, sport, granted)
        }
    }

    private fun endMatch() {
        if (WorkoutStateHolder.state.value.countOnly) CountOnlySession.stop(this) else WorkoutService.stop(this)
    }
}
