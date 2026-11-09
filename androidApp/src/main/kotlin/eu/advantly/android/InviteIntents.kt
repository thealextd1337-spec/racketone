package eu.advantly.android

import android.content.Intent
import eu.advantly.presentation.friends.InviteLinks

/**
 * App Link `https://advantly.eu/i/#<token>` (plan-einladungen §3.1). MainActivity ist exportiert: nur VIEW-Intents, deren
 * URL der strenge Parser annimmt; alles andere wird ignoriert. Der Token geht ins ViewModel (SavedStateHandle), nie in
 * Prefs oder Logs.
 */
internal fun Intent.inviteToken(): String? = if (action == Intent.ACTION_VIEW) dataString?.let(InviteLinks::parse) else null

/**
 * Hält ein Einladungsziel, bis der Konto-Host bereit ist ([deliver] gesetzt; die Installationsprüfung läuft asynchron).
 * Ein übernommener Intent verliert seine URL, damit eine Neuerzeugung (Drehen) ihn nicht erneut auslöst.
 */
internal class InviteInbox {
    private var waiting: String? = null

    var deliver: ((String) -> Unit)? = null
        set(value) {
            field = value
            val token = waiting ?: return
            if (value != null) {
                waiting = null
                value(token)
            }
        }

    /**
     * [restored]: Activity aus gespeichertem Zustand (Drehen, Prozessende) – der Link liegt dann schon im
     * SavedStateHandle. Ein Start aus „Zuletzt verwendet“ wiederholt den ursprünglichen Intent und zählt nicht.
     */
    fun take(
        intent: Intent?,
        restored: Boolean = false,
    ) {
        if (intent == null || restored || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val token = intent.inviteToken() ?: return
        intent.data = null
        val target = deliver
        if (target != null) target(token) else waiting = token
    }
}
