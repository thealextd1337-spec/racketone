package eu.advantly.wear.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.wear.R
import eu.advantly.wear.live.DecisionUi
import eu.advantly.wear.live.FinishedUi
import eu.advantly.wear.live.LiveModel
import eu.advantly.wear.live.LiveRole
import eu.advantly.wear.live.LiveUi
import eu.advantly.wear.live.SyncUi
import eu.advantly.wear.live.WatchToast
import eu.advantly.wear.workout.WorkoutError
import eu.advantly.wear.workout.WorkoutPhase
import eu.advantly.wear.workout.WorkoutUiState
import kotlinx.coroutines.delay

/** Liste mit optionalem EdgeButton (rund: Primäraktion unten am Rand, 04 §1.2). */
@Composable
private fun ListPage(
    edgeLabel: String?,
    onEdge: () -> Unit,
    content: ScalingLazyListScope.() -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            if (edgeLabel != null) EdgeButton(onClick = onEdge) { Text(edgeLabel) }
        },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth(), content = content)
    }
}

private fun ScalingLazyListScope.textItem(
    secondary: Boolean = true,
    text: @Composable () -> String,
) {
    item {
        Text(
            text(),
            style = if (secondary) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun ScalingLazyListScope.actionItem(
    label: Int,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    item {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
            label = { Text(stringResource(label), maxLines = 2) },
        )
    }
}

/**
 * Steuerseite (04 W4, Pager links): Zähler – Rückgängig, Wiederholen, Pause, Sperre, Match beenden; Anzeige – Rolleninfo,
 * Staleness und „Zählen übernehmen“. Dazu Sync-Status, Fitness (Puls, kcal, Schritte) und Workout-Fehler.
 * Kehrt nach 8 s ohne Eingabe zum Score zurück (04 §2.2).
 */
@Composable
fun ControlsPage(
    live: LiveUi,
    workout: WorkoutUiState,
    heartRate: Int?,
    actions: LiveActions,
    onClose: () -> Unit,
) {
    BackToScore(onClose)
    var interaction by remember { mutableIntStateOf(0) }
    LaunchedEffect(interaction) {
        delay(AUTO_RETURN_MS)
        onClose()
    }

    fun touch(action: () -> Unit): () -> Unit =
        {
            interaction++
            action()
        }
    ListPage(edgeLabel = stringResource(R.string.action_back_to_score), onEdge = onClose) {
        if (live.role == LiveRole.SCORER) {
            item { ListHeader { Text(stringResource(R.string.controls_title)) } }
            item {
                Text(
                    "${stringResource(R.string.role_scorer_short)} · ${syncText(live.sync)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            actionItem(R.string.action_undo, enabled = live.canUndo, onClick = touch { actions.undo() })
            if (live.canRedo) actionItem(R.string.action_redo, onClick = touch { actions.redo() })
            if (live.status == MatchStatus.PAUSED) {
                actionItem(R.string.action_resume_play, primary = true, onClick = touch { actions.resumePlay() })
            } else {
                actionItem(R.string.action_pause, onClick = touch { actions.pause() })
            }
            actionItem(R.string.action_lock, onClick = {
                actions.lock()
                onClose()
            })
            actionItem(R.string.action_end_match, onClick = actions::requestEnd)
        } else {
            item { ListHeader { Text(stringResource(R.string.role_view)) } }
            displayRoleInfo(live)
            if (live.status != MatchStatus.FINISHED) {
                actionItem(R.string.take_over_offer, primary = live.takeOverOffered, onClick = actions::requestTakeOver)
            }
        }
        if (live.rejectedInputs > 0) textItem { pluralStringResource(R.plurals.rejected_inputs, live.rejectedInputs, live.rejectedInputs) }
        fitnessItems(workout, heartRate, actions)
    }
}

private fun ScalingLazyListScope.displayRoleInfo(live: LiveUi) {
    item {
        val scorer = stringResource(R.string.scoring_by, deviceName(live.scorerKind))
        val sync =
            if (live.sync ==
                SyncUi.Live
            ) {
                stringResource(R.string.sync_live)
            } else {
                stringResource(R.string.sync_last_update, syncText(live.sync))
            }
        Text("$scorer\n$sync", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** Fitness-Bereich (04 W5): Puls, Dauer, kcal/Schritte, Workout-Status und -Fehler (Workout läuft unabhängig von der Rolle). */
private fun ScalingLazyListScope.fitnessItems(
    workout: WorkoutUiState,
    heartRate: Int?,
    actions: LiveActions,
) {
    item {
        Text(
            heartRate?.let { stringResource(R.string.heart_rate_value, it) } ?: stringResource(R.string.heart_rate_unknown),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    item { WorkoutStatusText(workout) }
    if (workout.phase == WorkoutPhase.ACTIVE) {
        textItem {
            val kcal = workout.caloriesKcal?.toInt()?.toString() ?: "--"
            "${workoutDuration(workout)} · ${stringResource(R.string.stats_line, kcal, workout.steps?.toString() ?: "--")}"
        }
    }
    if (workout.phase == WorkoutPhase.FAILED) {
        item {
            Text(
                "${stringResource(R.string.error_title)}: ${stringResource(errorText(workout.error))}",
                style = MaterialTheme.typography.bodySmall,
                color = ErrorRed,
                textAlign = TextAlign.Center,
            )
        }
        actionItem(R.string.action_retry, onClick = actions::retryWorkout)
        actionItem(R.string.action_without_workout, onClick = actions::skipWorkout)
    }
}

@Composable
private fun WorkoutStatusText(workout: WorkoutUiState) {
    val text =
        when {
            workout.countOnly -> stringResource(R.string.count_only_label)
            workout.phase == WorkoutPhase.STARTING -> stringResource(R.string.workout_starting)
            workout.phase == WorkoutPhase.ACTIVE -> stringResource(R.string.workout_running)
            else -> stringResource(R.string.workout_off)
        }
    Text(text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
}

/** Laufende Workout-Dauer h:mm:ss aus dem letzten Checkpoint (Sekundentakt nur auf dieser Seite). */
@Composable
private fun workoutDuration(workout: WorkoutUiState): String {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val anchor = workout.durationAnchorElapsedMillis
    val active = workout.activeDurationMillis + if (anchor != null) (now - anchor).coerceAtLeast(0) else 0
    val total = active / 1000
    return "${total / 3600}:${(total / 60 % 60).toString().padStart(2, '0')}:${(total % 60).toString().padStart(2, '0')}"
}

private fun errorText(error: WorkoutError?): Int =
    when (error) {
        WorkoutError.PERMISSIONS -> R.string.error_permissions
        WorkoutError.CAPABILITIES -> R.string.error_capabilities
        WorkoutError.STATUS_QUERY -> R.string.error_status_query
        WorkoutError.START -> R.string.error_start
        WorkoutError.END -> R.string.error_end
        WorkoutError.CALLBACK -> R.string.error_callback
        WorkoutError.EXTERNAL_END -> R.string.error_external_end
        WorkoutError.FOREGROUND -> R.string.error_foreground
        WorkoutError.INTERNAL, null -> R.string.error_internal
    }

/**
 * Satzende-Dialog (04 W6, 02 §5.2, Modus Offen): nur der Zähler entscheidet – „Ja“ (EdgeButton), „Nein, beenden“,
 * optional Match-Tiebreak; keine automatische Auswahl. Die Anzeige sieht das Satzergebnis und „Handy entscheidet“.
 */
@Composable
fun DecisionScreen(
    live: LiveUi,
    decision: DecisionUi,
    toast: WatchToast?,
    actions: LiveActions,
) {
    val scorer = live.role == LiveRole.SCORER
    val unit =
        if (live.sport == Sport.PICKLEBALL) {
            stringResource(R.string.unit_game, decision.setNumber)
        } else {
            stringResource(R.string.unit_set, decision.setNumber)
        }
    val outcome = stringResource(if (decision.winner == Side.A) R.string.decision_won else R.string.decision_lost)
    val question = stringResource(if (live.sport == Sport.PICKLEBALL) R.string.dialog_another_game else R.string.dialog_another_set)
    ListPage(
        edgeLabel = if (scorer) stringResource(R.string.action_yes) else null,
        onEdge = { actions.continueDecision(anotherSet = true, matchTiebreak = false) },
    ) {
        item { ListHeader { Text("$unit · ${decision.score.a}:${decision.score.b}") } }
        textItem(secondary = false) { outcome }
        if (toast != null) textItem { toastText(toast) }
        if (scorer) {
            item { Text(question, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
            if (decision.offerMatchTiebreak) {
                actionItem(R.string.action_match_tiebreak) { actions.continueDecision(anotherSet = true, matchTiebreak = true) }
            }
            actionItem(R.string.action_no_end) { actions.continueDecision(anotherSet = false, matchTiebreak = false) }
            actionItem(R.string.action_undo) { actions.undo() }
        } else {
            textItem { stringResource(R.string.decision_waiting, deviceName(live.scorerKind)) }
            if (live.takeOverOffered) actionItem(R.string.take_over_offer, primary = true, onClick = actions::requestTakeOver)
        }
    }
}

/** Matchende (04 W7): Ergebnis, Sätze; Zähler: „Noch einer?“ (Best-of, regulär) und Rückgängig; „Fertig“ schließt. */
@Composable
fun FinishedScreen(
    live: LiveUi,
    finished: FinishedUi,
    actions: LiveActions,
) {
    ListPage(edgeLabel = stringResource(R.string.action_done), onEdge = actions::close) {
        item { ListHeader { Text(stringResource(R.string.finished_title)) } }
        item {
            Text(
                resultText(finished.winner, finished.reason),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = finished.winner?.let(::teamColor) ?: TextSecondary,
            )
        }
        textItem(secondary = false) { "${sportName(live.sport)} · ${LiveModel.setsText(finished.sets)}" }
        if (finished.canFollowUp) actionItem(R.string.action_follow_up, primary = true) { actions.followUp() }
        if (live.role == LiveRole.SCORER && live.canUndo) actionItem(R.string.action_undo) { actions.undo() }
    }
}

/** „Auf dieser Uhr zählen?“ – erst das replizierte `ScorerChanged` schaltet die Eingaben frei (04 §3.2). */
@Composable
fun ConfirmTakeOverScreen(
    live: LiveUi,
    actions: LiveActions,
) {
    BackToScore(actions::dismissOverlay)
    ListPage(edgeLabel = stringResource(R.string.action_take_over), onEdge = { actions.confirmTakeOver() }) {
        item { ListHeader { Text(stringResource(R.string.confirm_take_over_title), textAlign = TextAlign.Center) } }
        textItem { stringResource(R.string.confirm_take_over_text, deviceName(live.scorerKind)) }
        if (live.sync is SyncUi.Stale) textItem { stringResource(R.string.sync_last_update, syncText(live.sync)) }
        actionItem(R.string.action_cancel, onClick = actions::dismissOverlay)
    }
}

/** „Match beenden?“ – kein Matchende ohne bewusste Bestätigung (04 §4.3.3 Punkt 8). */
@Composable
fun ConfirmEndScreen(
    live: LiveUi,
    actions: LiveActions,
) {
    BackToScore(actions::dismissOverlay)
    ListPage(edgeLabel = stringResource(R.string.action_confirm_end), onEdge = { actions.confirmEnd() }) {
        item { ListHeader { Text(stringResource(R.string.confirm_end_title)) } }
        if (live.status != MatchStatus.AWAITING_DECISION) textItem { stringResource(R.string.confirm_end_text) }
        actionItem(R.string.action_cancel, onClick = actions::dismissOverlay)
    }
}

/** Fremdes Training läuft: ersetzen nur nach ausdrücklicher Entscheidung; sonst zählt das Match ohne Workout weiter. */
@Composable
fun WorkoutConflictScreen(actions: LiveActions) {
    ListPage(edgeLabel = stringResource(R.string.action_without_workout), onEdge = actions::skipWorkout) {
        item { ListHeader { Text(stringResource(R.string.conflict_title), textAlign = TextAlign.Center) } }
        textItem { stringResource(R.string.conflict_text) }
        actionItem(R.string.action_replace, onClick = actions::replaceOtherWorkout)
    }
}

private const val AUTO_RETURN_MS = 8_000L
