package eu.advantly.umbrella.watch

import eu.advantly.core.scoring.CoreScoringInfo
import eu.advantly.data.DataInfo
import eu.advantly.device.link.DeviceLinkInfo

/**
 * Öffentliche Swift-API von `AdvantlyWatchKit` – bewusst klein halten (Facade).
 * Ohne SKIE exportiert: nur einfache Typen an der Grenze (String, Int, Long, Boolean, Listen, einfache Klassen);
 * keine Flows, suspend-Funktionen, sealed-Hierarchien oder Generics. Asynchrones später über Callback-Interfaces.
 * Liefert nur Daten, keine UI-Texte: Lokalisierung passiert auf der Swift-Seite (`watchApp/en.lproj`, `watchApp/de.lproj`).
 * Swift: `AdvantlyWatch.shared.engineModule()`.
 */
object AdvantlyWatch {
    const val MODULE = "umbrella-watch"
    const val FRAMEWORK = "AdvantlyWatchKit"

    /** Direkte Modulabhängigkeiten laut 01 §2.2 – referenziert, damit die Verdrahtung kompiliert. */
    val dependsOn: List<String> = listOf(DataInfo.MODULE, DeviceLinkInfo.MODULE)

    /** Name des Engine-Moduls – Platzhalter-Aufruf, der beweist, dass die Uhr Kotlin-Code ausführt. */
    fun engineModule(): String = CoreScoringInfo.MODULE
}
