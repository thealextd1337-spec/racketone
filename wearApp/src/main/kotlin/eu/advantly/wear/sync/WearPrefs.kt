package eu.advantly.wear.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import eu.advantly.core.model.MatchId
import eu.advantly.wear.live.WatchPrefs

/** Gerätelokale Einstellungen der Uhr (SharedPreferences, nicht synchronisiert). */
class WearPrefs(
    context: Context,
) : WatchPrefs {
    private val prefs: SharedPreferences = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)

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

    private companion object {
        const val KEY_WORKOUT = "workout_enabled"
        const val KEY_OPEN = "open_match"
        const val KEY_WORKOUT_HANDLED = "workout_handled_match"
    }
}
