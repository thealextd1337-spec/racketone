package eu.advantly.ui.phone.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.live.LiveScoreRules
import eu.advantly.presentation.live.PointLabel
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.duration_hours
import eu.advantly.ui.phone.resources.duration_minutes
import eu.advantly.ui.phone.resources.format_best_of
import eu.advantly.ui.phone.resources.format_open
import eu.advantly.ui.phone.resources.live_games_and_points
import eu.advantly.ui.phone.resources.live_score_advantage
import eu.advantly.ui.phone.resources.live_score_pair
import eu.advantly.ui.phone.resources.player_you
import eu.advantly.ui.phone.resources.sport_badminton
import eu.advantly.ui.phone.resources.sport_padel
import eu.advantly.ui.phone.resources.sport_pickleball
import eu.advantly.ui.phone.resources.sport_table_tennis
import eu.advantly.ui.phone.resources.sport_tennis
import eu.advantly.ui.phone.resources.team_separator
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** ID des eigenen Spielers (für „Du“ statt eines leeren Namens). */
val LocalSelfId = staticCompositionLocalOf<PlayerId?> { null }

fun sportName(sport: Sport): StringResource =
    when (sport) {
        Sport.TENNIS -> Res.string.sport_tennis
        Sport.PADEL -> Res.string.sport_padel
        Sport.PICKLEBALL -> Res.string.sport_pickleball
        Sport.BADMINTON -> Res.string.sport_badminton
        Sport.TABLE_TENNIS -> Res.string.sport_table_tennis
    }

/** Sport-Akzentfarbe (04 §5.2) – nie allein, immer mit Icon und Name. */
@Composable
@ReadOnlyComposable
fun sportColor(sport: Sport): Color {
    val c = AdvantlyTheme.colors
    return when (sport) {
        Sport.TENNIS -> c.sportTennis
        Sport.PADEL -> c.sportPadel
        Sport.PICKLEBALL -> c.sportPickleball
        Sport.BADMINTON -> c.sportBadminton
        Sport.TABLE_TENNIS -> c.sportTableTennis
    }
}

/** Anzeigename; der eigene Spieler ohne Namen heißt „Du“/„You“. */
@Composable
fun playerName(player: Player?): String {
    if (player == null) return ""
    if (player.displayName.isBlank() && player.id == LocalSelfId.current) return stringResource(Res.string.player_you)
    return player.displayName.ifBlank { stringResource(Res.string.player_you) }
}

/** Namen einer Side, im Doppel „Alex / Mia“. */
@Composable
fun teamName(
    setup: MatchSetup,
    side: Side,
): String {
    val names = setup.team(side).map { playerName(it) }
    return names.joinToString(stringResource(Res.string.team_separator))
}

/** „Best of 3“ bzw. „Offen“. */
@Composable
fun formatLabel(format: MatchFormat): String =
    if (format.match.mode == MatchMode.OPEN) {
        stringResource(Res.string.format_open)
    } else {
        stringResource(Res.string.format_best_of, format.match.bestOf ?: 1)
    }

@Composable
fun pointText(label: PointLabel): String =
    when (label) {
        is PointLabel.Number -> label.value
        PointLabel.Advantage -> stringResource(Res.string.live_score_advantage)
    }

/** „6:4“ (DE) bzw. „6–4“ (EN). */
@Composable
fun scorePair(
    a: Any,
    b: Any,
): String = stringResource(Res.string.live_score_pair, a.toString(), b.toString())

@Composable
fun scorePair(pair: SidePair<Int>): String = scorePair(pair.a, pair.b)

/** Kurzform eines Stands für die Undo-Snackbar: Punkte, mit [withGames] zusätzlich Spiele davor. */
@Composable
fun scoreSummary(
    state: MatchState,
    withGames: Boolean,
): String {
    val points =
        scorePair(pointText(LiveScoreRules.pointLabel(state, Side.A)), pointText(LiveScoreRules.pointLabel(state, Side.B)))
    val games = state.current?.games
    if (!withGames || games == null) return points
    return stringResource(Res.string.live_games_and_points, scorePair(games), points)
}

/** Dauer „1:32 h“ / „1h 32m“ bzw. „45 min“. */
@Composable
fun durationText(ms: Long): String {
    val totalMinutes = (ms / 60_000).toInt()
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) {
        stringResource(Res.string.duration_hours, h, m.toString().padStart(2, '0'))
    } else {
        stringResource(Res.string.duration_minutes, m)
    }
}
