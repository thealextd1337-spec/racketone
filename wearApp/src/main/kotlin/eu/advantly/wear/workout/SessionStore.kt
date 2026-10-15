package eu.advantly.wear.workout

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import eu.advantly.wear.scoring.SpikeScoreCounter

/**
 * Persistenter Spike-Zustand (SharedPreferences), damit ein Prozess-Neustart erkannt wird und Stand/Sportart überleben.
 * `sessionActive` wird beim Start gesetzt und nur bei einem regulären Ende gelöscht.
 */
class SessionStore(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("s5_session", Context.MODE_PRIVATE)

    var sessionActive: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, false)
        set(value) = prefs.edit { putBoolean(KEY_ACTIVE, value) }

    var sessionId: String?
        get() = prefs.getString(KEY_SESSION_ID, null)
        set(value) = prefs.edit { putString(KEY_SESSION_ID, value) }

    /** Session läuft im Rückfall „nur zählen“ ohne Service/Exercise ([CountOnlySession]). */
    var countOnly: Boolean
        get() = prefs.getBoolean(KEY_COUNT_ONLY, false)
        set(value) = prefs.edit { putBoolean(KEY_COUNT_ONLY, value) }

    var sport: SpikeSport
        get() = prefs.getString(KEY_SPORT, null)?.let { runCatching { SpikeSport.valueOf(it) }.getOrNull() } ?: SpikeSport.TENNIS
        set(value) = prefs.edit { putString(KEY_SPORT, value.name) }

    var scoreEvents: String
        get() = prefs.getString(KEY_SCORE, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_SCORE, value) }

    /** Zeitstempel des zuletzt protokollierten `ApplicationExitInfo` (verhindert Doppel-Logs). */
    var lastExitInfoTimestamp: Long
        get() = prefs.getLong(KEY_LAST_EXIT, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_EXIT, value) }

    fun loadCounter(): SpikeScoreCounter = SpikeScoreCounter.decode(scoreEvents)

    fun saveCounter(counter: SpikeScoreCounter) {
        scoreEvents = counter.encode()
    }

    private companion object {
        const val KEY_ACTIVE = "session_active"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_COUNT_ONLY = "count_only"
        const val KEY_SPORT = "sport"
        const val KEY_SCORE = "score_events"
        const val KEY_LAST_EXIT = "last_exit_info_ts"
    }
}
