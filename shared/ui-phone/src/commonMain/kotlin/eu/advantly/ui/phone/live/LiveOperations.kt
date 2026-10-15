package eu.advantly.ui.phone.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.presentation.live.LiveOperationRules
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.ui.phone.common.ChoiceChip
import eu.advantly.ui.phone.common.playerName
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.live_remove_point_them
import eu.advantly.ui.phone.resources.live_remove_point_us
import eu.advantly.ui.phone.resources.serve_double_fault
import eu.advantly.ui.phone.resources.serve_first_in
import eu.advantly.ui.phone.resources.serve_point_title
import eu.advantly.ui.phone.resources.serve_second_in
import eu.advantly.ui.phone.resources.server_choice_hint
import eu.advantly.ui.phone.resources.server_choice_title_game1
import eu.advantly.ui.phone.resources.server_choice_title_game2
import eu.advantly.ui.phone.resources.set_end_champions_10
import eu.advantly.ui.phone.resources.set_end_regular
import eu.advantly.ui.phone.resources.set_end_tiebreak_7
import org.jetbrains.compose.resources.stringResource

/** In Satz-/Matchende-Sheets bleiben auch seitenspezifische Korrekturen erreichbar. */
@Composable
internal fun PointRemovalActions(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
) {
    if (!snapshot.isScorer) return
    Side.entries.forEach { side ->
        TextButton(
            onClick = { controller.removeLastPointOf(side) },
            enabled = LiveOperationRules.canRemove(snapshot, side),
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget).testTag("remove_point_${side.name}"),
        ) {
            Text(stringResource(if (side == Side.A) Res.string.live_remove_point_us else Res.string.live_remove_point_them))
        }
    }
    controller.undoNotice?.let { notice ->
        UndoSnackbar(notice, controller::redo, { controller.dismissUndoNotice(notice.id) }, Modifier)
    }
}

@Composable
internal fun ServerChoiceBar(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
) {
    if (!snapshot.isScorer) return
    val choice = snapshot.state.serverChoice ?: return
    Column(Modifier.fillMaxWidth().testTag("server_choice"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            if (choice.game == 1) {
                stringResource(Res.string.server_choice_title_game1)
            } else {
                stringResource(Res.string.server_choice_title_game2, teamName(snapshot.setup, choice.side))
            },
            style = AdvantlyTheme.typography.label,
            color = AdvantlyTheme.colors.text,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            choice.players.forEach { player ->
                ChoiceChip(
                    text = playerName(snapshot.setup.player(player)),
                    selected = choice.chosen == player,
                    onClick = { controller.chooseServer(player) },
                    tag = "choose_server_${player.value}",
                )
            }
        }
        if (snapshot.awaitingServerChoice) {
            Text(
                stringResource(Res.string.server_choice_hint),
                style = AdvantlyTheme.typography.caption,
                color = AdvantlyTheme.colors.text2,
            )
        }
    }
}

/** Kein Dialog: Der nächste Punkt bleibt jederzeit zählbar. Erneutes Wählen löscht die Angabe. */
@Composable
internal fun ServeInfoBar(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
) {
    val options = LiveOperationRules.serveOptions(snapshot)
    val point = snapshot.lastPoint ?: return
    if (options.isEmpty() || controller.servePromptId != point.id) return
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("serve_info")
            .background(AdvantlyTheme.colors.surface2, Radii.chipShape)
            .padding(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            stringResource(Res.string.serve_point_title, playerName(snapshot.setup.player(point.server))),
            style = AdvantlyTheme.typography.caption,
            color = AdvantlyTheme.colors.text2,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            options.forEach { option ->
                ChoiceChip(
                    text =
                        stringResource(
                            when (option) {
                                ServeOutcome.FIRST_SERVE_IN -> Res.string.serve_first_in
                                ServeOutcome.SECOND_SERVE_IN -> Res.string.serve_second_in
                                ServeOutcome.DOUBLE_FAULT -> Res.string.serve_double_fault
                            },
                        ),
                    selected = point.serve == option,
                    onClick = { controller.setServeInfo(point.id, option.takeUnless { it == point.serve }) },
                    tag = "serve_${option.name}",
                )
            }
        }
    }
}

@Composable
internal fun nextSetLabel(kind: NextSetKind): String =
    stringResource(
        when (kind) {
            NextSetKind.REGULAR -> Res.string.set_end_regular
            NextSetKind.TIEBREAK_7 -> Res.string.set_end_tiebreak_7
            NextSetKind.MATCH_TIEBREAK, NextSetKind.MATCH_TIEBREAK_10 -> Res.string.set_end_champions_10
        },
    )
