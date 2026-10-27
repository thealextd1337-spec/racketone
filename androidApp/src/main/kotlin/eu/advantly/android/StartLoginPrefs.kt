package eu.advantly.android

import android.content.Context
import androidx.core.content.edit
import eu.advantly.ui.phone.auth.StartLoginHost
import kotlinx.coroutines.flow.MutableStateFlow

/** Wahl zum Startbildschirm in SharedPreferences (wie der Uhr-Installationshinweis), nicht in Konto oder Matchdaten. */
internal class StartLoginPrefs(
    context: Context,
) : StartLoginHost {
    private val prefs = context.applicationContext.getSharedPreferences("start_login", Context.MODE_PRIVATE)
    override val decided = MutableStateFlow(prefs.getBoolean(KEY_DECIDED, false))

    override fun decide() {
        if (decided.value) return
        decided.value = true
        prefs.edit { putBoolean(KEY_DECIDED, true) }
    }

    private companion object {
        const val KEY_DECIDED = "decided"
    }
}
