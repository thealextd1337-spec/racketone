package eu.advantly.umbrella.watch

import eu.advantly.core.scoring.CoreScoringInfo
import eu.advantly.data.DataInfo
import eu.advantly.device.link.DeviceLinkInfo

/**
 * Öffentliche Swift-API von `AdvantlyWatchKit` – bewusst klein halten (Facade).
 * Swift: `AdvantlyWatch.shared.hello()`.
 */
object AdvantlyWatch {
    const val MODULE = "umbrella-watch"
    const val FRAMEWORK = "AdvantlyWatchKit"

    /** Direkte Modulabhängigkeiten laut 01 §2.2 – referenziert, damit die Verdrahtung kompiliert. */
    val dependsOn: List<String> = listOf(DataInfo.MODULE, DeviceLinkInfo.MODULE)

    fun hello(): String = "Hello Advantly (${CoreScoringInfo.MODULE})"
}
