package eu.advantly.android

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Nur Ergebniskarten; getrennt vom Konto-Export, dessen Provider jede Freigabe einzeln prüft. */
class MatchImageProvider : FileProvider()

/**
 * Teilt die Ergebniskarte als PNG über den System-Chooser. Jede Freigabe bekommt ein eigenes zufälliges
 * Unterverzeichnis mit festem Dateinamen (Empfänger sehen „advantly-match.png“, keine ID). Karten älterer
 * Freigaben werden erst nach [MATCH_IMAGE_MAX_AGE_MS] gelöscht, damit ein noch offener Chooser oder Empfänger
 * (z. B. Kontaktauswahl) seine Datei nicht verliert, wenn inzwischen erneut geteilt wird.
 *
 * @throws IOException wenn das PNG nicht geschrieben werden kann oder die Activity nicht mehr im Vordergrund ist
 *   (Android blockiert den Chooser dann still; die UI zeigt so ihre Fehlermeldung statt eines Scheinerfolgs)
 */
internal suspend fun ComponentActivity.shareMatchImage(
    image: ImageBitmap,
    text: String,
) {
    val file =
        withContext(Dispatchers.IO) {
            val root = File(cacheDir, "match-images").apply { mkdirs() }
            val now = System.currentTimeMillis()
            root.listFiles()?.filter { now - it.lastModified() > MATCH_IMAGE_MAX_AGE_MS }?.forEach { it.deleteRecursively() }
            val dir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
            File(dir, MATCH_IMAGE_NAME).apply {
                outputStream().use { out ->
                    if (!image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG")
                }
            }
        }
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) throw IOException("Activity not started")
    // Wie FileProvider.getUriForFile (Wurzel „match-images“ aus match_image_paths.xml), aber ohne dessen
    // Pfadvergleich mit „/“, der auf Windows-Hosts (Robolectric) scheitert – wie AccountExportProvider.uriForFile.
    val uri =
        Uri
            .Builder()
            .scheme("content")
            .authority("$packageName.match.images")
            .appendPath("match-images")
            .appendPath(requireNotNull(file.parentFile).name)
            .appendPath(MATCH_IMAGE_NAME)
            .build()
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri(MATCH_IMAGE_NAME, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

internal const val MATCH_IMAGE_NAME = "advantly-match.png"

/** Eine Stunde reicht für jeden Teilvorgang; danach räumt die nächste Freigabe auf. */
internal const val MATCH_IMAGE_MAX_AGE_MS = 60 * 60 * 1000L
