package eu.advantly.android

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.live.LiveScoreSpikeApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Live-Screen ist immer schwarz → helle Systemleisten-Symbole
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            // Spike S4: Debug-Build startet direkt im Live-Score-Screen (inkl. Latenz-Overlay)
            if (BuildConfig.DEBUG) {
                LiveScoreSpikeApp()
            } else {
                AdvantlyApp(versionName = BuildConfig.VERSION_NAME)
            }
        }
    }
}
