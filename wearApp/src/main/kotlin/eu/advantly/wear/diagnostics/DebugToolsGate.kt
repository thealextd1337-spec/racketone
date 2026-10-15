package eu.advantly.wear.diagnostics

/**
 * Einzige Release-Sperre der Debug-Werkzeuge (Diagnose-Log S5, Feldtest-Modus P1-Gate) als reine Funktion, damit beide
 * Build-Typen im JVM-Test prüfbar sind: Host-Unit-Tests laufen nur für `debug` (AGP-Standard; eine eigene Release-Testvariante
 * hieße, alle Tests beider Apps doppelt zu bauen). Aufrufer übergeben `BuildConfig.DEBUG`.
 */
object DebugToolsGate {
    /** Werkzeug überhaupt vorhanden (Datei, Logcat, Schalter). Release: nie. */
    fun available(debugBuild: Boolean): Boolean = debugBuild

    /** Feldtest-Aufzeichnung aktiv. Im Release wird der Schalter gar nicht erst gelesen. */
    fun recording(
        debugBuild: Boolean,
        switchOn: () -> Boolean,
    ): Boolean = available(debugBuild) && switchOn()
}
