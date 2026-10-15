package eu.advantly.wear

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Process
import eu.advantly.match.session.SessionListener
import eu.advantly.wear.diagnostics.DiagLine
import eu.advantly.wear.diagnostics.DiagnosticLog
import eu.advantly.wear.sync.MatchNotifier
import eu.advantly.wear.sync.WearSessions
import eu.advantly.wear.workout.SessionStore
import eu.advantly.wear.workout.WorkoutAction
import eu.advantly.wear.workout.WorkoutCoordinator
import eu.advantly.wear.workout.WorkoutInputs
import eu.advantly.wear.workout.WorkoutService
import eu.advantly.wear.workout.WorkoutStateHolder

/**
 * Application der Wear-App: startet die prozessweite Match-Session (auch bei Weckruf durch den Sync-Dienst), meldet
 * neue Matches vom Handy per Benachrichtigung und beendet das Workout, sobald kein Match mehr offen ist (z. B. am Handy
 * gelöscht). Diagnose (nur Debug): Prozessstarts, unerwartete Neustarts während eines Workouts, frühere Prozessenden.
 */
class AdvantlyWearApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.init(this)
        val store = SessionStore(this)
        DiagnosticLog.log(
            "PROCESS_START",
            "pid" to Process.myPid(),
            "version" to BuildConfig.VERSION_NAME,
            "sessionActive" to store.sessionActive,
            "sessionId" to store.sessionId,
        )
        if (store.sessionActive) {
            DiagnosticLog.log("PROCESS_RESTART_DURING_SESSION", "sessionId" to store.sessionId)
        }
        logExitReasons(store)

        val holder = WearSessions.get(this)
        holder.session.addListener(MatchNotifier(this))
        holder.session.addListener(
            SessionListener { state ->
                val match = state.match
                val inputs =
                    WorkoutInputs(
                        enabled = WorkoutCoordinator.enabled(match?.meta, holder.prefs.workoutEnabled),
                        matchId = match?.id,
                        sport = match?.format?.sport,
                        phase = WorkoutStateHolder.state.value.phase,
                        handledMatchId = holder.prefs.workoutHandledMatchId,
                    )
                // Start nur aus der sichtbaren Activity (FGS-Startverbot im Hintergrund); Stoppen geht immer
                if (WorkoutCoordinator.decide(inputs) == WorkoutAction.Stop) WorkoutService.stop(this)
                if (match == null && WorkoutStateHolder.state.value.countOnly) WorkoutStateHolder.reset()
            },
        )
    }

    private fun logExitReasons(store: SessionStore) {
        val am = getSystemService(ActivityManager::class.java) ?: return
        val infos = runCatching { am.getHistoricalProcessExitReasons(packageName, 0, MAX_EXIT_INFOS) }.getOrDefault(emptyList())
        val newer = infos.filter { it.timestamp > store.lastExitInfoTimestamp }.sortedBy { it.timestamp }
        newer.forEach {
            DiagnosticLog.log(
                "PREVIOUS_EXIT",
                "at" to DiagLine.isoUtc(it.timestamp),
                "pid" to it.pid,
                "reason" to it.reason,
                "importance" to it.importance,
                "description" to it.description,
            )
        }
        newer.lastOrNull()?.let { store.lastExitInfoTimestamp = it.timestamp }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            DiagnosticLog.log("TRIM_MEMORY", "level" to level)
        }
    }

    private companion object {
        const val MAX_EXIT_INFOS = 10
    }
}
