package eu.advantly.device.link

/** Ist auf einer gekoppelten Uhr die Advantly-App installiert? – unabhängig davon, ob sie gerade läuft. */
enum class CompanionState {
    /** Plattform kann es nicht feststellen (Desktop, Play-Dienste fehlen, WCSession nicht unterstützt). */
    UNKNOWN,

    /** Keine Uhr gekoppelt bzw. verbunden. */
    NO_WATCH,

    /** Uhr gekoppelt, aber Advantly ist dort nicht installiert. */
    APP_NOT_INSTALLED,

    /** Uhr gekoppelt und Advantly installiert (die App muss nicht laufen). */
    APP_INSTALLED,
}

/**
 * Erkennt die eigene Uhr unabhängig von einer laufenden Verbindung (W1-I, Feldtest Pixel Watch: Uhr-App lief nicht,
 * „Meine Uhr“ war trotzdem wählbar zu machen). Android: Wear `CapabilityClient` (`FILTER_ALL`), iOS:
 * `WCSession.isPaired && isWatchAppInstalled`. Callbacks auf dem Main-Thread.
 */
interface CompanionDetector {
    /** Startet die Erkennung; [listener] bekommt jede Änderung (und sofort nach der ersten Abfrage den Stand). */
    fun start(listener: (CompanionState) -> Unit)

    /** Fragt den Stand sofort neu ab (Schritt „Wer zählt?“, App wieder im Vordergrund, „Erneut prüfen“). */
    fun refresh()

    fun stop()
}
