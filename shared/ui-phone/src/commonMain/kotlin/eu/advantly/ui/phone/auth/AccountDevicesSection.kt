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
import eu.advantly.ui.phone.resources.account_device_current
import eu.advantly.ui.phone.resources.account_device_current_hint
import eu.advantly.ui.phone.resources.account_device_current_revoked
import eu.advantly.ui.phone.resources.account_device_last_seen
import eu.advantly.ui.phone.resources.account_device_restore
import eu.advantly.ui.phone.resources.account_device_restore_explanation
import eu.advantly.ui.phone.resources.account_device_restore_explanation_watch
import eu.advantly.ui.phone.resources.account_device_revoke
import eu.advantly.ui.phone.resources.account_device_revoke_explanation
import eu.advantly.ui.phone.resources.account_device_revoked
import eu.advantly.ui.phone.resources.account_device_unknown
import eu.advantly.ui.phone.resources.account_devices_empty
import eu.advantly.ui.phone.resources.account_devices_show
import eu.advantly.ui.phone.resources.account_devices_title
import org.jetbrains.compose.resources.stringResource

/**
 * K4: eigene Geräte ansehen und den Zugriff einzelner Geräte entziehen oder wiederherstellen (K4-U), jeweils mit
 * Rückfrage. Das eigene Handy hat weder Widerruf noch Freigabe: Ein widerrufenes Handy gibt ein anderes Gerät oder der
 * Support frei, nie es selbst (der Server lehnt das ab).
 */
@Composable
internal fun AccountDevicesSection(
    state: AuthState,
    model: AuthViewModel,
) {
    // Gerät und ob es um die Freigabe (true) oder den Widerruf geht.
    var confirm by remember(state.devices) { mutableStateOf<Pair<String, Boolean>?>(null) }
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
            val details = listOfNotNull(device.model, device.platform, device.kind).distinct().joinToString(" · ")
            val current = stringResource(Res.string.account_device_current)
            val label =
                if (device.current) {
                    listOf(current, details).filter { it.isNotBlank() }.joinToString(" · ")
                } else {
                    details.ifBlank { stringResource(Res.string.account_device_unknown) }
                }
            val revoked = device.revokedAt != null
            Text(
                label,
                Modifier.testTag("account_device_${device.deviceId}"),
                style = if (device.current) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            )
            // Nur der Tag: genug zum Wiedererkennen, ohne Uhrzeit-/Zeitzonendetails.
            device.lastSeenAt?.take(10)?.let { Text(stringResource(Res.string.account_device_last_seen, it)) }
            if (revoked) {
                Text(stringResource(Res.string.account_device_revoked), Modifier.testTag("account_device_revoked_${device.deviceId}"))
            }
            if (device.current) {
                val hint = if (revoked) Res.string.account_device_current_revoked else Res.string.account_device_current_hint
                Text(
                    stringResource(hint),
                    Modifier.testTag("account_device_current_hint"),
                    color = if (revoked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (revoked) {
                TextButton(
                    { confirm = device.deviceId to true },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("account_device_restore_${device.deviceId}"),
                ) { Text(stringResource(Res.string.account_device_restore)) }
            } else {
                TextButton(
                    { confirm = device.deviceId to false },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("account_device_revoke_${device.deviceId}"),
                ) { Text(stringResource(Res.string.account_device_revoke)) }
            }
        }
    }
    confirm?.let { (deviceId, restore) ->
        // Eine Uhr meldet sich nicht selbst an; ihre Events lädt das Handy hoch.
        val watch = state.devices?.firstOrNull { it.deviceId == deviceId }?.kind == "watch"
        val explanation =
            when {
                !restore -> Res.string.account_device_revoke_explanation
                watch -> Res.string.account_device_restore_explanation_watch
                else -> Res.string.account_device_restore_explanation
            }
        val action = if (restore) Res.string.account_device_restore else Res.string.account_device_revoke
        val tag = if (restore) "account_device_restore" else "account_device_revoke"
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(action)) },
            text = { Text(stringResource(explanation)) },
            confirmButton = {
                TextButton({
                    confirm = null
                    if (restore) model.restoreDevice(deviceId) else model.revokeDevice(deviceId)
                }, modifier = Modifier.testTag("${tag}_confirm")) {
                    Text(stringResource(action))
                }
            },
            dismissButton = {
                TextButton({ confirm = null }, modifier = Modifier.testTag("${tag}_cancel")) {
                    Text(stringResource(Res.string.account_cancel))
                }
            },
        )
    }
}
