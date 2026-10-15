package eu.advantly.umbrella.watch

import eu.advantly.core.scoring.CoreScoringInfo
import eu.advantly.data.DataInfo
import eu.advantly.device.link.DeviceLinkInfo

/**
 * Öffentliche Swift-API von `AdvantlyWatchKit` – bewusst klein halten (Facade).
 * Ohne SKIE exportiert: nur einfache Typen an der Grenze (String, Int, Long, Boolean, Listen, einfache Klassen);
 * keine Flows, suspend-Funktionen, sealed-Hierarchien oder Generics. Asynchrones über Callback-Interfaces
 * (z. B. [LinkDebugListener]).
 * Liefert nur Daten, keine UI-Texte: Lokalisierung passiert auf der Swift-Seite (`watchApp/en.lproj`, `watchApp/de.lproj`).
 * Swift: `AdvantlyWatch.shared.engineModule()`, `AdvantlyWatch.shared.store().smokeCheck()`.
 */
object AdvantlyWatch {
    const val MODULE = "umbrella-watch"
    const val FRAMEWORK = "AdvantlyWatchKit"

    /** Direkte Modulabhängigkeiten laut 01 §2.2 – referenziert, damit die Verdrahtung kompiliert. */
    val dependsOn: List<String> = listOf(DataInfo.MODULE, DeviceLinkInfo.MODULE)

    // Ein EventStore (eine SQLite-Verbindung) für Store-Facade und Zählen
    private val sharedEvents by lazy { platformEventStore() }
    private val sharedStore: WatchStore by lazy { WatchStore(sharedEvents) }
    private val sharedScoring: WatchScoring by lazy { WatchScoring(sharedEvents, platformWatchPrefs()) }

    /** Name des Engine-Moduls – Platzhalter-Aufruf, der beweist, dass die Uhr Kotlin-Code ausführt. */
    fun engineModule(): String = CoreScoringInfo.MODULE

    /** Lokaler Event-Store der Uhr (SQLDelight, Datei `advantly.db`); eine Instanz je Prozess. */
    fun store(): WatchStore = sharedStore

    /** Eigenständiges Zählen auf der Uhr (W1-E); eine Instanz je Prozess. */
    fun scoring(): WatchScoring = sharedScoring

    /** Ping-Pong-Feldtest über WatchConnectivity (Spike S3). Nur Debug-UI. */
    fun linkDebug(): LinkDebug = platformLinkDebug()
}
