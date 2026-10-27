package eu.advantly.android

import android.content.Context
import androidx.core.content.edit
import eu.advantly.ui.phone.auth.StartLoginHost
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Wahl zum Startbildschirm in SharedPreferences, nicht in Konto oder Matchdaten. Wie `advantly_auth.xml` von Sicherung und
 * Geräteumzug ausgenommen: Auf einem neuen Handy fehlt die Anmeldung, also soll der Startbildschirm dort wieder kommen.
 */
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
