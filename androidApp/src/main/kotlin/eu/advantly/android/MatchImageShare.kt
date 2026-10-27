package eu.advantly.android

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Nur Ergebniskarten; getrennt vom Konto-Export, dessen Provider jede Freigabe einzeln prüft. */
class MatchImageProvider : FileProvider()

/**
 * Teilt die Ergebniskarte als PNG über den System-Chooser. Jede Freigabe bekommt eine neue Datei; ältere Karten
 * werden vorher gelöscht, damit ein früherer Empfänger mit seiner URI nicht das nächste Bild lesen kann.
 */
internal suspend fun ComponentActivity.shareMatchImage(
    image: ImageBitmap,
    text: String,
) {
    val file =
        withContext(Dispatchers.IO) {
            val dir = File(cacheDir, "match-images").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            File(dir, "advantly-match-${UUID.randomUUID()}.png").apply {
                outputStream().use { out ->
                    if (!image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG")
                }
            }
        }
    // Wie FileProvider.getUriForFile (Wurzel „match-images“ aus match_image_paths.xml), aber ohne dessen
    // Pfadvergleich mit „/“, der auf Windows-Hosts (Robolectric) scheitert – wie AccountExportProvider.uriForFile.
    val uri =
        Uri
            .Builder()
            .scheme("content")
            .authority("$packageName.match.images")
            .appendPath("match-images")
            .appendPath(file.name)
            .build()
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
