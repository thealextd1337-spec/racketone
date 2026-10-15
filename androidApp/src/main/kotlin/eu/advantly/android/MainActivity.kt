package eu.advantly.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.advantly.ui.phone.AdvantlyApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Systemleisten folgen dem System-Theme (die App ist Dark-first, der Live-Score immer schwarz)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Debug-Builds zeigen im Live-Menü zusätzlich Latenz-Overlay, Entprellung und Auto-Benchmark (Spike S4)
            AdvantlyApp(versionName = BuildConfig.VERSION_NAME, debugTools = BuildConfig.DEBUG)
        }
    }
}
