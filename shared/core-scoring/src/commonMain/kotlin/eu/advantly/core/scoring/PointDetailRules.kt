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
        if (detail.serve != null && serveOutcome(detail, side, servingSide, sport) == null) return false
        return when (detail.kind) {
            null -> true
            PointDetailKind.ACE, PointDetailKind.SERVICE_WINNER -> side == servingSide
            PointDetailKind.DOUBLE_FAULT -> tennisLike && side != servingSide
            PointDetailKind.SERVICE_FAULT -> !tennisLike && side != servingSide
            PointDetailKind.WINNER -> by == null || lineup.sideOf(by) == side
            PointDetailKind.FORCED_ERROR, PointDetailKind.UNFORCED_ERROR -> by == null || lineup.sideOf(by) == side.other
        }
    }

    /**
     * Fold-Regel für `PointDetailSet` (02 §7.3, Entscheidung 2026-10-15): Ein Detail mit Aufschlag-Info ([PointDetail.serve])
     * ist nur bei Tennis/Padel zulässig, `DOUBLE_FAULT` nur für einen Punkt der Rückschlagseite. Sonst wird das Event
     * übersprungen (`ignoredEvents`). Alle übrigen Inhalte prüft weiterhin nur die Statistik ([isConsistent]).
     */
    fun isAllowedInFold(
        detail: PointDetail?,
        side: Side,
        servingSide: Side,
        sport: Sport,
    ): Boolean {
        val serve = detail?.serve ?: return true
        if (!sport.isTennisLike) return false
        return serve != ServeOutcome.DOUBLE_FAULT || side != servingSide
    }

    /**
     * Neues Detail, wenn die Aufschlag-Info eines Punktes mit dem Detail [current] auf [serve] gesetzt wird (`null` =
     * löschen; Grundlage für `PointDetailSet` aus der Aufschlag-Leiste): übrige Felder bleiben, widersprüchliche ältere
     * Angaben (`secondServe`, `kind = DOUBLE_FAULT` bei anderer Aufschlag-Info) entfallen. Liefert `null`, wenn nichts übrig bleibt.
     */
    fun withServe(
        current: PointDetail?,
        serve: ServeOutcome?,
    ): PointDetail? {
        val base = current ?: PointDetail()
        val kind = base.kind.takeUnless { it == PointDetailKind.DOUBLE_FAULT && serve != ServeOutcome.DOUBLE_FAULT }
        return base.copy(kind = kind, secondServe = null, serve = serve).takeUnless { it == PointDetail() }
    }

    /**
     * Wirksame Aufschlag-Info eines Punktes für die Statistik (02 §11.2): [PointDetail.serve], sonst aus den älteren
     * Feldern abgeleitet (`kind = DOUBLE_FAULT` → Doppelfehler, `secondServe = true/false` → 2./1. Aufschlag).
     * `null`, wenn unbekannt, nicht Tennis/Padel oder widersprüchlich (z. B. Doppelfehler bei Punkt des Aufschlägers,
     * `serve` widerspricht `kind`/`secondServe`).
     */
    fun serveOutcome(
        detail: PointDetail?,
        side: Side,
        servingSide: Side,
        sport: Sport,
    ): ServeOutcome? {
        if (detail == null || !sport.isTennisLike) return null
        val legacy =
            when {
                detail.kind == PointDetailKind.DOUBLE_FAULT -> ServeOutcome.DOUBLE_FAULT
                detail.secondServe == true -> ServeOutcome.SECOND_SERVE_IN
                detail.secondServe == false -> ServeOutcome.FIRST_SERVE_IN
                else -> null
            }
        val outcome = detail.serve ?: legacy ?: return null
        val contradicts =
            (detail.kind == PointDetailKind.DOUBLE_FAULT && outcome != ServeOutcome.DOUBLE_FAULT) ||
                (detail.secondServe == true && outcome == ServeOutcome.FIRST_SERVE_IN) ||
                (detail.secondServe == false && outcome != ServeOutcome.FIRST_SERVE_IN) ||
                (outcome == ServeOutcome.DOUBLE_FAULT && detail.kind != null && detail.kind != PointDetailKind.DOUBLE_FAULT)
        if (contradicts) return null
        if (outcome == ServeOutcome.DOUBLE_FAULT && side == servingSide) return null
        return outcome
    }
}
