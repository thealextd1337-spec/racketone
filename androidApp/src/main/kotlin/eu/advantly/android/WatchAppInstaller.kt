package eu.advantly.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.wear.remote.interactions.RemoteActivityHelper
import eu.advantly.match.session.DeviceStatus
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.ui.phone.home.WatchInstallHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Uhren, denen der Installationshinweis gilt: `appInstalled == false` setzt der WearCompanionDetector für verbundene
 * Knoten ohne Capability `advantly_device_link` – also „Uhr verbunden, Advantly fehlt“; ausgeblendete Uhren fallen weg.
 */
internal fun watchInstallTargets(
    devices: List<DeviceStatus>,
    dismissed: Set<String>,
): List<DeviceStatus> = devices.filter { it.kind == PeerKind.WATCH && it.appInstalled == false && it.peer !in dismissed }

/**
 * Öffnet die Play-Store-Seite von Advantly direkt auf der Uhr (Googles Muster für Standalone-Wear-Apps). Neu bewertet
 * wird nur, wenn sich der Uhr-Status ändert (Capability-Listener, App-Vordergrund) – keine eigene Abfrageschleife.
 */
internal class WatchAppInstaller(
    context: Context,
    devices: StateFlow<List<DeviceStatus>>,
    scope: CoroutineScope,
) : WatchInstallHost {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("watch_install", Context.MODE_PRIVATE)
    private val dismissed = MutableStateFlow(prefs.getStringSet(KEY_DISMISSED, null).orEmpty().toSet())

    override val targets: StateFlow<List<DeviceStatus>> =
        combine(devices, dismissed, ::watchInstallTargets)
            .stateIn(scope, SharingStarted.Eagerly, watchInstallTargets(devices.value, dismissed.value))

    override fun install(
        peer: String,
        onResult: (Boolean) -> Unit,
    ) {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
        val future =
            try {
                RemoteActivityHelper(context).startRemoteActivity(intent, peer)
            } catch (_: Exception) {
                onResult(false)
                return
            }
        future.addListener({ onResult(runCatching { future.get() }.isSuccess) }, ContextCompat.getMainExecutor(context))
    }

    override fun dismiss(peer: String) {
        dismissed.value += peer
        prefs.edit { putStringSet(KEY_DISMISSED, dismissed.value) }
    }

    private companion object {
        const val KEY_DISMISSED = "dismissed"
    }
}
