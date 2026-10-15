package eu.advantly.wear.workout

import android.content.Context
import eu.advantly.wear.diagnostics.DiagnosticLog
import eu.advantly.wear.scoring.ScoreRepository

/**
 * Rückfall „nur zählen“: ohne Health-Foreground-Service und ohne Exercise, wenn weder Puls- noch Aktivitätsrecht
 * gewährt sind (FGS-Typ `health` ist dann nicht erlaubt). **Keine Laufzeitgarantie** – das System darf die App bei
 * gesenktem Handgelenk beenden. Das steht im UI (Mittelband/Setup) und im Log (`COUNT_ONLY_MODE`).
 */
object CountOnlySession {
    fun start(
        context: Context,
        sport: SpikeSport,
        permissions: WorkoutPermissions,
    ) {
        val store = SessionStore(context)
        val sessionId = "s5-${System.currentTimeMillis()}"
        store.sessionId = sessionId
        store.sport = sport
        store.countOnly = true
        store.sessionActive = true
        ScoreRepository.reset()
        DiagnosticLog.log("SESSION_START", "sessionId" to sessionId, "sport" to sport, "mode" to "count_only")
        DiagnosticLog.log(
            "COUNT_ONLY_MODE",
            "reason" to "permissions_denied",
            "heartRate" to permissions.heartRate,
            "activityRecognition" to permissions.activityRecognition,
        )
        WorkoutStateHolder.update { WorkoutUiState(phase = WorkoutPhase.ACTIVE, countOnly = true) }
    }

    /** Nach Prozess-Neustart: Zustand wiederherstellen (kein Service, nichts zu übernehmen). */
    fun restore(context: Context) {
        val store = SessionStore(context)
        DiagnosticLog.log("SESSION_RECOVER", "sessionId" to store.sessionId, "sport" to store.sport, "mode" to "count_only")
        WorkoutStateHolder.update { WorkoutUiState(phase = WorkoutPhase.ACTIVE, countOnly = true) }
    }

    fun stop(context: Context) {
        val store = SessionStore(context)
        DiagnosticLog.log(
            "SESSION_END",
            "sessionId" to store.sessionId,
            "mode" to "count_only",
            "score" to ScoreRepository.score.value.format(),
        )
        store.sessionActive = false
        store.countOnly = false
        WorkoutStateHolder.reset()
    }
}
