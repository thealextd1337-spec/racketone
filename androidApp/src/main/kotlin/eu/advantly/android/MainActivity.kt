package eu.advantly.android

import android.app.AlertDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import eu.advantly.android.auth.PhoneExportHost
import eu.advantly.android.auth.createOfflineSupport
import eu.advantly.android.auth.createPhoneAccount
import eu.advantly.android.auth.googleCredential
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.device.link.WearCompanionDetector
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.match.SessionMatchStore
import eu.advantly.presentation.support.SupportViewModel
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.app.rememberAdvantlyAppModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Systemleisten folgen dem System-Theme (die App ist Dark-first, der Live-Score immer schwarz)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val availability = AndroidInstallationGuard.check(applicationContext)
        if (availability !is AndroidInstallationAvailability.Ready) {
            showInstallationError()
            return
        }
        val afterInstallationRetry = intent.getBooleanExtra(INSTALLATION_RETRY_COMPLETE, false)
        intent.removeExtra(INSTALLATION_RETRY_COMPLETE)
        (application as? AdvantlyPhoneApplication)?.bootstrap()
        val installation = availability.installation
        // ViewModelStore hält Auth über Rotation; keine Credentials in Bundle oder SavedState.
        val auth =
            if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_KEY.isBlank()) {
                null
            } else {
                runCatching {
                    ViewModelProvider(
                        this,
                        object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = createPhoneAccount() as T
                        },
                    )[AuthViewModel::class.java]
                }.getOrNull()
            }
        val support =
            auth?.support ?: runCatching {
                ViewModelProvider(
                    this,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = createOfflineSupport() as T
                    },
                )[SupportViewModel::class.java]
            }.getOrNull()
        setContent {
            // Prozessweite Match-Session (Datenbank + Wear Data Layer zur Uhr); ohne Uhr bzw. Play Services bleibt der
            // Link „nicht verfügbar“ und das Handy zählt selbst (Director).
            // „Meine Uhr“: gekoppelte Uhr mit installierter App (Capability), auch wenn die Uhr-App gerade nicht läuft
            val model =
                rememberAdvantlyAppModel {
                    SessionMatchStore(
                        AndroidMatchSessions.get(applicationContext, PeerKind.PHONE),
                        companion = if (installation.privateTransportAllowed) WearCompanionDetector(applicationContext) else null,
                    )
                }
            // Debug-Builds zeigen im Live-Menü zusätzlich Latenz-Overlay, Entprellung und Auto-Benchmark (Spike S4)
            AdvantlyApp(
                model = model,
                versionName = BuildConfig.VERSION_NAME,
                buildCommit = getString(R.string.build_git_commit),
                buildTimeUtc = getString(R.string.build_time_utc),
                debugTools = BuildConfig.DEBUG,
                authModel = auth,
                onShareExport = this@MainActivity.phoneExportShareCallback(auth),
                supportModel = support,
                supportDiagnostics = mapOf("os" to "Android ${android.os.Build.VERSION.RELEASE}", "device" to android.os.Build.MODEL),
                googleCredential =
                    if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
                        null
                    } else {
                        { googleCredential() }
                    },
            )
        }
        if (!installation.privateTransportAllowed && (savedInstanceState == null || afterInstallationRetry)) {
            AlertDialog
                .Builder(this)
                .setTitle(R.string.recovery_notice_title)
                .setMessage(R.string.recovery_notice_body)
                .setPositiveButton(R.string.recovery_continue_offline, null)
                .show()
        }
    }
}

/** Gehört der aktuellen Activity/Composition; niemals im ViewModel oder mit remember halten. */
internal fun ComponentActivity.phoneExportShareCallback(auth: AuthViewModel?): (() -> Unit)? =
    (auth?.export as? PhoneExportHost)?.let { host -> { host.share(this) } }
