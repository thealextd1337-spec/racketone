package eu.advantly.android

import eu.advantly.device.link.WearDataLayerTransport
import eu.advantly.sync.protocol.LiveTelemetry
import eu.advantly.sync.protocol.LiveTelemetrySubscription
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

        fun pathsFor(action: String): List<Element> =
            filters
                .filter { f ->
                    (f.getElementsByTagName("action").item(0) as Element).getAttributeNS(android, "name") == action
                }.map { it.getElementsByTagName("data").item(0) as Element }

        val messages = pathsFor("com.google.android.gms.wearable.MESSAGE_RECEIVED")
        val dataItems = pathsFor("com.google.android.gms.wearable.DATA_CHANGED")
        assertTrue(
            messages.any {
                it.getAttributeNS(android, "pathPrefix").let { prefix ->
                    prefix.isNotEmpty() &&
                        WearDataLayerTransport.MESSAGE_PATH.startsWith(prefix)
                }
            },
        )
        assertEquals("/advantly/telemetry/live", LiveTelemetry.MESSAGE_PATH)
        assertEquals("/advantly/telemetry/subscribe", LiveTelemetrySubscription.MESSAGE_PATH)
        assertTrue(messages.any { it.getAttributeNS(android, "path") == LiveTelemetry.MESSAGE_PATH })
        assertTrue(dataItems.all { it.getAttributeNS(android, "pathPrefix") == WearDataLayerTransport.QUEUE_PREFIX })
    }

    /** B4: geplanter Abgleich nur über das System, mit Netzbedingung (ab Android 14 Pflichtrecht) und über Neustarts. */
    @Test
    fun syncJobServiceIsSystemBoundAndItsStateStaysOutOfBackups() {
        val service = elements("service").single { it.getAttributeNS(android, "name") == ".sync.PhoneSyncJobService" }
        assertEquals("false", service.getAttributeNS(android, "exported"))
        assertEquals("android.permission.BIND_JOB_SERVICE", service.getAttributeNS(android, "permission"))
        val permissions = elements("uses-permission").map { it.getAttributeNS(android, "name") }
        assertTrue(permissions.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertTrue(permissions.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        // Ein wiederhergestelltes „zuletzt gesichert“ beschriebe ein anderes Gerät.
        for (rules in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val excluded = File("src/main/res/xml/$rules").readText()
            assertEquals(rules, excluded.split("advantly_auth.xml").size, excluded.split("advantly_sync.xml").size)
            // Ausgeblendete Uhr-Installationshinweise hängen an Node-IDs dieses Geräts.
            assertEquals(rules, excluded.split("advantly_auth.xml").size, excluded.split("watch_install.xml").size)
        }
    }

    @Test
    fun watchListenerCoversExactSubscriptionPath() {
        val wearManifest =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(File("../wearApp/src/main/AndroidManifest.xml"))
        val nodes = wearManifest.getElementsByTagName("service")
        val service =
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .single { it.getAttributeNS(android, "name") == ".sync.SyncListenerService" }
        val filters = service.getElementsByTagName("intent-filter")
        val paths =
            (0 until filters.length)
                .map { filters.item(it) as Element }
                .filter {
                    (it.getElementsByTagName("action").item(0) as Element).getAttributeNS(android, "name") ==
                        "com.google.android.gms.wearable.MESSAGE_RECEIVED"
                }.map { (it.getElementsByTagName("data").item(0) as Element).getAttributeNS(android, "path") }
        assertTrue(paths.contains(LiveTelemetrySubscription.MESSAGE_PATH))
        assertTrue(paths.contains(WearDataLayerTransport.MESSAGE_PATH))
    }
}
