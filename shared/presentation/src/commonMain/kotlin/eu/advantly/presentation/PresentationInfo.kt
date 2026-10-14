package eu.advantly.presentation

import eu.advantly.data.DataInfo
import eu.advantly.device.link.DeviceLinkInfo
import eu.advantly.entitlements.EntitlementsInfo

/** Platzhalter des Moduls `presentation` (Welle 0a). Wird durch echte Fachlogik ersetzt. */
object PresentationInfo {
    const val MODULE = "presentation"

    /** Direkte Modulabhängigkeiten laut 01 §2.2 – referenziert, damit die Verdrahtung kompiliert. */
    val dependsOn: List<String> = listOf(DataInfo.MODULE, DeviceLinkInfo.MODULE, EntitlementsInfo.MODULE)
}
