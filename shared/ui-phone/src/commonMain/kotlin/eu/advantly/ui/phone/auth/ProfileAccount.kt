package eu.advantly.ui.phone.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.presentation.auth.AuthState
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.GoogleCredential
import eu.advantly.presentation.auth.PhoneSyncBlock
import eu.advantly.presentation.auth.PhoneSyncState
import eu.advantly.presentation.auth.PhoneSyncStatus
import eu.advantly.presentation.friends.FriendsAccess
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.friends.formatFriendCode
import eu.advantly.ui.phone.platform.formatBuildDate
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.account_cancel
import eu.advantly.ui.phone.resources.account_close
import eu.advantly.ui.phone.resources.account_delete
import eu.advantly.ui.phone.resources.account_delete_explanation
import eu.advantly.ui.phone.resources.account_export
import eu.advantly.ui.phone.resources.account_suspended
import eu.advantly.ui.phone.resources.auth_link_google
import eu.advantly.ui.phone.resources.cloud_pull_blocked_materialization
import eu.advantly.ui.phone.resources.cloud_pull_blocked_readiness
import eu.advantly.ui.phone.resources.cloud_pull_blocked_redactions
import eu.advantly.ui.phone.resources.cloud_pull_blocked_removals
import eu.advantly.ui.phone.resources.cloud_sync_backed_up
import eu.advantly.ui.phone.resources.cloud_sync_blocked_permission
import eu.advantly.ui.phone.resources.cloud_sync_blocked_rejected
import eu.advantly.ui.phone.resources.cloud_sync_blocked_suspended
import eu.advantly.ui.phone.resources.cloud_sync_blocked_waiting
import eu.advantly.ui.phone.resources.cloud_sync_confirm_terms
import eu.advantly.ui.phone.resources.cloud_sync_connection_failed
import eu.advantly.ui.phone.resources.cloud_sync_consent
import eu.advantly.ui.phone.resources.cloud_sync_documents_unavailable
import eu.advantly.ui.phone.resources.cloud_sync_failed
import eu.advantly.ui.phone.resources.cloud_sync_last_backup
import eu.advantly.ui.phone.resources.cloud_sync_retry
import eu.advantly.ui.phone.resources.cloud_sync_running
import eu.advantly.ui.phone.resources.cloud_sync_unavailable
import eu.advantly.ui.phone.resources.friends_code_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Profilkopf: Alias, Benutzername und Freundescode (lädt mit dem Freundescode, nur bei nutzbarem Konto). */
@Composable
fun AccountHeader(friends: FriendsViewModel) {
    val state by friends.state.collectAsState()
    // Erst wenn nichts anderes läuft: ein belegtes ViewModel würde den Aufruf sonst still verwerfen.
    LaunchedEffect(friends, state.access, state.busy) {
        if (state.access == FriendsAccess.READY && !state.busy && state.friendCode == null && state.error == null) friends.loadCode()
    }
    val colors = AdvantlyTheme.colors
    Column(Modifier.fillMaxWidth().testTag("profile_header"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        state.me?.alias?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = AdvantlyTheme.typography.title, color = colors.text, modifier = Modifier.testTag("profile_alias"))
        }
        state.me?.username?.let {
            Text("@$it", style = AdvantlyTheme.typography.body, color = colors.text2, modifier = Modifier.testTag("profile_username"))
        }
        state.friendCode?.let { code ->
            Text(stringResource(Res.string.friends_code_title), style = AdvantlyTheme.typography.caption, color = colors.text2)
            SelectionContainer {
                Text(
                    formatFriendCode(code),
                    style = AdvantlyTheme.typography.label,
                    color = colors.text,
                    modifier = Modifier.testTag("profile_friend_code"),
                )
            }
        }
    }
}

/** Was der eine Knopf der Statuszeile tut. */
internal enum class BackupAction { RETRY, CONFIRM_TERMS }

/**
 * Eine Statuszeile zur Sicherung (0.3.0): gesichert, wird gesichert oder ein Problem mit genau einem Knopf. Die Sicherung
 * läuft automatisch ([eu.advantly.presentation.auth.PhoneSyncPort]); manuelle Upload-/Download-Knöpfe gibt es nicht.
 */
internal fun backupStatus(
    auth: AuthState,
    sync: PhoneSyncState?,
): Pair<StringResource, BackupAction?> {
    val blocked = sync?.blocked
    return when {
        // Kein freigegebener Katalog (Betreiberfehler): nur die Sicherung pausiert, die App bleibt nutzbar.
        auth.consent?.status == ConsentStatus.UNAVAILABLE -> {
            Res.string.cloud_sync_documents_unavailable to BackupAction.RETRY
        }

        auth.consent != null && auth.consent?.status != ConsentStatus.CURRENT -> {
            Res.string.cloud_sync_consent to BackupAction.CONFIRM_TERMS
        }

        sync?.running == true || sync?.status == PhoneSyncStatus.PENDING -> {
            Res.string.cloud_sync_running to null
        }

        blocked != null -> {
            blockText(blocked) to BackupAction.RETRY
        }

        auth.unconfirmed || sync?.status == PhoneSyncStatus.CONNECTION_FAILED -> {
            Res.string.cloud_sync_connection_failed to BackupAction.RETRY
        }

        sync?.status == PhoneSyncStatus.FAILED -> {
            Res.string.cloud_sync_failed to BackupAction.RETRY
        }

        sync?.status == PhoneSyncStatus.BACKED_UP -> {
            Res.string.cloud_sync_backed_up to null
        }

        // Verfügbar, aber in diesem Prozess noch kein Lauf: der Koordinator startet ihn selbst (App-Start/Vordergrund).
        sync?.available == true -> {
            Res.string.cloud_sync_running to null
        }

        else -> {
            Res.string.cloud_sync_unavailable to BackupAction.RETRY
        }
    }
}

private fun blockText(block: PhoneSyncBlock): StringResource =
    when (block) {
        PhoneSyncBlock.PERMISSION -> Res.string.cloud_sync_blocked_permission
        PhoneSyncBlock.REMOVALS -> Res.string.cloud_pull_blocked_removals
        PhoneSyncBlock.REDACTIONS -> Res.string.cloud_pull_blocked_redactions
        PhoneSyncBlock.MATERIALIZATION -> Res.string.cloud_pull_blocked_materialization
        PhoneSyncBlock.READINESS -> Res.string.cloud_pull_blocked_readiness
        PhoneSyncBlock.UPLOAD_REJECTED -> Res.string.cloud_sync_blocked_rejected
        PhoneSyncBlock.UPLOAD_WAITING -> Res.string.cloud_sync_blocked_waiting
        PhoneSyncBlock.SUSPENDED -> Res.string.cloud_sync_blocked_suspended
    }

@Composable
fun BackupStatusLine(model: AuthViewModel) {
    val auth by model.state.collectAsState()
    val sync =
        model.sync
            ?.state
            ?.collectAsState()
            ?.value
    val (text, action) = backupStatus(auth, sync)
    val colors = AdvantlyTheme.colors
    val lastBackup = sync?.lastBackupAtMs
    Column(Modifier.fillMaxWidth().testTag("backup_status"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            if (text == Res.string.cloud_sync_backed_up && lastBackup != null) {
                stringResource(Res.string.cloud_sync_last_backup, timestamp(lastBackup))
            } else {
                stringResource(text)
            },
            style = AdvantlyTheme.typography.body,
            color = if (action == null) colors.text else colors.danger,
            modifier = Modifier.testTag("backup_status_text"),
        )
        action?.let {
            TextButton(
                {
                    // Ohne Cloud-Kontext erst das Konto prüfen; danach startet der Koordinator selbst.
                    val port = model.sync
                    if (it == BackupAction.RETRY && port != null && sync?.available == true) port.syncNow() else model.refresh()
                },
                enabled = !auth.busy,
                modifier = Modifier.testTag("backup_action"),
            ) {
                Text(stringResource(if (it == BackupAction.RETRY) Res.string.cloud_sync_retry else Res.string.cloud_sync_confirm_terms))
            }
        }
    }
}

/** Kontorechte unter „Konto & Datenschutz“: Export, Google verknüpfen, Löschantrag (Rücknahme im Konto-Fluss). */
@Composable
fun AccountRights(
    model: AuthViewModel,
    google: (suspend () -> GoogleCredential)?,
    onShareExport: (() -> Unit)?,
) {
    val state by model.state.collectAsState()
    var confirmDeletion by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxWidth().testTag("account_rights"), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (state.suspended) {
                Text(
                    stringResource(Res.string.account_suspended),
                    Modifier.testTag("account_suspended"),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val export = model.export
            if (export != null) {
                CloudExportSection(export, onShareExport)
            } else {
                TextButton(model::exportData, enabled = !state.busy, modifier = Modifier.testTag("account_export")) {
                    Text(stringResource(Res.string.account_export))
                }
            }
            google?.let {
                TextButton({ model.google(it, link = true) }, enabled = !state.busy, modifier = Modifier.testTag("auth_link_google")) {
                    Text(stringResource(Res.string.auth_link_google))
                }
            }
            // Rücknahme eines Löschantrags zeigt der Konto-Fluss ([AccountScreen]).
            if (!state.deletionRequested) {
                TextButton({ confirmDeletion = true }, enabled = !state.busy, modifier = Modifier.testTag("account_delete")) {
                    Text(stringResource(Res.string.account_delete))
                }
            }
            state.error?.let { error -> Text(stringResource(authError(error)), modifier = Modifier.testTag("auth_error")) }
        }
    }
    if (confirmDeletion && state.authenticated) {
        AlertDialog(
            onDismissRequest = { confirmDeletion = false },
            title = { Text(stringResource(Res.string.account_delete)) },
            text = { Text(stringResource(Res.string.account_delete_explanation)) },
            confirmButton = {
                TextButton({
                    confirmDeletion = false
                    model.requestDeletion()
                }, modifier = Modifier.testTag("account_delete_confirm")) {
                    Text(stringResource(Res.string.account_delete))
                }
            },
            dismissButton = { TextButton({ confirmDeletion = false }) { Text(stringResource(Res.string.account_cancel)) } },
        )
    }
    if (model.export == null) {
        state.exportedData?.let { data ->
            AlertDialog(
                onDismissRequest = model::dismissExport,
                title = { Text(stringResource(Res.string.account_export)) },
                text = { SelectionContainer { Text(data, Modifier.verticalScroll(rememberScrollState()).testTag("account_export_data")) } },
                confirmButton = { TextButton(model::dismissExport) { Text(stringResource(Res.string.account_close)) } },
            )
        }
    }
}

/** Lokale Uhrzeit mit Datum in der Gerätesprache (DE/EN) statt ISO-Instant. */
internal fun timestamp(milliseconds: Long): String = formatBuildDate(milliseconds)
