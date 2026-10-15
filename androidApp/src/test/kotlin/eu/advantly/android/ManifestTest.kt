package eu.advantly.android

import eu.advantly.device.link.WearDataLayerTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Manifest der Phone-App. Hintergrund-Empfang (W1-I): Application startet die Session, der WearableListenerService ist registriert und seine
 * Pfadfilter passen zu den Pfaden des Transports (Live-Nachrichten und DataItem-Queue).
 */
class ManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val manifest =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))

    private fun elements(name: String): List<Element> =
        manifest.getElementsByTagName(name).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    /** Feldtest Pixel 9 Pro XL: ohne adjustResize verschiebt Android das Fenster für die Tastatur unter die Statusleiste. */
    @Test
    fun mainActivityResizesForTheKeyboard() {
        val main = elements("activity").single { it.getAttributeNS(android, "name") == ".MainActivity" }
        assertEquals("adjustResize", main.getAttributeNS(android, "windowSoftInputMode"))
    }

    @Test
    fun applicationStartsTheSession() {
        assertEquals(".AdvantlyPhoneApplication", elements("application").single().getAttributeNS(android, "name"))
    }

    @Test
    fun listenerServiceCoversMessageAndQueuePaths() {
        val service = elements("service").single { it.getAttributeNS(android, "name") == ".sync.PhoneSyncListenerService" }
        assertEquals("true", service.getAttributeNS(android, "exported"))
        val filters = service.getElementsByTagName("intent-filter").let { n -> (0 until n.length).map { n.item(it) as Element } }

        fun prefixFor(action: String): String {
            val filter =
                filters.single { f ->
                    (f.getElementsByTagName("action").item(0) as Element).getAttributeNS(android, "name") == action
                }
            return (filter.getElementsByTagName("data").item(0) as Element).getAttributeNS(android, "pathPrefix")
        }
        assertTrue(WearDataLayerTransport.MESSAGE_PATH.startsWith(prefixFor("com.google.android.gms.wearable.MESSAGE_RECEIVED")))
        assertTrue(WearDataLayerTransport.QUEUE_PREFIX.startsWith(prefixFor("com.google.android.gms.wearable.DATA_CHANGED")))
    }
}
