package eu.advantly.ui.phone.platform

import androidx.compose.runtime.Composable

/**
 * Datei speichern bzw. öffnen über die Dateiauswahl des Systems, ohne Umweg über einen Server (Plan 0.5.1 §7b):
 * Android Storage Access Framework (`ACTION_CREATE_DOCUMENT`/`ACTION_OPEN_DOCUMENT`), iOS `UIDocumentPickerViewController`.
 * Callbacks kommen auf dem UI-Thread.
 */
interface DocumentFiles {
    /** Speichert [content] (UTF-8) unter dem Vorschlag [fileName]; [onDone]: `true` gespeichert, `false` Fehler, `null` abgebrochen. */
    fun save(
        fileName: String,
        content: String,
        onDone: (Boolean?) -> Unit,
    )

    /**
     * Liest eine gewählte Datei, höchstens [maxBytes] + 1 Byte (so erkennt der Aufrufer „zu groß“, ohne alles zu laden).
     * [onDone]: Inhalt, leer bei Lesefehler, `null` abgebrochen.
     */
    fun open(
        maxBytes: Int,
        onDone: (ByteArray?) -> Unit,
    )
}

/** `null`, wo es keine Dateiauswahl gibt (Desktop-Preview). */
@Composable
expect fun rememberDocumentFiles(): DocumentFiles?
