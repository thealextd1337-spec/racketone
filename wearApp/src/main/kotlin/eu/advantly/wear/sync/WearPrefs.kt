package eu.advantly.wear.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import eu.advantly.core.model.MatchId
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.wear.live.WatchPrefs
import eu.advantly.wear.workout.WorkoutSport

/** Gerätelokale Einstellungen der Uhr (SharedPreferences, nicht synchronisiert). */
class WearPrefs(
    context: Context,
) : WatchPrefs {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("wear_prefs_${AndroidInstallationGuard.get(context).cacheNamespace}", Context.MODE_PRIVATE)

    /** Genau einmal je neuer aktiver Datenbank: alte Systemanzeigen sind nur abgeleitete Caches. */
    fun initializeOperationalCache(reset: () -> Unit) {
        if (prefs.getBoolean("operational_cache_initialized", false)) return
        reset()
        check(prefs.edit().putBoolean("operational_cache_initialized", true).commit())
    }

    /** Letzter angeforderter Tile-/Complication-Zustand ohne Namen (E5); verhindert Updates allein durch Prozessstarts. */
    var glanceSentState: String?
        get() = prefs.getString(KEY_GLANCE_SENT, null)
        set(value) = prefs.edit { putString(KEY_GLANCE_SENT, value) }

    override var workoutEnabled: Boolean
        get() = prefs.getBoolean(KEY_WORKOUT, true)
        set(value) = prefs.edit { putBoolean(KEY_WORKOUT, value) }

    /** Offenes Match, um es nach einem Prozess-Neustart wieder zu öffnen. */
    var openMatchId: MatchId?
        get() = prefs.getString(KEY_OPEN, null)?.let(::MatchId)
        set(value) = prefs.edit { putString(KEY_OPEN, value?.value) }

    /** Match, für das das Workout bereits gestartet/abgelehnt wurde ([eu.advantly.wear.workout.WorkoutInputs.handledMatchId]). */
    var workoutHandledMatchId: MatchId?
        get() = prefs.getString(KEY_WORKOUT_HANDLED, null)?.let(::MatchId)
        set(value) = prefs.edit { putString(KEY_WORKOUT_HANDLED, value?.value) }

    /**
     * Ausstehender Workout-Auftrag während der Rechte-Abfrage (Match + Workout-Sportart). Persistent, damit er eine
     * Neuerstellung der Activity bzw. einen Prozessabbruch übersteht; „erledigt“ wird das Match erst nach der Antwort.
     */
    var workoutPending: Pair<MatchId, WorkoutSport>?
        get() {
            val id = prefs.getString(KEY_PENDING_MATCH, null) ?: return null
            val sport = prefs.getString(KEY_PENDING_SPORT, null)?.let { runCatching { WorkoutSport.valueOf(it) }.getOrNull() }
            return MatchId(id) to (sport ?: WorkoutSport.TENNIS)
        }
        set(value) =
            prefs.edit {
                putString(KEY_PENDING_MATCH, value?.first?.value)
                putString(KEY_PENDING_SPORT, value?.second?.name)
            }

    /** Benachrichtigungsrecht wurde beim ersten Start schon einmal angefragt. */
    var notificationsAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATIONS_ASKED, value) }

    private companion object {
        const val KEY_GLANCE_SENT = "glance_sent_state"
        const val KEY_WORKOUT = "workout_enabled"
        const val KEY_OPEN = "open_match"
        const val KEY_WORKOUT_HANDLED = "workout_handled_match"
        const val KEY_PENDING_MATCH = "workout_pending_match"
        const val KEY_PENDING_SPORT = "workout_pending_sport"
        const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
    }
}
