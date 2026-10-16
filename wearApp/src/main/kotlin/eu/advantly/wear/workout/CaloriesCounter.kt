package eu.advantly.wear.workout

/**
 * Kalorien-Anzeige mit Basis (P2-J): `CALORIES_TOTAL` zählt ab Exercise-Start. Nach „Gar nicht“ wird der Stand verworfen
 * ([restarted]); der nächste Rohwert wird die neue Basis, Kalorien aus der Zeit ohne Telemetrie tauchen nie wieder auf.
 * Reine RAM-Werte, kein Speichern.
 */
data class CaloriesCounter(
    private val total: Double? = null,
    private val baseline: Double = 0.0,
    private val baselinePending: Boolean = false,
) {
    val kcal: Double? get() = total?.let { (it - baseline).coerceAtLeast(0.0) }

    fun withTotal(next: Double): CaloriesCounter {
        val base =
            when {
                baselinePending -> next

                total != null && next < total -> 0.0

                // Exercise neu gestartet: Rohzähler beginnt wieder bei null
                else -> baseline
            }
        return copy(total = next, baseline = base, baselinePending = false)
    }

    fun restarted(): CaloriesCounter = CaloriesCounter(baselinePending = true)

    override fun toString(): String = "CaloriesCounter(<redacted>)"

    companion object {
        /**
         * Nach Prozessverlust (Exercise lief in Health Services weiter, REATTACH): Ob vorher „Gar nicht“ galt, ist ohne
         * Health-Persistenz unbekannt – konservativ ab null, der erste Rohwert wird die Basis.
         */
        fun recovered(): CaloriesCounter = CaloriesCounter(baselinePending = true)
    }
}
