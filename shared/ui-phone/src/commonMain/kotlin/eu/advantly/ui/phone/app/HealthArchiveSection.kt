package eu.advantly.ui.phone.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import eu.advantly.match.session.HealthArchive
import eu.advantly.match.session.HealthImportError
import eu.advantly.match.session.HealthImportResult
import eu.advantly.presentation.match.TelemetrySettingsPort
import eu.advantly.presentation.match.WallClock
import eu.advantly.ui.phone.common.AdvantlyButton
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.platform.DocumentFiles
import eu.advantly.ui.phone.platform.FileTooLargeException
import eu.advantly.ui.phone.platform.rememberDocumentFiles
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.action_cancel
import eu.advantly.ui.phone.resources.health_archive_working
import eu.advantly.ui.phone.resources.health_export
import eu.advantly.ui.phone.resources.health_export_confirm
import eu.advantly.ui.phone.resources.health_export_done
import eu.advantly.ui.phone.resources.health_export_empty
import eu.advantly.ui.phone.resources.health_export_failed
import eu.advantly.ui.phone.resources.health_export_text
import eu.advantly.ui.phone.resources.health_grant_confirm
import eu.advantly.ui.phone.resources.health_grant_text
import eu.advantly.ui.phone.resources.health_grant_title
import eu.advantly.ui.phone.resources.health_import
import eu.advantly.ui.phone.resources.health_import_done
import eu.advantly.ui.phone.resources.health_import_invalid
import eu.advantly.ui.phone.resources.health_import_newer
import eu.advantly.ui.phone.resources.health_import_no_consent
import eu.advantly.ui.phone.resources.health_import_read_failed
import eu.advantly.ui.phone.resources.health_import_too_large
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.CoroutineContext

/**
 * „Pulsverläufe exportieren“ und „Pulsverläufe importieren“ (Plan 0.5.1 §7b) unter „Puls & Schritte“: Gerätewechsel ohne
 * Cloud, die Datei legt der Nutzer selbst ab. Export erst nach dem Hinweis auf Gesundheitsdaten; Import nur mit Einwilligung
 * „Pulsverlauf aufzeichnen“ (sonst zuerst deren Dialog) und nicht bei „Gar nicht“ ([locked]). Ohne Dateiauswahl der
 * Plattform ([files] `null`, Desktop) entfällt der Abschnitt.
 *
 * Dateien bis 20 MB: Prüfen bzw. Bauen der Datei ([HealthArchive], rein) läuft auf [worker], nur der Datenbankteil über
 * den Port auf dem UI-Thread; währenddessen ein Fortschritt und beide Tasten gesperrt.
 */
@Composable
internal fun HealthArchiveSection(
    port: TelemetrySettingsPort,
    locked: Boolean,
    files: DocumentFiles? = rememberDocumentFiles(),
    clock: WallClock = WallClock.System,
    worker: CoroutineContext = Dispatchers.Default,
) {
    files ?: return
    val consent by port.healthConsent.collectAsState()
    var dialog by remember { mutableStateOf<ArchiveDialog?>(null) }
    var message by remember { mutableStateOf<Message?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun pickImport() {
        message = null
        files.open(HealthArchive.MAX_BYTES) { read ->
            val bytes = read?.getOrNull()
            if (bytes == null) {
                // abgebrochen: still; Lese-/Berechtigungsfehler sagt nichts über den Inhalt der Datei
                val tooLarge = read?.exceptionOrNull() is FileTooLargeException
                val error = if (tooLarge) HealthImportError.TOO_LARGE else HealthImportError.READ_FAILED
                if (read != null) message = importMessage(HealthImportResult(error = error))
                return@open
            }
            busy = true
            scope.launch {
                try {
                    val content = withContext(worker) { HealthArchive.decode(bytes) }
                    message = importMessage(port.importHealth(content))
                } finally {
                    busy = false
                }
            }
        }
    }

    fun export() {
        val series = port.exportHealthSeries()
        if (series.isEmpty()) {
            message = Message(Res.string.health_export_empty)
            return
        }
        message = null
        busy = true
        scope.launch {
            val content =
                try {
                    withContext(worker) { HealthArchive.encode(series, clock.nowMs()) }
                } finally {
                    busy = false
                }
            files.save(HealthArchive.fileName(clock.nowMs()), content) { saved ->
                message = saved?.let { Message(if (it) Res.string.health_export_done else Res.string.health_export_failed) }
            }
        }
    }

    AdvantlyButton(
        text = stringResource(Res.string.health_export),
        primary = false,
        onClick = { dialog = ArchiveDialog.EXPORT },
        modifier = Modifier.fillMaxWidth().testTag("health_export"),
        enabled = !busy,
    )
    AdvantlyButton(
        text = stringResource(Res.string.health_import),
        primary = false,
        onClick = { if (consent) pickImport() else dialog = ArchiveDialog.GRANT },
        modifier = Modifier.fillMaxWidth().testTag("health_import"),
        enabled = !locked && !busy,
    )
    if (busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("health_archive_busy"))
        Text(
            stringResource(Res.string.health_archive_working),
            style = AdvantlyTheme.typography.caption,
            color = AdvantlyTheme.colors.text2,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    message?.let {
        Text(
            stringResource(it.text, *it.args.toTypedArray()),
            style = AdvantlyTheme.typography.caption,
            color = AdvantlyTheme.colors.text2,
            modifier = Modifier.testTag("health_archive_message").semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    dialog?.let { kind ->
        ReportHealthDialog()
        // Gleicher Einwilligungstext wie der Schalter „Pulsverlauf aufzeichnen“.
        val (title, text, action) =
            when (kind) {
                ArchiveDialog.EXPORT -> Triple(Res.string.health_export, Res.string.health_export_text, Res.string.health_export_confirm)
                ArchiveDialog.GRANT -> Triple(Res.string.health_grant_title, Res.string.health_grant_text, Res.string.health_grant_confirm)
            }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialog = null
                        when (kind) {
                            ArchiveDialog.EXPORT -> {
                                export()
                            }

                            ArchiveDialog.GRANT -> {
                                port.setHealthConsent(true)
                                pickImport()
                            }
                        }
                    },
                    modifier = Modifier.testTag("health_archive_confirm"),
                ) { Text(stringResource(action), color = AdvantlyTheme.colors.brand) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

private enum class ArchiveDialog { EXPORT, GRANT }

private class Message(
    val text: StringResource,
    val args: List<Any> = emptyList(),
)

private fun importMessage(result: HealthImportResult): Message =
    when (result.error) {
        null -> Message(Res.string.health_import_done, listOf(result.imported, result.unknownMatch, result.skipped))
        HealthImportError.NO_CONSENT -> Message(Res.string.health_import_no_consent)
        HealthImportError.TOO_LARGE -> Message(Res.string.health_import_too_large)
        HealthImportError.INVALID -> Message(Res.string.health_import_invalid)
        HealthImportError.NEWER_VERSION -> Message(Res.string.health_import_newer)
        HealthImportError.READ_FAILED -> Message(Res.string.health_import_read_failed)
    }
