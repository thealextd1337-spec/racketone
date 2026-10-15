package eu.advantly.umbrella.watch

/**
 * Kleine Schlüssel-Wert-Ablage für Zeiger und Einstellungen der Watch-App (aktives Match, Geräte-ID,
 * „Wie letztes Mal“). Das Match selbst liegt ausschließlich im Event-Log.
 */
internal interface WatchPrefs {
    fun get(key: String): String?

    /** `null` löscht den Eintrag. */
    fun set(
        key: String,
        value: String?,
    )
}

/** Für Tests und als JVM-Platzhalter. */
internal class InMemoryWatchPrefs : WatchPrefs {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun set(
        key: String,
        value: String?,
    ) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** watchOS: `NSUserDefaults.standardUserDefaults`; JVM (nur Tests): In-Memory. */
internal expect fun platformWatchPrefs(): WatchPrefs

/** Wanduhrzeit in ms seit 1970 – nur für `wallClockMs` und UUIDv7, nie für die Event-Ordnung. */
internal expect fun platformNowMs(): Long
