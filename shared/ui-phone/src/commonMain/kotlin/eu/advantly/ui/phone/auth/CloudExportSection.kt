package eu.advantly.ui.phone.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.advantly.presentation.auth.PhoneExportAction
import eu.advantly.presentation.auth.PhoneExportError
import eu.advantly.presentation.auth.PhoneExportPhase
import eu.advantly.presentation.auth.PhoneExportPort
import eu.advantly.presentation.auth.PhoneExportState
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.cloud_export_byte_length
import eu.advantly.ui.phone.resources.cloud_export_cancel
import eu.advantly.ui.phone.resources.cloud_export_cancel_hint
import eu.advantly.ui.phone.resources.cloud_export_checking
import eu.advantly.ui.phone.resources.cloud_export_closed
import eu.advantly.ui.phone.resources.cloud_export_copy_hint
import eu.advantly.ui.phone.resources.cloud_export_discard
import eu.advantly.ui.phone.resources.cloud_export_download
import eu.advantly.ui.phone.resources.cloud_export_downloading
import eu.advantly.ui.phone.resources.cloud_export_error_connection
import eu.advantly.ui.phone.resources.cloud_export_error_expired
import eu.advantly.ui.phone.resources.cloud_export_error_integrity
import eu.advantly.ui.phone.resources.cloud_export_error_invalid_response
import eu.advantly.ui.phone.resources.cloud_export_error_local_io
import eu.advantly.ui.phone.resources.cloud_export_error_not_found
import eu.advantly.ui.phone.resources.cloud_export_error_session_rejected
import eu.advantly.ui.phone.resources.cloud_export_error_timeout
import eu.advantly.ui.phone.resources.cloud_export_error_too_large
import eu.advantly.ui.phone.resources.cloud_export_expires_at
import eu.advantly.ui.phone.resources.cloud_export_exported_at
import eu.advantly.ui.phone.resources.cloud_export_failed
import eu.advantly.ui.phone.resources.cloud_export_idle
import eu.advantly.ui.phone.resources.cloud_export_local_file
import eu.advantly.ui.phone.resources.cloud_export_outcome_unknown
import eu.advantly.ui.phone.resources.cloud_export_pending
import eu.advantly.ui.phone.resources.cloud_export_ready
import eu.advantly.ui.phone.resources.cloud_export_refresh_status
import eu.advantly.ui.phone.resources.cloud_export_request
import eu.advantly.ui.phone.resources.cloud_export_requesting
import eu.advantly.ui.phone.resources.cloud_export_retry
import eu.advantly.ui.phone.resources.cloud_export_retry_after
import eu.advantly.ui.phone.resources.cloud_export_server_busy
import eu.advantly.ui.phone.resources.cloud_export_share
import eu.advantly.ui.phone.resources.cloud_export_share_hint
import eu.advantly.ui.phone.resources.cloud_export_share_unavailable
import eu.advantly.ui.phone.resources.cloud_export_timestamp
import eu.advantly.ui.phone.resources.cloud_export_title
import eu.advantly.ui.phone.resources.cloud_export_unavailable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** Isolierte Kontorechte-Sektion; der aktuelle Host-Callback prüft die Freigabe erneut. */
@Composable
fun CloudExportSection(
    port: PhoneExportPort,
    onShare: (() -> Unit)? = null,
) {
    val state by port.state.collectAsState()
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxWidth().testTag("cloud_export_section"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider()
            Text(
                stringResource(Res.string.cloud_export_title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(state.phase.message()), Modifier.fillMaxWidth().testTag("cloud_export_status"))
            state.error?.let {
                val message = stringResource(it.message())
                Text(
                    message,
                    Modifier.fillMaxWidth().testTag("cloud_export_error").semantics { error(message) },
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            state.exportedAtMs?.let { Text(stringResource(Res.string.cloud_export_exported_at, exportTimestamp(it))) }
            state.expiresAtMs?.let { Text(stringResource(Res.string.cloud_export_expires_at, exportTimestamp(it))) }
            state.byteLength?.let { Text(stringResource(Res.string.cloud_export_byte_length, it.toString())) }
            state.retryAtMs?.let {
                Text(stringResource(Res.string.cloud_export_retry_after, exportTimestamp(it)), Modifier.testTag("cloud_export_wait"))
            }
            if (state.phase == PhoneExportPhase.LOCAL_FILE) {
                Text(stringResource(Res.string.cloud_export_share_hint))
                Text(stringResource(Res.string.cloud_export_copy_hint))
                if (onShare == null) Text(stringResource(Res.string.cloud_export_share_unavailable))
            }
            val primary = state.primaryAction()
            if (primary != null) {
                Button(
                    onClick = {
                        // Auch ein vor der nächsten Rekombination eintreffender Klick liest den aktuellen Portzustand.
                        val current = port.state.value
                        if (current.canRun(primary) &&
                            (primary != PhoneExportAction.SHARE || current.phase == PhoneExportPhase.LOCAL_FILE)
                        ) {
                            when (primary) {
                                PhoneExportAction.REQUEST -> port.requestExport()
                                PhoneExportAction.RETRY -> port.retry()
                                PhoneExportAction.REFRESH_STATUS -> port.refreshStatus()
                                PhoneExportAction.DOWNLOAD -> port.download()
                                PhoneExportAction.SHARE -> onShare?.invoke()
                                else -> Unit
                            }
                        }
                    },
                    enabled = state.canRun(primary) && (primary != PhoneExportAction.SHARE || onShare != null),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cloud_export_primary"),
                ) { Text(stringResource(primary.label())) }
            }
            if (state.phase == PhoneExportPhase.OUTCOME_UNKNOWN && PhoneExportAction.REFRESH_STATUS in state.allowedActions) {
                TextButton(
                    onClick = { if (port.state.value.canRun(PhoneExportAction.REFRESH_STATUS)) port.refreshStatus() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cloud_export_check"),
                ) { Text(stringResource(Res.string.cloud_export_refresh_status)) }
            }
            for (action in listOf(PhoneExportAction.CANCEL, PhoneExportAction.DISCARD)) {
                if (action in state.allowedActions && state.phase != PhoneExportPhase.CLOSED &&
                    state.phase != PhoneExportPhase.UNAVAILABLE
                ) {
                    if (action == PhoneExportAction.CANCEL) Text(stringResource(Res.string.cloud_export_cancel_hint))
                    TextButton(
                        onClick = {
                            val current = port.state.value
                            if (action in current.allowedActions && current.phase != PhoneExportPhase.CLOSED &&
                                current.phase != PhoneExportPhase.UNAVAILABLE
                            ) {
                                if (action == PhoneExportAction.CANCEL) port.cancel() else port.discard()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cloud_export_${action.name.lowercase()}"),
                    ) { Text(stringResource(action.label())) }
                }
            }
        }
    }
}

private fun PhoneExportState.canRun(action: PhoneExportAction): Boolean =
    !busy && phase != PhoneExportPhase.UNAVAILABLE && phase != PhoneExportPhase.CLOSED && action in allowedActions

private fun PhoneExportState.primaryAction(): PhoneExportAction? =
    when (phase) {
        PhoneExportPhase.UNAVAILABLE, PhoneExportPhase.CLOSED -> {
            null
        }

        PhoneExportPhase.IDLE, PhoneExportPhase.REQUESTING -> {
            PhoneExportAction.REQUEST
        }

        PhoneExportPhase.OUTCOME_UNKNOWN, PhoneExportPhase.SERVER_BUSY -> {
            PhoneExportAction.RETRY
        }

        PhoneExportPhase.PENDING, PhoneExportPhase.CHECKING -> {
            PhoneExportAction.REFRESH_STATUS
        }

        PhoneExportPhase.READY, PhoneExportPhase.DOWNLOADING -> {
            PhoneExportAction.DOWNLOAD
        }

        PhoneExportPhase.LOCAL_FILE -> {
            PhoneExportAction.SHARE
        }

        PhoneExportPhase.FAILED -> {
            listOf(PhoneExportAction.RETRY, PhoneExportAction.DOWNLOAD, PhoneExportAction.REFRESH_STATUS, PhoneExportAction.REQUEST)
                .firstOrNull { it in allowedActions }
        }
    }

private fun PhoneExportPhase.message(): StringResource =
    when (this) {
        PhoneExportPhase.UNAVAILABLE -> Res.string.cloud_export_unavailable
        PhoneExportPhase.IDLE -> Res.string.cloud_export_idle
        PhoneExportPhase.REQUESTING -> Res.string.cloud_export_requesting
        PhoneExportPhase.OUTCOME_UNKNOWN -> Res.string.cloud_export_outcome_unknown
        PhoneExportPhase.PENDING -> Res.string.cloud_export_pending
        PhoneExportPhase.SERVER_BUSY -> Res.string.cloud_export_server_busy
        PhoneExportPhase.CHECKING -> Res.string.cloud_export_checking
        PhoneExportPhase.READY -> Res.string.cloud_export_ready
        PhoneExportPhase.DOWNLOADING -> Res.string.cloud_export_downloading
        PhoneExportPhase.LOCAL_FILE -> Res.string.cloud_export_local_file
        PhoneExportPhase.FAILED -> Res.string.cloud_export_failed
        PhoneExportPhase.CLOSED -> Res.string.cloud_export_closed
    }

private fun PhoneExportError.message(): StringResource =
    when (this) {
        PhoneExportError.CONNECTION -> Res.string.cloud_export_error_connection
        PhoneExportError.SESSION_REJECTED -> Res.string.cloud_export_error_session_rejected
        PhoneExportError.NOT_FOUND -> Res.string.cloud_export_error_not_found
        PhoneExportError.EXPIRED -> Res.string.cloud_export_error_expired
        PhoneExportError.TOO_LARGE -> Res.string.cloud_export_error_too_large
        PhoneExportError.INVALID_RESPONSE -> Res.string.cloud_export_error_invalid_response
        PhoneExportError.INTEGRITY -> Res.string.cloud_export_error_integrity
        PhoneExportError.LOCAL_IO -> Res.string.cloud_export_error_local_io
        PhoneExportError.TIMEOUT -> Res.string.cloud_export_error_timeout
    }

private fun PhoneExportAction.label(): StringResource =
    when (this) {
        PhoneExportAction.REQUEST -> Res.string.cloud_export_request
        PhoneExportAction.RETRY -> Res.string.cloud_export_retry
        PhoneExportAction.REFRESH_STATUS -> Res.string.cloud_export_refresh_status
        PhoneExportAction.DOWNLOAD -> Res.string.cloud_export_download
        PhoneExportAction.CANCEL -> Res.string.cloud_export_cancel
        PhoneExportAction.DISCARD -> Res.string.cloud_export_discard
        PhoneExportAction.SHARE -> Res.string.cloud_export_share
    }

@Composable
private fun exportTimestamp(milliseconds: Long): String {
    val instant = Instant.fromEpochMilliseconds(milliseconds).toString()
    return stringResource(
        Res.string.cloud_export_timestamp,
        instant.substringBefore('T'),
        instant.substringAfter('T').substringBefore('.').removeSuffix("Z"),
    )
}
