package eu.advantly.wear.workout

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import eu.advantly.data.AndroidInstallationGuard

/**
 * Persistenter Workout-Zustand (SharedPreferences), damit ein Prozess-Neustart erkannt und die Exercise wieder
 * übernommen wird. `sessionActive` wird beim Start gesetzt und nur bei einem regulären Ende gelöscht. Der Spielstand
 * selbst liegt in der Match-Datenbank (`MatchSession`), nicht hier.
 *
 * Datei mit festem Namen [FILE_NAME], vom Backup ausgeschlossen (`backup_rules.xml`, `data_extraction_rules.xml`):
 * Ein wiederhergestelltes `sessionActive` würde auf einem neuen Gerät ein altes Workout wieder aufnehmen (Plan 0.5.1 §7).
 * Die Schlüssel tragen den Namensraum der Installation (wie zuvor der Dateiname `workout_session_<namespace>`); ein
 * Zustand aus der alten Datei wird beim ersten Zugriff übernommen, ein laufendes Workout überlebt das Update also.
 */
class SessionStore(
    context: Context,
) : WorkoutMarker {
    private val namespace = AndroidInstallationGuard.get(context).cacheNamespace
    private val prefs: SharedPreferences = migrated(context, namespace)

    override var sessionActive: Boolean
        get() = prefs.getBoolean(key(KEY_ACTIVE), false)
        set(value) = prefs.edit { putBoolean(key(KEY_ACTIVE), value) }

    /**
     * Beenden wurde verlangt (Match geschlossen/gelöscht), aber das Ende der Exercise ist noch nicht bestätigt. Überlebt
     * einen Prozessabbruch: Die Recovery beendet dann, statt wieder anzubinden oder neu zu starten.
     */
    override var endRequested: Boolean
        get() = prefs.getBoolean(key(KEY_END_REQUESTED), false)
        set(value) = prefs.edit { putBoolean(key(KEY_END_REQUESTED), value) }

    var sessionId: String?
        get() = prefs.getString(key(KEY_SESSION_ID), null)
        set(value) = prefs.edit { putString(key(KEY_SESSION_ID), value) }

    var sport: WorkoutSport
        get() = prefs.getString(key(KEY_SPORT), null)?.let { runCatching { WorkoutSport.valueOf(it) }.getOrNull() } ?: WorkoutSport.TENNIS
        set(value) = prefs.edit { putString(key(KEY_SPORT), value.name) }

    /** Zeitstempel des zuletzt protokollierten `ApplicationExitInfo` (verhindert Doppel-Logs). */
    var lastExitInfoTimestamp: Long
        get() = prefs.getLong(key(KEY_LAST_EXIT), 0L)
        set(value) = prefs.edit { putLong(key(KEY_LAST_EXIT), value) }

    private fun key(name: String) = "$namespace.$name"

    companion object {
        /** Datei des Workout-Zustands; muss in beiden Backup-Regeln ausgeschlossen sein. */
        const val FILE_NAME = "workout_session"

        /** Bis 0.5.0: eigene Datei je Installations-Namensraum (vom Backup nicht ausschließbar). */
        internal fun legacyName(namespace: String) = "workout_session_$namespace"

        private val lock = Any()

        /** Übernimmt den Zustand aus [legacyName] (vorhandene Werte gewinnen) und löscht die alte Datei danach. */
        private fun migrated(
            context: Context,
            namespace: String,
        ): SharedPreferences {
            val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            synchronized(lock) {
                val legacy = context.getSharedPreferences(legacyName(namespace), Context.MODE_PRIVATE)
                val old = legacy.all
                if (old.isEmpty()) return prefs
                // erst sicher schreiben (commit), dann die alte Datei löschen: ein Abbruch dazwischen wiederholt nur
                val copied =
                    prefs
                        .edit()
                        .apply {
                            for ((name, value) in old) {
                                val key = "$namespace.$name"
                                if (prefs.contains(key)) continue
                                when (value) {
                                    is Boolean -> putBoolean(key, value)
                                    is String -> putString(key, value)
                                    is Long -> putLong(key, value)
                                }
                            }
                        }.commit()
                if (copied) {
                    legacy.edit().clear().commit()
                    context.deleteSharedPreferences(legacyName(namespace))
                }
            }
            return prefs
        }

        private const val KEY_ACTIVE = "session_active"
        private const val KEY_END_REQUESTED = "end_requested"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_SPORT = "sport"
        private const val KEY_LAST_EXIT = "last_exit_info_ts"
    }
}
