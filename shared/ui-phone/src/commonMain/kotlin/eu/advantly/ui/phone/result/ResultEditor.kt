package eu.advantly.ui.phone.result

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.CorrectedSet
import eu.advantly.core.scoring.CorrectionIssueKind
import eu.advantly.core.scoring.GameServer
import eu.advantly.core.scoring.ResultCorrection
import eu.advantly.core.scoring.SetKind
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.match.MatchRecord
import eu.advantly.presentation.stats.ResultEditing
import eu.advantly.ui.phone.common.AdvantlyButton
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.common.SegmentedChoice
import eu.advantly.ui.phone.common.TopBar
import eu.advantly.ui.phone.common.playerName
import eu.advantly.ui.phone.common.safeContentPadding
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.action_cancel
import eu.advantly.ui.phone.resources.result_edit_add_set
import eu.advantly.ui.phone.resources.result_edit_failed
import eu.advantly.ui.phone.resources.result_edit_game
import eu.advantly.ui.phone.resources.result_edit_issue_after_match_end
import eu.advantly.ui.phone.resources.result_edit_issue_no_sets
import eu.advantly.ui.phone.resources.result_edit_issue_set_kind
import eu.advantly.ui.phone.resources.result_edit_issue_set_score
import eu.advantly.ui.phone.resources.result_edit_issue_tiebreak_required
import eu.advantly.ui.phone.resources.result_edit_issue_tiebreak_score
import eu.advantly.ui.phone.resources.result_edit_issue_too_many_sets
import eu.advantly.ui.phone.resources.result_edit_kind_match_tiebreak
import eu.advantly.ui.phone.resources.result_edit_kind_regular
import eu.advantly.ui.phone.resources.result_edit_minus
import eu.advantly.ui.phone.resources.result_edit_no_winner
import eu.advantly.ui.phone.resources.result_edit_note
import eu.advantly.ui.phone.resources.result_edit_plus
import eu.advantly.ui.phone.resources.result_edit_remove_set
import eu.advantly.ui.phone.resources.result_edit_restore
import eu.advantly.ui.phone.resources.result_edit_save
import eu.advantly.ui.phone.resources.result_edit_servers
import eu.advantly.ui.phone.resources.result_edit_servers_hint
import eu.advantly.ui.phone.resources.result_edit_servers_unavailable
import eu.advantly.ui.phone.resources.result_edit_set
import eu.advantly.ui.phone.resources.result_edit_sets
import eu.advantly.ui.phone.resources.result_edit_tiebreak
import eu.advantly.ui.phone.resources.result_edit_title
import eu.advantly.ui.phone.resources.result_edit_winner
import org.jetbrains.compose.resources.stringResource

/**
 * „Ergebnis bearbeiten“ (0.3.0): Sätze direkt eintippen (Spiele bzw. Punkte, Tennis/Padel mit Tiebreak-Punkten und
 * Match-Tiebreak), Sätze hinzufügen/entfernen und – nur Tennis/Padel – den Aufschläger je Spiel festlegen. Plausibilität
 * und Sieger kommen aus der Engine ([ResultEditing]); gespeichert wird nur Geändertes.
 */
@Composable
internal fun ResultEditor(
    record: MatchRecord,
    onCorrectResult: (List<CorrectedSet>) -> Boolean,
    onCorrectServers: (List<GameServer>) -> Boolean,
    onClose: () -> Unit,
) {
    val editing = remember(record) { ResultEditing(record) }
    val setup = record.summary.setup
    var sets by remember(record) { mutableStateOf(editing.initialSets) }
    var servers by remember(record) { mutableStateOf(editing.initialServers) }
    var failed by remember { mutableStateOf(false) }
    val check = remember(sets) { editing.check(sets) }
    val games = remember(sets, check.issue) { if (check.issue == null) editing.serviceGames(sets) else emptyList() }
    val colors = AdvantlyTheme.colors

    fun update(
        index: Int,
        set: CorrectedSet,
    ) {
        sets = sets.toMutableList().also { it[index] = set }
        failed = false
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeContentPadding()
            .testTag("result_editor"),
    ) {
        TopBar(stringResource(Res.string.result_edit_title), onClose, stringResource(Res.string.action_cancel))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenEdgeCompact, vertical = Spacing.s),
        ) {
            Text(stringResource(Res.string.result_edit_note), style = AdvantlyTheme.typography.caption, color = colors.text2)
            SectionTitle(stringResource(Res.string.result_edit_sets))
            sets.forEachIndexed { index, set ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.s)
                        .background(colors.surface, Radii.cardShape)
                        .padding(Spacing.m)
                        .testTag("edit_set_$index"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Text(stringResource(Res.string.result_edit_set, index + 1), style = AdvantlyTheme.typography.label, color = colors.text)
                    if (editing.setKinds.size > 1) {
                        SegmentedChoice(
                            options = editing.setKinds,
                            selected = set.kind,
                            label = {
                                stringResource(
                                    if (it ==
                                        SetKind.MATCH_TIEBREAK
                                    ) {
                                        Res.string.result_edit_kind_match_tiebreak
                                    } else {
                                        Res.string.result_edit_kind_regular
                                    },
                                )
                            },
                            onSelect = { update(index, CorrectedSet(it, set.score)) },
                            tagPrefix = "edit_kind_$index",
                        )
                    }
                    for (side in Side.entries) {
                        Stepper(teamName(setup, side), set.score[side], "edit_score_${index}_${side.name}") {
                            // Neuer Satzstand: Tiebreak-Punkte neu eingeben (passen sonst womöglich nicht mehr)
                            update(index, CorrectedSet(set.kind, set.score.with(side, it)))
                        }
                    }
                    val issue = check.issue
                    val tiebreak = set.tiebreak
                    val asksTiebreak = issue?.set == index && issue.kind == CorrectionIssueKind.TIEBREAK_REQUIRED
                    if (set.kind == SetKind.REGULAR && (tiebreak != null || asksTiebreak)) {
                        Text(
                            stringResource(Res.string.result_edit_tiebreak),
                            style = AdvantlyTheme.typography.caption,
                            color = colors.text2,
                        )
                        val points = tiebreak ?: SidePair(0, 0)
                        for (side in Side.entries) {
                            Stepper(teamName(setup, side), points[side], "edit_tiebreak_${index}_${side.name}") {
                                update(index, set.copy(tiebreak = points.with(side, it)))
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                AdvantlyButton(
                    text = stringResource(Res.string.result_edit_add_set),
                    primary = false,
                    enabled = sets.size < ResultCorrection.MAX_SETS,
                    onClick = { sets = sets + CorrectedSet(editing.setKinds.first(), SidePair(0, 0)) },
                    modifier = Modifier.weight(1f).testTag("edit_add_set"),
                )
                AdvantlyButton(
                    text = stringResource(Res.string.result_edit_remove_set),
                    primary = false,
                    enabled = sets.isNotEmpty(),
                    onClick = { sets = sets.dropLast(1) },
                    modifier = Modifier.weight(1f).testTag("edit_remove_set"),
                )
            }
            val issue = check.issue
            val winner = check.result?.winner
            Text(
                when {
                    issue != null -> issueText(issue.kind, (issue.set ?: 0) + 1)
                    winner != null -> stringResource(Res.string.result_edit_winner, teamName(setup, winner))
                    else -> stringResource(Res.string.result_edit_no_winner)
                },
                style = AdvantlyTheme.typography.body,
                color = if (issue != null) colors.danger else colors.text,
                modifier = Modifier.padding(vertical = Spacing.m).testTag("edit_status"),
            )
            SectionTitle(stringResource(Res.string.result_edit_servers))
            if (!editing.serversEditable) {
                Text(
                    stringResource(Res.string.result_edit_servers_unavailable),
                    style = AdvantlyTheme.typography.caption,
                    color = colors.text2,
                )
            } else {
                Text(stringResource(Res.string.result_edit_servers_hint), style = AdvantlyTheme.typography.caption, color = colors.text2)
                val players = setup.lineup.a + setup.lineup.b
                games.forEach { game ->
                    val key = game.set to game.game
                    Text(
                        stringResource(Res.string.result_edit_game, game.set + 1, game.game + 1),
                        style = AdvantlyTheme.typography.label,
                        color = colors.text,
                        modifier = Modifier.padding(top = Spacing.s),
                    )
                    SegmentedChoice(
                        options = players,
                        selected = servers[key] ?: game.server,
                        label = { id: PlayerId -> playerName(setup, id) },
                        onSelect = { servers = servers + (key to it) },
                        tagPrefix = "edit_server_${game.set}_${game.game}",
                    )
                }
            }
            if (failed) {
                Text(stringResource(Res.string.result_edit_failed), color = colors.danger, modifier = Modifier.padding(top = Spacing.m))
            }
            AdvantlyButton(
                text = stringResource(Res.string.result_edit_save),
                primary = true,
                enabled = check.issue == null,
                onClick = {
                    val chosen = editing.servers(servers, games)
                    val ok =
                        (sets == editing.initialSets || onCorrectResult(sets)) &&
                            (chosen == editing.servers(editing.initialServers, games) || onCorrectServers(chosen))
                    if (ok) onClose() else failed = true
                },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.l).testTag("edit_save"),
            )
            if (record.summary.result.corrected) {
                AdvantlyButton(
                    text = stringResource(Res.string.result_edit_restore),
                    primary = false,
                    onClick = { if (onCorrectResult(emptyList())) onClose() else failed = true },
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.m).testTag("edit_restore"),
                )
            }
        }
    }
}

@Composable
private fun issueText(
    kind: CorrectionIssueKind,
    set: Int,
): String =
    when (kind) {
        CorrectionIssueKind.NO_SETS -> stringResource(Res.string.result_edit_issue_no_sets)
        CorrectionIssueKind.TOO_MANY_SETS -> stringResource(Res.string.result_edit_issue_too_many_sets)
        CorrectionIssueKind.SET_KIND -> stringResource(Res.string.result_edit_issue_set_kind, set)
        CorrectionIssueKind.SET_SCORE -> stringResource(Res.string.result_edit_issue_set_score, set)
        CorrectionIssueKind.TIEBREAK_REQUIRED -> stringResource(Res.string.result_edit_issue_tiebreak_required, set)
        CorrectionIssueKind.TIEBREAK_SCORE -> stringResource(Res.string.result_edit_issue_tiebreak_score, set)
        CorrectionIssueKind.AFTER_MATCH_END -> stringResource(Res.string.result_edit_issue_after_match_end, set)
    }

/** Zahl mit −/+ (0…99), Bezeichnung links; Knöpfe mit sprechender Beschreibung für Screenreader. */
@Composable
private fun Stepper(
    label: String,
    value: Int,
    tag: String,
    onChange: (Int) -> Unit,
) {
    val colors = AdvantlyTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AdvantlyTheme.typography.body, color = colors.text, modifier = Modifier.weight(1f))
        StepButton("−", stringResource(Res.string.result_edit_minus, label), "${tag}_minus", value > 0) { onChange(value - 1) }
        Text(
            value.toString(),
            style = AdvantlyTheme.typography.title,
            color = colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 40.dp).testTag("${tag}_value"),
        )
        StepButton("+", stringResource(Res.string.result_edit_plus, label), "${tag}_plus", value < 99) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(
    symbol: String,
    description: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = AdvantlyTheme.colors
    Row(
        Modifier
            .testTag(tag)
            .size(Spacing.minTouchTarget)
            .background(colors.surface2, Radii.pillShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(symbol, style = AdvantlyTheme.typography.title, color = if (enabled) colors.text else colors.text3)
    }
}
