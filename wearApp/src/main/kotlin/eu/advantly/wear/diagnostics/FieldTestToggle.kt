package eu.advantly.wear.diagnostics

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import eu.advantly.wear.R

/**
 * Versteckter Schalter „Feldtest-Modus“ ganz unten auf dem Startbildschirm – nur im Debug-Build sichtbar
 * ([FieldTestRecorder.AVAILABLE]), Standard aus. Im Release-Build rendert er nichts.
 */
@Composable
fun FieldTestToggle() {
    if (!FieldTestRecorder.AVAILABLE) return
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(FieldTestRecorder.isEnabled(context)) }
    SwitchButton(
        checked = enabled,
        onCheckedChange = {
            FieldTestRecorder.setEnabled(context, it)
            enabled = it
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.field_test_toggle)) },
        secondaryLabel = { Text(stringResource(R.string.field_test_hint, FieldTestRecorder.FILE_NAME)) },
    )
}
