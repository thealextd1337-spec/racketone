package eu.advantly.core.scoring

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport

/** Konsistenzregeln für [PointDetail] (02 §7.2). Inkonsistente Details verwirft nur die Statistik, nie den Score. */
object PointDetailRules {
    /**
     * Prüft [detail] für einen von [side] gewonnenen Punkt; [servingSide] ist die aufschlagende Side **vor** dem Punkt.
     */
    fun isConsistent(
        detail: PointDetail,
        side: Side,
        servingSide: Side,
        sport: Sport,
        lineup: Lineup,
    ): Boolean {
        val by = detail.by
        val tennisLike = sport.isTennisLike
        if (detail.secondServe != null && !tennisLike && sport != Sport.PICKLEBALL) return false
        return when (detail.kind) {
            PointDetailKind.ACE, PointDetailKind.SERVICE_WINNER -> side == servingSide
            PointDetailKind.DOUBLE_FAULT -> tennisLike && side != servingSide
            PointDetailKind.SERVICE_FAULT -> !tennisLike && side != servingSide
            PointDetailKind.WINNER -> by == null || lineup.sideOf(by) == side
            PointDetailKind.FORCED_ERROR, PointDetailKind.UNFORCED_ERROR -> by == null || lineup.sideOf(by) == side.other
        }
    }
}
