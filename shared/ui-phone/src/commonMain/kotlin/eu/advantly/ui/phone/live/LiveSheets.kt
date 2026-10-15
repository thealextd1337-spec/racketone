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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
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
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.ResultReason
import eu.advantly.core.scoring.SetKind
import eu.advantly.presentation.live.LiveScoreRules
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.presentation.match.isDraw
import eu.advantly.ui.phone.common.AdvantlyButton
import eu.advantly.ui.phone.common.playerName
import eu.advantly.ui.phone.common.resultLine
import eu.advantly.ui.phone.common.scorePair
import eu.advantly.ui.phone.common.stateScore
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
import eu.advantly.ui.phone.resources.dialog_no_finish
import eu.advantly.ui.phone.resources.dialog_set_lost
import eu.advantly.ui.phone.resources.dialog_set_won
import eu.advantly.ui.phone.resources.dialog_show_result
import eu.advantly.ui.phone.resources.dialog_yes
import eu.advantly.ui.phone.resources.end_failed
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
import eu.advantly.ui.phone.resources.set_end_finish
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
            .semantics {
                paneTitle = title
                isTraversalGroup = true
            }.fillMaxWidth()
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
    return if (set.kind == SetKind.MATCH_TIEBREAK) "[${scorePair(pair)}]" else scorePair(pair)
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
        ServeInfoBar(controller, snapshot, persistent = true)
        snapshot.nextSetOptions.forEach { kind ->
            SheetButton(
                nextSetLabel(kind, singleOption = snapshot.nextSetOptions.size == 1),
                primary = kind == NextSetKind.REGULAR,
                onClick = { controller.startNextSet(kind) },
                modifier =
                    Modifier.fillMaxWidth().testTag(
                        when (kind) {
                            NextSetKind.REGULAR -> "decision_yes"
                            NextSetKind.TIEBREAK_7 -> "decision_tiebreak_7"
                            else -> "decision_match_tiebreak"
                        },
                    ),
            )
        }
        SheetButton(
            stringResource(if (snapshot.nextSetOptions.size == 1) Res.string.dialog_no_finish else Res.string.set_end_finish),
            primary = false,
            onClick = { controller.continueDecision(anotherSet = false) },
            modifier = Modifier.fillMaxWidth().testTag("decision_no"),
        )
        SheetTextButton(stringResource(Res.string.action_undo), "sheet_undo") { controller.undo() }
        AbandonFromSetEnd(controller, snapshot)
        PointRemovalActions(controller, snapshot)
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
                result.isDraw -> Res.string.hint_match_drawn
                result.winner == null -> Res.string.hint_match_abandoned
                result.winner == Side.A -> Res.string.hint_match_won
                else -> Res.string.hint_match_lost
            },
        )
    // Folge-Match und Undo nur am Zähler (04 §3.3); Anzeige-Geräte sehen Ergebnis und „Ergebnis ansehen“
    val canFollowUp =
        snapshot.isScorer &&
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
        SheetBigScore(resultLine(state.sets, state.current, result, state.setsWon))
        ServeInfoBar(controller, snapshot, persistent = true)
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
        PointRemovalActions(controller, snapshot)
    }
}

/**
 * Matchende am Zähler (F21, 04 Prinzip 4): „Beenden – Ergebnis gilt“, Aufgabe Wir/Gegner bzw. – solange kein Ballwechsel
 * wirksam gespielt wurde – „nicht angetreten“ (02 §9.8) und „Abbrechen (ohne Wertung)“; Beenden und Abbrechen mit
 * Bestätigung. Das Sheet schließt nur, wenn die Engine das Ende annimmt; sonst bleibt es mit Fehlerhinweis offen.
 */
@Composable
internal fun BoxScope.EndMatchSheet(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
    onClose: () -> Unit,
) {
    // Ballwechsel statt Score: Bei Pickleball Side-Out bleibt der Stand nach einem Rückschlag-Ballwechsel 0:0
    val noRallies = snapshot.pointsPlayed == 0
    var failed by remember { mutableStateOf(false) }
    EndChoicesSheet(controller, snapshot, title = stringResource(Res.string.end_title), tag = "end_match_sheet", onClose = onClose) {
        for (side in Side.entries) {
            val reason = if (noRallies) EndReason.WALKOVER else EndReason.RETIRED
            SheetButton(
                stringResource(if (noRallies) Res.string.end_walkover else Res.string.end_retired, teamName(snapshot.setup, side)),
                primary = false,
                onClick = { if (controller.endMatch(reason, side)) onClose() else failed = true },
                modifier = Modifier.fillMaxWidth().testTag(if (side == Side.A) "end_retire_us" else "end_retire_them"),
            )
        }
        if (failed) {
            Text(
                stringResource(Res.string.end_failed),
                style = AdvantlyTheme.typography.label,
                color = AdvantlyTheme.colors.danger,
                modifier = Modifier.testTag("end_failed").semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

/**
 * Matchende auf dem Anzeige-Gerät (F21): Ein anderes Gerät (z. B. die Uhr) zählt – das Sheet erklärt, dass dieses Handy
 * vorher das Zählen übernimmt, und bietet „Beenden – Ergebnis gilt“ und „Abbrechen (ohne Wertung)“ mit Bestätigung.
 */
@Composable
internal fun BoxScope.DisplayEndSheet(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
    onClose: () -> Unit,
) = EndChoicesSheet(controller, snapshot, title = displayEndTitle(), tag = "display_end_sheet", onClose = onClose)

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

/** Kurzer Stand für Mini-Scorebar und Resume-Karte: „6:4 (3:2) 30:15“. */
@Composable
fun compactScore(snapshot: LiveMatchSnapshot): String = stateScore(snapshot.state, snapshot.setup.sport)
