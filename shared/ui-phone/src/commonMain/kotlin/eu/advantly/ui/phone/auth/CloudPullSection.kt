package eu.advantly.ui.phone.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.advantly.presentation.auth.PhonePullBlock
import eu.advantly.presentation.auth.PhonePullPort
import eu.advantly.presentation.auth.PhonePullResult
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.cloud_pull_blocked
import eu.advantly.ui.phone.resources.cloud_pull_blocked_materialization
import eu.advantly.ui.phone.resources.cloud_pull_blocked_permission
import eu.advantly.ui.phone.resources.cloud_pull_blocked_readiness
import eu.advantly.ui.phone.resources.cloud_pull_blocked_redactions
import eu.advantly.ui.phone.resources.cloud_pull_blocked_removals
import eu.advantly.ui.phone.resources.cloud_pull_cancelled
import eu.advantly.ui.phone.resources.cloud_pull_complete
import eu.advantly.ui.phone.resources.cloud_pull_connection_failed
import eu.advantly.ui.phone.resources.cloud_pull_failed
import eu.advantly.ui.phone.resources.cloud_pull_limit
import eu.advantly.ui.phone.resources.cloud_pull_now
import eu.advantly.ui.phone.resources.cloud_pull_running
import eu.advantly.ui.phone.resources.cloud_pull_title
import org.jetbrains.compose.resources.stringResource

/** Ergebnis des letzten expliziten Cloud-Abgleichs; BLOCKED nennt immer einen Grund. */
@Composable
fun CloudPullSection(
    port: PhonePullPort,
    /** Ohne eigenen Download-Knopf, wenn „Jetzt synchronisieren“ Download und Upload in fester Reihenfolge startet. */
    manual: Boolean = true,
) {
    val state by port.state.collectAsState()
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxWidth().testTag("cloud_pull_section"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.cloud_pull_title), style = MaterialTheme.typography.titleMedium)
            val outcome =
                if (state.busy) {
                    Res.string.cloud_pull_running
                } else {
                    when (state.result) {
                        PhonePullResult.COMPLETE -> Res.string.cloud_pull_complete
                        PhonePullResult.LIMIT_REACHED -> Res.string.cloud_pull_limit
                        PhonePullResult.BLOCKED -> Res.string.cloud_pull_blocked
                        PhonePullResult.CONNECTION_FAILED -> Res.string.cloud_pull_connection_failed
                        PhonePullResult.FAILED -> Res.string.cloud_pull_failed
                        PhonePullResult.CANCELLED -> Res.string.cloud_pull_cancelled
                        null -> null
                    }
                }
            outcome?.let { Text(stringResource(it), Modifier.testTag("cloud_pull_result")) }
            state.blocked?.let {
                val reason =
                    when (it) {
                        PhonePullBlock.PERMISSION -> Res.string.cloud_pull_blocked_permission
                        PhonePullBlock.REMOVALS -> Res.string.cloud_pull_blocked_removals
                        PhonePullBlock.REDACTIONS -> Res.string.cloud_pull_blocked_redactions
                        PhonePullBlock.MATERIALIZATION -> Res.string.cloud_pull_blocked_materialization
                        PhonePullBlock.READINESS -> Res.string.cloud_pull_blocked_readiness
                    }
                Text(stringResource(reason), Modifier.testTag("cloud_pull_blocked"), color = MaterialTheme.colorScheme.error)
            }
            if (manual) {
                Button(
                    port::pullNow,
                    enabled = state.available && !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("cloud_pull_now"),
                ) { Text(stringResource(Res.string.cloud_pull_now)) }
            }
        }
    }
}
