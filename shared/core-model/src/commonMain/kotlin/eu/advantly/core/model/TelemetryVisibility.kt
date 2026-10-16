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
 * Telemetrie-Einstellung mit **Lamport-Zähler** (Review #47, Orchestrator-Entscheidung): ordnet Änderungen ohne Wanduhr –
 * eine abweichende Uhr eines Geräts kann keine spätere Datenschutzentscheidung aufheben. Konstante Größe.
 *
 * **Konfliktregel:** [merge] nimmt das Maximum nach der totalen Ordnung (counter, Restriktivität OFF > PRIVATE > SHARE,
 * origin lexikografisch). Damit kommutativ, assoziativ und idempotent; alle eigenen Geräte enden beim selben Wert.
 * - Eine kausal spätere Änderung (sie kannte die andere) hat immer den höheren counter und gewinnt – auch wenn sie
 *   weniger restriktiv ist.
 * - Konkurrierende Offline-Änderungen mit gleichem counter → der restriktivere Wert gewinnt („im Zweifel restriktiv“).
 * - **Bewusster Kompromiss:** Ändert ein Gerät offline mehrmals, kann sein höherer counter eine konkurrierende einzelne
 *   Änderung des anderen Geräts schlagen, auch wenn diese restriktiver war. Dafür bleibt der Zustand konstant klein und
 *   die Zusammenführung eine echte Halbverbandsoperation (kein unbegrenzt wachsender Versionsvektor).
 *
 * @property counter Lamport-Zähler; `0` = nie geändert (Standard), ein Standard verdrängt nie eine Änderung
 * @property origin Installations-ID des Geräts, das diesen Stand gesetzt hat; `null` beim Standard
 */
data class TelemetryPreference(
    val visibility: TelemetryVisibility = TelemetryVisibility.DEFAULT,
    val counter: Long = 0,
    val origin: String? = null,
) {
    /** Nie geändert: es gilt der Standard, auf dem Draht wird nichts gesendet. */
    val isDefault: Boolean get() = counter == 0L

    /** Lokale Änderung auf [device]: counter = größter bisher gesehener + 1 (der eigene Stand ist bereits das Maximum). */
    fun changedBy(
        device: String,
        visibility: TelemetryVisibility,
    ): TelemetryPreference = TelemetryPreference(visibility, counter + 1, device)

    /** Maximum nach der totalen Ordnung; bei Gleichheit dieser Stand. */
    fun merge(other: TelemetryPreference): TelemetryPreference = if (ORDER.compare(other, this) > 0) other else this

    companion object {
        /** Totale Ordnung: counter, dann Restriktivität, dann origin (Standard ohne origin zuerst). */
        val ORDER: Comparator<TelemetryPreference> =
            compareBy<TelemetryPreference>({ it.counter }, { it.visibility.restriction }, { it.origin ?: "" })
    }
}
