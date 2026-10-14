package eu.advantly.umbrella.ios

import eu.advantly.ui.phone.UiPhoneInfo

/** Platzhalter des Moduls `umbrella-ios` (XCFramework `AdvantlyKit`). */
object AdvantlyKitInfo {
    const val MODULE = "umbrella-ios"
    const val FRAMEWORK = "AdvantlyKit"

    /** Direkte Modulabhängigkeiten laut 01 §2.2 – referenziert, damit die Verdrahtung kompiliert. */
    val dependsOn: List<String> = listOf(UiPhoneInfo.MODULE)
}
