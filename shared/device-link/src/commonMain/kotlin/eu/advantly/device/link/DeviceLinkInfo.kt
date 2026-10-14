package eu.advantly.device.link

import eu.advantly.sync.protocol.SyncProtocolInfo

/** Platzhalter des Moduls `device-link` (Welle 0a). Wird durch echte Fachlogik ersetzt. */
object DeviceLinkInfo {
    const val MODULE = "device-link"

    /** Direkte Modulabhängigkeiten laut 01 §2.2 – referenziert, damit die Verdrahtung kompiliert. */
    val dependsOn: List<String> = listOf(SyncProtocolInfo.MODULE)
}
