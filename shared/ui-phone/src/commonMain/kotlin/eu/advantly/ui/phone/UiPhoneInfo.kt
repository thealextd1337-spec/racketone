package eu.advantly.ui.phone

import eu.advantly.presentation.PresentationInfo

/** Platzhalter des Moduls `ui-phone` (Welle 0a). Wird durch echte Fachlogik ersetzt. */
object UiPhoneInfo {
    const val MODULE = "ui-phone"

    /** Direkte Modulabhängigkeiten laut 01 §2.2 – referenziert, damit die Verdrahtung kompiliert. */
    val dependsOn: List<String> = listOf(PresentationInfo.MODULE)
}
