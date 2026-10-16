package eu.advantly.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import eu.advantly.android.auth.createOfflineSupport
import eu.advantly.android.auth.createPhoneAccount
import eu.advantly.android.auth.googleCredential
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
                        companion = WearCompanionDetector(applicationContext),
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
    }
}
