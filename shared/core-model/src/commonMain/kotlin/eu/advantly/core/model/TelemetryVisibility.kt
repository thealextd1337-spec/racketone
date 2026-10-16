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
 * Telemetrie-Einstellung mit Versionsvektor (Gerät → Zahl seiner Änderungen). Ordnet Änderungen **kausal**, nicht nach
 * Wanduhr: Eine abweichende Uhr eines Geräts kann so keine spätere Datenschutzentscheidung aufheben (Review #47).
 *
 * **Konfliktregel:**
 * - Hat ein Gerät die Änderung des anderen schon gekannt (Vektor dominiert), gilt die spätere – auch wenn sie
 *   weniger restriktiv ist (bewusste Entscheidung nach dem Abgleich).
 * - Haben beide Seiten seit dem letzten gemeinsamen Stand geändert (nebenläufig, z. B. Uhr offline), gewinnt der
 *   **restriktivere** Wert („im Zweifel restriktiv“); der Vektor wird punktweise zusammengeführt.
 * - Gleicher Vektor, anderer Wert (kann nur durch Zusammenführung entstehen) → ebenfalls restriktiver Wert.
 *
 * [merge] ist kommutativ und idempotent; alle eigenen Geräte enden beim selben Wert, sobald sie sich gesehen haben.
 *
 * @property versions Gerät (Installations-ID) → Zahl seiner Änderungen; leer = nie geändert (Standard), ein Standard
 *   verdrängt nie eine Änderung
 */
data class TelemetryPreference(
    val visibility: TelemetryVisibility = TelemetryVisibility.DEFAULT,
    val versions: Map<String, Long> = emptyMap(),
) {
    /** Nie geändert: es gilt der Standard, auf dem Draht wird nichts gesendet. */
    val isDefault: Boolean get() = versions.isEmpty()

    /** Lokale Änderung auf [device]: dessen Zähler steigt, der neue Stand folgt kausal auf alles Bekannte. */
    fun changedBy(
        device: String,
        visibility: TelemetryVisibility,
    ): TelemetryPreference = TelemetryPreference(visibility, versions + (device to (versions[device] ?: 0) + 1))

    /** Zusammenführen mit [other] nach der Konfliktregel. */
    fun merge(other: TelemetryPreference): TelemetryPreference {
        if (other.isDefault) return this
        if (isDefault) return other
        val keys = versions.keys + other.versions.keys
        val thisCovers = keys.all { (versions[it] ?: 0) >= (other.versions[it] ?: 0) }
        val otherCovers = keys.all { (other.versions[it] ?: 0) >= (versions[it] ?: 0) }
        return when {
            thisCovers && !otherCovers -> {
                this
            }

            otherCovers && !thisCovers -> {
                other
            }

            else -> {
                // gleich oder nebenläufig: restriktiver Wert, punktweises Maximum
                val strict = if (other.visibility.restriction > visibility.restriction) other.visibility else visibility
                val merged = TelemetryPreference(strict, keys.associateWith { maxOf(versions[it] ?: 0, other.versions[it] ?: 0) })
                if (merged == this) this else merged
            }
        }
    }
}
