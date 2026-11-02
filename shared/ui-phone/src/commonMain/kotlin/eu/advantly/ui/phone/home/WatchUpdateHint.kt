package eu.advantly.ui.phone.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import eu.advantly.match.session.DeviceStatus
import eu.advantly.presentation.match.LiveMatchPort
import eu.advantly.sync.protocol.AppBuild
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.ui.phone.common.AdvantlyCard
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.action_later
import eu.advantly.ui.phone.resources.watch_update_action
import eu.advantly.ui.phone.resources.watch_update_failed
import eu.advantly.ui.phone.resources.watch_update_manual
import eu.advantly.ui.phone.resources.watch_update_opened
import eu.advantly.ui.phone.resources.watch_update_pulse
import eu.advantly.ui.phone.resources.watch_update_text
import eu.advantly.ui.phone.resources.watch_update_title
import org.jetbrains.compose.resources.stringResource

/**
 * Plan 0.5.1 §3: verbundene Uhren, deren App älter ist als die dieses Handys. Verglichen wird die `versionName` aus dem
 * Hello ([AppBuild.version]): Den `buildNumber` senden Uhren bis 0.5.0 nicht, und die Version steigt mit jedem Release.
 * Unbekannte oder unlesbare Versionen zählen nicht als älter.
 */
internal fun watchUpdateTargets(
    devices: List<DeviceStatus>,
    local: AppBuild?,
): List<DeviceStatus> {
    val own = local?.version?.let(::versionParts) ?: return emptyList()
    return devices.filter { device ->
        val watch = device.appBuild?.version?.let(::versionParts)
        device.kind == PeerKind.WATCH && device.reachable && device.appInstalled != false && watch != null &&
            VERSION_ORDER.compare(watch, own) < 0
    }
}

/** „x.y.z“ → Zahlen; alles andere `null`. */
internal fun versionParts(version: String): List<Int>? =
    version
        .split('.')
        .map { it.toIntOrNull() ?: return null }
        .takeIf { it.size == 3 }

private val VERSION_ORDER = compareBy<List<Int>>({ it[0] }, { it[1] }, { it[2] })

/** Pulsverlauf gibt es auf der Uhr ab 0.5.0 (Plan Puls). */
private val PULSE_HISTORY_VERSION = listOf(0, 5, 0)

/** „Später“ gilt für genau diesen Stand von Uhr und Handy; ein neuerer Handy- oder Uhr-Stand zeigt die Karte wieder. */
private fun laterKey(device: DeviceStatus) = "watchUpdate.later.${device.peer}"

private fun laterValue(
    device: DeviceStatus,
    local: AppBuild?,
) = "${device.appBuild?.version}@${local?.version}"

/** Watch-Update-Karten, die [port] gerade zeigen würde (auch für „Was ist neu?“). */
internal fun pendingWatchUpdates(
    port: LiveMatchPort,
    devices: List<DeviceStatus>,
): List<DeviceStatus> =
    watchUpdateTargets(devices, port.localAppBuild).filter { port.localNote(laterKey(it)) != laterValue(it, port.localAppBuild) }

/**
 * Karte „Uhr-App aktualisieren“ (Plan 0.5.1 §3) je Uhr mit älterer App. Nur ein Hinweis, keine Sperre (Versionen bleiben
 * über die Protokoll-Aushandlung kompatibel). Mit [host] (Android) öffnet „Auf der Uhr öffnen“ die Store-Seite auf der Uhr;
 * ohne (iOS: die Uhr-App kommt mit dem iPhone-Bundle, kein Fernaufruf des Uhr-App-Stores) steht nur die Anleitung da.
 */
@Composable
internal fun WatchUpdateHint(
    port: LiveMatchPort,
    host: WatchInstallHost?,
) {
    val devices by port.deviceStatuses.collectAsState()
    var dismissals by remember { mutableIntStateOf(0) }
    val targets = remember(devices, dismissals) { pendingWatchUpdates(port, devices) }
    targets.forEach { device ->
        key(device.peer) {
            var opened by remember { mutableStateOf<Boolean?>(null) }
            val version = device.appBuild?.version.orEmpty()
            AdvantlyCard(Modifier.padding(bottom = Spacing.m).testTag("watch_update_${device.peer}")) {
                Text(
                    stringResource(Res.string.watch_update_title),
                    style = AdvantlyTheme.typography.label,
                    color = AdvantlyTheme.colors.text,
                )
                Text(
                    stringResource(Res.string.watch_update_text, version),
                    style = AdvantlyTheme.typography.body,
                    color = AdvantlyTheme.colors.text,
                )
                if (VERSION_ORDER.compare(versionParts(version) ?: PULSE_HISTORY_VERSION, PULSE_HISTORY_VERSION) < 0) {
                    Text(
                        stringResource(Res.string.watch_update_pulse),
                        style = AdvantlyTheme.typography.caption,
                        color = AdvantlyTheme.colors.text2,
                        modifier = Modifier.testTag("watch_update_pulse_${device.peer}"),
                    )
                }
                val note =
                    when {
                        host == null -> Res.string.watch_update_manual
                        opened == true -> Res.string.watch_update_opened
                        opened == false -> Res.string.watch_update_failed
                        else -> null
                    }
                note?.let {
                    Text(
                        stringResource(it),
                        style = AdvantlyTheme.typography.caption,
                        color = if (opened == false) AdvantlyTheme.colors.danger else AdvantlyTheme.colors.text2,
                        modifier = Modifier.testTag("watch_update_result_${device.peer}").semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    host?.let {
                        TextButton(
                            { it.install(device.peer) { result -> opened = result } },
                            modifier = Modifier.testTag("watch_update_action_${device.peer}"),
                        ) { Text(stringResource(Res.string.watch_update_action)) }
                    }
                    TextButton(
                        {
                            port.putLocalNote(laterKey(device), laterValue(device, port.localAppBuild))
                            dismissals++
                        },
                        modifier = Modifier.testTag("watch_update_later_${device.peer}"),
                    ) { Text(stringResource(Res.string.action_later)) }
                }
            }
        }
    }
}
