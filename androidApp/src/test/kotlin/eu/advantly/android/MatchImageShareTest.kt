package eu.advantly.android

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
// Gleiche Shadow-Liste wie die übrigen Sandbox-Tests: Robolectric teilt die Sandbox, ein abweichender ShadowLinux bräche sie.
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class MatchImageShareTest {
    private fun card() = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).asImageBitmap()

    @Test
    fun sharesPngWithLinkTextOverChooserAndKeepsEarlierCardsOfTheLastHour() =
        runBlocking {
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            val uris =
                List(2) {
                    activity.shareMatchImage(card(), "Mein Match: https://advantly.eu")
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
                    // Anzeigename beim Empfänger: fester Name ohne ID
                    assertEquals("advantly-match.png", uri.lastPathSegment)
                    assertEquals("advantly-match.png", send.clipData!!.description.label)
                    uri
                }
            assertNotEquals(uris[0], uris[1])
            // Die erste Karte ist jünger als eine Stunde und bleibt für einen noch offenen Empfänger lesbar.
            val files = uris.map { File(activity.cacheDir, "match-images/${it.pathSegments[1]}/advantly-match.png") }
            assertTrue(files.all { it.length() > 0 })
            // Rückweg über den FileProvider (Manifest + match_image_paths.xml). FileProvider prüft Pfade mit „/“ und
            // scheitert daher nur auf Windows-Hosts; CI (Linux) führt diesen Teil aus.
            if (File.separatorChar == '/') {
                for ((uri, file) in uris.zip(files)) {
                    val bytes = activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    assertEquals(file.length(), bytes.size.toLong())
                }
            }
        }

    @Test
    fun deletesOnlyCardsOlderThanAnHour() =
        runBlocking {
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            val root = File(activity.cacheDir, "match-images")
            val now = System.currentTimeMillis()
            val old = File(root, "old").apply { mkdirs() }
            File(old, "advantly-match.png").writeText("x")
            old.setLastModified(now - MATCH_IMAGE_MAX_AGE_MS - 60_000)
            val young = File(root, "young").apply { mkdirs() }
            File(young, "advantly-match.png").writeText("x")
            young.setLastModified(now - MATCH_IMAGE_MAX_AGE_MS / 2)

            activity.shareMatchImage(card(), "t")

            val left = root.list()!!.toSet()
            assertFalse("old" in left)
            assertTrue("young" in left)
            assertEquals(2, left.size)
        }

    @Test
    fun failsInsteadOfSilentlyDroppingTheChooserInTheBackground() =
        runBlocking {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = controller.get()
            controller.pause().stop()
            val error = runCatching { activity.shareMatchImage(card(), "t") }.exceptionOrNull()
            assertTrue(error is IOException)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
}
