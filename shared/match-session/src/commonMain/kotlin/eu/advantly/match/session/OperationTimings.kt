package eu.advantly.match.session

/**
 * Gemeinsame Zeiten der Bedienung (W2-Vertrag, Feldtest 2026-10-16) für Wear OS, Apple Watch (über die Facade
 * `AdvantlyWatch`) und Handy – eine Quelle, damit Restzeit-Anzeige und Halte-Fortschritt überall gleich laufen.
 */
object OperationTimings {
    /** Aufschlag-Leiste nach dem Punkt: so lange sichtbar, wenn nichts gewählt wird. */
    const val SERVE_BAR_VISIBLE_MILLIS: Long = 5_000

    /** Nach einer Auswahl bleibt die Leiste noch so lange stehen (Markierung, Korrektur) – verlängert nie. */
    const val SERVE_BAR_AFTER_CHOICE_MILLIS: Long = 1_500

    /** Halten auf einer Hälfte: letzten Punkt dieser Seite abziehen (bzw. auf der Uhr entsperren). */
    const val HOLD_TO_REMOVE_MILLIS: Long = 1_000

    /**
     * Anteil 1 → 0 einer Restzeit-Anzeige: [remainingMillis] von [totalMillis] (auf 0..1 begrenzt). Für die
     * Aufschlag-Leiste ist [totalMillis] immer [SERVE_BAR_VISIBLE_MILLIS] – nach einer Auswahl springt die Anzeige also
     * auf die verkürzte Restzeit und läuft von dort ab.
     */
    fun remainingFraction(
        remainingMillis: Long,
        totalMillis: Long = SERVE_BAR_VISIBLE_MILLIS,
    ): Float = if (totalMillis <= 0) 0f else (remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)

    /** Anteil 0 → 1 eines Halte-Fortschritts nach [heldMillis] (voll genau bei [HOLD_TO_REMOVE_MILLIS]). */
    fun holdFraction(
        heldMillis: Long,
        thresholdMillis: Long = HOLD_TO_REMOVE_MILLIS,
    ): Float = if (thresholdMillis <= 0) 1f else (heldMillis.toFloat() / thresholdMillis).coerceIn(0f, 1f)
}
