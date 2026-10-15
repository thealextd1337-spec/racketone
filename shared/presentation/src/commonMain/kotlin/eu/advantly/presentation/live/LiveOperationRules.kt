package eu.advantly.presentation.live

import eu.advantly.core.model.Side
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.presentation.match.LiveMatchSnapshot

/** UI-Freigaben aus dem Port-Vertrag; die Session prüft Kommandos zusätzlich selbst. */
object LiveOperationRules {
    fun canRemove(
        snapshot: LiveMatchSnapshot,
        side: Side,
    ): Boolean = snapshot.isScorer && snapshot.canRemovePoint[side]

    fun serveOptions(snapshot: LiveMatchSnapshot): List<ServeOutcome> {
        if (!snapshot.isScorer || !snapshot.serveInfoEnabled) return emptyList()
        val point = snapshot.lastPoint ?: return emptyList()
        return listOf(ServeOutcome.FIRST_SERVE_IN, ServeOutcome.SECOND_SERVE_IN) +
            if (point.doubleFaultAllowed) listOf(ServeOutcome.DOUBLE_FAULT) else emptyList()
    }
}
