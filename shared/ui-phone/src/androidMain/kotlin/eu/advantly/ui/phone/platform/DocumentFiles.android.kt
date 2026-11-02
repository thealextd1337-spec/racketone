package eu.advantly.ui.phone.platform

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** MIME-Typ der Exportdatei; beim Öffnen auch falsch typisierte Ablagen (Cloud-Speicher, Downloads) zulassen. */
private const val JSON_MIME = "application/json"
private val OPEN_MIME_TYPES = arrayOf(JSON_MIME, "application/octet-stream", "text/plain")

/**
 * Storage Access Framework. shortcut: Wird die Activity während der Auswahl neu erzeugt (Drehen), geht der offene Auftrag
 * verloren – eine schon angelegte Datei bleibt leer, der Nutzer startet neu; erst bei Bedarf über SavedState lösen.
 */
@Composable
actual fun rememberDocumentFiles(): DocumentFiles? {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val pending = remember { Pending() }
    val create =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON_MIME)) { uri ->
            val job = pending.save ?: return@rememberLauncherForActivityResult
            pending.save = null
            if (uri == null) {
                job.second(null)
            } else {
                scope.launch { job.second(withContext(Dispatchers.IO) { write(context, uri, job.first) }) }
            }
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val job = pending.open ?: return@rememberLauncherForActivityResult
            pending.open = null
            if (uri == null) {
                job.second(null)
            } else {
                scope.launch { job.second(withContext(Dispatchers.IO) { read(context, uri, job.first) }) }
            }
        }
    return remember(create, open) {
        object : DocumentFiles {
            override fun save(
                fileName: String,
                content: String,
                onDone: (Boolean?) -> Unit,
            ) {
                pending.save = content to onDone
                create.launch(fileName)
            }

            override fun open(
                maxBytes: Int,
                onDone: (Result<ByteArray>?) -> Unit,
            ) {
                pending.open = maxBytes to onDone
                open.launch(OPEN_MIME_TYPES)
            }
        }
    }
}

private class Pending {
    var save: Pair<String, (Boolean?) -> Unit>? = null
    var open: Pair<Int, (Result<ByteArray>?) -> Unit>? = null
}

/** "wt" ersetzt eine vorhandene Datei gleichen Namens; manche Anbieter (Cloud-Speicher) kennen "wt" nicht, dann "w". */
private fun write(
    context: Context,
    uri: Uri,
    content: String,
): Boolean = writeMode(context, uri, content, "wt") || writeMode(context, uri, content, "w")

private fun writeMode(
    context: Context,
    uri: Uri,
    content: String,
    mode: String,
): Boolean =
    try {
        context.contentResolver.openOutputStream(uri, mode)?.use { it.write(content.encodeToByteArray()) } != null
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

private fun read(
    context: Context,
    uri: Uri,
    maxBytes: Int,
): Result<ByteArray> =
    try {
        val bytes =
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (out.size() <= maxBytes) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        bytes?.let { Result.success(it) } ?: Result.failure(IOException("kein Datenstrom"))
    } catch (e: IOException) {
        Result.failure(e)
    } catch (e: SecurityException) {
        Result.failure(e)
    }
