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
     * löschen; Grundlage für `PointDetailSet` aus der Aufschlag-Leiste). Widersprüchliche Angaben werden bereinigt:
     * `secondServe` entfällt immer; `DOUBLE_FAULT` setzt `kind = DOUBLE_FAULT` und entfernt `by` (ein Doppelfehler
     * beendet den Punkt, eine andere Kategorie wie `WINNER` ist dann falsch); eine andere Aufschlag-Info entfernt ein
     * vorhandenes `kind = DOUBLE_FAULT`. Übrige Felder bleiben. Liefert `null`, wenn nichts übrig bleibt.
     */
    fun withServe(
        current: PointDetail?,
        serve: ServeOutcome?,
    ): PointDetail? {
        val base = (current ?: PointDetail()).copy(secondServe = null, serve = serve)
        val cleaned =
            when {
                serve == ServeOutcome.DOUBLE_FAULT -> base.copy(kind = PointDetailKind.DOUBLE_FAULT, by = null)
                base.kind == PointDetailKind.DOUBLE_FAULT -> base.copy(kind = null)
                else -> base
            }
        return cleaned.takeUnless { it == PointDetail() }
    }

    /**
     * Wirksame Aufschlag-Info eines Punktes für die Statistik (02 §7.2, §11.2). **Vorrangregel (gleich wie im Fold):**
     * [PointDetail.serve] gilt, wenn gesetzt – widersprechende ältere Angaben (`kind`, `secondServe`) werden dann
     * ignoriert, so wie der Fold ein solches Detail akzeptiert. Ohne `serve` wird aus den älteren Feldern abgeleitet
     * (`kind = DOUBLE_FAULT` → Doppelfehler, sonst `secondServe = true/false` → 2./1. Aufschlag). `null`, wenn unbekannt,
     * nicht Tennis/Padel oder ein Doppelfehler bei einem Punkt des Aufschlägers (den der Fold für `PointDetailSet` ablehnt).
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
        if (outcome == ServeOutcome.DOUBLE_FAULT && side == servingSide) return null
        return outcome
    }
}
