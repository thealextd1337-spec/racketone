package eu.advantly.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.presentation.match.SessionMatchStore
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.app.rememberAdvantlyAppModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Systemleisten folgen dem System-Theme (die App ist Dark-first, der Live-Score immer schwarz)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Prozessweite Match-Session (Datenbank + Wear Data Layer zur Uhr); ohne Uhr bzw. Play Services bleibt der
            // Link „nicht verfügbar“ und das Handy zählt selbst (Director).
            val model = rememberAdvantlyAppModel { SessionMatchStore(AndroidMatchSessions.get(applicationContext, PeerKind.PHONE)) }
            // Debug-Builds zeigen im Live-Menü zusätzlich Latenz-Overlay, Entprellung und Auto-Benchmark (Spike S4)
            AdvantlyApp(model = model, versionName = BuildConfig.VERSION_NAME, debugTools = BuildConfig.DEBUG)
        }
    }
}
