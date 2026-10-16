package eu.advantly.core.model

/**
 * Telemetrie-Sichtbarkeit des Besitzers (F19/F29, P2-J): wer Puls und Schritte sehen darf. Eine Einstellung je Besitzer,
 * gerätelokal gespeichert und zwischen den eigenen Geräten synchronisiert; später mit `profile_settings.telemetry_visibility`
 * abgeglichen (P2-E). Keine Health-Speicherung (P3).
 *
 * @property wire Wert auf dem Draht und in der Datenbank (`share`/`private`/`off`, CHECK-Constraint in Supabase)
 * @property restriction Strenge für den Gleichstand in [TelemetryPreference.merge]: höher = restriktiver
 */
enum class TelemetryVisibility(
    val wire: String,
    val restriction: Int,
) {
    /** Heute wie [PRIVATE]; zusätzlich für die spätere Freigabe an Freunde/Live-Ansicht (P2-E/P2-H) vorgemerkt. */
    SHARE("share", 0),

    /** Standard: Live-Werte nur auf der eigenen Uhr und dem eigenen Handy. */
    PRIVATE("private", 1),

    /** Keine Live-Anzeige, keine Übertragung; gepufferte Werte werden verworfen. */
    OFF("off", 2),
    ;

    /** Darf die Uhr Live-Werte anzeigen und an das eigene Handy senden? */
    val allowsLive: Boolean get() = this != OFF

    /** Vorgemerkt für die Freigabe an andere (wirkt erst mit Freunden/Live-Ansicht). */
    val sharesWithOthers: Boolean get() = this == SHARE

    companion object {
        /** Standard ohne Änderung durch den Nutzer (F29). */
        val DEFAULT: TelemetryVisibility = PRIVATE

        /** Unbekannte Werte (z. B. einer künftigen Version) → `null`, nie eine stille Umdeutung. */
        fun fromWire(value: String?): TelemetryVisibility? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Telemetrie-Einstellung mit Zeitpunkt der letzten Änderung (Wanduhr des ändernden Geräts).
 *
 * **Konfliktregel (letzte Änderung gewinnt):** Handy und Uhr dürfen die Einstellung ändern; es gilt die jüngere Änderung.
 * Bei gleichem Zeitstempel gewinnt der restriktivere Wert (datenschutzfreundlich und auf allen Geräten gleich). Die Wanduhr
 * der Uhr wird vom gekoppelten Handy gestellt, daher genügt sie zum Ordnen zweier Nutzerentscheidungen.
 *
 * @property changedAtMs `0` = nie geändert (Standard); ein nie geänderter Wert verdrängt nie eine Änderung
 */
data class TelemetryPreference(
    val visibility: TelemetryVisibility = TelemetryVisibility.DEFAULT,
    val changedAtMs: Long = 0,
) {
    /** Zusammenführen mit [other] nach der Konfliktregel; kommutativ und idempotent. */
    fun merge(other: TelemetryPreference): TelemetryPreference =
        when {
            other.changedAtMs > changedAtMs -> other
            other.changedAtMs < changedAtMs -> this
            other.visibility.restriction > visibility.restriction -> other
            else -> this
        }

    /** Lokale Änderung zu [nowMs]; immer jünger als der bisherige Stand, auch wenn die Wanduhr zurückgesprungen ist. */
    fun changedTo(
        visibility: TelemetryVisibility,
        nowMs: Long,
    ): TelemetryPreference = TelemetryPreference(visibility, maxOf(nowMs, changedAtMs + 1))
}
