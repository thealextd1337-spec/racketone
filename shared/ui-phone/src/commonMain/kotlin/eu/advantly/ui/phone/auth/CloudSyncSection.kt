package eu.advantly.ui.phone.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import eu.advantly.presentation.auth.PhoneSyncBlock
import eu.advantly.presentation.auth.PhoneSyncPort
import eu.advantly.presentation.auth.PhoneSyncStatus
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.cloud_pull_blocked_materialization
import eu.advantly.ui.phone.resources.cloud_pull_blocked_readiness
import eu.advantly.ui.phone.resources.cloud_pull_blocked_redactions
import eu.advantly.ui.phone.resources.cloud_pull_blocked_removals
import eu.advantly.ui.phone.resources.cloud_sync_backed_up
import eu.advantly.ui.phone.resources.cloud_sync_blocked
import eu.advantly.ui.phone.resources.cloud_sync_blocked_permission
import eu.advantly.ui.phone.resources.cloud_sync_blocked_rejected
import eu.advantly.ui.phone.resources.cloud_sync_blocked_suspended
import eu.advantly.ui.phone.resources.cloud_sync_blocked_waiting
import eu.advantly.ui.phone.resources.cloud_sync_connection_failed
import eu.advantly.ui.phone.resources.cloud_sync_failed
import eu.advantly.ui.phone.resources.cloud_sync_last_backup
import eu.advantly.ui.phone.resources.cloud_sync_never
import eu.advantly.ui.phone.resources.cloud_sync_next_attempt
import eu.advantly.ui.phone.resources.cloud_sync_now
import eu.advantly.ui.phone.resources.cloud_sync_pending
import eu.advantly.ui.phone.resources.cloud_sync_running
import eu.advantly.ui.phone.resources.cloud_sync_title
import eu.advantly.ui.phone.resources.cloud_sync_unavailable
import org.jetbrains.compose.resources.stringResource

/** Sicherungsstatus des automatischen Abgleichs; „Jetzt synchronisieren“ ersetzt die einzelnen Upload-/Download-Knöpfe. */
@Composable
fun CloudSyncSection(port: PhoneSyncPort) {
    val state by port.state.collectAsState()
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxWidth().testTag("cloud_sync_section"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider()
            Text(stringResource(Res.string.cloud_sync_title), style = MaterialTheme.typography.titleMedium)
            if (!state.available) Text(stringResource(Res.string.cloud_sync_unavailable))
            Text(
                state.lastBackupAtMs?.let { stringResource(Res.string.cloud_sync_last_backup, timestamp(it)) }
                    ?: stringResource(Res.string.cloud_sync_never),
                Modifier.testTag("cloud_sync_last_backup"),
            )
            val status =
                if (state.running) {
                    Res.string.cloud_sync_running
                } else {
                    when (state.status) {
                        PhoneSyncStatus.BACKED_UP -> Res.string.cloud_sync_backed_up
                        PhoneSyncStatus.PENDING -> Res.string.cloud_sync_pending
                        PhoneSyncStatus.BLOCKED -> Res.string.cloud_sync_blocked
                        PhoneSyncStatus.CONNECTION_FAILED -> Res.string.cloud_sync_connection_failed
                        PhoneSyncStatus.FAILED -> Res.string.cloud_sync_failed
                        null -> null
                    }
                }
            status?.let { Text(stringResource(it), Modifier.testTag("cloud_sync_status")) }
            state.blocked?.let {
                val reason =
                    when (it) {
                        PhoneSyncBlock.PERMISSION -> Res.string.cloud_sync_blocked_permission
                        PhoneSyncBlock.REMOVALS -> Res.string.cloud_pull_blocked_removals
                        PhoneSyncBlock.REDACTIONS -> Res.string.cloud_pull_blocked_redactions
                        PhoneSyncBlock.MATERIALIZATION -> Res.string.cloud_pull_blocked_materialization
                        PhoneSyncBlock.READINESS -> Res.string.cloud_pull_blocked_readiness
                        PhoneSyncBlock.UPLOAD_REJECTED -> Res.string.cloud_sync_blocked_rejected
                        PhoneSyncBlock.UPLOAD_WAITING -> Res.string.cloud_sync_blocked_waiting
                        PhoneSyncBlock.SUSPENDED -> Res.string.cloud_sync_blocked_suspended
                    }
                Text(stringResource(reason), Modifier.testTag("cloud_sync_blocked"), color = MaterialTheme.colorScheme.error)
            }
            state.nextAttemptAtMs?.takeIf { !state.running }?.let {
                Text(stringResource(Res.string.cloud_sync_next_attempt, timestamp(it)), Modifier.testTag("cloud_sync_next_attempt"))
            }
            Button(
                port::syncNow,
                enabled = state.available && !state.running,
                modifier = Modifier.fillMaxWidth().testTag("cloud_sync_now"),
            ) { Text(stringResource(Res.string.cloud_sync_now)) }
        }
    }
}
