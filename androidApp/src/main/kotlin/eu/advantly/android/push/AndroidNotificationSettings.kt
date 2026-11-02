package eu.advantly.android.push

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import eu.advantly.presentation.push.NotificationSettingsPort
import eu.advantly.presentation.push.PushRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Profil-Schalter und Hinweis: Einschalten fragt ab Android 13 erst jetzt `POST_NOTIFICATIONS` ab. Ist die Berechtigung
 * dauerhaft verweigert (kein Systemdialog mehr), öffnet sich die Benachrichtigungs-Einstellung der App.
 */
internal class AndroidNotificationSettings(
    private val activity: ComponentActivity,
    private val push: PushRegistration,
    /** Prozess-Scope des Konto-Hosts: Abmelden vom Token läuft auch nach dem Schließen der Activity zu Ende. */
    private val scope: CoroutineScope,
    private val requestPermission: (onResult: (Boolean) -> Unit) -> Unit,
) : NotificationSettingsPort {
    override val enabled = push.enabled
    override val hint = push.hint

    override fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            scope.launch { push.disable() }
            return
        }
        if (Build.VERSION.SDK_INT < 33 ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            scope.launch { push.enable() }
            return
        }
        requestPermission { granted ->
            if (granted) {
                scope.launch { push.enable() }
            } else if (!activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                try {
                    activity.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName),
                    )
                } catch (_: ActivityNotFoundException) {
                    // Ohne Einstellungsseite bleibt der Schalter aus.
                }
            }
        }
    }

    override fun dismissHint() = push.dismissHint()
}
