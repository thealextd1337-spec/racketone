package eu.advantly.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.app_name
import eu.advantly.ui.phone.resources.app_tagline
import eu.advantly.ui.phone.resources.app_version
import org.jetbrains.compose.resources.stringResource

/** Wurzel-Composable der Phone-App. Platzhalter bis Welle 1 (Spike S4 startet direkt in [eu.advantly.ui.phone.live.LiveScoreSpikeApp]). */
@Composable
fun AdvantlyApp(
    versionName: String,
    modifier: Modifier = Modifier,
) {
    AdvantlyTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = stringResource(Res.string.app_name), style = MaterialTheme.typography.displayMedium)
                Text(
                    text = stringResource(Res.string.app_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Text(text = stringResource(Res.string.app_version, versionName), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
