package eu.advantly.data

import eu.advantly.core.scoring.CoreScoringInfo

/** Platzhalter des Moduls `data` (Welle 0a). Wird durch echte Fachlogik ersetzt. */
object DataInfo {
    const val MODULE = "data"

    /** Direkte Modulabhängigkeiten laut 01 §2.2 – referenziert, damit die Verdrahtung kompiliert. */
    val dependsOn: List<String> = listOf(CoreScoringInfo.MODULE)
}
