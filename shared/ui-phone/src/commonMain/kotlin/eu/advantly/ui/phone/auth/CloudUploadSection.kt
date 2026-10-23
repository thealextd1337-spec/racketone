package eu.advantly.ui.phone.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import eu.advantly.presentation.auth.PhoneUploadAction
import eu.advantly.presentation.auth.PhoneUploadError
import eu.advantly.presentation.auth.PhoneUploadPhase
import eu.advantly.presentation.auth.PhoneUploadPort
import eu.advantly.presentation.auth.PhoneUploadResult
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.cloud_upload_binding
import eu.advantly.ui.phone.resources.cloud_upload_blocked
import eu.advantly.ui.phone.resources.cloud_upload_cancelled
import eu.advantly.ui.phone.resources.cloud_upload_connection
import eu.advantly.ui.phone.resources.cloud_upload_drained
import eu.advantly.ui.phone.resources.cloud_upload_events
import eu.advantly.ui.phone.resources.cloud_upload_failed
import eu.advantly.ui.phone.resources.cloud_upload_last_response
import eu.advantly.ui.phone.resources.cloud_upload_limit
import eu.advantly.ui.phone.resources.cloud_upload_local
import eu.advantly.ui.phone.resources.cloud_upload_metadata
import eu.advantly.ui.phone.resources.cloud_upload_now
import eu.advantly.ui.phone.resources.cloud_upload_observed
import eu.advantly.ui.phone.resources.cloud_upload_partial
import eu.advantly.ui.phone.resources.cloud_upload_permission
import eu.advantly.ui.phone.resources.cloud_upload_refresh
import eu.advantly.ui.phone.resources.cloud_upload_rejected
import eu.advantly.ui.phone.resources.cloud_upload_response
import eu.advantly.ui.phone.resources.cloud_upload_retry
import eu.advantly.ui.phone.resources.cloud_upload_running
import eu.advantly.ui.phone.resources.cloud_upload_snapshot
import eu.advantly.ui.phone.resources.cloud_upload_stale
import eu.advantly.ui.phone.resources.cloud_upload_title
import eu.advantly.ui.phone.resources.cloud_upload_unavailable
import eu.advantly.ui.phone.resources.cloud_upload_unknown
import eu.advantly.ui.phone.resources.cloud_upload_waiting
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** Eigene Uploadaktionen blockieren keine Kontoaktion. Sämtliche Zahlen bleiben datierte Momentaufnahmen. */
@Composable
fun CloudUploadSection(
    port: PhoneUploadPort,
    /** Ohne eigenen Upload-Knopf, wenn „Jetzt synchronisieren“ Download und Upload in fester Reihenfolge startet. */
    manual: Boolean = true,
) {
    val state by port.state.collectAsState()
    val observedAt = state.observedAtMs
    val events = state.pendingEvents
    val metadata = state.pendingMetadata
    val rejected = state.rejectedEvents
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxWidth().testTag("cloud_upload_section"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider()
            Text(stringResource(Res.string.cloud_upload_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.cloud_upload_snapshot))
            if (!state.available) Text(stringResource(Res.string.cloud_upload_unavailable))
            if (observedAt == null || events == null || metadata == null || rejected == null) {
                Text(stringResource(Res.string.cloud_upload_unknown), Modifier.testTag("cloud_upload_unknown"))
            } else {
                Text(stringResource(Res.string.cloud_upload_events, events), Modifier.testTag("cloud_upload_events"))
                Text(stringResource(Res.string.cloud_upload_metadata, metadata), Modifier.testTag("cloud_upload_metadata"))
                Text(stringResource(Res.string.cloud_upload_rejected, rejected), Modifier.testTag("cloud_upload_rejected"))
                Text(
                    stringResource(Res.string.cloud_upload_observed, timestamp(observedAt)),
                    Modifier.testTag("cloud_upload_observed"),
                )
            }
            if (state.stale) Text(stringResource(Res.string.cloud_upload_stale), Modifier.testTag("cloud_upload_stale"))
            state.lastResponseAtMs?.let {
                Text(stringResource(Res.string.cloud_upload_last_response, timestamp(it)), Modifier.testTag("cloud_upload_last_response"))
            }
            val outcome =
                when {
                    state.phase == PhoneUploadPhase.WAITING -> {
                        Res.string.cloud_upload_waiting
                    }

                    state.phase == PhoneUploadPhase.BINDING -> {
                        Res.string.cloud_upload_binding
                    }

                    state.phase == PhoneUploadPhase.UPLOADING -> {
                        Res.string.cloud_upload_running
                    }

                    else -> {
                        when (state.result) {
                            PhoneUploadResult.DRAINED -> Res.string.cloud_upload_drained
                            PhoneUploadResult.PARTIAL -> Res.string.cloud_upload_partial
                            PhoneUploadResult.LIMIT_REACHED -> Res.string.cloud_upload_limit
                            PhoneUploadResult.BLOCKED -> Res.string.cloud_upload_blocked
                            PhoneUploadResult.FAILED -> Res.string.cloud_upload_failed
                            PhoneUploadResult.CANCELLED -> Res.string.cloud_upload_cancelled
                            null -> null
                        }
                    }
                }
            outcome?.let { Text(stringResource(it), Modifier.testTag("cloud_upload_result")) }
            state.error?.let {
                val message =
                    when (it) {
                        PhoneUploadError.CONNECTION -> Res.string.cloud_upload_connection
                        PhoneUploadError.PERMISSION -> Res.string.cloud_upload_permission
                        PhoneUploadError.RESPONSE -> Res.string.cloud_upload_response
                        PhoneUploadError.LOCAL -> Res.string.cloud_upload_local
                    }
                Text(stringResource(message), Modifier.testTag("cloud_upload_error"), color = MaterialTheme.colorScheme.error)
            }
            if (manual) {
                Button(
                    port::uploadNow,
                    enabled = state.available && !state.busy && state.action != PhoneUploadAction.NONE,
                    modifier = Modifier.fillMaxWidth().testTag("cloud_upload_now"),
                ) {
                    Text(
                        stringResource(
                            if (state.action ==
                                PhoneUploadAction.RETRY
                            ) {
                                Res.string.cloud_upload_retry
                            } else {
                                Res.string.cloud_upload_now
                            },
                        ),
                    )
                }
            }
            TextButton(
                port::refreshStatus,
                enabled = state.available && state.canRefreshStatus && !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("cloud_upload_refresh"),
            ) {
                Text(stringResource(Res.string.cloud_upload_refresh))
            }
        }
    }
}

internal fun timestamp(milliseconds: Long): String = Instant.fromEpochMilliseconds(milliseconds).toString()
