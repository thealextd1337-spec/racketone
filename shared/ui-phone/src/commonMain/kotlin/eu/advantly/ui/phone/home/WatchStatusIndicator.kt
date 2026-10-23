package eu.advantly.ui.phone.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.advantly.match.session.DeviceLight
import eu.advantly.match.session.DeviceStatus
import eu.advantly.presentation.match.LiveMatchPort
import eu.advantly.sync.protocol.AppBuild
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.ui.phone.app.versionDetails
import eu.advantly.ui.phone.designsystem.AdvantlyIcons
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.platform.formatBuildDate
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.device_details
import eu.advantly.ui.phone.resources.device_development
import eu.advantly.ui.phone.resources.device_gray
import eu.advantly.ui.phone.resources.device_green
import eu.advantly.ui.phone.resources.device_last_seen
import eu.advantly.ui.phone.resources.device_more
import eu.advantly.ui.phone.resources.device_phone_build
import eu.advantly.ui.phone.resources.device_ready
import eu.advantly.ui.phone.resources.device_reconnect
import eu.advantly.ui.phone.resources.device_red
import eu.advantly.ui.phone.resources.device_repair
import eu.advantly.ui.phone.resources.device_repair_hint
import eu.advantly.ui.phone.resources.device_unknown
import eu.advantly.ui.phone.resources.device_update_phone
import eu.advantly.ui.phone.resources.device_update_recommended
import eu.advantly.ui.phone.resources.device_update_watch
import eu.advantly.ui.phone.resources.device_watch
import eu.advantly.ui.phone.resources.device_watch_build
import eu.advantly.ui.phone.resources.device_yellow
import eu.advantly.ui.phone.resources.wizard_scorer_not_installed
import org.jetbrains.compose.resources.stringResource

/** F31: bis zu zwei Einzelsymbole, danach eine kompakte Gruppe; Details enthalten alle Uhren. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WatchStatusIndicator(port: LiveMatchPort) {
    val devices by port.deviceStatuses.collectAsState()
    val watches = devices.filter { it.kind == PeerKind.WATCH }
    var details by remember { mutableStateOf(false) }
    val local = port.localAppBuild
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (watches.isEmpty()) {
            WatchButton(null, local, "watch_status_none") { details = true }
        } else {
            watches.take(2).forEach { device ->
                WatchButton(device, local, "watch_status_${device.peer}") { details = true }
            }
            if (watches.size > 2) {
                val hiddenLights = watches.drop(2).map { it.light(local) }
                val light =
                    when {
                        DeviceLight.RED in hiddenLights -> DeviceLight.RED
                        DeviceLight.YELLOW in hiddenLights -> DeviceLight.YELLOW
                        DeviceLight.GRAY in hiddenLights -> DeviceLight.GRAY
                        else -> DeviceLight.GREEN
                    }
                val description = stringResource(Res.string.device_more, watches.size - 2) + ": " + statusText(light)
                IconButton(
                    onClick = { details = true },
                    modifier =
                        Modifier.testTag("watch_status_group").semantics {
                            contentDescription =
                                description
                        },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("+${watches.size - 2}", color = statusColor(light), style = AdvantlyTheme.typography.caption)
                        Text(statusBadge(light), color = statusColor(light), style = AdvantlyTheme.typography.caption)
                    }
                }
            }
        }
    }
    if (details) {
        ModalBottomSheet(onDismissRequest = { details = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .testTag("watch_status_sheet"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(Res.string.device_details), style = AdvantlyTheme.typography.title)
                if (watches.isEmpty()) Text(stringResource(Res.string.device_gray))
                watches.forEach { device ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(deviceName(device), style = AdvantlyTheme.typography.title)
                        Text(statusText(device, local))
                        Text(stringResource(Res.string.device_watch_build, buildText(device.appBuild)))
                        Text(stringResource(Res.string.device_phone_build, buildText(local)))
                        Text(
                            stringResource(
                                Res.string.device_last_seen,
                                device.lastSeenAtMs?.let(::formatBuildDate)
                                    ?: stringResource(Res.string.device_unknown),
                            ),
                        )
                        val hint =
                            when {
                                device.appInstalled == false -> Res.string.wizard_scorer_not_installed
                                device.compatibility == CompatibilityStatus.PEER_TOO_OLD -> Res.string.device_update_watch
                                device.compatibility == CompatibilityStatus.PEER_TOO_NEW -> Res.string.device_update_phone
                                !device.reachable -> Res.string.device_reconnect
                                device.isDevelopmentBuild(local) -> Res.string.device_development
                                device.light(local) == DeviceLight.YELLOW -> Res.string.device_update_recommended
                                else -> Res.string.device_ready
                            }
                        Text(stringResource(hint), style = AdvantlyTheme.typography.body)
                    }
                }
                if (watches.isNotEmpty()) {
                    Text(stringResource(Res.string.device_repair_hint), style = AdvantlyTheme.typography.body)
                    TextButton({
                        port.repairWatchPairing()
                        details = false
                    }, modifier = Modifier.testTag("watch_repair")) { Text(stringResource(Res.string.device_repair)) }
                }
            }
        }
    }
}

@Composable
private fun WatchButton(
    device: DeviceStatus?,
    local: AppBuild?,
    tag: String,
    onClick: () -> Unit,
) {
    val light = device?.light(local) ?: DeviceLight.GRAY
    val color = statusColor(light)
    val label = listOfNotNull(device?.let { deviceName(it) }, device?.let { statusText(it, local) } ?: statusText(light)).joinToString(": ")
    IconButton(onClick = onClick, modifier = Modifier.testTag(tag).semantics { contentDescription = label }) {
        Box(Modifier.size(32.dp)) {
            Icon(AdvantlyIcons.Watch, contentDescription = null, tint = color, modifier = Modifier.size(28.dp).align(Alignment.Center))
            Text(
                statusBadge(light),
                color = color,
                style = AdvantlyTheme.typography.caption,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
private fun deviceName(device: DeviceStatus): String = device.name.ifBlank { stringResource(Res.string.device_watch) }

@Composable
private fun buildText(build: AppBuild?): String =
    build?.let { versionDetails(it.version, it.commit, it.timeUtc) }
        ?: stringResource(Res.string.device_unknown)

private fun DeviceStatus.isDevelopmentBuild(local: AppBuild?): Boolean =
    local?.commit?.endsWith("-dirty") == true || appBuild?.commit?.endsWith("-dirty") == true

@Composable
private fun statusText(
    device: DeviceStatus,
    local: AppBuild?,
): String =
    when {
        device.appInstalled == false -> stringResource(Res.string.wizard_scorer_not_installed)
        device.compatibility == CompatibilityStatus.PEER_TOO_OLD -> stringResource(Res.string.device_update_watch)
        device.compatibility == CompatibilityStatus.PEER_TOO_NEW -> stringResource(Res.string.device_update_phone)
        device.light(local) == DeviceLight.YELLOW && device.isDevelopmentBuild(local) -> stringResource(Res.string.device_development)
        else -> statusText(device.light(local))
    }

@Composable
private fun statusText(light: DeviceLight): String =
    stringResource(
        when (light) {
            DeviceLight.GREEN -> Res.string.device_green
            DeviceLight.YELLOW -> Res.string.device_yellow
            DeviceLight.RED -> Res.string.device_red
            DeviceLight.GRAY -> Res.string.device_gray
        },
    )

@Composable
private fun statusColor(light: DeviceLight): androidx.compose.ui.graphics.Color =
    when (light) {
        DeviceLight.GREEN -> AdvantlyTheme.colors.success
        DeviceLight.YELLOW -> AdvantlyTheme.colors.hint
        DeviceLight.RED -> AdvantlyTheme.colors.danger
        DeviceLight.GRAY -> AdvantlyTheme.colors.text3
    }

private fun statusBadge(light: DeviceLight): String =
    when (light) {
        DeviceLight.GREEN -> "✓"
        DeviceLight.YELLOW -> "!"
        DeviceLight.RED -> "×"
        DeviceLight.GRAY -> "–"
    }
