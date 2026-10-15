package eu.advantly.presentation.match

import eu.advantly.core.scoring.MatchResult
import eu.advantly.core.scoring.ResultReason

/**
 * Unentschieden: `DRAW` (offener Modus, gleich viele Sätze) oder „Beenden, wie es steht“ bei Gleichstand (`STOPPED` ohne
 * Sieger, 02 §9.27).
 */
val MatchResult.isDraw: Boolean get() = reason == ResultReason.DRAW || (reason == ResultReason.STOPPED && winner == null)

/** Abgebrochen ohne Wertung (`ABANDONED`, 02 §9.9, F21). */
val MatchResult.isAbandoned: Boolean get() = reason == ResultReason.ABANDONED

/**
 * Wertet das Match – zählt es in Siege, Bilanz, Wochen- und Langzeitstatistik? Alle Gründe außer `ABANDONED` (F21):
 * Abgebrochene Matches bleiben im Verlauf mit Stand, gehen aber nicht in die Statistik ein.
 */
val MatchResult.isRated: Boolean get() = !isAbandoned

/** Siehe [MatchResult.isRated]. */
val MatchSummary.isRated: Boolean get() = result.isRated
