package eu.advantly.ui.phone.wizard

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import eu.advantly.core.model.DeuceMode
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.MidGameEndChange
import eu.advantly.core.model.PickleballScoring
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Surface
import eu.advantly.presentation.wizard.DecidingSetChoice
import eu.advantly.presentation.wizard.PlayerPlace
import eu.advantly.presentation.wizard.SetsChoice
import eu.advantly.presentation.wizard.TiebreakAtChoice
import eu.advantly.presentation.wizard.WizardUiState
import eu.advantly.ui.phone.common.formatLabel
import eu.advantly.ui.phone.common.playerName
import eu.advantly.ui.phone.common.sportName
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.fmt_deciding_adv_set
import eu.advantly.ui.phone.resources.fmt_deciding_mtb
import eu.advantly.ui.phone.resources.fmt_deciding_normal
import eu.advantly.ui.phone.resources.fmt_deciding_padel_no_tb
import eu.advantly.ui.phone.resources.fmt_deciding_padel_super_tb
import eu.advantly.ui.phone.resources.fmt_deciding_tb10
import eu.advantly.ui.phone.resources.fmt_deciding_title
import eu.advantly.ui.phone.resources.fmt_deciding_title_padel
import eu.advantly.ui.phone.resources.fmt_deuce_advantage
import eu.advantly.ui.phone.resources.fmt_deuce_golden
import eu.advantly.ui.phone.resources.fmt_deuce_hint_advantage
import eu.advantly.ui.phone.resources.fmt_deuce_hint_golden
import eu.advantly.ui.phone.resources.fmt_deuce_hint_noad
import eu.advantly.ui.phone.resources.fmt_deuce_hint_star
import eu.advantly.ui.phone.resources.fmt_deuce_noad
import eu.advantly.ui.phone.resources.fmt_deuce_star
import eu.advantly.ui.phone.resources.fmt_deuce_title
import eu.advantly.ui.phone.resources.fmt_games
import eu.advantly.ui.phone.resources.fmt_games_pro
import eu.advantly.ui.phone.resources.fmt_games_short
import eu.advantly.ui.phone.resources.fmt_games_title
import eu.advantly.ui.phone.resources.fmt_one_game
import eu.advantly.ui.phone.resources.fmt_one_set
import eu.advantly.ui.phone.resources.fmt_pb_mid_deciding
import eu.advantly.ui.phone.resources.fmt_pb_mid_every
import eu.advantly.ui.phone.resources.fmt_pb_mid_never
import eu.advantly.ui.phone.resources.fmt_pb_mid_title
import eu.advantly.ui.phone.resources.fmt_pb_rally
import eu.advantly.ui.phone.resources.fmt_pb_scoring_hint
import eu.advantly.ui.phone.resources.fmt_pb_scoring_title
import eu.advantly.ui.phone.resources.fmt_pb_sideout
import eu.advantly.ui.phone.resources.fmt_pb_winby
import eu.advantly.ui.phone.resources.fmt_pb_winby_title
import eu.advantly.ui.phone.resources.fmt_points
import eu.advantly.ui.phone.resources.fmt_points_badminton_11
import eu.advantly.ui.phone.resources.fmt_points_per_game
import eu.advantly.ui.phone.resources.fmt_points_per_set
import eu.advantly.ui.phone.resources.fmt_points_tt_21
import eu.advantly.ui.phone.resources.fmt_rule_bad_cap
import eu.advantly.ui.phone.resources.fmt_rule_bad_interval
import eu.advantly.ui.phone.resources.fmt_rule_bad_no_extension
import eu.advantly.ui.phone.resources.fmt_rule_mid_change
import eu.advantly.ui.phone.resources.fmt_rule_mid_change_open
import eu.advantly.ui.phone.resources.fmt_rule_tt_receive_swap
import eu.advantly.ui.phone.resources.fmt_rule_tt_serve
import eu.advantly.ui.phone.resources.fmt_sets_title
import eu.advantly.ui.phone.resources.fmt_sets_title_pickleball
import eu.advantly.ui.phone.resources.fmt_sets_to_win
import eu.advantly.ui.phone.resources.fmt_summary_games
import eu.advantly.ui.phone.resources.fmt_summary_points
import eu.advantly.ui.phone.resources.fmt_tiebreak_at
import eu.advantly.ui.phone.resources.fmt_tiebreak_none
import eu.advantly.ui.phone.resources.fmt_tiebreak_title
import eu.advantly.ui.phone.resources.format_best_of
import eu.advantly.ui.phone.resources.format_open
import eu.advantly.ui.phone.resources.sport_tagline_badminton
import eu.advantly.ui.phone.resources.sport_tagline_padel
import eu.advantly.ui.phone.resources.sport_tagline_pickleball
import eu.advantly.ui.phone.resources.sport_tagline_table_tennis
import eu.advantly.ui.phone.resources.sport_tagline_tennis
import eu.advantly.ui.phone.resources.surface_artificial
import eu.advantly.ui.phone.resources.surface_artificial_desc
import eu.advantly.ui.phone.resources.surface_carpet
import eu.advantly.ui.phone.resources.surface_carpet_desc
import eu.advantly.ui.phone.resources.surface_clay
import eu.advantly.ui.phone.resources.surface_clay_desc
import eu.advantly.ui.phone.resources.surface_grass
import eu.advantly.ui.phone.resources.surface_grass_desc
import eu.advantly.ui.phone.resources.surface_hard
import eu.advantly.ui.phone.resources.surface_hard_desc
import eu.advantly.ui.phone.resources.surface_other
import eu.advantly.ui.phone.resources.wizard_empty_slot
import eu.advantly.ui.phone.resources.wizard_place
import eu.advantly.ui.phone.resources.wizard_team_them
import eu.advantly.ui.phone.resources.wizard_team_us
import eu.advantly.ui.phone.resources.wizard_you
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun sportTagline(sport: Sport): StringResource =
    when (sport) {
        Sport.TENNIS -> Res.string.sport_tagline_tennis
        Sport.PADEL -> Res.string.sport_tagline_padel
        Sport.PICKLEBALL -> Res.string.sport_tagline_pickleball
        Sport.BADMINTON -> Res.string.sport_tagline_badminton
        Sport.TABLE_TENNIS -> Res.string.sport_tagline_table_tennis
    }

/** Belag-Swatches aus 04 §5.2 (nur Illustration). */
internal fun surfaceSwatch(surface: Surface): Color =
    when (surface) {
        Surface.CLAY -> Color(0xFFC4572A)
        Surface.HARD -> Color(0xFF2F6DB3)
        Surface.GRASS -> Color(0xFF3F8F3A)
        Surface.CARPET -> Color(0xFF7A5CA8)
        Surface.ARTIFICIAL_GRASS -> Color(0xFF5E9E4E)
        else -> Color(0xFF6E7581)
    }

fun surfaceName(surface: Surface): StringResource =
    when (surface) {
        Surface.CLAY -> Res.string.surface_clay
        Surface.HARD -> Res.string.surface_hard
        Surface.GRASS -> Res.string.surface_grass
        Surface.CARPET -> Res.string.surface_carpet
        Surface.ARTIFICIAL_GRASS -> Res.string.surface_artificial
        else -> Res.string.surface_other
    }

internal fun surfaceDescription(surface: Surface): StringResource =
    when (surface) {
        Surface.CLAY -> Res.string.surface_clay_desc
        Surface.HARD -> Res.string.surface_hard_desc
        Surface.GRASS -> Res.string.surface_grass_desc
        Surface.CARPET -> Res.string.surface_carpet_desc
        else -> Res.string.surface_artificial_desc
    }

internal fun setsTitle(sport: Sport): StringResource =
    if (sport == Sport.PICKLEBALL) Res.string.fmt_sets_title_pickleball else Res.string.fmt_sets_title

@Composable
internal fun setsLabel(
    choice: SetsChoice,
    sport: Sport,
): String =
    when (choice) {
        SetsChoice.Open -> {
            stringResource(Res.string.format_open)
        }

        is SetsChoice.BestOf -> {
            if (choice.n == 1) {
                stringResource(if (sport == Sport.PICKLEBALL) Res.string.fmt_one_game else Res.string.fmt_one_set)
            } else {
                stringResource(Res.string.format_best_of, choice.n)
            }
        }
    }

internal fun gamesTitle(): StringResource = Res.string.fmt_games_title

@Composable
internal fun gamesLabel(games: Int): String =
    when (games) {
        4 -> stringResource(Res.string.fmt_games_short, games)
        8 -> stringResource(Res.string.fmt_games_pro, games)
        else -> stringResource(Res.string.fmt_games, games)
    }

internal fun pointsTitle(sport: Sport): StringResource =
    if (sport == Sport.PICKLEBALL) Res.string.fmt_points_per_game else Res.string.fmt_points_per_set

@Composable
internal fun pointsLabel(
    points: Int,
    sport: Sport,
): String =
    when {
        sport == Sport.TABLE_TENNIS && points == 21 -> stringResource(Res.string.fmt_points_tt_21, points)
        sport == Sport.BADMINTON && points == 11 -> stringResource(Res.string.fmt_points_badminton_11, points)
        else -> stringResource(Res.string.fmt_points, points)
    }

internal fun pickleballScoringTitle(): StringResource = Res.string.fmt_pb_scoring_title

@Composable
internal fun pickleballScoringLabel(scoring: PickleballScoring): String =
    stringResource(if (scoring == PickleballScoring.SIDE_OUT) Res.string.fmt_pb_sideout else Res.string.fmt_pb_rally)

internal fun pickleballScoringHint(): StringResource = Res.string.fmt_pb_scoring_hint

internal fun pickleballWinByTitle(): StringResource = Res.string.fmt_pb_winby_title

@Composable
internal fun pickleballWinByLabel(winBy: Int): String = stringResource(Res.string.fmt_pb_winby, winBy)

internal fun pickleballMidTitle(): StringResource = Res.string.fmt_pb_mid_title

@Composable
internal fun pickleballMidLabel(mid: MidGameEndChange): String =
    stringResource(
        when (mid) {
            MidGameEndChange.DECIDING_GAME_ONLY -> Res.string.fmt_pb_mid_deciding
            MidGameEndChange.EVERY_GAME -> Res.string.fmt_pb_mid_every
            MidGameEndChange.NEVER -> Res.string.fmt_pb_mid_never
        },
    )

internal fun deuceTitle(): StringResource = Res.string.fmt_deuce_title

@Composable
internal fun deuceLabel(
    mode: DeuceMode,
    sport: Sport?,
): String =
    stringResource(
        when (mode) {
            DeuceMode.ADVANTAGE -> Res.string.fmt_deuce_advantage
            DeuceMode.DECIDING_POINT -> if (sport == Sport.PADEL) Res.string.fmt_deuce_golden else Res.string.fmt_deuce_noad
            DeuceMode.STAR_POINT -> Res.string.fmt_deuce_star
        },
    )

internal fun deuceHint(
    mode: DeuceMode,
    sport: Sport?,
): StringResource =
    when (mode) {
        DeuceMode.ADVANTAGE -> Res.string.fmt_deuce_hint_advantage
        DeuceMode.DECIDING_POINT -> if (sport == Sport.PADEL) Res.string.fmt_deuce_hint_golden else Res.string.fmt_deuce_hint_noad
        DeuceMode.STAR_POINT -> Res.string.fmt_deuce_hint_star
    }

internal fun decidingTitle(sport: Sport?): StringResource =
    if (sport == Sport.PADEL) Res.string.fmt_deciding_title_padel else Res.string.fmt_deciding_title

@Composable
internal fun decidingLabel(
    choice: DecidingSetChoice,
    sport: Sport?,
): String {
    val padel = sport == Sport.PADEL
    return stringResource(
        when (choice) {
            DecidingSetChoice.NORMAL -> Res.string.fmt_deciding_normal
            DecidingSetChoice.MATCH_TIEBREAK -> if (padel) Res.string.fmt_deciding_padel_super_tb else Res.string.fmt_deciding_mtb
            DecidingSetChoice.ADVANTAGE_SET -> if (padel) Res.string.fmt_deciding_padel_no_tb else Res.string.fmt_deciding_adv_set
            DecidingSetChoice.TIEBREAK_TO_10 -> Res.string.fmt_deciding_tb10
        },
    )
}

internal fun tiebreakTitle(): StringResource = Res.string.fmt_tiebreak_title

@Composable
internal fun tiebreakLabel(
    choice: TiebreakAtChoice,
    games: Int,
): String =
    when (choice) {
        TiebreakAtChoice.AT_GAMES -> stringResource(Res.string.fmt_tiebreak_at, games, games)
        TiebreakAtChoice.AT_GAMES_MINUS_ONE -> stringResource(Res.string.fmt_tiebreak_at, games - 1, games - 1)
        TiebreakAtChoice.NONE -> stringResource(Res.string.fmt_tiebreak_none)
    }

/** Nur lesende Regeln für Badminton/Tischtennis (02 §5.1.1 „Erweitert“, U15). */
@Composable
internal fun rallyRules(state: WizardUiState): List<String> {
    val format = state.format ?: return emptyList()
    val rally = format.rally ?: return emptyList()
    val open = format.match.mode == MatchMode.OPEN
    val mid = rally.effectiveMidGameEndChangeAt
    val lines = mutableListOf<String>()
    when (format.sport) {
        Sport.BADMINTON -> {
            val cap = rally.cap
            lines +=
                if (rally.winBy == 1 || cap == null) {
                    stringResource(Res.string.fmt_rule_bad_no_extension, rally.pointsToWin)
                } else {
                    stringResource(Res.string.fmt_rule_bad_cap, rally.pointsToWin - 1, cap)
                }
            rally.intervalAt?.let { lines += stringResource(Res.string.fmt_rule_bad_interval, it) }
        }

        else -> {
            lines += stringResource(Res.string.fmt_rule_tt_serve, rally.serveEvery ?: 2, rally.pointsToWin - 1)
            if (format.discipline == Discipline.DOUBLES && !open) lines += stringResource(Res.string.fmt_rule_tt_receive_swap)
        }
    }
    lines += if (open) stringResource(Res.string.fmt_rule_mid_change_open) else stringResource(Res.string.fmt_rule_mid_change, mid)
    return lines
}

/** Sticky-Zusammenfassung: „Tennis · Best of 3 · 6 Games · Vorteil“. */
@Composable
internal fun formatSummary(state: WizardUiState): String {
    val sport = state.input.sport ?: return ""
    val format = state.format
    val parts = mutableListOf(stringResource(sportName(sport)))
    if (format == null) return parts.joinToString(" · ")
    parts += formatLabel(format)
    if (format.match.mode == MatchMode.BEST_OF && format.match.bestOf != 1 && sport == Sport.TABLE_TENNIS) {
        parts += stringResource(Res.string.fmt_sets_to_win, format.setsToWin ?: 1)
    }
    when {
        sport.isTennisLike -> {
            parts += stringResource(Res.string.fmt_summary_games, format.set?.gamesToWin ?: 6)
            format.game?.let { parts += deuceLabel(it.deuceMode, sport) }
        }

        sport == Sport.PICKLEBALL -> {
            val pb = format.pickleball
            if (pb != null) {
                parts += stringResource(Res.string.fmt_summary_points, pb.pointsToWin)
                parts += pickleballScoringLabel(pb.scoring)
            }
        }

        else -> {
            format.rally?.let { parts += stringResource(Res.string.fmt_summary_points, it.pointsToWin) }
        }
    }
    return parts.joinToString(" · ")
}

/** Platz und Besetzung gemeinsam für Touch, TalkBack und VoiceOver benennen. */
@Composable
internal fun placeLabel(
    state: WizardUiState,
    place: PlayerPlace,
): String =
    stringResource(
        Res.string.wizard_place,
        stringResource(if (place.side == Side.A) Res.string.wizard_team_us else Res.string.wizard_team_them),
        place.index + 1,
        playerSlotName(state, place),
    )

@Composable
internal fun playerSlotName(
    state: WizardUiState,
    place: PlayerPlace,
): String {
    val player = state.input.team(place.side).getOrNull(place.index)
    val name =
        when {
            player == null -> stringResource(Res.string.wizard_empty_slot)
            player.id == state.selfId -> stringResource(Res.string.wizard_you, playerName(player))
            else -> playerName(player)
        }
    return name
}
