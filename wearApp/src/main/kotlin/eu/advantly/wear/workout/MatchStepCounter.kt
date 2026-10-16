package eu.advantly.wear.workout

import eu.advantly.core.model.MatchId

/** Reine RAM-Baseline: Match-Wechsel beginnt bei null, Exercise-Neustart setzt den Rohzähler zurück. */
data class MatchStepCounter(
    val matchId: MatchId? = null,
    private val total: Long? = null,
    private val stepsAtMatchStart: Long = 0,
    private val baselinePending: Boolean = false,
) {
    val steps: Long? get() = if (matchId == null) null else total?.let { (it - stepsAtMatchStart).coerceAtLeast(0) }

    fun forMatch(next: MatchId?): MatchStepCounter = if (next == matchId) this else copy(matchId = next, stepsAtMatchStart = total ?: 0)

    fun withTotal(next: Long): MatchStepCounter {
        require(next >= 0)
        val baseline =
            when {
                baselinePending -> next
                total != null && next < total -> 0
                else -> stepsAtMatchStart
            }
        return copy(total = next, stepsAtMatchStart = baseline, baselinePending = false)
    }

    /**
     * Verwirft den Stand („Gar nicht“, P2-J): keine Schritte, bis wieder Werte kommen; der nächste Rohwert wird die neue
     * Basis. Schritte aus der Zeit ohne Telemetrie tauchen so auch nach dem Wiedereinschalten nie auf.
     */
    fun restarted(): MatchStepCounter = copy(total = null, stepsAtMatchStart = 0, baselinePending = true)

    override fun toString(): String = "MatchStepCounter(<redacted>)"

    companion object {
        /** Eine verlorene Match-Grenze lässt sich ohne Health-Persistenz nicht rekonstruieren: konservativ neu ab null. */
        fun recovered(): MatchStepCounter = MatchStepCounter(baselinePending = true)
    }
}
