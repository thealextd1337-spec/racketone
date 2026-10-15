package eu.advantly.ui.phone.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.GameKind
import eu.advantly.core.scoring.ResultReason
import eu.advantly.core.scoring.SetKind
import eu.advantly.presentation.live.LiveScoreRules
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.ui.phone.common.AdvantlyButton
import eu.advantly.ui.phone.common.playerName
import eu.advantly.ui.phone.common.scorePair
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.action_cancel
import eu.advantly.ui.phone.resources.action_undo
import eu.advantly.ui.phone.resources.correct_server_from_now
import eu.advantly.ui.phone.resources.correct_server_from_start
import eu.advantly.ui.phone.resources.correct_server_title
import eu.advantly.ui.phone.resources.dialog_another_match
import eu.advantly.ui.phone.resources.dialog_another_set
import eu.advantly.ui.phone.resources.dialog_match_tiebreak
import eu.advantly.ui.phone.resources.dialog_no_finish
import eu.advantly.ui.phone.resources.dialog_set_lost
import eu.advantly.ui.phone.resources.dialog_set_won
import eu.advantly.ui.phone.resources.dialog_show_result
import eu.advantly.ui.phone.resources.dialog_yes
import eu.advantly.ui.phone.resources.end_abandon
import eu.advantly.ui.phone.resources.end_abandon_hint
import eu.advantly.ui.phone.resources.end_retired
import eu.advantly.ui.phone.resources.end_title
import eu.advantly.ui.phone.resources.end_walkover
import eu.advantly.ui.phone.resources.hint_match_abandoned
import eu.advantly.ui.phone.resources.hint_match_drawn
import eu.advantly.ui.phone.resources.hint_match_lost
import eu.advantly.ui.phone.resources.hint_match_won
import eu.advantly.ui.phone.resources.match_end_retired_suffix
import eu.advantly.ui.phone.resources.match_end_walkover_suffix
import eu.advantly.ui.phone.resources.result_followup_hint
import org.jetbrains.compose.resources.stringResource

/** Bottom-Sheet über dem Live-Score (04 §5.6 „Bottom-Sheet-Dialog“): Scrim blockiert die Tap-Flächen. */
@Composable
internal fun BoxScope.LiveSheet(
    title: String,
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AdvantlyTheme.colors
    Box(
        Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.6f))
            // Taps auf den Scrim zählen nicht (kein Auto-Weiter, keine versehentlichen Punkte)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
    )
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .testTag(tag)
            .semantics { paneTitle = title }
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .background(colors.surface, RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
        content = content,
    )
}

/** Knopf in Sheets (siehe [AdvantlyButton]). */
@Composable
internal fun SheetButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = AdvantlyButton(text, primary, onClick, modifier, enabled)

@Composable
private fun SheetOverline(text: String) {
    Text(text.uppercase(), style = AdvantlyTheme.typography.overline, color = AdvantlyTheme.colors.text2)
}

@Composable
private fun SheetBigScore(text: String) {
    Text(
        text,
        style = AdvantlyTheme.typography.scoreM.copy(fontSize = 44.sp, lineHeight = 48.sp),
        color = AdvantlyTheme.colors.text,
        modifier = Modifier.testTag("sheet_score"),
    )
}

@Composable
private fun SheetQuestion(text: String) {
    Text(text, style = AdvantlyTheme.typography.title, color = AdvantlyTheme.colors.text)
}

/** Text-Knopf (z. B. „Rückgängig“ im Sheet). */
@Composable
private fun SheetTextButton(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .testTag(tag)
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AdvantlyTheme.typography.label, color = AdvantlyTheme.colors.brand)
    }
}

/** Satzergebnis „6:4“ bzw. „11:7“ (Match-Tiebreak: Tiebreak-Punkte) des Satzes [index]. */
@Composable
private fun setScore(
    snapshot: LiveMatchSnapshot,
    index: Int,
): String {
    val set = snapshot.state.sets.getOrNull(index) ?: return ""
    val pair =
        when (set.kind) {
            SetKind.REGULAR -> set.games
            SetKind.MATCH_TIEBREAK -> set.tiebreak ?: set.points
            else -> set.points
        } ?: return ""
    return scorePair(pair)
}

/**
 * H7 Satzende-Sheet (OPEN-Modus, 04 §3.3): „Noch ein Satz in gleicher Besetzung?“ – Ja (primär) · Match-Tiebreak
 * (falls konfiguriert) · Nein, beenden. „Rückgängig“ nimmt den Satzball zurück.
 */
@Composable
internal fun BoxScope.SetEndSheet(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
) {
    val sets = snapshot.state.sets
    val last = sets.lastOrNull()
    val question = stringResource(Res.string.dialog_another_set)
    LiveSheet(title = question, tag = "set_end_sheet") {
        if (last != null) {
            SheetOverline(stringResource(if (last.winner == Side.A) Res.string.dialog_set_won else Res.string.dialog_set_lost, sets.size))
            SheetBigScore(setScore(snapshot, sets.lastIndex))
        }
        SheetQuestion(question)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxWidth()) {
            SheetButton(
                stringResource(Res.string.dialog_no_finish),
                primary = false,
                onClick = { controller.continueDecision(anotherSet = false) },
                modifier = Modifier.weight(1f).testTag("decision_no"),
            )
            SheetButton(
                stringResource(Res.string.dialog_yes),
                primary = true,
                onClick = { controller.continueDecision(anotherSet = true) },
                modifier = Modifier.weight(1f).testTag("decision_yes"),
            )
        }
        if (snapshot.setup.format.matchTiebreak != null) {
            SheetButton(
                stringResource(Res.string.dialog_match_tiebreak),
                primary = false,
                onClick = { controller.continueDecision(anotherSet = true, matchTiebreak = true) },
                modifier = Modifier.fillMaxWidth().testTag("decision_match_tiebreak"),
            )
        }
        SheetTextButton(stringResource(Res.string.action_undo), "sheet_undo") { controller.undo() }
    }
}

/**
 * Matchende-Sheet: Ergebnis, bei regulär beendetem Best-of „Noch einer in gleicher Besetzung?“ (Folge-Match, 02 §5.2),
 * „Ergebnis ansehen“ und „Rückgängig“ (öffnet das Match wieder, 02 §9.1).
 */
@Composable
internal fun BoxScope.MatchEndSheet(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
    onShowResult: () -> Unit,
) {
    val state = snapshot.state
    val result = state.result ?: return
    val title =
        stringResource(
            when {
                result.reason == ResultReason.DRAW -> Res.string.hint_match_drawn
                result.winner == null -> Res.string.hint_match_abandoned
                result.winner == Side.A -> Res.string.hint_match_won
                else -> Res.string.hint_match_lost
            },
        )
    val canFollowUp =
        result.reason == ResultReason.COMPLETED &&
            snapshot.setup.format.match.mode == MatchMode.BEST_OF &&
            state.continuation != null
    LiveSheet(title = title, tag = "match_end_sheet") {
        SheetOverline(teamName(snapshot.setup, Side.A) + " – " + teamName(snapshot.setup, Side.B))
        Text(
            title,
            style = AdvantlyTheme.typography.headline,
            color = AdvantlyTheme.colors.text,
            modifier = Modifier.testTag("match_end_title"),
        )
        val suffix =
            when (result.reason) {
                ResultReason.RETIRED -> " " + stringResource(Res.string.match_end_retired_suffix)
                ResultReason.WALKOVER -> " " + stringResource(Res.string.match_end_walkover_suffix)
                else -> ""
            }
        val setScores = state.sets.indices.map { setScore(snapshot, it) }
        val wonSets = scorePair(state.setsWon)
        SheetBigScore(setScores.joinToString("  ").ifEmpty { wonSets } + suffix)
        if (canFollowUp) {
            SheetQuestion(stringResource(Res.string.dialog_another_match))
            Text(stringResource(Res.string.result_followup_hint), style = AdvantlyTheme.typography.body, color = AdvantlyTheme.colors.text2)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxWidth()) {
                SheetButton(
                    stringResource(Res.string.dialog_show_result),
                    primary = false,
                    onClick = onShowResult,
                    modifier = Modifier.weight(1f).testTag("show_result"),
                )
                SheetButton(
                    stringResource(Res.string.dialog_yes),
                    primary = true,
                    onClick = { controller.createFollowUp() },
                    modifier = Modifier.weight(1f).testTag("follow_up_yes"),
                )
            }
        } else {
            SheetButton(
                stringResource(Res.string.dialog_show_result),
                primary = true,
                onClick = onShowResult,
                modifier = Modifier.fillMaxWidth().testTag("show_result"),
            )
        }
        if (snapshot.canUndo) SheetTextButton(stringResource(Res.string.action_undo), "sheet_undo") { controller.undo() }
    }
}

/** Bestätigung für vorzeitiges Ende (04 Prinzip 4): Aufgabe Wir/Gegner, Nicht angetreten, Abbruch ohne Wertung. */
@Composable
internal fun BoxScope.EndMatchSheet(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
    onClose: () -> Unit,
) {
    val title = stringResource(Res.string.end_title)
    val noPoints =
        snapshot.state.sets.isEmpty() &&
            snapshot.state.current?.let { it.points.a + it.points.b == 0 && (it.games?.let { g -> g.a + g.b } ?: 0) == 0 } != false
    LiveSheet(title = title, tag = "end_match_sheet") {
        SheetQuestion(title)
        for (side in Side.entries) {
            val reason = if (noPoints) EndReason.WALKOVER else EndReason.RETIRED
            SheetButton(
                stringResource(if (noPoints) Res.string.end_walkover else Res.string.end_retired, teamName(snapshot.setup, side)),
                primary = false,
                onClick = {
                    controller.endMatch(reason, side)
                    onClose()
                },
                modifier = Modifier.fillMaxWidth().testTag(if (side == Side.A) "end_retire_us" else "end_retire_them"),
            )
        }
        SheetButton(
            stringResource(Res.string.end_abandon),
            primary = false,
            onClick = {
                controller.endMatch(EndReason.ABANDONED, null)
                onClose()
            },
            modifier = Modifier.fillMaxWidth().testTag("end_abandon"),
        )
        Text(stringResource(Res.string.end_abandon_hint), style = AdvantlyTheme.typography.caption, color = AdvantlyTheme.colors.text2)
        SheetButton(
            stringResource(Res.string.action_cancel),
            primary = true,
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().testTag("end_cancel"),
        )
    }
}

/**
 * Aufschläger korrigieren (02 §9.4): im ersten Spiel bzw. ersten Satz „ab Beginn“ (rückwirkend,
 * `Correction(INITIAL_SERVER)`), sonst „ab jetzt“ (`Correction(SERVER)`).
 */
@Composable
internal fun BoxScope.CorrectServerSheet(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
    onClose: () -> Unit,
) {
    val title = stringResource(Res.string.correct_server_title)
    val current = snapshot.state.current
    val firstGame =
        snapshot.state.sets.isEmpty() &&
            when {
                snapshot.setup.sport.isTennisLike -> {
                    current?.games?.let { it.a + it.b == 0 } == true &&
                        current.gameKind == GameKind.STANDARD
                }

                else -> {
                    true
                }
            }
    val serverId = snapshot.state.serve?.player
    LiveSheet(title = title, tag = "correct_server_sheet") {
        SheetQuestion(title)
        Text(
            stringResource(if (firstGame) Res.string.correct_server_from_start else Res.string.correct_server_from_now),
            style = AdvantlyTheme.typography.body,
            color = AdvantlyTheme.colors.text2,
        )
        snapshot.setup.players.forEach { player ->
            SheetButton(
                playerName(player),
                primary = player.id == serverId,
                onClick = {
                    controller.correctServer(player.id, retroactive = firstGame)
                    onClose()
                },
                modifier = Modifier.fillMaxWidth().testTag("server_${player.id.value}"),
            )
        }
        SheetButton(stringResource(Res.string.action_cancel), primary = false, onClick = onClose, modifier = Modifier.fillMaxWidth())
    }
}

/** Kurzer Satzstand für Mini-Scorebar, Verlauf und Ergebnis: „6:4 3:6 (4:2)“. */
@Composable
fun compactScore(
    snapshot: LiveMatchSnapshot,
    sport: Sport = snapshot.setup.sport,
): String {
    val columns = LiveScoreRules.setColumns(snapshot.state)
    val done = columns.filter { !it.isCurrent }.map { scorePair(it.values) }
    val current = columns.firstOrNull { it.isCurrent }
    val live = current?.let { "(" + scorePair(it.values) + ")" }
    val points =
        if (sport.isTennisLike && snapshot.state.current != null) {
            snapshot.state.current!!
                .display
                .let { scorePair(it.a, it.b) }
        } else {
            null
        }
    return (done + listOfNotNull(live, points)).joinToString(" ")
}
