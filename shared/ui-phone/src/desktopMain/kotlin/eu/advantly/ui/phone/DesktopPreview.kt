package eu.advantly.ui.phone

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** Desktop-Preview auf Windows (kein Produkt): `./gradlew :shared:ui-phone:run`. */
fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Advantly – Desktop-Preview",
            state = rememberWindowState(width = 400.dp, height = 800.dp),
        ) {
            AdvantlyApp(versionName = "desktop-preview")
        }
    }
