package eu.advantly.ui.phone.common

import androidx.compose.runtime.Composable
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.CurrentSet
import eu.advantly.core.scoring.MatchResult
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.ResultReason
import eu.advantly.core.scoring.SetKind
import eu.advantly.core.scoring.SetRecord
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.live.LiveScoreRules
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.live_score_advantage
import eu.advantly.ui.phone.resources.match_end_retired_suffix
import eu.advantly.ui.phone.resources.match_end_stopped_suffix
import eu.advantly.ui.phone.resources.match_end_walkover_suffix
import org.jetbrains.compose.resources.stringResource

/** Anzeigewerte eines abgeschlossenen Satzes: Spiele, Match-Tiebreak-Punkte bzw. Rally-/Pickleball-Punkte. */
fun setRecordPair(set: SetRecord): SidePair<Int>? =
    when (set.kind) {
        SetKind.REGULAR -> set.games
        SetKind.MATCH_TIEBREAK -> set.tiebreak ?: set.points
        else -> set.points
    }

@Composable
private fun displayText(text: String): String = if (text == "AD") stringResource(Res.string.live_score_advantage) else text

/**
 * Ergebniszeile aus Sicht von Wir, z. B. „6:4 3:6 10:7“ – bei vorzeitigem Ende mit eingefrorenem Satz und Zusatz
 * („6:4 2:1 (30:0) Aufg.“, 02 §9.2/§9.22). Ohne abgeschlossene Sätze die gewonnenen Sätze.
 */
@Composable
fun resultLine(
    sets: List<SetRecord>,
    interrupted: CurrentSet?,
    result: MatchResult?,
    setsWon: SidePair<Int>,
): String {
    val parts =
        sets
            .mapNotNull { set ->
                setRecordPair(set)?.let {
                    if (set.kind ==
                        SetKind.MATCH_TIEBREAK
                    ) {
                        "[${scorePair(it)}]"
                    } else {
                        scorePair(it)
                    }
                }
            }.toMutableList()
    if (interrupted != null) {
        val games = interrupted.games
        val points = scorePair(displayText(interrupted.display.a), displayText(interrupted.display.b))
        if (interrupted.setKind == SetKind.MATCH_TIEBREAK) {
            parts += "[$points]"
        } else if (games != null) {
            parts += scorePair(games)
            if (interrupted.points.a + interrupted.points.b > 0) parts += "($points)"
        } else {
            parts += points
        }
    }
    if (parts.isEmpty()) parts += scorePair(setsWon)
    when (result?.reason) {
        ResultReason.RETIRED -> parts += stringResource(Res.string.match_end_retired_suffix)
        ResultReason.WALKOVER -> parts += stringResource(Res.string.match_end_walkover_suffix)
        ResultReason.STOPPED -> parts += stringResource(Res.string.match_end_stopped_suffix)
        else -> Unit
    }
    return parts.joinToString(" ")
}

/** Kurzer Stand eines (laufenden) Zustands: „6:4 (3:2) 30:15“ bzw. „21:19 (11:7)“. */
@Composable
fun stateScore(
    state: MatchState,
    sport: Sport,
): String {
    val columns = LiveScoreRules.setColumns(state)
    val done =
        state.sets.mapNotNull { set ->
            setRecordPair(set)?.let { if (set.kind == SetKind.MATCH_TIEBREAK) "[${scorePair(it)}]" else scorePair(it) }
        }
    val current =
        columns.firstOrNull { it.isCurrent }?.let {
            if (it.isTiebreakSet) "[${scorePair(it.values)}]" else "(${scorePair(it.values)})"
        }
    val points =
        if (sport.isTennisLike && state.current != null && state.current?.setKind != SetKind.MATCH_TIEBREAK) {
            scorePair(
                pointText(LiveScoreRules.pointLabel(state, Side.A)),
                pointText(LiveScoreRules.pointLabel(state, Side.B)),
            )
        } else {
            null
        }
    return (done + listOfNotNull(current, points)).joinToString(" ")
}
