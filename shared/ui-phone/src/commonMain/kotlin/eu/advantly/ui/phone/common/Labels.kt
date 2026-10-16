package eu.advantly.ui.phone.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import eu.advantly.core.model.DeuceMode
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.MatchTiebreakFormat
import eu.advantly.core.model.PickleballScoring
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.RegularSet
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
import eu.advantly.ui.phone.resources.fmt_deuce_advantage
import eu.advantly.ui.phone.resources.fmt_deuce_golden
import eu.advantly.ui.phone.resources.fmt_deuce_noad
import eu.advantly.ui.phone.resources.fmt_deuce_star
import eu.advantly.ui.phone.resources.fmt_pb_rally
import eu.advantly.ui.phone.resources.fmt_pb_sideout
import eu.advantly.ui.phone.resources.format_best_of
import eu.advantly.ui.phone.resources.format_cap
import eu.advantly.ui.phone.resources.format_custom
import eu.advantly.ui.phone.resources.format_decider
import eu.advantly.ui.phone.resources.format_games
import eu.advantly.ui.phone.resources.format_mtb
import eu.advantly.ui.phone.resources.format_no_tb
import eu.advantly.ui.phone.resources.format_open
import eu.advantly.ui.phone.resources.format_points
import eu.advantly.ui.phone.resources.format_super_tb
import eu.advantly.ui.phone.resources.format_tb
import eu.advantly.ui.phone.resources.format_win_by
import eu.advantly.ui.phone.resources.live_games_and_points
import eu.advantly.ui.phone.resources.live_score_advantage
import eu.advantly.ui.phone.resources.live_score_pair
import eu.advantly.ui.phone.resources.player_opponent
import eu.advantly.ui.phone.resources.player_opponent_1
import eu.advantly.ui.phone.resources.player_opponent_2
import eu.advantly.ui.phone.resources.player_partner
import eu.advantly.ui.phone.resources.player_you
import eu.advantly.ui.phone.resources.setup_padel_tiebreak_only_10
import eu.advantly.ui.phone.resources.setup_tiebreak_only_10
import eu.advantly.ui.phone.resources.setup_tiebreak_only_7
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
val LocalSelfAliases = staticCompositionLocalOf<Set<PlayerId>> { emptySet() }

/** Archivierte Personen nur aus Auswahlen ausblenden, nie aus historischen Matchdaten. */
internal val LocalArchivedPlayerIds = staticCompositionLocalOf<Set<PlayerId>> { emptySet() }

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

/** Unbesetzte Plätze lokalisiert anhand der unveränderlichen Lineup-Position benennen. */
@Composable
fun playerName(
    setup: MatchSetup,
    id: PlayerId,
): String {
    if (setup.player(id)?.isPlaceholder != true) return playerName(setup.player(id))
    val side = setup.lineup.sideOf(id)
    val index = side?.let { setup.lineup.players(it).indexOf(id) } ?: 0
    val ownerSide =
        (setup.lineup.a + setup.lineup.b)
            .firstOrNull { playerId ->
                playerId == LocalSelfId.current || playerId in LocalSelfAliases.current
            }?.let(setup.lineup::sideOf) ?: Side.A
    return stringResource(
        when {
            side == ownerSide -> Res.string.player_partner
            setup.lineup.b.size == 1 -> Res.string.player_opponent
            index == 1 -> Res.string.player_opponent_2
            else -> Res.string.player_opponent_1
        },
    )
}

/** Namen einer Side, im Doppel „Alex / Mia“. */
@Composable
fun teamName(
    setup: MatchSetup,
    side: Side,
): String {
    val names = setup.lineup.players(side).map { playerName(setup, it) }
    return names.joinToString(stringResource(Res.string.team_separator))
}

/** „Best of 3“ bzw. „Offen“. */
@Composable
fun formatLabel(format: MatchFormat): String =
    if (format.presetId == null) stringResource(Res.string.format_custom) else formatModeLabel(format)

@Composable
fun formatModeLabel(format: MatchFormat): String =
    if (format.match.bestOf == 1 && format.decidingSet is MatchTiebreakFormat) {
        stringResource(
            when {
                (format.decidingSet as? MatchTiebreakFormat)?.pointsToWin == 7 -> Res.string.setup_tiebreak_only_7
                format.sport == Sport.PADEL -> Res.string.setup_padel_tiebreak_only_10
                else -> Res.string.setup_tiebreak_only_10
            },
        )
    } else if (format.match.mode == MatchMode.OPEN) {
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

/** Match-Tiebreak sportgerecht: Tennis „Champions-Tiebreak bis 10“, Padel „Super-Tiebreak bis 10“. */
@Composable
fun matchTiebreakText(
    sport: Sport,
    pointsToWin: Int,
): String = stringResource(if (sport == Sport.PADEL) Res.string.format_super_tb else Res.string.format_mtb, pointsToWin)

/** Struktur bleibt auch bei benutzerdefiniertem Titel vollständig sichtbar. */
@Composable
fun formatDescription(format: MatchFormat): String {
    val onlyTiebreak = (format.decidingSet as? MatchTiebreakFormat)?.takeIf { format.match.bestOf == 1 }
    if (onlyTiebreak != null) {
        return listOf(
            stringResource(Res.string.format_best_of, 1),
            matchTiebreakText(format.sport, onlyTiebreak.pointsToWin),
            stringResource(Res.string.format_win_by, onlyTiebreak.winBy),
        ).joinToString(" · ")
    }
    val parts = mutableListOf(formatModeLabel(format))

    @Composable fun regular(set: RegularSet): String =
        listOf(
            stringResource(Res.string.format_games, set.gamesToWin),
            set.tiebreakAt?.let { stringResource(Res.string.format_tb, it, set.tiebreak.pointsToWin) }
                ?: stringResource(Res.string.format_no_tb),
        ).joinToString(" · ")
    format.set?.let { parts += regular(it) }
    format.game?.let {
        parts +=
            stringResource(
                when (it.deuceMode) {
                    DeuceMode.ADVANTAGE -> Res.string.fmt_deuce_advantage
                    DeuceMode.DECIDING_POINT -> if (format.sport == Sport.PADEL) Res.string.fmt_deuce_golden else Res.string.fmt_deuce_noad
                    DeuceMode.STAR_POINT -> Res.string.fmt_deuce_star
                },
            )
    }
    format.decidingSet?.takeIf { format.match.bestOf != 1 }?.let {
        val text =
            when (it) {
                is RegularSet -> regular(it)
                is MatchTiebreakFormat -> matchTiebreakText(format.sport, it.pointsToWin)
            }
        parts += stringResource(Res.string.format_decider, text)
    }
    format.pickleball?.let {
        parts += stringResource(Res.string.format_points, it.pointsToWin, it.winBy)
        parts += stringResource(if (it.scoring == PickleballScoring.SIDE_OUT) Res.string.fmt_pb_sideout else Res.string.fmt_pb_rally)
        it.pointCap?.let { cap -> parts += stringResource(Res.string.format_cap, cap) }
    }
    format.rally?.let {
        parts += stringResource(Res.string.format_points, it.pointsToWin, it.winBy)
        it.cap?.let { cap -> parts += stringResource(Res.string.format_cap, cap) }
    }
    return parts.joinToString(" · ")
}
