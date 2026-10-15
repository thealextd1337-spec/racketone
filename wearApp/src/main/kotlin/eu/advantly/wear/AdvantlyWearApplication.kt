package eu.advantly.wear

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Process
import eu.advantly.wear.diagnostics.DiagLine
import eu.advantly.wear.diagnostics.DiagnosticLog
import eu.advantly.wear.workout.SessionStore

/**
 * Application der Wear-App. Für Spike S5: protokolliert jeden Prozessstart und – falls dabei noch eine Session als
 * aktiv markiert ist – einen **unerwarteten Neustart** (Prozess wurde während des Matches beendet).
 * Zusätzlich werden die vom System gemeldeten Gründe früherer Prozessenden (`ApplicationExitInfo`) geloggt.
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
