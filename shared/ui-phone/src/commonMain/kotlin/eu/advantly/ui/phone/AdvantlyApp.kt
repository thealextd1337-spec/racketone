package eu.advantly.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Wurzel-Composable der Phone-App (Android, iOS, Desktop-Preview). Platzhalter bis Welle 1. */
@Composable
fun AdvantlyApp(
    versionName: String,
    modifier: Modifier = Modifier,
) {
    MaterialTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = "Advantly", style = MaterialTheme.typography.displayMedium)
                Text(text = "Version $versionName", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
