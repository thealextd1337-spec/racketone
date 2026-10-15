package eu.advantly.wear.workout

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persistenter Workout-Zustand (SharedPreferences), damit ein Prozess-Neustart erkannt und die Exercise wieder
 * übernommen wird. `sessionActive` wird beim Start gesetzt und nur bei einem regulären Ende gelöscht. Der Spielstand
 * selbst liegt in der Match-Datenbank (`MatchSession`), nicht hier.
 */
class SessionStore(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("workout_session", Context.MODE_PRIVATE)

    var sessionActive: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, false)
        set(value) = prefs.edit { putBoolean(KEY_ACTIVE, value) }

    /**
     * Beenden wurde verlangt (Match geschlossen/gelöscht), aber das Ende der Exercise ist noch nicht bestätigt. Überlebt
     * einen Prozessabbruch: Die Recovery beendet dann, statt wieder anzubinden oder neu zu starten.
     */
    var endRequested: Boolean
        get() = prefs.getBoolean(KEY_END_REQUESTED, false)
        set(value) = prefs.edit { putBoolean(KEY_END_REQUESTED, value) }

    var sessionId: String?
        get() = prefs.getString(KEY_SESSION_ID, null)
        set(value) = prefs.edit { putString(KEY_SESSION_ID, value) }

    var sport: WorkoutSport
        get() = prefs.getString(KEY_SPORT, null)?.let { runCatching { WorkoutSport.valueOf(it) }.getOrNull() } ?: WorkoutSport.TENNIS
        set(value) = prefs.edit { putString(KEY_SPORT, value.name) }

    /** Zeitstempel des zuletzt protokollierten `ApplicationExitInfo` (verhindert Doppel-Logs). */
    var lastExitInfoTimestamp: Long
        get() = prefs.getLong(KEY_LAST_EXIT, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_EXIT, value) }

    private companion object {
        const val KEY_ACTIVE = "session_active"
        const val KEY_END_REQUESTED = "end_requested"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_SPORT = "sport"
        const val KEY_LAST_EXIT = "last_exit_info_ts"
    }
}
