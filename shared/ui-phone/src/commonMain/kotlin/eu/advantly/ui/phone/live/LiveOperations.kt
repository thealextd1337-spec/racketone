package eu.advantly.ui.phone.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.MatchStatus
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
import eu.advantly.ui.phone.resources.dialog_yes
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
                    text = playerName(snapshot.setup, player),
                    selected = choice.chosen == player,
                    onClick = { snapshot.serverChoiceContext?.let { controller.chooseServer(player, it) } },
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

/** Fester Platz im begrenzten Info-Bereich: Prompts und Timeout verschieben keine Score-Flächen. */
@Composable
internal fun LiveOperationsSlot(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
) {
    val reservesServerChoice =
        snapshot.setup.sport in listOf(Sport.TENNIS, Sport.PADEL) && snapshot.setup.format.discipline == Discipline.DOUBLES
    if (!snapshot.isScorer || (!snapshot.serveInfoEnabled && !reservesServerChoice)) return
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    Column(
        Modifier
            .fillMaxWidth()
            .height(112.dp * fontScale)
            .testTag("live_operations_slot")
            .verticalScroll(rememberScrollState()),
    ) {
        ServerChoiceBar(controller, snapshot)
        if (snapshot.state.status == MatchStatus.IN_PROGRESS || snapshot.state.status == MatchStatus.PAUSED) {
            ServeInfoBar(controller, snapshot)
        }
    }
}

/** Kein Dialog: Der nächste Punkt bleibt jederzeit zählbar. Erneutes Wählen löscht die Angabe. */
@Composable
internal fun ServeInfoBar(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
    persistent: Boolean = false,
) {
    val options = LiveOperationRules.serveOptions(snapshot)
    val point = snapshot.lastPoint ?: return
    if (options.isEmpty() || (!persistent && controller.servePromptId != point.id)) return
    val lastOption = remember { BringIntoViewRequester() }
    val fontScale = LocalDensity.current.fontScale
    LaunchedEffect(point.id, fontScale, persistent) {
        if (!persistent && fontScale > 1f) {
            withFrameNanos { }
            lastOption.bringIntoView()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("serve_info")
            .background(AdvantlyTheme.colors.surface2, Radii.chipShape)
            .padding(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            stringResource(Res.string.serve_point_title, playerName(snapshot.setup, point.server)),
            modifier = Modifier.testTag("serve_info_title").semantics { liveRegion = LiveRegionMode.Polite },
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
                    modifier = if (option == options.last()) Modifier.bringIntoViewRequester(lastOption) else Modifier,
                    role = Role.RadioButton,
                )
            }
        }
    }
}

@Composable
internal fun nextSetLabel(
    kind: NextSetKind,
    singleOption: Boolean,
): String =
    stringResource(
        if (singleOption) {
            Res.string.dialog_yes
        } else {
            when (kind) {
                NextSetKind.REGULAR -> Res.string.set_end_regular
                NextSetKind.TIEBREAK_7 -> Res.string.set_end_tiebreak_7
                NextSetKind.MATCH_TIEBREAK, NextSetKind.MATCH_TIEBREAK_10 -> Res.string.set_end_champions_10
            }
        },
    )
