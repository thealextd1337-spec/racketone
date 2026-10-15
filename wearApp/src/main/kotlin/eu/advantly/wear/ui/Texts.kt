package eu.advantly.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.ResultReason
import eu.advantly.presentation.live.PointLabel
import eu.advantly.presentation.live.ScoreHint
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.wear.R
import eu.advantly.wear.live.PlayerUi
import eu.advantly.wear.live.SyncUi
import eu.advantly.wear.live.WatchToast
import eu.advantly.wear.match.FormatChoice

// Texte der Wear-UI aus res/values[-de]/strings.xml; die Logik liefert nur Enums/Zahlen (CLAUDE.md „UI-Strings“).

@Composable
fun sportName(sport: Sport): String =
    stringResource(
        when (sport) {
            Sport.TENNIS -> R.string.sport_tennis
            Sport.PADEL -> R.string.sport_padel
            Sport.PICKLEBALL -> R.string.sport_pickleball
            Sport.BADMINTON -> R.string.sport_badminton
            Sport.TABLE_TENNIS -> R.string.sport_table_tennis
        },
    )

@Composable
fun formatLabel(choice: FormatChoice): String =
    when (choice) {
        FormatChoice.TENNIS_BO3 -> {
            stringResource(R.string.format_best_of, 3)
        }

        FormatChoice.TENNIS_BO3_MTB -> {
            stringResource(R.string.format_tennis_mtb)
        }

        FormatChoice.TENNIS_ONE_SET -> {
            stringResource(R.string.format_one_set)
        }

        FormatChoice.TENNIS_SHORT_SETS -> {
            stringResource(R.string.format_short_sets)
        }

        FormatChoice.TENNIS_OPEN, FormatChoice.PADEL_OPEN, FormatChoice.BADMINTON_OPEN, FormatChoice.TABLE_TENNIS_OPEN -> {
            stringResource(R.string.format_open_sets)
        }

        FormatChoice.PADEL_GOLDEN -> {
            stringResource(R.string.format_padel_golden)
        }

        FormatChoice.PADEL_STAR -> {
            stringResource(R.string.format_padel_star)
        }

        FormatChoice.PADEL_GOLDEN_STB -> {
            stringResource(R.string.format_padel_golden_stb)
        }

        FormatChoice.PICKLEBALL_11 -> {
            stringResource(R.string.format_pickleball_one, 11)
        }

        FormatChoice.PICKLEBALL_BO3_11 -> {
            stringResource(R.string.format_pickleball_bo3, 11)
        }

        FormatChoice.PICKLEBALL_15 -> {
            stringResource(R.string.format_pickleball_one, 15)
        }

        FormatChoice.PICKLEBALL_RALLY_21 -> {
            stringResource(R.string.format_pickleball_rally, 21)
        }

        FormatChoice.PICKLEBALL_OPEN -> {
            stringResource(R.string.format_open_games)
        }

        FormatChoice.BADMINTON_BO3_21 -> {
            stringResource(R.string.format_badminton_bo3, 21)
        }

        FormatChoice.BADMINTON_BO3_15 -> {
            stringResource(R.string.format_badminton_bo3, 15)
        }

        FormatChoice.BADMINTON_ONE_21 -> {
            stringResource(R.string.format_badminton_one, 21)
        }

        FormatChoice.BADMINTON_ONE_15 -> {
            stringResource(R.string.format_badminton_one, 15)
        }

        FormatChoice.TABLE_TENNIS_BO3 -> {
            stringResource(R.string.format_table_tennis, 3, 2)
        }

        FormatChoice.TABLE_TENNIS_BO5 -> {
            stringResource(R.string.format_table_tennis, 5, 3)
        }

        FormatChoice.TABLE_TENNIS_BO7 -> {
            stringResource(R.string.format_table_tennis, 7, 4)
        }
    }

/** Beschriftung eines Formats ohne passenden Uhr-Vorschlag (z. B. am Handy angelegt). */
@Composable
fun genericFormatLabel(format: MatchFormat): String {
    val bestOf = format.match.bestOf
    return when {
        format.match.mode == MatchMode.OPEN && format.sport == Sport.PICKLEBALL -> stringResource(R.string.format_open_games)
        format.match.mode == MatchMode.OPEN || bestOf == null -> stringResource(R.string.format_open_sets)
        else -> stringResource(R.string.format_best_of, bestOf)
    }
}

@Composable
fun pointText(label: PointLabel): String =
    when (label) {
        is PointLabel.Number -> label.value
        PointLabel.Advantage -> "AD"
    }

/** Spielername oder Standardbezeichnung („Du“, „Partner“, „Gegner 1“ …). */
@Composable
fun playerName(
    player: PlayerUi,
    doubles: Boolean,
): String = if (player.isSelf) stringResource(R.string.player_you) else player.name ?: defaultPlayerName(player.side, player.index, doubles)

/** Standardbezeichnung eines Slots („Du“, „Partner“, „Gegner 1/2“). */
@Composable
fun defaultPlayerName(
    side: Side,
    index: Int,
    doubles: Boolean,
): String =
    stringResource(
        when {
            side == Side.A && index == 0 -> R.string.player_you
            side == Side.A -> R.string.player_partner
            !doubles -> R.string.player_opponent
            index == 0 -> R.string.player_opponent_1
            else -> R.string.player_opponent_2
        },
    )

@Composable
fun deviceName(kind: PeerKind?): String =
    stringResource(
        when (kind) {
            PeerKind.PHONE -> R.string.device_phone
            PeerKind.WATCH -> R.string.device_watch
            PeerKind.OTHER, null -> R.string.device_other
        },
    )

/** „vor 42 s“ / „vor 3 min“ / „Kein Kontakt“. */
@Composable
fun agoText(seconds: Long?): String =
    when {
        seconds == null -> stringResource(R.string.sync_no_contact)
        seconds < 60 -> stringResource(R.string.sync_ago_seconds, seconds.toInt())
        else -> stringResource(R.string.sync_ago_minutes, (seconds / 60).toInt())
    }

/** Sync-Status kurz (Zähler: „Synchron“ / „3 ausstehend“; Anzeige: „Live“ / „vor 42 s“). */
@Composable
fun syncText(sync: SyncUi): String =
    when (sync) {
        SyncUi.Synced -> stringResource(R.string.sync_synced)
        SyncUi.LocalOnly -> stringResource(R.string.sync_local)
        is SyncUi.Pending -> pluralStringResource(R.plurals.sync_pending, sync.count, sync.count)
        SyncUi.Live -> stringResource(R.string.sync_live)
        is SyncUi.Stale -> agoText(sync.seconds)
    }

/** Text der Hinweis-Pill oder `null` (Spielball zeigt nur das Handy, 02 §8.2). */
@Composable
fun hintText(
    hint: ScoreHint,
    sport: Sport,
): String? {
    val many = hint.count > 1
    val rally = sport.isRallySport
    return when (hint.kind) {
        ScoreHint.Kind.MatchPoint -> {
            if (many) {
                pluralStringResource(
                    R.plurals.hint_match_points,
                    hint.count,
                    hint.count,
                )
            } else {
                stringResource(R.string.hint_match_point)
            }
        }

        ScoreHint.Kind.SetPoint -> {
            if (many) pluralStringResource(R.plurals.hint_set_points, hint.count, hint.count) else stringResource(R.string.hint_set_point)
        }

        ScoreHint.Kind.BreakPoint -> {
            if (many) {
                pluralStringResource(
                    R.plurals.hint_break_points,
                    hint.count,
                    hint.count,
                )
            } else {
                stringResource(R.string.hint_break_point)
            }
        }

        ScoreHint.Kind.GamePoint -> {
            null
        }

        ScoreHint.Kind.DecidingPoint -> {
            stringResource(if (rally) R.string.hint_deciding_point else R.string.hint_golden_point)
        }

        ScoreHint.Kind.StarPoint -> {
            stringResource(R.string.hint_star_point)
        }

        ScoreHint.Kind.Deuce -> {
            stringResource(if (rally) R.string.hint_extension else R.string.hint_deuce)
        }

        ScoreHint.Kind.Tiebreak -> {
            stringResource(R.string.hint_tiebreak)
        }

        ScoreHint.Kind.MatchTiebreak -> {
            stringResource(R.string.hint_match_tiebreak)
        }

        ScoreHint.Kind.ChangeEnds -> {
            val base = stringResource(R.string.hint_change_ends)
            if (hint.also == ScoreHint.Kind.ReceiveOrderSwapped) "$base · ${stringResource(R.string.hint_receive_swap)}" else base
        }

        ScoreHint.Kind.ReceiveOrderSwapped -> {
            stringResource(R.string.hint_receive_swap)
        }

        ScoreHint.Kind.SideOut -> {
            stringResource(R.string.hint_side_out)
        }

        ScoreHint.Kind.SecondServer -> {
            stringResource(R.string.hint_second_server)
        }

        ScoreHint.Kind.Interval -> {
            stringResource(R.string.hint_interval)
        }

        ScoreHint.Kind.Paused -> {
            stringResource(R.string.hint_paused)
        }

        ScoreHint.Kind.MatchWon -> {
            stringResource(R.string.result_won)
        }

        ScoreHint.Kind.MatchLost -> {
            stringResource(R.string.result_lost)
        }

        ScoreHint.Kind.MatchDrawn -> {
            stringResource(R.string.result_drawn)
        }

        ScoreHint.Kind.MatchAbandoned -> {
            stringResource(R.string.result_abandoned)
        }
    }
}

@Composable
fun resultText(
    winner: Side?,
    reason: ResultReason?,
): String =
    stringResource(
        when {
            reason == ResultReason.DRAW -> R.string.result_drawn
            winner == Side.A -> R.string.result_won
            winner == Side.B -> R.string.result_lost
            else -> R.string.result_abandoned
        },
    )

@Composable
fun toastText(toast: WatchToast): String =
    when (toast) {
        is WatchToast.Undone -> {
            stringResource(R.string.undo_toast, toast.before, toast.after)
        }

        WatchToast.Redone -> {
            stringResource(R.string.redo_toast)
        }

        WatchToast.NothingToUndo -> {
            stringResource(R.string.nothing_to_undo)
        }

        WatchToast.Locked -> {
            stringResource(R.string.toast_locked)
        }

        WatchToast.Unlocked -> {
            stringResource(R.string.toast_unlocked)
        }

        WatchToast.Corrected -> {
            stringResource(R.string.toast_corrected)
        }

        is WatchToast.RoleChanged -> {
            if (toast.nowScorer) {
                stringResource(R.string.toast_now_scorer)
            } else {
                stringResource(R.string.toast_other_scores, deviceName(toast.scorerKind))
            }
        }

        is WatchToast.InputsRejected -> {
            pluralStringResource(R.plurals.rejected_inputs, toast.count, toast.count)
        }

        WatchToast.MatchRemoved -> {
            stringResource(R.string.toast_match_removed)
        }

        WatchToast.StartFailed -> {
            stringResource(R.string.toast_start_failed)
        }
    }
