package eu.advantly.android.push

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import eu.advantly.presentation.push.NotificationPrompt
import eu.advantly.presentation.push.NotificationPromptPort
import eu.advantly.presentation.push.NotificationPromptStore
import eu.advantly.presentation.push.NotificationSettingsPort
import eu.advantly.presentation.push.PromptStage
import eu.advantly.presentation.push.PushRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Profil-Schalter, Hinweis und Erklärseite (Plan W12, Paket 2). Einschalten fragt ab Android 13 erst jetzt
 * `POST_NOTIFICATIONS` ab ([requestPermission]); das Ergebnis kommt über [permissionResult] – auch bei einer neu erzeugten
 * Activity (Drehen, Prozesstod während des Systemdialogs), deshalb ohne Rückruf je Anfrage; ob die Erklärseite fragte, steht
 * darum in den Einstellungen. Lehnt man über den Schalter dauerhaft ab (kein Systemdialog mehr), öffnet sich die
 * Benachrichtigungs-Einstellung der App, nach der Erklärseite nicht.
 */
internal class AndroidNotificationSettings(
    private val activity: ComponentActivity,
    private val push: PushRegistration,
    /** Prozess-Scope des Konto-Hosts: Abmelden vom Token läuft auch nach dem Schließen der Activity zu Ende. */
    private val scope: CoroutineScope,
    private val requestPermission: () -> Unit,
) : NotificationSettingsPort,
    NotificationPromptPort {
    private val prefs = activity.getSharedPreferences(AndroidPushPreferences.FILE, Context.MODE_PRIVATE)
    private val machine = NotificationPrompt(AndroidPromptStore(prefs), System::currentTimeMillis)
    private val allowed = MutableStateFlow(systemAllowed())

    /** Neu auswerten: Hinweis ausgeblendet, Frist abgelaufen. */
    private val tick = MutableStateFlow(0)

    /** Gewünscht und vom System erlaubt; eine entzogene Berechtigung zeigt den Schalter aus. */
    override val enabled: StateFlow<Boolean> =
        combine(push.enabled, allowed) { wanted, system -> wanted && system }
            .stateIn(activity.lifecycleScope, SharingStarted.Eagerly, displayOn())
    override val hint: StateFlow<Boolean> =
        combine(enabled, tick) { on, _ -> machine.hintVisible(on) }
            .stateIn(activity.lifecycleScope, SharingStarted.Eagerly, machine.hintVisible(displayOn()))
    override val prompt = machine.prompt

    override fun setEnabled(enabled: Boolean) {
        machine.decided()
        if (enabled) turnOn(fromPrompt = false) else scope.launch { push.disable() }
    }

    override fun dismissHint() {
        machine.hideHint()
        tick.value++
    }

    override fun onMoment() = machine.onMoment(displayOn())

    override fun accept() {
        machine.decided()
        turnOn(fromPrompt = true)
    }

    override fun later() = machine.later()

    /** Activity im Vordergrund: Systemeinstellung kann sich geändert haben, Ausblend-Frist abgelaufen sein. */
    fun refresh() {
        allowed.value = systemAllowed()
        tick.value++
    }

    /** Antwort auf den Systemdialog aus [turnOn] (ggf. aus einer früheren Activity-Instanz). */
    fun permissionResult(granted: Boolean) {
        val fromPrompt = prefs.getBoolean(KEY_FROM_PROMPT, false)
        prefs.edit().remove(KEY_FROM_PROMPT).apply()
        refresh()
        when {
            granted -> scope.launch { push.enable() }
            fromPrompt -> machine.denied()
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> openSettings()
        }
    }

    /** Bis Android 12 gibt es keine Laufzeit-Berechtigung: direkt einschalten. */
    private fun turnOn(fromPrompt: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean(KEY_FROM_PROMPT, fromPrompt).apply()
            requestPermission()
            return
        }
        scope.launch { push.enable() }
        // In den Systemeinstellungen gesperrt: nur über den Schalter dorthin, nie direkt aus der Erklärseite.
        if (!fromPrompt && !allowed.value) openSettings()
    }

    private fun displayOn() = push.enabled.value && allowed.value

    private fun systemAllowed() = activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun openSettings() {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName),
            )
        } catch (_: ActivityNotFoundException) {
            // Ohne Einstellungsseite bleibt der Schalter aus.
        }
    }

    private companion object {
        const val KEY_FROM_PROMPT = "permission_from_prompt"
    }
}

/**
 * Stand der Erklärseite in [AndroidPushPreferences.FILE] (nicht im Backup). Ohne gespeicherten Stand (Update von 0.7.x):
 * wer nie entschieden hat (`hint_done` aus), sieht die Seite einmal; wer schon ein- oder ausgeschaltet hat, nur den Hinweis.
 */
internal class AndroidPromptStore(
    private val prefs: SharedPreferences,
) : NotificationPromptStore {
    override var stage: PromptStage
        get() =
            prefs.getString("prompt_stage", null)?.let { saved -> PromptStage.entries.firstOrNull { it.name == saved } }
                ?: if (prefs.getBoolean("hint_done", false) || prefs.getBoolean("enabled", false)) PromptStage.DONE else PromptStage.NEW
        set(value) = prefs.edit().putString("prompt_stage", value.name).apply()
    override var laterAtMs: Long
        get() = prefs.getLong("prompt_later_at", 0)
        set(value) = prefs.edit().putLong("prompt_later_at", value).apply()
    override var hintHiddenAtMs: Long
        get() = prefs.getLong("hint_hidden_at", 0)
        set(value) = prefs.edit().putLong("hint_hidden_at", value).apply()
}
