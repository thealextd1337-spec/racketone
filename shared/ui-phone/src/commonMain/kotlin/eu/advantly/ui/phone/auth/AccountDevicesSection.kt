package eu.advantly.ui.phone.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.advantly.presentation.auth.AuthState
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.account_cancel
import eu.advantly.ui.phone.resources.account_device_last_seen
import eu.advantly.ui.phone.resources.account_device_revoke
import eu.advantly.ui.phone.resources.account_device_revoke_explanation
import eu.advantly.ui.phone.resources.account_device_revoked
import eu.advantly.ui.phone.resources.account_device_unknown
import eu.advantly.ui.phone.resources.account_devices_empty
import eu.advantly.ui.phone.resources.account_devices_show
import eu.advantly.ui.phone.resources.account_devices_title
import org.jetbrains.compose.resources.stringResource

/** K4: eigene Geräte ansehen und den Zugriff einzelner Geräte endgültig entziehen (mit Rückfrage). */
@Composable
internal fun AccountDevicesSection(
    state: AuthState,
    model: AuthViewModel,
) {
    var confirm by remember(state.devices) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().testTag("account_devices"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(stringResource(Res.string.account_devices_title), style = MaterialTheme.typography.titleMedium)
        val devices = state.devices
        if (devices == null) {
            TextButton(model::loadDevices, enabled = !state.busy, modifier = Modifier.testTag("account_devices_show")) {
                Text(stringResource(Res.string.account_devices_show))
            }
        } else if (devices.isEmpty()) {
            Text(stringResource(Res.string.account_devices_empty))
        }
        devices?.forEach { device ->
            Text(
                listOfNotNull(device.model, device.platform, device.kind).distinct().joinToString(" · ").ifBlank {
                    stringResource(Res.string.account_device_unknown)
                },
            )
            // Nur der Tag: genug zum Wiedererkennen, ohne Uhrzeit-/Zeitzonendetails.
            device.lastSeenAt?.take(10)?.let { Text(stringResource(Res.string.account_device_last_seen, it)) }
            if (device.revokedAt != null) {
                Text(stringResource(Res.string.account_device_revoked), Modifier.testTag("account_device_revoked_${device.deviceId}"))
            } else {
                TextButton(
                    { confirm = device.deviceId },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("account_device_revoke_${device.deviceId}"),
                ) { Text(stringResource(Res.string.account_device_revoke)) }
            }
        }
    }
    confirm?.let { deviceId ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(Res.string.account_device_revoke)) },
            text = { Text(stringResource(Res.string.account_device_revoke_explanation)) },
            confirmButton = {
                TextButton({
                    confirm = null
                    model.revokeDevice(deviceId)
                }, modifier = Modifier.testTag("account_device_revoke_confirm")) {
                    Text(stringResource(Res.string.account_device_revoke))
                }
            },
            dismissButton = {
                TextButton({ confirm = null }, modifier = Modifier.testTag("account_device_revoke_cancel")) {
                    Text(stringResource(Res.string.account_cancel))
                }
            },
        )
    }
}
