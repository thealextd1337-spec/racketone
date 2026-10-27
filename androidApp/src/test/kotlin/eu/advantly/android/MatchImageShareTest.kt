package eu.advantly.android

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
// Gleiche Shadow-Liste wie die übrigen Sandbox-Tests: Robolectric teilt die Sandbox, ein abweichender ShadowLinux bräche sie.
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class MatchImageShareTest {
    @Test
    fun sharesPngWithLinkTextOverChooserAndKeepsOnlyTheLatestCard() =
        runBlocking {
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            val uris =
                List(2) {
                    activity.shareMatchImage(
                        Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).asImageBitmap(),
                        "Mein Match: https://advantly.eu",
                    )
                    val chooser = shadowOf(activity).nextStartedActivity
                    assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                    assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    @Suppress("DEPRECATION")
                    val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                    assertEquals(Intent.ACTION_SEND, send.action)
                    assertEquals("image/png", send.type)
                    assertEquals("Mein Match: https://advantly.eu", send.getStringExtra(Intent.EXTRA_TEXT))
                    assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    @Suppress("DEPRECATION")
                    val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                    assertEquals("content", uri.scheme)
                    assertEquals("${activity.packageName}.match.images", uri.authority)
                    assertEquals(uri, send.clipData!!.getItemAt(0).uri)
                    uri
                }
            assertNotEquals(uris[0], uris[1])
            val files = File(activity.cacheDir, "match-images").listFiles()!!.toList()
            assertEquals(listOf(uris[1].lastPathSegment), files.map { it.name })
            assertTrue(files.single().length() > 0)
            // Rückweg über den FileProvider (Manifest + match_image_paths.xml). FileProvider prüft Pfade mit „/“ und
            // scheitert daher nur auf Windows-Hosts; CI (Linux) führt diesen Teil aus.
            if (File.separatorChar == '/') {
                val bytes = activity.contentResolver.openInputStream(uris[1])!!.use { it.readBytes() }
                assertEquals(files.single().length(), bytes.size.toLong())
            }
        }
}
