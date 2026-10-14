package eu.advantly.sync.protocol

import eu.advantly.core.scoring.CoreScoringInfo

/** Platzhalter des Moduls `sync-protocol` (Welle 0a). Wird durch echte Fachlogik ersetzt. */
object SyncProtocolInfo {
    const val MODULE = "sync-protocol"

    /** Direkte Modulabhängigkeiten laut 01 §2.2 – referenziert, damit die Verdrahtung kompiliert. */
    val dependsOn: List<String> = listOf(CoreScoringInfo.MODULE)
}
