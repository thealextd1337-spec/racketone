package eu.advantly.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import eu.advantly.match.session.DeviceLight
import eu.advantly.wear.R

@Composable
internal fun PhoneStatusLabel(light: DeviceLight) {
    val text =
        stringResource(
            when (light) {
                DeviceLight.GREEN -> R.string.phone_status_green
                DeviceLight.YELLOW -> R.string.phone_status_yellow
                DeviceLight.RED -> R.string.phone_status_red
                DeviceLight.GRAY -> R.string.phone_status_gray
            },
        )
    val color =
        when (light) {
            DeviceLight.GREEN -> Color(0xFF4ADE80)
            DeviceLight.YELLOW -> HintAmber
            DeviceLight.RED -> MaterialTheme.colorScheme.error
            DeviceLight.GRAY -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val badge =
        when (light) {
            DeviceLight.GREEN -> "✓"
            DeviceLight.YELLOW, DeviceLight.RED -> "!"
            DeviceLight.GRAY -> "–"
        }
    Text(
        "$badge $text",
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag(PHONE_STATUS_TAG)
                .semantics { contentDescription = text },
        style = MaterialTheme.typography.labelSmall,
        color = color,
        textAlign = TextAlign.Center,
    )
}

const val PHONE_STATUS_TAG = "home_phone_status"
