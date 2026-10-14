package eu.advantly.core.scoring

import eu.advantly.core.model.CoreModelInfo

/** Platzhalter des Moduls `core-scoring` (Welle 0a). Wird durch echte Fachlogik ersetzt. */
object CoreScoringInfo {
    const val MODULE = "core-scoring"

    /** Direkte Modulabhängigkeiten laut 01 §2.2 – referenziert, damit die Verdrahtung kompiliert. */
    val dependsOn: List<String> = listOf(CoreModelInfo.MODULE)
}
