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

    // Eine Datenbank (eine SQLite-Verbindung) für Store-Smoke und Match-Session; die Session entsteht erst beim ersten
    // Zugriff in WatchScoring (Fehler, z. B. veraltetes Schema, landen dort als isAvailable() = false statt als Absturz)
    private val sharedData by lazy { platformLocalData() }
    private val sharedStore: WatchStore by lazy { WatchStore(sharedData.events) }
    private val sharedScoring: WatchScoring by lazy {
        WatchScoring({ sharedData.let { WatchScoring.Backend(platformWatchSession(it), it) } })
    }

    /** Name des Engine-Moduls – Platzhalter-Aufruf, der beweist, dass die Uhr Kotlin-Code ausführt. */
    fun engineModule(): String = CoreScoringInfo.MODULE

    /** Lokaler Event-Store der Uhr (SQLDelight, Datei `advantly.db`); eine Instanz je Prozess. */
    fun store(): WatchStore = sharedStore

    /** Zählen bzw. Anzeigen auf der Uhr über die gemeinsame Match-Session (W1-E); eine Instanz je Prozess. */
    fun scoring(): WatchScoring = sharedScoring

    /** Ping-Pong-Feldtest über WatchConnectivity (Spike S3). Nur Debug-UI. */
    fun linkDebug(): LinkDebug = platformLinkDebug()
}
