package eu.advantly.ui.phone.live

import androidx.compose.runtime.Composable
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.ServeCourt
import eu.advantly.presentation.live.ScoreHint
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.court_left
import eu.advantly.ui.phone.resources.court_right
import eu.advantly.ui.phone.resources.hint_breakpoint
import eu.advantly.ui.phone.resources.hint_breakpoints
import eu.advantly.ui.phone.resources.hint_changeends
import eu.advantly.ui.phone.resources.hint_deciding_point
import eu.advantly.ui.phone.resources.hint_deuce
import eu.advantly.ui.phone.resources.hint_extension
import eu.advantly.ui.phone.resources.hint_gamepoint
import eu.advantly.ui.phone.resources.hint_gamepoints
import eu.advantly.ui.phone.resources.hint_golden_point
import eu.advantly.ui.phone.resources.hint_interval
import eu.advantly.ui.phone.resources.hint_match_abandoned
import eu.advantly.ui.phone.resources.hint_match_drawn
import eu.advantly.ui.phone.resources.hint_match_lost
import eu.advantly.ui.phone.resources.hint_match_tiebreak
import eu.advantly.ui.phone.resources.hint_match_won
import eu.advantly.ui.phone.resources.hint_matchpoint
import eu.advantly.ui.phone.resources.hint_matchpoints
import eu.advantly.ui.phone.resources.hint_paused
import eu.advantly.ui.phone.resources.hint_pb_gamepoint
import eu.advantly.ui.phone.resources.hint_pb_gamepoints
import eu.advantly.ui.phone.resources.hint_rally_setpoint
import eu.advantly.ui.phone.resources.hint_rally_setpoints
import eu.advantly.ui.phone.resources.hint_receive_order_swapped
import eu.advantly.ui.phone.resources.hint_second_server
import eu.advantly.ui.phone.resources.hint_setpoint
import eu.advantly.ui.phone.resources.hint_setpoints
import eu.advantly.ui.phone.resources.hint_side_out
import eu.advantly.ui.phone.resources.hint_star_point
import eu.advantly.ui.phone.resources.hint_super_tiebreak
import eu.advantly.ui.phone.resources.hint_tiebreak
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Text der Hinweis-Pill, sportgerecht (02 §8.4: B/TT „Satzball“/„Verlängerung“, EN „game point“/„deuce“). */
@Composable
fun hintText(
    hint: ScoreHint,
    sport: Sport,
): String {
    fun counted(
        one: StringResource,
        many: StringResource,
    ): Pair<StringResource, Int?> = if (hint.count > 1) many to hint.count else one to null

    val rally = sport.isRallySport
    val (resource, count) =
        when (hint.kind) {
            ScoreHint.Kind.MatchPoint -> {
                counted(Res.string.hint_matchpoint, Res.string.hint_matchpoints)
            }

            ScoreHint.Kind.SetPoint -> {
                when {
                    sport == Sport.PICKLEBALL -> counted(Res.string.hint_pb_gamepoint, Res.string.hint_pb_gamepoints)
                    rally -> counted(Res.string.hint_rally_setpoint, Res.string.hint_rally_setpoints)
                    else -> counted(Res.string.hint_setpoint, Res.string.hint_setpoints)
                }
            }

            ScoreHint.Kind.BreakPoint -> {
                counted(Res.string.hint_breakpoint, Res.string.hint_breakpoints)
            }

            ScoreHint.Kind.GamePoint -> {
                counted(Res.string.hint_gamepoint, Res.string.hint_gamepoints)
            }

            ScoreHint.Kind.DecidingPoint -> {
                (if (sport == Sport.PADEL) Res.string.hint_golden_point else Res.string.hint_deciding_point) to null
            }

            ScoreHint.Kind.StarPoint -> {
                Res.string.hint_star_point to null
            }

            ScoreHint.Kind.Deuce -> {
                (if (rally) Res.string.hint_extension else Res.string.hint_deuce) to null
            }

            ScoreHint.Kind.Tiebreak -> {
                Res.string.hint_tiebreak to null
            }

            // Tennis: Champions-Tiebreak, Padel: Super-Tiebreak
            ScoreHint.Kind.MatchTiebreak -> {
                (if (sport == Sport.PADEL) Res.string.hint_super_tiebreak else Res.string.hint_match_tiebreak) to null
            }

            ScoreHint.Kind.ChangeEnds -> {
                Res.string.hint_changeends to null
            }

            ScoreHint.Kind.ReceiveOrderSwapped -> {
                Res.string.hint_receive_order_swapped to null
            }

            ScoreHint.Kind.SideOut -> {
                Res.string.hint_side_out to null
            }

            ScoreHint.Kind.SecondServer -> {
                Res.string.hint_second_server to null
            }

            ScoreHint.Kind.Interval -> {
                Res.string.hint_interval to null
            }

            ScoreHint.Kind.Paused -> {
                Res.string.hint_paused to null
            }

            ScoreHint.Kind.MatchWon -> {
                Res.string.hint_match_won to null
            }

            ScoreHint.Kind.MatchLost -> {
                Res.string.hint_match_lost to null
            }

            ScoreHint.Kind.MatchDrawn -> {
                Res.string.hint_match_drawn to null
            }

            ScoreHint.Kind.MatchAbandoned -> {
                Res.string.hint_match_abandoned to null
            }
        }
    val main = if (count != null) stringResource(resource, count) else stringResource(resource)
    // TT-Doppel im Entscheidungssatz: Seitenwechsel UND Rückschlagtausch gleichzeitig (02 §8.4)
    val also = hint.also?.let { hintText(ScoreHint(it), sport) }
    return if (also != null) "$main · $also" else main
}

/** „R“/„L“ für das Aufschlagfeld (Badminton, Pickleball, 02 §8.4); `null` sonst. */
@Composable
fun courtText(court: ServeCourt?): String? =
    when (court) {
        ServeCourt.RIGHT -> stringResource(Res.string.court_right)
        ServeCourt.LEFT -> stringResource(Res.string.court_left)
        else -> null
    }
