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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import eu.advantly.match.session.DeviceStatus
import eu.advantly.ui.phone.common.AdvantlyCard
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.watch_install_action
import eu.advantly.ui.phone.resources.watch_install_dismiss
import eu.advantly.ui.phone.resources.watch_install_failed
import eu.advantly.ui.phone.resources.watch_install_opened
import eu.advantly.ui.phone.resources.watch_install_prompt
import eu.advantly.ui.phone.resources.watch_install_prompt_named
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * Uhr ohne Advantly: Store-Seite der Uhr-App auf der Uhr öffnen. Nur Android liefert eine Umsetzung
 * (Wear Data Layer + RemoteActivityHelper); iOS/Desktop übergeben `null`, der Hinweis erscheint dort nie.
 */
interface WatchInstallHost {
    /** Verbundene Uhren ohne Advantly, deren Hinweis nicht ausgeblendet wurde (Entscheidung trifft die Plattform). */
    val targets: StateFlow<List<DeviceStatus>>

    /** Öffnet die Store-Seite auf der Uhr [peer]; [onResult] kommt auf dem Main-Thread, `true` = auf der Uhr geöffnet. */
    fun install(
        peer: String,
        onResult: (Boolean) -> Unit,
    )

    /** „Nicht mehr anzeigen“ für diese Uhr; bleibt über App-Starts erhalten. */
    fun dismiss(peer: String)
}

/** Unauffälliger Hinweis unter dem Uhr-Status der Startseite, je Uhr ohne Advantly einer. */
@Composable
internal fun WatchInstallHint(host: WatchInstallHost) {
    val targets by host.targets.collectAsState()
    targets.forEach { device ->
        key(device.peer) {
            var opened by remember { mutableStateOf<Boolean?>(null) }
            AdvantlyCard(Modifier.padding(bottom = Spacing.m).testTag("watch_install_${device.peer}")) {
                Text(
                    if (targets.size > 1 && device.name.isNotBlank()) {
                        stringResource(Res.string.watch_install_prompt_named, device.name)
                    } else {
                        stringResource(Res.string.watch_install_prompt)
                    },
                    style = AdvantlyTheme.typography.body,
                    color = AdvantlyTheme.colors.text,
                )
                opened?.let {
                    Text(
                        stringResource(if (it) Res.string.watch_install_opened else Res.string.watch_install_failed),
                        style = AdvantlyTheme.typography.caption,
                        color = if (it) AdvantlyTheme.colors.text2 else AdvantlyTheme.colors.danger,
                        modifier = Modifier.testTag("watch_install_result_${device.peer}"),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    TextButton(
                        { host.install(device.peer) { opened = it } },
                        modifier = Modifier.testTag("watch_install_action_${device.peer}"),
                    ) { Text(stringResource(Res.string.watch_install_action)) }
                    TextButton(
                        { host.dismiss(device.peer) },
                        modifier = Modifier.testTag("watch_install_dismiss_${device.peer}"),
                    ) { Text(stringResource(Res.string.watch_install_dismiss)) }
                }
            }
        }
    }
}
